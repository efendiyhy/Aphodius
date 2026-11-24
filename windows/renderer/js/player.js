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
      this.ready = true;
    } catch (e) { this.ready = false; }
  },
  apply: function (surround, eq) {
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

  var video = h('video', { playsinline: true, crossorigin: 'anonymous' });
  video.autoplay = false;
  var fx = new AudioFx(video);
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
    try {
      variants = await Extractor.extract(film.link, function (i, n) { stageText = 'Mengambil video… (' + i + '/' + n + ')'; render(); });
    } catch (e) { return fail(e.message || String(e)); }
    if (!alive) return;
    var v = Extractor.chooseVariant(variants, App.quality);
    if (!v) return fail('Tweet ini tidak berisi video');
    startPlay(v);
  }
  function fail(detail) { phase = 2; errDetail = String(detail); errText = friendlyError(detail); render(); }
  video.addEventListener('error', function () { var c = video.error ? video.error.code : 0; fail('media error ' + c + (video.error && video.error.message ? ' ' + video.error.message : '')); });
  video.addEventListener('waiting', function () { buffering = true; renderCenter(); });
  video.addEventListener('playing', function () { buffering = false; renderCenter(); });
  video.addEventListener('canplay', function () { buffering = false; renderCenter(); });
  video.addEventListener('ended', function () { ended = true; if (!room) App.saveProgress(film.id, dur(), dur()); var n = nextFn(); if (n && !room) { panel = 'next'; showCtl(); } render(); });
  video.addEventListener('play', function () { updateControls(); }); video.addEventListener('pause', function () { updateControls(); });
  video.addEventListener('timeupdate', updateTime);
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
  function setVol(d) { video.muted = false; video.volume = Math.min(1, Math.max(0, video.volume + d)); showHud('Volume ' + Math.round(video.volume * 100) + '%'); }
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
    ro.disconnect(); document.removeEventListener('keydown', onKey, true); document.removeEventListener('fullscreenchange', onFs);
    if (document.fullscreenElement) document.exitFullscreen().catch(function () {});
    root.remove(); if (Player.cur === api) Player.cur = null; document.title = 'Aphodius';
  }

  /* ---- bilah kendali ---- */
  var timeEl = h('span', { class: 'time' }), barFill = h('i'), barKnob = h('b'), barBuf = h('div', { class: 'buf' });
  var playBtn = h('button', { class: 'pbtn', onclick: togglePlay });
  var muteBtn = h('button', { class: 'pbtn', onclick: function () { video.muted = !video.muted; } });
  var dragging = false;
  var seek = h('div', { class: 'seek' }, h('div', { class: 'bar' }, barBuf, barFill, barKnob));
  var seekFrac = function (e) { var r = seek.getBoundingClientRect(); return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)); };
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
    clear(muteBtn); muteBtn.append(icon(video.muted || video.volume === 0 ? 'micoff' : 'vol', 20));
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
      muteBtn, timeEl, h('div', { class: 'sp' }),
      variants.length ? pbtn('quality', qLabel(), panel === 'quality', function () { togglePanel('quality'); }) : null,
      !room ? pbtn('speed', 'Kecepatan · ' + fmtSpeed(App.speed), panel === 'speed', function () { togglePanel('speed'); }) : null,
      pbtn('skip', 'Lompat · ' + App.skipSec + ' dtk', panel === 'skip', function () { togglePanel('skip'); }),
      pbtn('moon', 'Timer tidur' + (sleepEnd ? ' · ' + (Math.floor((sleepEnd - Date.now()) / 60000) + 1) + ' mnt' : ''), panel === 'sleep', function () { togglePanel('sleep'); }),
      pbtn('aspect', ASPECTS[aspect][1], panel === 'aspect', function () { togglePanel('aspect'); }),
      pbtn('crop', 'Crop · ' + (App.cropOn && App.crop.some(function (x) { return x > 0.001; }) ? 'Aktif' : 'Mati'), panel === 'crop', function () { togglePanel('crop'); }),
      pbtn('sound', 'Suara', panel === 'sound', function () { togglePanel('sound'); }),
      room ? pbtn('star', 'Reaksi', panel === 'react', function () { togglePanel('react'); }) : null,
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
  var dockEl = null, chatRef = null, floatEl = null;
  function renderDock() {
    if (!room) return;
    if (dockEl) { dockEl.remove(); dockEl = null; chatRef = null; }
    if (!chatOpen) return;
    dockEl = h('div', { class: 'dock' });
    dockEl.append(h('div', { style: { display: 'flex', alignItems: 'center' } }, h('div', { style: { flex: 1 } }, h('b', {}, 'Nobar · ' + room.code), h('div', { style: { color: 'var(--muted)', fontSize: '12px' } }, room.members.length + ' dari ' + MAX_MEMBERS + ' bergabung')),
      h('button', { class: 'iconbtn', onclick: function () { chatOpen = false; renderDock(); renderCtl(); } }, icon('close', 16))));
    dockEl.append(memberStrip(room, true));
    if (room.isHost) {
      var waiting = (room.info && room.info.gather) > 0;
      dockEl.append(btn(waiting ? 'MENUNGGU SEMUA SIAP…' : 'TUNGGU SEMUA SIAP', null, !waiting, function () { if (!waiting) room.gather(pos()); }, 'block'));
    }
    dockEl.append(reactionBar(room));
    chatRef = chatBox(room, 15); chatRef.style.flex = '1'; dockEl.append(chatRef);
    root.append(dockEl);
    setTimeout(applyView, 30);
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
    var lastStamp = room.stamp(room.pb), lastPlaying = room.pb.playing, gatherLoop = null, lastMembersKey = '';
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
      var mk = room.members.map(function (m) { return m.uid + m.buf + m.voice + m.mic; }).join() + (room.info ? room.info.gather : '');
      if (mk !== lastMembersKey) { lastMembersKey = mk; if (dockEl) renderDock(); renderCtl(); renderGather(); }
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
    else if (k === 'm' || k === 'M') video.muted = !video.muted;
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
  video.volume = Store.get('vol', 1); video.addEventListener('volumechange', function () { Store.set('vol', video.volume); });
  video.addEventListener('play', applyFx, { once: true });
  document.title = film.name + ' — Aphodius';
  render(); showCtl(); load();
  var api = { destroy: destroy, film: film };
  return api;
}
