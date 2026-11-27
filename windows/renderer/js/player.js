'use strict';
/* Pemutar video: ekstraksi X, MP4/HLS, kualitas, kecepatan, lompat, timer tidur, kunci layar, crop, EQ, dan sinkron Nobar. */
var Player = {
  cur: null,
  open: function (opts) { if (this.cur) this.cur.destroy(true); this.cur = createPlayer(opts); },
  close: function () { if (this.cur) this.cur.destroy(false); },
};

var SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
var ASPECTS = [['contain', 'Sesuai layar'], ['cover', 'Isi layar'], ['fill', 'Regangkan']];
var EQ_FREQS = [60, 230, 910, 3600, 14000];

/* Efek suara lewat Web Audio: equalizer 5 pita + "Bioskop" (bass, lebar suara, penguat) */
function AudioFx(video) {
  this.ctx = null; this.video = video; this.ready = false;
}
AudioFx.prototype = {
  init: function () {
    if (this.ready) return;
    try {
      var ctx = this.ctx = new (window.AudioContext || window.webkitAudioContext)();
      var src = ctx.createMediaElementSource(this.video);
      this.filters = EQ_FREQS.map(function (f) { var b = ctx.createBiquadFilter(); b.type = 'peaking'; b.frequency.value = f; b.Q.value = 1; b.gain.value = 0; return b; });
      this.low = ctx.createBiquadFilter(); this.low.type = 'lowshelf'; this.low.frequency.value = 120; this.low.gain.value = 0;
      this.high = ctx.createBiquadFilter(); this.high.type = 'highshelf'; this.high.frequency.value = 8000; this.high.gain.value = 0;
      var split = ctx.createChannelSplitter(2), merge = ctx.createChannelMerger(2);
      this.gLL = ctx.createGain(); this.gRL = ctx.createGain(); this.gRR = ctx.createGain(); this.gLR = ctx.createGain();
      split.connect(this.gLL, 0); split.connect(this.gLR, 0); split.connect(this.gRR, 1); split.connect(this.gRL, 1);
      this.gLL.connect(merge, 0, 0); this.gRL.connect(merge, 0, 0); this.gRR.connect(merge, 0, 1); this.gLR.connect(merge, 0, 1);
      this.loud = ctx.createGain();
      var node = src;
      this.filters.forEach(function (f) { node.connect(f); node = f; });
      node.connect(this.low); this.low.connect(this.high); this.high.connect(split);
      merge.connect(this.loud); this.loud.connect(ctx.destination);
      this.an = ctx.createAnalyser(); this.an.fftSize = 512; this.loud.connect(this.an); this.buf = new Uint8Array(this.an.fftSize);
      this.ready = true;
    } catch (e) { this.ready = false; }
  },
  /** true bila ada sinyal di keluaran efek (membedakan efek yang membisukan suara). */
  active: function () { if (!this.ready) return true; this.an.getByteTimeDomainData(this.buf); for (var i = 0; i < this.buf.length; i++) if (this.buf[i] !== 128) return true; return false; },
  apply: function (surround, eq) {
    if (this.disabled) return;
    var on = surround || eq.some(function (v) { return Math.abs(v) > 0.01; });
    if (!on && !this.ready) return; // tidak perlu menyentuh audio bila efek mati
    this.init(); if (!this.ready) return;
    if (this.ctx.state === 'suspended') this.ctx.resume();
    var k = surround ? 0.3 : 0;
    this.filters.forEach(function (f, i) { f.gain.value = (eq[i] || 0) * 12; });
    this.low.gain.value = surround ? 5 : 0; this.high.gain.value = surround ? 3 : 0;
    this.gLL.gain.value = 1 + k; this.gRR.gain.value = 1 + k; this.gLR.gain.value = -k; this.gRL.gain.value = -k;
    this.loud.gain.value = surround ? 1.25 : 1;
  },
};

