'use strict';
/* Nobar: ruangan berkode (maks 4), Firebase RTDB lewat REST+SSE, suara WebRTC. Skema sama persis dengan Android. */
var MAX_MEMBERS = 4;
var FB_KEY = 'AIzaSyBT2ilMvsaGdfGeWYzWOFzr8OWzHC-jqXY';
var DB_CANDIDATES = [
  'https://ekinstru-default-rtdb.firebaseio.com',
  'https://ekinstru-default-rtdb.asia-southeast1.firebasedatabase.app',
  'https://ekinstru-default-rtdb.europe-west1.firebasedatabase.app',
];
var CODE_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
var SV = { '.sv': 'timestamp' };
var ROOM_MAX_AGE = 7 * 24 * 3600 * 1000;

var Fb = {
  idToken: '', exp: 0, uid: Store.get('fb.uid', ''), dbUrl: Store.get('fb.db', ''), _tokenP: null,

  async call(method, url, body, headers, timeout) {
    var last = null;
    for (var i = 0; i < 3; i++) {
      var ctl = new AbortController();
      var t = setTimeout(function () { ctl.abort(); }, timeout || 15000);
      try {
        var r = await fetch(url, { method: method, body: body == null ? undefined : body, headers: Object.assign({ 'Content-Type': 'application/json' }, headers || {}), signal: ctl.signal, cache: 'no-store' });
        var text = await r.text();
        return { code: r.status, text: text };
      } catch (e) { last = e; await sleep(400 * (i + 1)); }
      finally { clearTimeout(t); }
    }
    var host = ''; try { host = new URL(url).host; } catch (e) { host = 'server'; }
    throw new Error('Koneksi ke ' + host + ' gagal: ' + (last && last.message));
  },

  async signUp() {
    var r = await this.call('POST', 'https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=' + FB_KEY, '{"returnSecureToken":true}');
    if (r.code !== 200) throw new Error(/ADMIN_ONLY_OPERATION|OPERATION_NOT_ALLOWED/.test(r.text) ? 'Login anonim Firebase belum diaktifkan' : 'Gagal masuk ke server nobar');
    var j = JSON.parse(r.text);
    this.idToken = j.idToken; this.uid = j.localId; this.exp = Date.now() + Number(j.expiresIn || 3600) * 1000;
    Store.set('fb.refresh', j.refreshToken); Store.set('fb.uid', this.uid);
  },
  async refreshTok(rt) {
    var r = await this.call('POST', 'https://securetoken.googleapis.com/v1/token?key=' + FB_KEY, 'grant_type=refresh_token&refresh_token=' + encodeURIComponent(rt), { 'Content-Type': 'application/x-www-form-urlencoded' });
    if (r.code !== 200) return false;
    var j = JSON.parse(r.text);
    this.idToken = j.id_token; this.uid = j.user_id; this.exp = Date.now() + Number(j.expires_in || 3600) * 1000;
    Store.set('fb.refresh', j.refresh_token || rt); Store.set('fb.uid', this.uid);
    return true;
  },
  token() {
    var self = this;
    if (!self._tokenP) {
      self._tokenP = (async function () {
        try {
          if (!self.idToken || self.exp - Date.now() < 300000) {
            var rt = Store.get('fb.refresh', '');
            if (!rt || !(await self.refreshTok(rt))) await self.signUp();
          }
          return self.idToken;
        } finally { self._tokenP = null; }
      })();
    }
    return self._tokenP;
  },
  async db() {
    if (this.dbUrl) return this.dbUrl;
    for (var i = 0; i < DB_CANDIDATES.length; i++) {
      var c = DB_CANDIDATES[i];
      var code = -1;
      try { code = (await this.call('GET', c + '/.json?shallow=true', null, null, 8000)).code; } catch (e) { code = -1; }
      if (code === 200 || code === 401 || code === 403) { this.dbUrl = c; Store.set('fb.db', c); return c; }
    }
    throw new Error('Database nobar belum dibuat di Firebase');
  },
  async req(method, path, body, headers, query) {
    var t = await this.token(); var base = await this.db();
    return this.call(method, base + '/' + path + '.json?auth=' + t + (query || ''), body, headers);
  },
};

function NobarRoom(code, nick, onEnd) {
  var self = this;
  this.code = code; this.nick = nick; this.onEnd = onEnd;
  this.root = null; this.offset = 0; this.joined = false; this.handledSig = new Set();
  this.info = null; this.pb = { playing: false, pos: 0, at: 0, seq: 0, by: '' };
  this.members = []; this.chat = []; this.reactions = [];
  this.kicked = false; this.myBuf = true; this.status = 'connecting';
  this.voiceOn = false; this.micOn = false; this.camOn = false; this.videos = {}; this.vels = {}; this.voiceError = ''; this.peerState = {};
  this.voice = null; this.closed = false; this.abort = null; this.beat = null; this.listeners = new Set();
  this.me = Fb.uid;
}
NobarRoom.prototype = {
  on: function (fn) { this.listeners.add(fn); var s = this; return function () { s.listeners.delete(fn); }; },
  emit: function () { var s = this; Array.from(this.listeners).forEach(function (fn) { try { fn(s); } catch (e) { console.error(e); } }); },
  /** Ruangan berakhir dari sisi server (ditutup host / dikeluarkan). */
  end: function (msg) {
    if (this.closed) return;
    this.closed = true; this.status = 'closed'; clearInterval(this.beat);
    this.stopVoiceEngine(); this.voiceOn = false; this.micOn = false;
    if (this.abort) try { this.abort.abort(); } catch (e) {}
    this.emit(); if (msg) toast(msg, 4000);
    if (this.onEnd) this.onEnd(this);
  },
  get isHost() { return !!this.info && this.info.host === this.me; },
  canControl: function () { return this.isHost || (this.info && this.info.ctl === 'all'); },
  serverNow: function () { return Date.now() + this.offset; },
  expected: function () { var p = this.pb; return Math.max(0, p.playing ? p.pos + (this.serverNow() - p.at) : p.pos); },
  stamp: function (p) { return p.seq + '_' + p.by; },

  memberBody: function () {
    return JSON.stringify({ name: (this.nick() || '').trim() || 'Tamu', seen: SV, voice: this.voiceOn, mic: this.voiceOn && this.micOn, buf: this.myBuf, cam: this.voiceOn && this.camOn });
  },
  async heartbeat() {
    try {
      var t0 = Date.now();
      var r = await Fb.req('PUT', 'rooms/' + this.code + '/members/' + this.me, this.memberBody());
      var t1 = Date.now();
      if (r.code === 200) { var sv = Number(JSON.parse(r.text).seen || 0); if (sv > 0) this.offset = sv - (t0 + t1) / 2; this.joined = true; }
    } catch (e) { /* abaikan */ }
  },
  async enter() {
    var t0 = Date.now();
    var r = await Fb.req('PUT', 'rooms/' + this.code + '/members/' + this.me, this.memberBody());
    var t1 = Date.now();
    if (r.code === 200) { var sv = Number(JSON.parse(r.text).seen || 0); if (sv > 0) this.offset = sv - (t0 + t1) / 2; this.joined = true; return null; }
    return r.code === 401 || r.code === 403 ? 'Tidak bisa masuk ruangan (periksa aturan database)' : 'Gagal bergabung (kode ' + r.code + ')';
  },
  start: function () {
    var self = this;
    this.streamLoop();
    this.beat = setInterval(function () { self.heartbeat(); }, 10000);
  },
  leave: function (deleteRoom) {
    var host = this.isHost, c = this.code, u = this.me;
    this.stopVoiceEngine(); this.voiceOn = false; this.micOn = false;
    this.closed = true;
    if (this.abort) try { this.abort.abort(); } catch (e) {}
    clearInterval(this.beat);
    (async function () {
      try {
        if (deleteRoom && host) { await Fb.req('DELETE', 'rooms/' + c); await Fb.req('DELETE', 'roomIdx/' + c); }
        else await Fb.req('DELETE', 'rooms/' + c + '/members/' + u);
      } catch (e) {}
    })();
    this.status = 'closed'; this.emit();
    if (this.onEnd) this.onEnd(this);
  },

  async streamLoop() {
    while (!this.closed) {
      try {
        var t = await Fb.token(); var base = await Fb.db();
        this.abort = new AbortController();
        var res = await fetch(base + '/rooms/' + this.code + '.json?auth=' + t, { headers: { Accept: 'text/event-stream' }, signal: this.abort.signal, cache: 'no-store' });
        if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);
        this.status = 'online'; this.emit();
        var rd = res.body.getReader(), dec = new TextDecoder(), buf = '', ev = '';
        while (true) {
          var chunk = await rd.read();
          if (chunk.done) break;
          buf += dec.decode(chunk.value, { stream: true });
          var lines = buf.split('\n'); buf = lines.pop();
          for (var i = 0; i < lines.length; i++) {
            var line = lines[i].replace(/\r$/, '');
            if (line.indexOf('event:') === 0) ev = line.slice(6).trim();
            else if (line.indexOf('data:') === 0) this.handleEvent(ev, line.slice(5).trim());
          }
          if (this.closed) return;
        }
      } catch (e) { /* sambung ulang */ }
      if (this.closed || this.status === 'closed') return;
      this.status = 'offline'; this.emit();
      await sleep(1500);
    }
  },

  setAt: function (path, value) {
    var segs = path.split('/').filter(Boolean);
    if (!segs.length) return;
    if (!this.root || typeof this.root !== 'object') this.root = {};
    var cur = this.root;
    for (var i = 0; i < segs.length - 1; i++) {
      if (cur[segs[i]] == null || typeof cur[segs[i]] !== 'object') cur[segs[i]] = {};
      cur = cur[segs[i]];
    }
    if (value == null) delete cur[segs[segs.length - 1]]; else cur[segs[segs.length - 1]] = value;
  },
  handleEvent: function (ev, data) {
    if (ev === 'keep-alive') return;
    if (ev === 'cancel' || ev === 'auth_revoked') throw new Error(ev);
    if (ev !== 'put' && ev !== 'patch') return;
    var j; try { j = JSON.parse(data); } catch (e) { return; }
    if (!j) return;
    var path = j.path || '/', value = j.data == null ? null : j.data;
    if (ev === 'put') {
      if (path === '/') {
        this.root = (value && typeof value === 'object') ? value : null;
        if (!this.root && this.joined) { this.end('Ruangan ditutup oleh host'); throw new Error('closed'); }
      } else this.setAt(path, value);
    } else {
      if (!value || typeof value !== 'object') return;
      var self = this;
      Object.keys(value).forEach(function (k) { self.setAt(('/' + path.replace(/^\/|\/$/g, '') + '/' + k).replace(/\/\//g, '/'), value[k]); });
    }
    this.status = 'online';
    this.parse();
  },

  parse: function () {
    var r = this.root; if (!r) return;
    var i = r.info;
    if (i) this.info = { host: i.host || '', filmId: i.film || '', filmName: i.filmName || '', ctl: i.ctl || 'host', created: Number(i.created) || 0, gather: Number(i.gather) || 0 };
    var p = r.pb;
    if (p) {
      var n = { playing: p.playing === true, pos: Number(p.pos) || 0, at: Number(p.at) || 0, seq: Number(p.seq) || 0, by: p.by || '' };
      if (this.stamp(n) !== this.stamp(this.pb) || n.playing !== this.pb.playing || n.pos !== this.pb.pos) this.pb = n;
    }
    var ms = [];
    var m = r.members || {};
    Object.keys(m).forEach(function (k) {
      var o = m[k]; if (!o || typeof o !== 'object') return;
      ms.push({ uid: k, name: o.name || 'Tamu', seen: Number(o.seen) || 0, voice: o.voice === true, mic: o.mic === true, buf: o.buf !== false, cam: o.cam === true });
    });
    var host = this.info && this.info.host;
    ms.sort(function (a, b) { return ((a.uid !== host) - (b.uid !== host)) || a.name.toLowerCase().localeCompare(b.name.toLowerCase()); });
    this.members = ms;
    var cs = [], rs = [];
    var c = r.chat || {};
    Object.keys(c).sort().slice(-160).forEach(function (k) {
      var o = c[k]; if (!o) return;
      var isR = o.k === 'r';
      var msg = { id: k, uid: o.u || '', name: o.n || '', text: o.m || '', react: isR, q: o.q || '', qn: o.qn || '', qm: o.qm || '' };
      (isR ? rs : cs).push(msg);
    });
    var lastC = this.chat.length ? this.chat[this.chat.length - 1].id : null;
    var lastR = this.reactions.length ? this.reactions[this.reactions.length - 1].id : null;
    var newReact = rs.length && rs[rs.length - 1].id !== lastR;
    if (cs.length !== this.chat.length || (cs.length && cs[cs.length - 1].id !== lastC)) {
      var nm = cs.length ? cs[cs.length - 1] : null;
      if (lastC !== null && nm && nm.uid !== this.me && window.notifyChat) window.notifyChat({ nick: nm.name, text: nm.text });
      this.chat = cs;
    }
    if (newReact) this.reactions = rs.slice(-20);
    if (!this.isHost && r.kick && r.kick[this.me] && !this.kicked) {
      this.kicked = true; this.end('Kamu dikeluarkan oleh host'); return;
    }
    var sg = r.sig && r.sig[this.me];
    if (sg) {
      var self = this;
      Object.keys(sg).sort().forEach(function (k) {
        if (self.handledSig.has(k)) return;
        self.handledSig.add(k);
        var o = sg[k];
        if (o && self.voice) self.voice.onSignal(o.from || '', o.type || '', o.data || '');
        Fb.req('DELETE', 'rooms/' + self.code + '/sig/' + self.me + '/' + k).catch(function () {});
      });
    }
    if (this.voice) this.voice.sync(ms);
    this.emit();
  },

  /* aksi */
  put: function (path, body) { Fb.req('PUT', 'rooms/' + this.code + '/' + path, body).catch(function () {}); },
  publish: function (playing, pos) {
    var seq = this.pb.seq + 1;
    this.pb = { playing: playing, pos: Math.floor(pos), at: this.serverNow(), seq: seq, by: this.me };
    this.put('pb', JSON.stringify({ playing: playing, pos: Math.floor(pos), at: SV, seq: seq, by: this.me }));
    this.emit();
  },
  setControl: function (all) {
    if (!this.isHost) return;
    this.put('info/ctl', JSON.stringify(all ? 'all' : 'host'));
    this.info = Object.assign({}, this.info, { ctl: all ? 'all' : 'host' }); this.emit();
  },
  _id: function () { return Date.now() + '_' + this.me.slice(0, 4); },
  send: function (text, reply) {
    var t = String(text).trim().slice(0, 300); if (!t) return;
    var b = { u: this.me, n: (this.nick() || '').trim() || 'Tamu', m: t };
    if (reply) { b.q = reply.id; b.qn = reply.name.slice(0, 30); b.qm = reply.text.slice(0, 80); }
    this.put('chat/' + this._id(), JSON.stringify(b));
  },
  react: function (emoji) {
    this.put('chat/' + this._id(), JSON.stringify({ u: this.me, n: (this.nick() || '').trim() || 'Tamu', m: String(emoji).slice(0, 8), k: 'r' }));
  },
  kick: function (uid) {
    if (!this.isHost || uid === this.me) return;
    this.put('kick/' + uid, 'true');
    Fb.req('DELETE', 'rooms/' + this.code + '/members/' + uid).catch(function () {});
  },
  setReady: function (ready) {
    var nb = !ready; if (nb === this.myBuf) return;
    this.myBuf = nb; this.put('members/' + this.me + '/buf', String(nb));
  },
  gather: function (pos) {
    if (!this.isHost) return;
    this.publish(false, pos);
    this.info = Object.assign({}, this.info, { gather: this.serverNow() });
    this.put('info/gather', String(Math.floor(this.serverNow()))); this.emit();
  },
  endGather: function (pos) {
    if (!this.isHost) return;
    this.info = Object.assign({}, this.info, { gather: 0 });
    this.put('info/gather', '0');
    this.publish(true, pos);
  },
  sendSig: function (to, type, data) {
    var id = Date.now() + '_' + Math.floor(Math.random() * 1000);
    this.put('sig/' + to + '/' + id, JSON.stringify({ from: this.me, type: type, data: data }));
  },

  /* suara */
  async enableVoice() {
    if (this.voice) return;
    this.voiceError = ''; this.voiceOn = true; this.micOn = true; this.emit();
    try {
      var v = new VoiceEngine(this);
      this.voice = v;
      await v.start(this.members);
      this.heartbeat();
    } catch (e) { this.voiceFailed(e && e.name === 'NotAllowedError' ? 'izin mikrofon ditolak' : (e && e.message) || String(e)); }
  },
  toggleMic: function () { if (!this.voiceOn) return; this.micOn = !this.micOn; if (this.voice) this.voice.setMic(this.micOn); this.heartbeat(); this.emit(); },
  voiceFailed: function (msg) {
    this.voiceError = 'Suara gagal: ' + msg; this.stopVoiceEngine(); this.voiceOn = false; this.micOn = false; this.emit();
  },
  stopVoiceEngine: function () { var v = this.voice; this.voice = null; this.camOn = false; this.videos = {}; this.vels = {}; if (v) v.stop(); },
  disableVoice: function () {
    if (!this.voice) return;
    this.stopVoiceEngine(); this.voiceOn = false; this.micOn = false; this.peerState = {}; this.heartbeat(); this.emit();
  },
  /** Video call: nyalakan/matikan kamera (suara ikut aktif). */
  async toggleCam() {
    try {
      if (!this.voiceOn) await this.enableVoice();
      if (!this.voice) return;
      var on = !this.camOn;
      await this.voice.setCam(on);
      this.camOn = on; this.heartbeat(); this.emit();
    } catch (e) {
      this.camOn = false; this.voiceError = 'Kamera gagal: ' + (e && e.name === 'NotAllowedError' ? 'izin kamera ditolak' : (e && e.message) || e); this.emit();
    }
  },
};

/* Mesh WebRTC audio (maks 4). Format sinyal sama dengan Android: offer/answer = SDP, ice = JSON {mid, idx, c}. */
function VoiceEngine(room) {
  this.room = room; this.peers = {}; this.remoteSet = {}; this.pending = {}; this.audios = {}; this.stream = null; this.stopped = false;
  this.ice = [{ urls: 'stun:stun.cloudflare.com:3478' }, { urls: 'stun:stun.l.google.com:19302' }];
}
VoiceEngine.prototype = {
  async start(current) {
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }, video: false });
    this.stream.getAudioTracks().forEach(function (t) { t.enabled = true; });
    var room = this.room, self = this;
    this.watch(room.me, this.stream); // indikator bicara untuk diri sendiri (diam saat mik dimatikan)
    current.filter(function (m) { return m.uid !== room.me && m.voice; }).forEach(function (m) { self.connect(m.uid); });
  },
  getVol: function (uid) { try { var v = JSON.parse(localStorage.getItem('aph.pvol') || '{}')[uid]; return v == null ? 1 : v; } catch (e) { return 1; } },
  setVol: function (uid, v) {
    try { var o = JSON.parse(localStorage.getItem('aph.pvol') || '{}'); o[uid] = v; localStorage.setItem('aph.pvol', JSON.stringify(o)); } catch (e) {}
    if (this.audios[uid]) this.audios[uid].volume = v;
  },
  /** Pantau tingkat suara tiap lawan bicara untuk indikator "sedang bicara". */
  watch: function (uid, stream) {
    var self = this, room = this.room;
    try {
      if (!this.actx) this.actx = new (window.AudioContext || window.webkitAudioContext)();
      if (this.actx.state === 'suspended') this.actx.resume().catch(function () {});
      var src = this.actx.createMediaStreamSource(stream), an = this.actx.createAnalyser(); an.fftSize = 256; src.connect(an);
      var buf = new Uint8Array(an.fftSize); this.meters = this.meters || {};
      if (this.meters[uid]) clearInterval(this.meters[uid]);
      this.meters[uid] = setInterval(function () {
        an.getByteTimeDomainData(buf); var mx = 0; for (var i = 0; i < buf.length; i++) mx = Math.max(mx, Math.abs(buf[i] - 128));
        var sp = mx > 8; room.speaking = room.speaking || {};
        // ubah kelas langsung (tanpa emit) supaya panel chat/kotak tulis tidak digambar ulang
        if (!!room.speaking[uid] !== sp) { room.speaking[uid] = sp; document.querySelectorAll('[data-uid="' + uid + '"]').forEach(function (el) { el.classList.toggle('speaking', sp); }); }
      }, 200);
    } catch (e) { /* tanpa indikator */ }
  },
  setMic: function (on) { if (this.stream) this.stream.getAudioTracks().forEach(function (t) { t.enabled = on; }); },
  /** Nyalakan/matikan kamera lalu sambung ulang semua peserta. shortcut: renegosiasi = sambungan baru (jeda singkat), upgrade ke renegotiation bila mengganggu. */
  async setCam(on) {
    if (!this.stream || this.stopped) return;
    var self = this;
    if (on) {
      var vs = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 480 }, frameRate: { ideal: 24 } }, audio: false });
      vs.getVideoTracks().forEach(function (t) { self.stream.addTrack(t); });
      this.preview = new MediaStream(this.stream.getVideoTracks());
    } else {
      this.stream.getVideoTracks().forEach(function (t) { t.stop(); self.stream.removeTrack(t); });
      this.preview = null;
    }
    Object.keys(this.peers).forEach(function (uid) { self.connect(uid); });
  },
  newPc: function (uid) {
    var self = this, room = this.room;
    if (this.peers[uid]) try { this.peers[uid].close(); } catch (e) {}
    delete this.remoteSet[uid]; delete this.pending[uid]; delete room.videos[uid];
    var pc = new RTCPeerConnection({ iceServers: this.ice });
    if (this.stream) this.stream.getTracks().forEach(function (t) { pc.addTrack(t, self.stream); });
    pc.onicecandidate = function (e) {
      if (!e.candidate) return;
      room.sendSig(uid, 'ice', JSON.stringify({ mid: e.candidate.sdpMid, idx: e.candidate.sdpMLineIndex, c: e.candidate.candidate }));
    };
    pc.ontrack = function (e) {
      if (e.track.kind === 'video') { room.videos[uid] = new MediaStream([e.track]); room.emit(); return; }
      var a = self.audios[uid];
      if (!a) { a = new Audio(); a.autoplay = true; self.audios[uid] = a; }
      a.srcObject = e.streams[0] || new MediaStream([e.track]);
      a.volume = self.getVol(uid);
      if (window.App && App.outDev && a.setSinkId) a.setSinkId(App.outDev).catch(function () {});
      a.play().catch(function () {});
      self.watch(uid, a.srcObject);
    };
    pc.onconnectionstatechange = function () {
      var s = pc.connectionState;
      room.peerState[uid] = s === 'connected' ? 'ok' : (s === 'failed' || s === 'disconnected') ? 'gagal' : s === 'closed' ? 'tutup' : 'menyambung';
      // koneksi gagal: satu pihak (uid terkecil) menyambung ulang supaya tidak tabrakan penawaran
      if (s === 'failed' && room.me < uid) setTimeout(function () { if (!self.stopped && self.peers[uid] === pc) self.connect(uid); }, 2000);
      room.emit();
    };
    this.peers[uid] = pc; room.peerState[uid] = 'menyambung';
    return pc;
  },
  async connect(uid) {
    var pc = this.newPc(uid);
    try {
      var offer = await pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true });
      await pc.setLocalDescription(offer);
      this.room.sendSig(uid, 'offer', offer.sdp);
    } catch (e) { /* abaikan */ }
  },
  async onSignal(from, type, data) {
    var room = this.room;
    if (this.stopped || !this.stream || from === room.me) return;
    try {
      if (type === 'offer') {
        var ex = this.peers[from];
        if (ex && ex.signalingState === 'have-local-offer' && room.me < from) return;
        var pc = this.newPc(from);
        await pc.setRemoteDescription({ type: 'offer', sdp: data });
        this.remoteSet[from] = true; this.flush(from, pc);
        var ans = await pc.createAnswer();
        await pc.setLocalDescription(ans);
        room.sendSig(from, 'answer', ans.sdp);
      } else if (type === 'answer') {
        var p2 = this.peers[from]; if (!p2) return;
        await p2.setRemoteDescription({ type: 'answer', sdp: data });
        this.remoteSet[from] = true; this.flush(from, p2);
      } else if (type === 'ice') {
        var o = JSON.parse(data);
        var cand = { candidate: o.c, sdpMid: o.mid, sdpMLineIndex: o.idx };
        var p3 = this.peers[from];
        if (p3 && this.remoteSet[from]) await p3.addIceCandidate(cand).catch(function () {});
        else (this.pending[from] = this.pending[from] || []).push(cand);
      }
    } catch (e) { /* abaikan */ }
  },
  flush: function (uid, pc) { (this.pending[uid] || []).forEach(function (c) { pc.addIceCandidate(c).catch(function () {}); }); delete this.pending[uid]; },
  sync: function (members) {
    if (this.stopped) return;
    var live = {}; var room = this.room;
    members.forEach(function (m) { if (m.voice && m.uid !== room.me) live[m.uid] = true; });
    var self = this;
    Object.keys(this.peers).forEach(function (k) {
      if (live[k]) return;
      try { self.peers[k].close(); } catch (e) {}
      delete self.peers[k]; delete self.remoteSet[k]; delete self.pending[k]; delete room.peerState[k]; delete room.videos[k];
      if (self.audios[k]) { self.audios[k].srcObject = null; delete self.audios[k]; }
    });
  },
  stop: function () {
    this.stopped = true;
    var self = this;
    Object.keys(this.peers).forEach(function (k) { try { self.peers[k].close(); } catch (e) {} });
    this.peers = {};
    Object.keys(this.audios).forEach(function (k) { self.audios[k].srcObject = null; });
    this.audios = {};
    if (this.stream) this.stream.getTracks().forEach(function (t) { t.stop(); });
    this.stream = null;
    if (this.meters) Object.keys(this.meters).forEach(function (k) { clearInterval(self.meters[k]); }); this.meters = {};
    if (this.actx) { try { this.actx.close(); } catch (e) {} this.actx = null; }
    if (this.room) this.room.speaking = {};
  },
};