function createPlayer(opts) {
  var film = opts.film, room = opts.room || null;
  var nextFn = opts.next || function () { return null; };
  var phase = 0, buffering = false, ended = false, errText = '', errDetail = '', stageText = 'Mengambil video…';
  var variants = [], variant = null, hls = null, aspect = 0, panel = '', locked = false, sleepEnd = 0, hudT = 0, idleT = 0, alive = true;
  var timers = [], disposers = [];
  var dock = Store.get('dockOpen', true) && !!room;
  var chatOpen = dock;
  var subs = [], subOn = true, subDelay = 0, subTxt = '', introShown = false, pv = null, pvReady = false, silentTicks = 0, noAudioWarned = false;
  var base = String(film.id).split('#')[0], isEp = String(film.id).indexOf('#') > 0;
  var userVol = Math.max(0.2, Store.get('vol', 1)); // volume pilihan pengguna; volume efektif bisa diredam saat mik nobar hidup

  var video = h('video', { playsinline: true, crossorigin: 'anonymous' });
  video.autoplay = false;
  var fx = new AudioFx(video); if (opts.noFx) fx.disabled = true;
  var ctl = h('div', { class: 'ctl' });
  var center = h('div', { class: 'center' });
  var hud = h('div', { class: 'hud', style: { display: 'none' } });
  var stage = h('div', { class: 'stage' }, video, center, ctl, hud);
  var root = h('div', { class: 'player' }, stage);
  document.body.append(root);

  var every = function (fn, ms) { var t = setInterval(fn, ms); timers.push(t); return t; };
  var showHud = function (t) { hud.textContent = t; hud.style.display = 'block'; clearTimeout(hudT); hudT = setTimeout(function () { hud.style.display = 'none'; }, 1200); };
  var allowed = function () { if (!room || room.canControl()) return true; showHud('Hanya host yang bisa mengatur'); return false; };
  var pos = function () { return Math.floor((video.currentTime || 0) * 1000); };
  var dur = function () { return isFinite(video.duration) ? Math.floor(video.duration * 1000) : 0; };
  var pub = function () { if (room) room.publish(!video.paused, pos()); };

  /* ---- memuat ---- */
  function destroyHls() { if (hls) { try { hls.destroy(); } catch (e) {} hls = null; } }
  function startPlay(v, seekMs, keepPlaying) {
    pvReady = false; if (pv) { pv.removeAttribute('src'); pv = null; }
    variant = v; destroyHls(); ended = false; buffering = true;
    var apply = function () {
      var pr = App.progress[film.id];
      var seek = seekMs;
      if (seek == null) seek = (!room && !opts.fromStart && pr && App.frac(pr) >= 0.02 && App.frac(pr) <= 0.95) ? Math.max(0, pr.pos - 2000) : 0;
      if (room) { var e = room.expected(); if (e > 0) video.currentTime = e / 1000; video.playbackRate = 1; if (room.pb.playing) video.play().catch(function () {}); }
      else {
        if (seek > 0) video.currentTime = seek / 1000;
        video.playbackRate = App.speed;
        if (keepPlaying !== false) video.play().catch(function () {});
      }
      phase = 1; render();
    };
    video.onloadedmetadata = function () { apply(); applyView(); };
    if (v.hls && window.Hls && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true }); hls.loadSource(v.url); hls.attachMedia(video);
      hls.on(Hls.Events.ERROR, function (e, d) { if (d.fatal) fail('hls ' + d.type + ' ' + d.details); });
      hls.on(Hls.Events.MANIFEST_PARSED, function () { if (!phase) {} });
    } else { video.src = v.url; video.load(); }
  }
  async function load() {
    phase = 0; errText = ''; stageText = 'Mengambil video…'; render();
    if (App.downloads[film.id] && window.aph) {
      var local = await aph.dlExists(film.id);
      if (local) { variants = []; if (!alive) return; return startPlay({ url: 'file:///' + local.path.replace(/\\/g, '/'), height: 0, hls: false, local: true }, opts.seekMs); }
      delete App.downloads[film.id]; Store.set('downloads', App.downloads);
    }
    try {
      variants = await Extractor.extract(film.link, function (i, n) { stageText = 'Mengambil video… (' + i + '/' + n + ')'; render(); });
    } catch (e) { return fail(e.message || String(e)); }
    if (!alive) return;
    var v = Extractor.chooseVariant(variants, App.quality);
    if (!v) return fail('Tweet ini tidak berisi video');
    startPlay(v, opts.seekMs);
  }
  function fail(detail) { phase = 2; errDetail = String(detail); errText = friendlyError(detail); render(); }
  video.addEventListener('error', function () { var c = video.error ? video.error.code : 0; fail('media error ' + c + (video.error && video.error.message ? ' ' + video.error.message : '')); });
  video.addEventListener('waiting', function () { buffering = true; renderCenter(); });
  video.addEventListener('playing', function () { buffering = false; renderCenter(); });
  video.addEventListener('canplay', function () { buffering = false; renderCenter(); });
  video.addEventListener('ended', function () {
    ended = true; if (!room) App.saveProgress(film.id, dur(), dur());
    var n = nextFn();
    if (n && !room) { if (opts.auto && App.autoNext) autoNextBox(n); else { panel = 'next'; showCtl(); } }
    render();
  });
  video.addEventListener('play', function () { updateControls(); }); video.addEventListener('pause', function () { updateControls(); });
  video.addEventListener('timeupdate', function () { updateTime(); drawSub(); drawIntro(); });
  video.addEventListener('volumechange', function () { updateControls(); });
  video.addEventListener('loadedmetadata', function () { applyView(); });

  /* ---- tampilan: crop & aspek ---- */
  function applyView() {
    var W = stage.clientWidth, H = stage.clientHeight;
    var hasCrop = App.crop.some(function (x) { return x > 0.001; });
    video.style.objectFit = ASPECTS[aspect][0];
    if (App.cropOn && hasCrop && video.videoWidth && W && H) {
      var l = App.crop[0], t = App.crop[1], r = App.crop[2], b = App.crop[3];
      var vw = video.videoWidth, vh = video.videoHeight, sc = Math.min(W / vw, H / vh), dw = vw * sc, dh = vh * sc, ox = (W - dw) / 2, oy = (H - dh) / 2;
      var cw = dw * (1 - l - r), ch = dh * (1 - t - b), s = Math.min(W / cw, H / ch), cx = ox + dw * l + cw / 2, cy = oy + dh * t + ch / 2;
      video.style.objectFit = 'contain'; video.style.transformOrigin = cx + 'px ' + cy + 'px';
      video.style.transform = 'translate(' + (W / 2 - cx) + 'px,' + (H / 2 - cy) + 'px) scale(' + s + ')';
      video.style.clipPath = 'inset(' + (oy + dh * t) + 'px ' + (W - (ox + dw * (1 - r))) + 'px ' + (H - (oy + dh * (1 - b))) + 'px ' + (ox + dw * l) + 'px)';
    } else { video.style.transform = ''; video.style.clipPath = ''; video.style.transformOrigin = ''; }
  }
  var ro = new ResizeObserver(applyView); ro.observe(stage);
  function applyFx() { fx.apply(App.surround && !(room && room.voiceOn), App.eq); }

  /* ---- putar otomatis episode berikutnya ---- */
  var nextBox = null, nextT = 0;
  function autoNextBox(n) {
    var left = 8;
    var lab = h('span');
    nextBox = h('div', { class: 'nextbox' }, h('div', {}, 'Berikutnya: ', h('b', {}, n.name)), lab,
      h('div', { class: 'acts', style: { marginTop: '8px' } }, btn('PUTAR SEKARANG', null, true, function () { clearInterval(nextT); goNext(n); }), btn('BATAL', null, false, function () { clearInterval(nextT); nextBox.remove(); nextBox = null; })));
    stage.append(nextBox);
    var tick = function () { lab.textContent = 'Putar otomatis dalam ' + left + ' detik'; if (left-- <= 0) { clearInterval(nextT); goNext(n); } };
    tick(); nextT = setInterval(tick, 1000); timers.push(nextT);
  }

  /* ---- subtitle (.srt / .vtt), ditampilkan sendiri agar mudah digeser waktunya ---- */
  function parseSub(text) {
    var out = [], blocks = String(text).replace(/\r/g, '').replace(/^\uFEFF/, '').split(/\n\n+/);
    var tm = function (x) { var m = /(?:(\d+):)?(\d+):(\d+)[.,](\d+)/.exec(x); return m ? ((+m[1] || 0) * 3600 + (+m[2]) * 60 + (+m[3]) + (+('0.' + m[4]))) : NaN; };
    blocks.forEach(function (b) {
      var lines = b.split('\n'), i = lines.findIndex(function (l) { return l.indexOf('-->') >= 0; });
      if (i < 0) return;
      var parts = lines[i].split('-->'); var s0 = tm(parts[0]), e0 = tm(parts[1]);
      var t = lines.slice(i + 1).join('\n').replace(/<[^>]+>/g, '').replace(/\{[^}]*\}/g, '').trim();
      if (!isNaN(s0) && !isNaN(e0) && t) out.push({ s: s0, e: e0, t: t });
    });
    return out;
  }
  var subEl = h('div', { class: 'subs' }); stage.append(subEl);
  function drawSub() {
    if (!subs.length || !subOn) { if (subTxt) { subTxt = ''; subEl.textContent = ''; } return; }
    var t = (video.currentTime || 0) - subDelay, txt = '';
    for (var i = 0; i < subs.length; i++) { if (subs[i].s <= t && t < subs[i].e) { txt = subs[i].t; break; } }
    if (txt !== subTxt) { subTxt = txt; subEl.textContent = txt; }
    subEl.style.fontSize = (App.subSize || 24) + 'px';
  }
  async function loadSubFile(file) {
    try {
      var buf = await file.arrayBuffer(); var txt = new TextDecoder('utf-8').decode(buf);
      if (txt.indexOf('\uFFFD') >= 0) txt = new TextDecoder('windows-1252').decode(buf);
      subs = parseSub(txt); subOn = true; subDelay = 0;
      showHud(subs.length ? 'Subtitle dimuat (' + subs.length + ' baris)' : 'Subtitle tidak terbaca');
    } catch (e) { showHud('Subtitle gagal dimuat'); }
    renderCtl();
  }
  var subInput = h('input', { type: 'file', accept: '.srt,.vtt', style: { display: 'none' } });
  subInput.addEventListener('change', function () { if (subInput.files[0]) loadSubFile(subInput.files[0]); subInput.value = ''; });
  root.append(subInput);
  stage.addEventListener('dragover', function (e) { e.preventDefault(); });
  stage.addEventListener('drop', function (e) { e.preventDefault(); var f = e.dataTransfer.files[0]; if (f && /\.(srt|vtt)$/i.test(f.name)) loadSubFile(f); });

  /* ---- Picture-in-Picture ---- */
  function togglePip() {
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(function () {});
    else if (video.requestPictureInPicture) video.requestPictureInPicture().catch(function () { showHud('PiP tidak tersedia'); });
  }

  /* ---- keluaran audio ---- */
  function applySink() {
    if (!App.outDev) return;
    if (video.setSinkId) video.setSinkId(App.outDev).catch(function () { App.set('outDev', ''); });
    if (fx.ctx && fx.ctx.setSinkId) fx.ctx.setSinkId(App.outDev).catch(function () {});
  }
  var devs = [];
  function loadDevices() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return Promise.resolve();
    return navigator.mediaDevices.enumerateDevices().then(function (l) { devs = l.filter(function (d) { return d.kind === 'audiooutput'; }); }).catch(function () {});
  }
  var onDev = function () { loadDevices().then(function () { if (App.outDev && !devs.some(function (d) { return d.deviceId === App.outDev; })) { App.set('outDev', ''); showHud('Perangkat audio berganti, kembali ke bawaan'); video.setSinkId && video.setSinkId(''); } }); };
  if (navigator.mediaDevices) navigator.mediaDevices.addEventListener('devicechange', onDev);
  loadDevices();

  /* ---- pratinjau seek ---- */
  var tip = h('div', { class: 'tip', style: { display: 'none' } }); var tipCv = h('canvas', { width: 160, height: 90 }); var tipT = h('div');
  tip.append(tipCv, tipT);
  var pvT = 0;
  function pvSrc() {
    if (pv || !variants.length) return;
    var v = variants.filter(function (x) { return !x.hls; }).sort(function (a, b) { return a.pixels - b.pixels; })[0];
    if (!v) return;
    pv = document.createElement('video'); pv.muted = true; pv.preload = 'metadata'; pv.crossOrigin = 'anonymous';
    pv.addEventListener('seeked', function () { try { tipCv.getContext('2d').drawImage(pv, 0, 0, 160, 90); } catch (e) {} });
    pv.src = v.url;
  }
  function tipMove(e) {
    var r = seek.getBoundingClientRect(), f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)), d = video.duration;
    if (!isFinite(d)) return;
    tip.style.display = 'block'; tip.style.left = Math.min(r.width - 90, Math.max(90, f * r.width)) + 'px';
    tipT.textContent = fmtTime(f * d * 1000);
    tipCv.style.display = variants.length ? 'block' : 'none';
    pvSrc(); clearTimeout(pvT);
    if (pv) pvT = setTimeout(function () { try { pv.currentTime = f * d; } catch (x) {} }, 90);
  }

  /* ---- lewati intro (ditandai manual per series) ---- */
  var introBtnEl = h('button', { class: 'btn primary skipintro', style: { display: 'none' } }, 'LEWATI INTRO');
  introBtnEl.addEventListener('click', function () { var r = App.intro[base]; if (r) { video.currentTime = r[1]; pub(); } });
  stage.append(introBtnEl);
  function drawIntro() {
    var r = isEp && App.intro[base], t = video.currentTime || 0;
    var show = !!(r && !locked && r[1] > r[0] && t >= r[0] && t < r[1] - 1);
    if (show !== introShown) { introShown = show; introBtnEl.style.display = show ? 'block' : 'none'; }
  }

  /* ---- tombol media Windows, baki, dan taskbar ---- */
  function reportState() {
    if (window.aph) aph.setState({ playing: !video.paused, title: film.name, hasNext: !room && !!nextFn() });
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = video.paused ? 'paused' : 'playing';
  }
  if ('mediaSession' in navigator) {
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: film.name, artist: 'Aphodius' });
      navigator.mediaSession.setActionHandler('play', function () { if (video.paused) togglePlay(); });
      navigator.mediaSession.setActionHandler('pause', function () { if (!video.paused) togglePlay(); });
      navigator.mediaSession.setActionHandler('seekbackward', function () { seekBy(-App.skipSec * 1000); });
      navigator.mediaSession.setActionHandler('seekforward', function () { seekBy(App.skipSec * 1000); });
      navigator.mediaSession.setActionHandler('nexttrack', function () { var n = !room && nextFn(); if (n) goNext(n); });
    } catch (e) {}
  }
  var offCmd = window.aph ? aph.onCmd(function (c) {
    if (c === 'toggle') togglePlay(); else if (c === 'back') seekBy(-App.skipSec * 1000);
    else if (c === 'next') { var n = !room && nextFn(); if (n) goNext(n); }
  }) : null;
  video.addEventListener('play', reportState); video.addEventListener('pause', reportState);

  /* ---- deteksi masalah suara ---- */
  every(function () {
    if (phase !== 1 || video.paused || video.muted || video.volume === 0 || (video.currentTime || 0) < 3) { silentTicks = 0; return; }
    if (video.webkitAudioDecodedByteCount === 0) {
      if (!noAudioWarned) { noAudioWarned = true; showHud('Video ini tidak punya trek suara'); if (window.aph) aph.log('tanpa audio: ' + film.id); }
      return;
    }
    if (fx.ready && !fx.active()) {
      if (++silentTicks >= 3) {
        if (window.aph) aph.log('efek suara membisukan audio, dimatikan');
        App.surround = false; Store.set('surround', false); App.eq = EQ_FLAT.slice(); Store.set('eq', App.eq);
        toast('Efek suara dimatikan karena membuat suara hilang', 4000);
        var o2 = Object.assign({}, opts, { seekMs: pos(), fromStart: false, noFx: true }); destroy(true); Player.open(o2);
      }
    } else silentTicks = 0;
  }, 1000);

  /* ---- kendali ---- */
  function seekBy(ms) {
    if (!allowed()) return;
    var d = video.duration; var max = isFinite(d) ? d : 1e9;
    video.currentTime = Math.min(max, Math.max(0, video.currentTime + ms / 1000));
    pub(); showHud((ms > 0 ? '+' : '−') + Math.abs(ms / 1000) + ' dtk');
  }
  function togglePlay() {
    if (!allowed()) return;
    if (ended) { video.currentTime = 0; ended = false; video.play(); }
    else if (video.paused) video.play().catch(function () {}); else video.pause();
    pub(); showHud(video.paused ? 'Jeda' : 'Putar');
  }
  function seekTo(frac) { if (!allowed() || !isFinite(video.duration)) return; video.currentTime = frac * video.duration; pub(); }
  function toggleFull() { if (document.fullscreenElement) document.exitFullscreen(); else root.requestFullscreen().catch(function () {}); }
  function setVol(d) { video.muted = false; userVol = Math.min(1, Math.max(0, userVol + d)); applyVol(); showHud('Volume ' + Math.round(userVol * 100) + '%' + (ducked() ? ' (diredam)' : '')); }
  /* redam suara film saat mik nobar hidup (pengaturan "Redam suara film saat mik hidup") */
  var DUCK = 0.3;
  function ducked() { return !!(room && App.duck && room.voiceOn && room.micOn); }
  function volTarget() { return Math.min(1, Math.max(0, userVol * (ducked() ? DUCK : 1))); }
  function applyVol() { if (userVol >= 0.2) Store.set('vol', userVol); video.volume = volTarget(); updateControls(); }
  every(function () { // transisi halus saat redam berubah
    var t = volTarget(), d = t - video.volume;
    if (Math.abs(d) > 0.005) video.volume = Math.abs(d) < 0.04 ? t : video.volume + (d > 0 ? 0.04 : -0.04);
  }, 50);
  function leave() {
    if (!room) { if (phase === 1 && dur() > 0) App.saveProgress(film.id, ended ? dur() : pos(), dur()); }
    else if (room.canControl() && phase === 1) room.publish(false, pos());
    destroy(false); if (opts.onBack) opts.onBack();
  }
  function destroy(replace) {
    if (!alive) return; alive = false;
    if (!room && phase === 1 && !replace && dur() > 0) App.saveProgress(film.id, ended ? dur() : pos(), dur());
    timers.forEach(clearInterval); disposers.forEach(function (d) { d(); });
    if (room) room.setReady(false);
    destroyHls(); try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) {}
    try { fx.ctx && fx.ctx.close(); } catch (e) {}
    ro.disconnect(); clearInterval(nextT); if (offCmd) offCmd(); if (pv) { pv.removeAttribute('src'); pv = null; }
    if (navigator.mediaDevices) navigator.mediaDevices.removeEventListener('devicechange', onDev);
    if (window.aph) aph.setState({ title: '', playing: false, hasNext: false });
    if (document.pictureInPictureElement) document.exitPictureInPicture().catch(function () {});
    document.removeEventListener('keydown', onKey, true); document.removeEventListener('fullscreenchange', onFs);
    if (document.fullscreenElement) document.exitFullscreen().catch(function () {});
    root.remove(); if (Player.cur === api) Player.cur = null; document.title = 'Aphodius';
  }

  /* ---- bilah kendali ---- */
  var timeEl = h('span', { class: 'time' }), barFill = h('i'), barKnob = h('b'), barBuf = h('div', { class: 'buf' });
  var playBtn = h('button', { class: 'pbtn', onclick: togglePlay });
  var muteBtn = h('button', { class: 'pbtn', onclick: function () { toggleMute(); } });
  var volEl = h('input', { type: 'range', min: 0, max: 100, class: 'vol', title: 'Volume' });
  volEl.addEventListener('input', function () { video.muted = false; userVol = volEl.value / 100; applyVol(); });
  volEl.addEventListener('wheel', function (e) { e.stopPropagation(); });
  function toggleMute() { if (video.muted || userVol === 0) { video.muted = false; if (userVol < 0.05) userVol = 0.5; applyVol(); } else video.muted = true; }
  var dragging = false;
  var seek = h('div', { class: 'seek' }, h('div', { class: 'bar' }, barBuf, barFill, barKnob));
  seek.addEventListener('mouseleave', function () { tip.style.display = 'none'; });
  seek.addEventListener('mousemove', tipMove);
  var seekFrac = function (e) { var r = seek.getBoundingClientRect(); return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)); };
  seek.append(tip);
  seek.addEventListener('mousedown', function (e) { dragging = true; seekTo(seekFrac(e)); });
  window.addEventListener('mousemove', function (e) { if (dragging) seekTo(seekFrac(e)); });
  window.addEventListener('mouseup', function () { dragging = false; });
  function updateTime() {
    var d = video.duration, c = video.currentTime || 0;
    timeEl.textContent = fmtTime(c * 1000) + ' / ' + (isFinite(d) ? fmtTime(d * 1000) : '--:--');
    var f = isFinite(d) && d > 0 ? c / d : 0;
    barFill.style.width = (f * 100) + '%'; barKnob.style.left = (f * 100) + '%';
    try { if (video.buffered.length && isFinite(d) && d > 0) barBuf.style.width = (video.buffered.end(video.buffered.length - 1) / d * 100) + '%'; } catch (e) {}
  }
  function updateControls() {
    clear(playBtn); playBtn.append(icon(video.paused ? 'play' : 'pause', 22));
    clear(muteBtn); muteBtn.append(icon(video.muted || userVol === 0 ? 'micoff' : 'vol', 20));
    volEl.value = video.muted ? 0 : Math.round(userVol * 100);
  }
  var qLabel = function () { return 'Kualitas · ' + (App.quality === 0 ? 'Otomatis' : App.quality + 'p'); };
  function pbtn(iconName, label, on, fn) { return h('button', { class: 'pbtn' + (on ? ' on' : ''), onclick: function (e) { e.stopPropagation(); fn(); } }, icon(iconName, 18), label); }
  function togglePanel(n) { panel = panel === n ? '' : n; renderCtl(); }
  function renderCtl() {
    clear(ctl);
    if (locked) {
      ctl.append(h('div'), h('div'));
      return;
    }
    var next = !room ? nextFn() : null;
    var top = h('div', { class: 'top' }, h('button', { class: 'pbtn', onclick: leave, title: 'Kembali (Esc)' }, icon('back', 22)),
      h('div', { class: 'ti' }, film.name + (room ? '  ·  Nobar ' + room.code : '')));
    if (room) {
      top.append(pbtn('chat', chatOpen ? 'Tutup chat' : 'Chat', chatOpen, function () { chatOpen = !chatOpen; renderDock(); renderCtl(); }));
    }
    var bot = h('div', { class: 'bot' }, seek, h('div', { class: 'btns' }, playBtn,
      pbtn('rew', '', false, function () { seekBy(-App.skipSec * 1000); }), pbtn('fwd', '', false, function () { seekBy(App.skipSec * 1000); }),
      muteBtn, volEl, timeEl, h('div', { class: 'sp' }),
      variants.length ? pbtn('quality', qLabel(), panel === 'quality', function () { togglePanel('quality'); }) : null,
      !room ? pbtn('speed', 'Kecepatan · ' + fmtSpeed(App.speed), panel === 'speed', function () { togglePanel('speed'); }) : null,
      pbtn('skip', 'Lompat · ' + App.skipSec + ' dtk', panel === 'skip', function () { togglePanel('skip'); }),
      pbtn('moon', 'Timer tidur' + (sleepEnd ? ' · ' + (Math.floor((sleepEnd - Date.now()) / 60000) + 1) + ' mnt' : ''), panel === 'sleep', function () { togglePanel('sleep'); }),
      pbtn('aspect', ASPECTS[aspect][1], panel === 'aspect', function () { togglePanel('aspect'); }),
      pbtn('crop', 'Crop · ' + (App.cropOn && App.crop.some(function (x) { return x > 0.001; }) ? 'Aktif' : 'Mati'), panel === 'crop', function () { togglePanel('crop'); }),
      pbtn('sound', 'Suara', panel === 'sound', function () { togglePanel('sound'); }),
      pbtn('chat', 'Subtitle' + (subs.length ? ' · ' + (subOn ? 'Aktif' : 'Mati') : ''), panel === 'sub', function () { togglePanel('sub'); }),
      isEp ? pbtn('skip', 'Intro', panel === 'intro', function () { togglePanel('intro'); }) : null,
      pbtn('ext', 'PiP', !!document.pictureInPictureElement, togglePip),
      room ? pbtn('star', 'Reaksi', panel === 'react', function () { togglePanel('react'); }) : null,
      room ? pbtn(room.camOn ? 'cam' : 'camoff', room.camOn ? 'Kamera aktif' : 'Kamera', room.camOn, function () { room.toggleCam(); }) : null,
      room ? pbtn(room.voiceOn && room.micOn ? 'mic' : 'micoff', !room.voiceOn ? 'Suara' : room.micOn ? 'Mik aktif' : 'Mik mati', room.voiceOn && room.micOn, function () { micAction(room); }) : null,
      next ? pbtn('next', 'Berikutnya', panel === 'next', function () { togglePanel('next'); }) : null,
      pbtn('lock', 'Kunci', false, function () { locked = true; panel = ''; renderCtl(); showHud('Layar dikunci. Tekan tombol gembok untuk membuka'); }),
      pbtn('full', '', false, toggleFull)));
    ctl.append(top, bot);
    var m = buildMenu(next); if (m) stage.append(m), (ctl.menu = m);
    updateControls(); updateTime();
  }
  var menuEl = null;
  function buildMenu(next) {
    if (menuEl) { menuEl.remove(); menuEl = null; }
    if (!panel || locked) return null;
    var m = h('div', { class: 'menu', onclick: function (e) { e.stopPropagation(); } });
    var item = function (label, on, fn) { return h('button', { class: on ? 'on' : '', onclick: fn }, label, on ? icon('check', 16) : null); };
    if (panel === 'quality') {
      m.append(h('h5', {}, 'KUALITAS'));
      m.append(item('Otomatis', App.quality === 0, function () { setQuality(0); }));
      var hs = []; variants.filter(function (v) { return !v.hls && v.height; }).forEach(function (v) { if (hs.indexOf(v.height) < 0) hs.push(v.height); });
      hs.sort(function (a, b) { return b - a; }).forEach(function (q) { m.append(item(q + 'p', App.quality === q, function () { setQuality(q); })); });
    } else if (panel === 'speed') {
      m.append(h('h5', {}, 'KECEPATAN'));
      SPEEDS.forEach(function (s) { m.append(item(fmtSpeed(s), App.speed === s, function () { App.set('speed', s); video.playbackRate = s; panel = ''; renderCtl(); })); });
    } else if (panel === 'skip') {
      m.append(h('h5', {}, 'LOMPAT'));
      [5, 10, 30].forEach(function (s) { m.append(item(s + ' detik', App.skipSec === s, function () { App.set('skipSec', s); panel = ''; renderCtl(); })); });
    } else if (panel === 'sleep') {
      m.append(h('h5', {}, 'TIMER TIDUR'));
      m.append(item('Mati', !sleepEnd, function () { sleepEnd = 0; panel = ''; renderCtl(); }));
      [15, 30, 45, 60].forEach(function (mn) { m.append(item(mn + ' menit', false, function () { sleepEnd = Date.now() + mn * 60000; panel = ''; showHud('Timer tidur ' + mn + ' menit'); renderCtl(); })); });
    } else if (panel === 'aspect') {
      m.append(h('h5', {}, 'RASIO'));
      ASPECTS.forEach(function (a, i) { m.append(item(a[1], aspect === i, function () { aspect = i; applyView(); panel = ''; renderCtl(); })); });
    } else if (panel === 'crop') {
      m.append(h('h5', {}, 'CROP (sembunyikan banner)'));
      var names = ['Kiri', 'Atas', 'Kanan', 'Bawah'];
      names.forEach(function (n, i) {
        var inp = h('input', { type: 'range', min: 0, max: 45, value: Math.round(App.crop[i] * 100) });
        inp.addEventListener('input', function () { var c = App.crop.slice(); c[i] = inp.value / 100; App.crop = c; Store.set('crop', c); App.cropOn = c.some(function (x) { return x > 0.001; }); Store.set('cropOn', App.cropOn); lab.textContent = n + ' ' + inp.value + '%'; applyView(); });
        var lab = h('span', {}, n + ' ' + Math.round(App.crop[i] * 100) + '%');
        m.append(h('div', { class: 'sl' }, lab, inp));
      });
      m.append(item('Reset crop', false, function () { App.crop = [0, 0, 0, 0]; App.cropOn = false; Store.set('crop', App.crop); Store.set('cropOn', false); applyView(); panel = ''; renderCtl(); }));
    } else if (panel === 'sound') {
      m.append(h('h5', {}, 'SUARA'));
      var vo = room && room.voiceOn;
      m.append(item(vo ? 'Bioskop (nonaktif saat suara nobar)' : 'Bioskop', App.surround && !vo, function () { if (vo) return; App.set('surround', !App.surround); applyFx(); renderCtl(); }));
      EQ_PRESETS.forEach(function (p) { m.append(item('EQ ' + p[0], JSON.stringify(App.eq) === JSON.stringify(p[1]), function () { App.set('eq', p[1].slice()); applyFx(); renderCtl(); })); });
      m.append(h('h5', {}, 'KELUARAN AUDIO'));
      m.append(item('Bawaan Windows', !App.outDev, function () { App.set('outDev', ''); video.setSinkId && video.setSinkId(''); if (fx.ctx && fx.ctx.setSinkId) fx.ctx.setSinkId(''); renderCtl(); }));
      devs.filter(function (d) { return d.deviceId !== 'default' && d.deviceId !== 'communications'; }).forEach(function (d, i) { m.append(item(d.label || 'Perangkat ' + (i + 1), App.outDev === d.deviceId, function () { App.set('outDev', d.deviceId); applySink(); renderCtl(); })); });
    } else if (panel === 'sub') {
      m.append(h('h5', {}, 'SUBTITLE'));
      m.append(item('Pilih file (.srt / .vtt)…', false, function () { subInput.click(); }));
      if (subs.length) {
        m.append(item(subOn ? 'Tampilkan: ya' : 'Tampilkan: tidak', subOn, function () { subOn = !subOn; drawSub(); renderCtl(); }));
        m.append(item('Waktu: ' + (subDelay >= 0 ? '+' : '') + subDelay.toFixed(1) + ' dtk  (lebih lambat)', false, function () { subDelay += 0.5; drawSub(); renderCtl(); }));
        m.append(item('Waktu: lebih cepat', false, function () { subDelay -= 0.5; drawSub(); renderCtl(); }));
        [20, 24, 30, 38].forEach(function (z) { m.append(item('Ukuran ' + z, (App.subSize || 24) === z, function () { App.set('subSize', z); drawSub(); renderCtl(); })); });
        m.append(item('Hapus subtitle', false, function () { subs = []; drawSub(); panel = ''; renderCtl(); }));
      } else m.append(h('div', { class: 'sl' }, 'Atau seret file .srt ke jendela pemutar.'));
    } else if (panel === 'intro') {
      var r = App.intro[base];
      m.append(h('h5', {}, 'INTRO SERIES'));
      m.append(h('div', { class: 'sl' }, r ? 'Intro: ' + fmtTime(r[0] * 1000) + ' – ' + fmtTime(r[1] * 1000) : 'Belum ditandai. Tandai sekali, berlaku untuk semua episode.'));
      m.append(item('Tandai AWAL intro di sini', false, function () { var c = video.currentTime; var cur = App.intro[base] || [c, c + 60]; App.setIntro(base, [c, Math.max(c + 1, cur[1])]); renderCtl(); showHud('Awal intro ditandai'); }));
      m.append(item('Tandai AKHIR intro di sini', false, function () { var c = video.currentTime; var cur = App.intro[base] || [Math.max(0, c - 60), c]; App.setIntro(base, [Math.min(cur[0], c - 1), c]); renderCtl(); showHud('Akhir intro ditandai'); }));
      if (r) m.append(item('Hapus tanda intro', false, function () { App.setIntro(base, null); panel = ''; renderCtl(); }));
      m.append(item('Putar otomatis episode berikutnya: ' + (App.autoNext ? 'ya' : 'tidak'), App.autoNext, function () { App.set('autoNext', !App.autoNext); renderCtl(); }));
    } else if (panel === 'react') {
      m.append(h('h5', {}, 'REAKSI')); m.append(h('div', { style: { padding: '6px' } }, reactionBar(room)));
    } else if (panel === 'next' && next) {
      m.append(h('h5', {}, 'BERIKUTNYA'), h('div', { style: { padding: '6px 12px', maxWidth: '260px' } }, next.name),
        item('Putar sekarang', false, function () { goNext(next); }), item('Batal', false, function () { panel = ''; renderCtl(); }));
    }
    menuEl = m; return m;
  }
  function goNext(n) { destroy(true); opts.onNext ? opts.onNext(n) : Player.open({ film: n, next: opts.nextOf ? opts.nextOf(n) : function () { return null; }, onBack: opts.onBack, onNext: opts.onNext }); }
  function setQuality(q) {
    App.set('quality', q); panel = '';
    var v = Extractor.chooseVariant(variants, q);
    if (v && (!variant || v.url !== variant.url)) { var p = pos(); startPlay(v, p); }
    renderCtl();
  }

  /* ---- pusat layar: memuat / galat / buffering ---- */
  function renderCenter() {
    clear(center);
    if (phase === 0) center.append(h('div', { class: 'spinner' }), h('div', {}, stageText));
    else if (phase === 2) {
      center.append(h('div', { class: 'msg2' }, h('div', { style: { fontSize: '16px', fontWeight: 700 } }, errText),
        h('div', { style: { color: 'var(--subtle)', fontSize: '11px', margin: '6px 0 12px', wordBreak: 'break-all' } }, errDetail),
        h('div', { class: 'acts', style: { justifyContent: 'center' } }, btn('COBA LAGI', 'refresh', true, load), btn('KEMBALI', null, false, leave))));
    } else if (buffering && !video.paused) center.append(h('div', { class: 'spinner' }));
    else if (buffering && !ended && video.readyState < 3) center.append(h('div', { class: 'spinner' }));
  }
  function render() { renderCenter(); renderCtl(); renderDock(); }

  /* ---- nobar: dock chat, chat melayang, reaksi ---- */
  var dockEl = null, chatRef = null, floatEl = null, vidEl = null, dk = null;
  /** Video call melayang di atas pemutar saat panel chat tertutup (saat terbuka, video ada di panel). Wadah tetap, tidak dibuat ulang. */
  function renderVideos() {
    if (!room) return;
    if (chatOpen) { if (vidEl) vidEl.style.display = 'none'; return; }
    if (!vidEl) { vidEl = h('div', { class: 'vfloat vgrid', title: 'Klik untuk perkecil/perbesar', onclick: function () { vidEl.classList.toggle('mini'); } }); stage.append(vidEl); }
    syncVideos(vidEl, room);
  }
  /** Panel chat dibangun sekali; pembaruan hanya mengganti isi bagian yang berubah (kotak tulis & video tidak ikut dibuat ulang). */
  function renderDock() {
    if (!room) return;
    if (!chatOpen) { if (dockEl) { dockEl.remove(); dockEl = null; chatRef = null; dk = null; } renderVideos(); return; }
    renderVideos(); // sembunyikan video melayang
    if (!dockEl) {
      dk = { count: h('div', { style: { color: 'var(--muted)', fontSize: '12px' } }), mem: h('div'), vid: h('div', { class: 'vgrid' }), calls: h('div', { class: 'acts' }), gather: h('div') };
      dockEl = h('div', { class: 'dock' },
        h('div', { style: { display: 'flex', alignItems: 'center' } }, h('div', { style: { flex: 1 } }, h('b', {}, 'Nobar · ' + room.code), dk.count),
          h('button', { class: 'iconbtn', onclick: function () { chatOpen = false; renderDock(); renderCtl(); } }, icon('close', 16))),
        dk.mem, dk.calls, dk.vid, dk.gather, reactionBar(room));
      chatRef = chatBox(room, 15); chatRef.style.flex = '1'; dockEl.append(chatRef);
      root.append(dockEl);
      setTimeout(applyView, 30);
    }
    dk.count.textContent = room.members.length + ' dari ' + MAX_MEMBERS + ' bergabung';
    clear(dk.mem); dk.mem.append(memberStrip(room, true));
    clear(dk.calls); callButtons(room).forEach(function (b) { dk.calls.append(b); });
    syncVideos(dk.vid, room);
    clear(dk.gather);
    if (room.isHost) {
      var waiting = (room.info && room.info.gather) > 0;
      dk.gather.append(btn(waiting ? 'MENUNGGU SEMUA SIAP…' : 'TUNGGU SEMUA SIAP', null, !waiting, function () { if (!waiting) room.gather(pos()); }, 'block'));
    }
  }
  function addFloat(m) {
    if (!App.floatChat || chatOpen) return;
    if (!floatEl) { floatEl = h('div', { class: 'floatchat' }); stage.append(floatEl); }
    var el = h('div', { class: m.uid === room.me ? 'me' : 'o' }, h('b', {}, m.name), m.text);
    floatEl.append(el);
    while (floatEl.children.length > 4) floatEl.firstChild.remove();
    setTimeout(function () { el.remove(); }, 8000);
  }
  function floatReaction(e) {
    var el = h('div', { class: 'floater', style: { left: (10 + Math.random() * 70) + '%' } }, e);
    stage.append(el); setTimeout(function () { el.remove(); }, 3300);
  }

  if (room) {
    var lastChatId = room.chat.length ? room.chat[room.chat.length - 1].id : '';
    var lastReactId = room.reactions.length ? room.reactions[room.reactions.length - 1].id : '';
    var lastStamp = room.stamp(room.pb), lastPlaying = room.pb.playing, gatherLoop = null, lastMembersKey = '', lastCtlKey = '', lastDuck = false;
    disposers.push(room.on(function () {
      if (!alive) return;
      if (room.status === 'closed') { leave(); return; }
      // chat baru
      if (room.chat.length) {
        var tail = room.chat[room.chat.length - 1].id;
        if (tail !== lastChatId) {
          var idx = room.chat.findIndex(function (m) { return m.id === lastChatId; });
          var fresh = idx >= 0 ? room.chat.slice(idx + 1) : room.chat.slice(-3);
          lastChatId = tail; fresh.forEach(addFloat);
          if (chatRef) chatRef.refresh();
        }
      }
      if (room.reactions.length) {
        var rt = room.reactions[room.reactions.length - 1].id;
        if (rt !== lastReactId) {
          var ri = room.reactions.findIndex(function (m) { return m.id === lastReactId; });
          (ri >= 0 ? room.reactions.slice(ri + 1) : room.reactions.slice(-2)).forEach(function (m) { if (App.floatChat) floatReaction(m.text); });
          lastReactId = rt;
        }
      }
      // isi panel/video hanya diperbarui bila memang berubah; tombol pemutar hanya digambar ulang bila status mik/kamera berubah
      var mk = room.members.map(function (m) { return m.uid + m.name + m.buf + m.voice + m.mic + m.cam; }).join() + (room.info ? room.info.gather : '') + room.voiceOn + room.micOn + room.camOn + Object.keys(room.videos).join() + JSON.stringify(room.peerState);
      if (mk !== lastMembersKey) { lastMembersKey = mk; renderDock(); renderGather(); }
      var ck = '' + room.voiceOn + room.micOn + room.camOn;
      if (ck !== lastCtlKey) { var wasDuck = lastCtlKey && lastDuck; lastCtlKey = ck; lastDuck = ducked(); renderCtl(); if (lastDuck !== !!wasDuck && App.duck) showHud(lastDuck ? 'Suara film diredam selama mik hidup' : 'Suara film normal'); }
      // sinkron dari pb orang lain
      var st = room.stamp(room.pb);
      if ((st !== lastStamp || room.pb.playing !== lastPlaying) && phase === 1 && room.pb.by !== room.me) {
        var e = room.expected();
        if (Math.abs(pos() - e) > 1200) video.currentTime = e / 1000;
        if (room.pb.playing) video.play().catch(function () {}); else video.pause();
      }
      lastStamp = st; lastPlaying = room.pb.playing;
      // host: kumpulkan semua siap
      var g = room.info ? room.info.gather : 0;
      if (room.isHost && g > 0 && !gatherLoop) {
        var okSince = 0, t0 = Date.now();
        gatherLoop = setInterval(function () {
          var all = room.members.length && room.members.every(function (m) { return !m.buf; }) && !room.myBuf;
          var now = Date.now();
          if (all) { if (!okSince) okSince = now; } else okSince = 0;
          if ((okSince && now - okSince > 1200) || now - t0 > 90000 || !alive) { clearInterval(gatherLoop); gatherLoop = null; if (alive) { room.endGather(pos()); video.play().catch(function () {}); } }
        }, 400);
        timers.push(gatherLoop);
      }
    }));
    every(function () { // koreksi berkala
      if (phase !== 1 || document.visibilityState !== 'visible') return;
      if (room.pb.playing) {
        var e = room.expected();
        if (video.paused) video.play().catch(function () {});
        if (video.readyState >= 3 && Math.abs(pos() - e) > 2500) video.currentTime = e / 1000;
      } else if (!video.paused) { video.pause(); if (Math.abs(pos() - room.pb.pos) > 800) video.currentTime = room.pb.pos / 1000; }
    }, 2500);
    var rdy = function () { room.setReady(phase === 1 && !(buffering && video.readyState < 3)); };
    ['waiting', 'playing', 'canplay', 'pause', 'seeked'].forEach(function (ev) { video.addEventListener(ev, rdy); });
    every(rdy, 1500);
  }
  var gatherBar = null;
  function renderGather() {
    if (gatherBar) { gatherBar.remove(); gatherBar = null; }
    if (room && room.info && room.info.gather > 0) {
      var n = room.members.filter(function (m) { return m.buf; }).length;
      gatherBar = h('div', { class: 'gatherbar' }, 'Menunggu ' + n + ' peserta siap…'); stage.append(gatherBar);
    }
  }

  /* ---- simpan progres, timer tidur ---- */
  every(function () { if (!room && phase === 1 && !video.paused && dur() > 0) App.saveProgress(film.id, pos(), dur()); }, 5000);
  every(function () { if (sleepEnd && Date.now() >= sleepEnd) { sleepEnd = 0; video.pause(); pub(); showHud('Timer tidur selesai'); renderCtl(); } }, 1000);

  /* ---- gestur & keyboard ---- */
  function showCtl() { ctl.classList.remove('hide'); stage.classList.remove('idle'); clearTimeout(idleT); idleT = setTimeout(function () { if (!panel && !video.paused && !locked) { ctl.classList.add('hide'); stage.classList.add('idle'); } }, 3200); }
  stage.addEventListener('mousemove', showCtl);
  stage.addEventListener('click', function (e) { if (e.target === stage || e.target === video || e.target === center) { if (panel) { panel = ''; renderCtl(); } else if (!locked) togglePlay(); else showLockBtn(); } });
  stage.addEventListener('dblclick', function (e) { if ((e.target === video || e.target === stage) && !locked) toggleFull(); });
  stage.addEventListener('wheel', function (e) { if (locked) { e.preventDefault(); return; } e.preventDefault(); setVol(e.deltaY < 0 ? 0.05 : -0.05); }, { passive: false });
  var lockBtn = null;
  function showLockBtn() {
    if (!locked) return;
    if (lockBtn) lockBtn.remove();
    lockBtn = h('div', { class: 'lockov' }, h('button', { class: 'pbtn', onclick: function () { locked = false; lockBtn.remove(); lockBtn = null; renderCtl(); showCtl(); showHud('Layar dibuka'); } }, icon('unlock', 20), 'Buka kunci'));
    stage.append(lockBtn); setTimeout(function () { if (lockBtn) { lockBtn.remove(); lockBtn = null; } }, 3000);
  }
  stage.addEventListener('mousemove', function () { if (locked) showLockBtn(); });
  function onKey(e) {
    if (!alive) return;
    var tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA') { if (e.key === 'Escape') e.target.blur(); return; }
    if (locked) { e.preventDefault(); e.stopPropagation(); return; }
    var k = e.key;
    if (k === ' ' || k === 'k') { togglePlay(); e.preventDefault(); }
    else if (k === 'ArrowLeft') { seekBy(-App.skipSec * 1000); e.preventDefault(); }
    else if (k === 'ArrowRight') { seekBy(App.skipSec * 1000); e.preventDefault(); }
    else if (k === 'ArrowUp') { setVol(0.05); e.preventDefault(); }
    else if (k === 'ArrowDown') { setVol(-0.05); e.preventDefault(); }
    else if (k === 'f' || k === 'F') toggleFull();
    else if (k === 'm' || k === 'M') toggleMute();
    else if (k === 'j' || k === 'J') seekBy(-10000);
    else if (k === 'l' || k === 'L') seekBy(10000);
    else if (k === '<' || k === ',') { var si = SPEEDS.indexOf(App.speed); if (!room && si > 0) { App.set('speed', SPEEDS[si - 1]); video.playbackRate = App.speed; showHud('Kecepatan ' + fmtSpeed(App.speed)); } }
    else if (k === '>' || k === '.') { var sj = SPEEDS.indexOf(App.speed); if (!room && sj < SPEEDS.length - 1) { App.set('speed', SPEEDS[sj + 1]); video.playbackRate = App.speed; showHud('Kecepatan ' + fmtSpeed(App.speed)); } }
    else if (/^[0-9]$/.test(k) && !e.ctrlKey && !e.altKey) seekTo(+k / 10);
    else if (k === 'n' || k === 'N') { var nn = !room && nextFn(); if (nn) goNext(nn); }
    else if (k === 'p' || k === 'P') togglePip();
    else if (k === 's' || k === 'S') { if (subs.length) { subOn = !subOn; drawSub(); showHud('Subtitle ' + (subOn ? 'aktif' : 'mati')); } }
    else if (k === 'c' && room) { chatOpen = !chatOpen; renderDock(); renderCtl(); }
    else if (k === 'Escape') { if (document.fullscreenElement) document.exitFullscreen(); else leave(); }
    else return;
    showCtl(); e.stopPropagation();
  }
  document.addEventListener('keydown', onKey, true);
  var onFs = function () { setTimeout(applyView, 50); }; document.addEventListener('fullscreenchange', onFs);

  if (!App.hintSeen) {
    var hint = h('div', { class: 'hint' }, 'Spasi: putar/jeda · ← →: lompat · ↑ ↓ atau roda mouse: volume · F: layar penuh · klik dua kali: layar penuh · Esc: kembali');
    stage.append(hint); setTimeout(function () { hint.remove(); }, 7000); App.hintSeen = true; Store.set('hintSeen', true);
  }
  video.volume = volTarget(); video.muted = false;
  video.addEventListener('loadedmetadata', applySink);
  video.addEventListener('play', applyFx, { once: true });
  document.title = film.name + ' — Aphodius'; reportState();
  render(); showCtl(); load();
  var api = { destroy: destroy, film: film };
  return api;
}