/* Pintu masuk Nobar untuk UI */
var Nobar = {
  room: null, busy: false, error: '', history: Store.get('nb.hist', []), listeners: new Set(),
  on: function (fn) { this.listeners.add(fn); return function () { Nobar.listeners.delete(fn); }; },
  emit: function () { Array.from(this.listeners).forEach(function (fn) { try { fn(); } catch (e) { console.error(e); } }); },
  remember: function (h) { this.history = [h].concat(this.history.filter(function (x) { return x.code !== h.code; })).slice(0, 8); Store.set('nb.hist', this.history); },
  forget: function (code) { this.history = this.history.filter(function (x) { return x.code !== code; }); Store.set('nb.hist', this.history); },
  genCode: function () { var s = ''; for (var i = 0; i < 6; i++) s += CODE_CHARS[Math.floor(Math.random() * CODE_CHARS.length)]; return s; },
  make: function (code, nick) { return new NobarRoom(code, nick, function (r) { if (Nobar.room === r) { Nobar.room = null; Nobar.emit(); } }); },

  async create(film, nick) {
    if (this.busy) return false;
    this.busy = true; this.error = ''; this.emit();
    try {
      await Fb.token(); var me = Fb.uid; var made = null;
      for (var i = 0; i < 6; i++) {
        var code = this.genCode();
        var body = { info: { host: me, created: SV, film: film.id, filmName: film.name, ctl: 'host' }, pb: { playing: false, pos: 0, at: SV, seq: 0, by: me }, members: {} };
        body.members[me] = { name: (nick() || '').trim() || 'Host', seen: SV, voice: false, mic: false };
        var r = await Fb.req('PUT', 'rooms/' + code, JSON.stringify(body), { 'If-Match': 'null_etag' });
        if (r.code === 200) { made = code; break; }
        if (r.code !== 412) { this.error = 'Server nobar menolak (kode ' + r.code + '): ' + r.text.slice(0, 160); return false; }
      }
      if (!made) { this.error = this.error || 'Gagal membuat ruangan, coba lagi'; return false; }
      Fb.req('PUT', 'roomIdx/' + made, String(Date.now())).catch(function () {});
      this.remember({ code: made, filmId: film.id, filmName: film.name, host: true });
      var room = this.make(made, nick); this.room = room; room.start(); this.cleanup();
      return true;
    } catch (e) { this.error = e.message || 'Gagal membuat ruangan'; return false; }
    finally { this.busy = false; this.emit(); }
  },
  async join(codeRaw, nick) {
    if (this.busy) return false;
    var code = String(codeRaw || '').trim().toUpperCase();
    if (code.length !== 6) { this.error = 'Kode terdiri dari 6 karakter'; this.emit(); return false; }
    this.busy = true; this.error = ''; this.emit();
    try {
      await Fb.token();
      var info = await Fb.req('GET', 'rooms/' + code + '/info');
      if (info.code !== 200 || info.text === 'null') { this.error = 'Ruangan tidak ditemukan atau sudah ditutup host'; this.forget(code); return false; }
      var ij = JSON.parse(info.text); var created = Number(ij.created) || 0;
      if (created > 0 && Date.now() - created > ROOM_MAX_AGE) { this.error = 'Ruangan sudah kedaluwarsa'; this.forget(code); return false; }
      var ms = await Fb.req('GET', 'rooms/' + code + '/members');
      if (ms.code === 200 && ms.text !== 'null') {
        var j = JSON.parse(ms.text), now = Date.now(), keys = Object.keys(j);
        var seen = function (k) { return Number((j[k] || {}).seen) || 0; };
        keys.filter(function (k) { return now - seen(k) > 90000 && k !== Fb.uid; }).forEach(function (k) { Fb.req('DELETE', 'rooms/' + code + '/members/' + k).catch(function () {}); });
        var alive = keys.filter(function (k) { return now - seen(k) <= 90000 && k !== Fb.uid; }).length;
        if (alive >= MAX_MEMBERS) { this.error = 'Ruangan penuh (maks ' + MAX_MEMBERS + ' orang)'; return false; }
      }
      var kk = await Fb.req('GET', 'rooms/' + code + '/kick/' + Fb.uid);
      if (kk.code === 200 && kk.text.trim() === 'true') { this.error = 'Kamu dikeluarkan dari ruangan ini'; this.forget(code); return false; }
      var room = this.make(code, nick);
      var err = await room.enter();
      if (err) { this.error = err; return false; }
      this.remember({ code: code, filmId: ij.film || '', filmName: ij.filmName || '', host: ij.host === Fb.uid });
      this.room = room; room.start();
      return true;
    } catch (e) { this.error = e.message || 'Gagal bergabung'; return false; }
    finally { this.busy = false; this.emit(); }
  },
  leave: function () { var r = this.room; this.room = null; if (r) r.leave(false); this.emit(); },
  close: function () { var r = this.room; if (!r) return; this.forget(r.code); this.room = null; r.leave(true); this.emit(); },
  async cleanup() {
    try {
      var cutoff = Date.now() - ROOM_MAX_AGE;
      var r = await Fb.req('GET', 'roomIdx', null, null, '&orderBy=%22%24value%22&endAt=' + cutoff + '&limitToFirst=10');
      if (r.code === 200 && r.text !== 'null') {
        var j = JSON.parse(r.text);
        for (var k of Object.keys(j)) { await Fb.req('DELETE', 'rooms/' + k); await Fb.req('DELETE', 'roomIdx/' + k); }
      }
    } catch (e) {}
  },
};
