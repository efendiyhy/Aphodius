'use strict';
/* Router, kunci aplikasi, siklus hidup. */
var State = { tab: 'home', stack: [], genre: null, mode: 'Genre', sort: 0, q: '', film: null, update: null, version: '', locked: false, keyTarget: null, disposers: [], leftAt: 0, rerender: function () { render(); } };
var $app = null;

var TABS = [['home', 'Beranda', 'home'], ['new', 'Baru', 'news'], ['movie', 'Movie', 'film'], ['series', 'Series', 'series'], ['mylist', 'Daftarku', 'list'], ['nobar', 'Nobar', 'people'], ['profile', 'Profil', 'user']];

function route() { return State.stack.length ? State.stack[State.stack.length - 1] : { t: State.tab }; }
UI.go = function (r) { State.q = r.t === 'search' ? '' : State.q; State.film = r.film || null; State.stack.push(r); render(); };
UI.back = function () { if (State.stack.length) State.stack.pop(); render(); };
UI.openFilm = function (f) { UI.go({ t: 'detail', id: f.id }); };
UI.play = function (f, o) { startPlayback(f, o || {}); };
UI.watchRoom = function (f) { startPlayback(f, {}, Nobar.room); };

function queueNext(room) {
  if (room) return null;
  for (var i = 0; i < App.queue.length; i++) { var f = App.byId(App.queue[i]); if (f) return f; }
  return null;
}
function startPlayback(f, o, room) {
  var back = function () { render(); };
  var viaQueue = function (n) { App.dequeue(n.id); startPlayback(n, { fromStart: true }); };
  if (isSeries(f)) {
    var play = function (i) {
      var ef = App.epFilm(f, i);
      Player.open({
        film: ef, room: room, fromStart: o.fromStart, onBack: back, auto: !room,
        next: function () { return i + 1 < f.episodes.length ? App.epFilm(f, i + 1) : queueNext(room); },
        onNext: function (n) { if (n.id.indexOf(f.id + '#') === 0) play(i + 1); else viaQueue(n); },
      });
    };
    var idx = o.ep != null ? o.ep : App.resumeEp(f);
    if (idx >= 0) play(idx);
    return;
  }
  var run = function (film, fromStart) {
    Player.open({
      film: film, room: room, fromStart: fromStart, onBack: back, auto: !room,
      next: function () { return room ? null : (queueNext(room) || App.nextFor(film)); },
      onNext: function (n) { if (App.queue.indexOf(n.id) >= 0) viaQueue(n); else run(n, true); },
    });
  };
  run(f, !!o.fromStart);
}

function sideNav() {
  var cur = State.stack.length ? ({ detail: State.tab, search: State.tab, history: 'profile' }[route().t] || route().t) : State.tab;
  var goTab = function (key) { State.stack = []; State.film = null; State.tab = key; render(); };
  // rail ikon melayang; label muncul saat disorot
  var rail = h('nav', { class: 'rail' });
  TABS.forEach(function (t) {
    var key = t[0], label = t[1], ic = t[2];
    if (key === 'mylist' && App.extra) { key = 'extra'; label = 'Extraordinary'; ic = 'gem'; }
    if (key === 'profile') return; // profil ada di avatar bawah
    rail.append(h('button', { class: 'rnav' + (cur === key ? ' on' : ''), 'aria-label': label, onclick: function () { goTab(key); } }, icon(ic, 22), h('span', { class: 'tipr' }, label)));
  });
  return h('div', { class: 'side' },
    h('button', { class: 'logo', title: 'Beranda', onclick: function () { goTab('home'); } }, h('img', { src: 'icon.png', alt: 'Aphodius' })),
    rail,
    h('div', { class: 'sidebot' }, h('button', { class: 'meav' + (cur === 'profile' ? ' on' : ''), title: 'Profil', onclick: function () { goTab('profile'); } }, avatar(App.nickname, 40)),
      h('div', { class: 'ver' }, 'v' + (State.version || ''))));
}

function screenFor(r) {
  switch (r.t) {
    case 'home': return homeScreen(State);
    case 'new': return newScreen();
    case 'movie': return browseScreen(false, State);
    case 'series': return browseScreen(true, State);
    case 'mylist': return App.extra ? extraScreen() : myListScreen(State);
    case 'extra': return extraScreen();
    case 'detail': return detailScreen(r.id);
    case 'search': return searchScreen(State);
    case 'profile': return profileScreen(State);
    case 'history': return historyScreen(State);
    case 'nobar': return nobarScreen(State);
    case 'queue': return queueScreen(State);
    case 'all': return allScreen(r);
  }
  return h('div');
}

var renderQ = 0;
function render() {
  if (!$app) return;
  State.disposers.forEach(function (d) { d(); }); State.disposers = [];
  State.keyTarget = null; Lazy.reset();
  var r = route();
  var scroll = $app.querySelector('.main'); var st = scroll ? scroll.scrollTop : 0; var same = scroll && scroll._r === JSON.stringify(r);
  if (r.t === 'nobar') State.film = r.film || State.film;
  if (r.t === 'nobar' && !r.film && !State.stack.length) State.film = null;
  if (r.t === 'extra' || (r.t === 'mylist' && App.extra)) r = { t: 'extra' };
  if (r.t === 'extra' && !App.extra) { State.tab = 'home'; State.stack = []; r = { t: 'home' }; }
  var main = h('div', { class: 'main' });
  var page = screenFor(r);
  main.append(page); main._r = JSON.stringify(route());
  clear($app).append(h('div', { class: 'shell' }, sideNav(), main));
  if (same) main.scrollTop = st;
  if (State.refreshNote) {}
  if (App.refreshNote) {
    var n = h('div', { class: 'note' }, App.refreshNote); document.body.append(n); App.refreshNote = ''; setTimeout(function () { n.remove(); }, 3500);
  }
}
var AUTO = { all: 1, home: 1, new: 1, movie: 1, series: 1, mylist: 1, extra: 1, detail: 1, history: 1, queue: 1 };
App.on(function () {
  cancelAnimationFrame(renderQ);
  renderQ = requestAnimationFrame(function () { if (!State.locked && AUTO[route().t] && !Player.cur) render(); else if (App.refreshNote) { var n = h('div', { class: 'note' }, App.refreshNote); document.body.append(n); App.refreshNote = ''; setTimeout(function () { n.remove(); }, 3500); } });
});
Nobar.on(function () { if (route().t === 'nobar' && !Player.cur) render(); });

/* ---- kunci aplikasi ---- */
var lockEl = null;
function showLock() {
  if (lockEl || !App.lockEnabled) return;
  State.locked = true;
  var pad = pinPad({
    title: 'Masukkan PIN', subtitle: 'Aphodius dikunci',
    onComplete: async function (pin, c) { if (await App.checkPin(pin)) { lockEl.remove(); lockEl = null; State.locked = false; render(); } else c.error('PIN salah'); },
  });
  lockEl = h('div', { class: 'lock' }, h('div', { style: { display: 'flex', flexDirection: 'column', alignItems: 'center' } },
    avatar(App.nickname, 64), h('div', { style: { color: 'var(--muted)', marginTop: '12px' } }, 'Halo,'), h('div', { style: { fontSize: '28px', fontWeight: 900, marginBottom: '18px' } }, App.nickname || 'Aphodius'), pad));
  lockEl._pad = pad; document.body.append(lockEl);
}
document.addEventListener('keydown', function (e) {
  if (lockEl && lockEl._pad) { lockEl._pad._key(e); return; }
  if (State.keyTarget && State.keyTarget._key && !/INPUT|TEXTAREA/.test((e.target || {}).tagName || '')) State.keyTarget._key(e);
  if (e.key === 'Escape' && !Player.cur && State.stack.length && !document.querySelector('.modal')) UI.back();
});
function leftApp() { State.leftAt = Date.now(); }
function backToApp() { if (App.lockEnabled && State.leftAt && Date.now() - State.leftAt > 45000) showLock(); State.leftAt = 0; }
document.addEventListener('visibilitychange', function () { if (document.hidden) leftApp(); else backToApp(); });
window.addEventListener('blur', function () { if (!State.leftAt) leftApp(); });
window.addEventListener('focus', backToApp);
document.addEventListener('mouseup', function (e) { if (e.button === 3 && !Player.cur) { UI.back(); } });

/* ---- sapaan awal ---- */
function welcome(done) {
  var inp = h('input', { class: 'field', placeholder: 'Nama sapaan', maxlength: 24, style: { textAlign: 'center' } });
  var go = function () { var n = inp.value.trim(); if (!n) { inp.focus(); return; } App.set('nickname', n); done(); };
  inp.addEventListener('keydown', function (e) { if (e.key === 'Enter') go(); });
  clear($app).append(h('div', { class: 'welcome' }, h('img', { src: 'icon.png', width: 96, height: 96 }), h('h2', {}, 'Selamat datang di Aphodius'), h('p', {}, 'Siapa nama sapaanmu?'), inp,
    h('div', { style: { marginTop: '16px' } }, btn('MULAI', null, true, go, 'block'))));
  setTimeout(function () { inp.focus(); }, 50);
}

/* ---- mode TV / hemat memori ---- */
function applyMode() {
  document.body.classList.toggle('tv', !!App.tvMode);
  document.body.classList.toggle('saver', !!App.saver);
  if (window.aph) aph.zoom(App.tvMode ? 1.35 : 1);
}
/* ---- navigasi panah (mode TV) ---- */
function tvNav(e) {
  if (!App.tvMode || Player.cur || lockEl || /INPUT|TEXTAREA/.test((e.target || {}).tagName || '')) return;
  var dir = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[e.key];
  if (!dir) return;
  var els = Array.prototype.slice.call(document.querySelectorAll('button, .poster, a, input, [tabindex]')).filter(function (x) { var r = x.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight; });
  var cur = document.activeElement && els.indexOf(document.activeElement) >= 0 ? document.activeElement : null;
  if (!cur) { if (els[0]) { els[0].focus(); e.preventDefault(); } return; }
  var c = cur.getBoundingClientRect(), cx = c.left + c.width / 2, cy = c.top + c.height / 2, best = null, bs = 1e9;
  els.forEach(function (x) {
    if (x === cur) return; var r = x.getBoundingClientRect(), dx = r.left + r.width / 2 - cx, dy = r.top + r.height / 2 - cy;
    var along = dx * dir[0] + dy * dir[1]; if (along <= 4) return;
    var side = Math.abs(dx * dir[1]) + Math.abs(dy * dir[0]); var sc = along + side * 2;
    if (sc < bs) { bs = sc; best = x; }
  });
  if (best) { best.focus(); best.scrollIntoView({ block: 'nearest', inline: 'nearest' }); e.preventDefault(); }
}
document.addEventListener('keydown', function (e) {
  tvNav(e);
  if (Player.cur || lockEl) return;
  var typing = /INPUT|TEXTAREA/.test((e.target || {}).tagName || '');
  if (e.key === 'F11') { e.preventDefault(); aph.fullscreen(!document.fullscreenElement && !window._full); window._full = !window._full; }
  else if (e.key === '/' && !typing) { e.preventDefault(); UI.go({ t: 'search' }); }
  else if (App.tvMode && e.key === 'Enter' && document.activeElement && !typing && document.activeElement.click && document.activeElement.classList.contains('poster')) document.activeElement.click();
});
/* ---- undangan ---- */
function joinInvite(code) {
  if (!code) return;
  code = String(code).toUpperCase().replace(/[^A-Z0-9]/g, '');
  if (code.length !== 6) return;
  State.stack = []; State.tab = 'nobar'; State.film = null; render();
  if (Nobar.room) { toast('Kamu sudah berada di ruangan nobar', 3500); return; }
  Nobar.join(code, function () { return App.nickname; }).then(function () { render(); });
}
/* ---- notifikasi chat saat jendela tidak aktif ---- */
var lastChatN = 0;
function notifyChat(msg) {
  if (!document.hidden && document.hasFocus()) return;
  if (!('Notification' in window)) return;
  var show = function () { try { var n = new Notification(msg.nick || 'Nobar', { body: msg.text, silent: false }); n.onclick = function () { aph.setState && 0; window.focus(); }; } catch (e) {} };
  if (Notification.permission === 'granted') show(); else if (Notification.permission === 'default') Notification.requestPermission().then(function (p) { if (p === 'granted') show(); });
}
window.notifyChat = notifyChat;

async function boot() {
  $app = document.getElementById('app');
  try { var m = await aph.meta(); State.version = m.version; } catch (e) { State.version = '1.10.0'; }
  App.init(); Dl.init(); applyMode();
  if (window.aph) { aph.setCfg({ trayClose: App.trayClose }); aph.inviteReady(joinInvite); }
  window.addEventListener('error', function (e) { aph.log('ERR ' + e.message + ' @' + (e.filename || '').split('/').pop() + ':' + e.lineno); });
  window.addEventListener('unhandledrejection', function (e) { aph.log('REJ ' + (e.reason && e.reason.stack || e.reason)); });
  var start = function () {
    if (App.lockEnabled) showLock();
    render();
    App.refresh();
    setInterval(function () { if (!document.hidden && !Player.cur) App.refresh(); }, 30000);
    setInterval(function () { if (App.saver && !Player.cur && window.aph) { aph.clearCache(); } }, 15 * 60000);
    var last = Store.get('lastUpdateCheck', 0);
    if (Date.now() - last > 6 * 3600 * 1000) {
      Update.check(State.version).then(function (u) { Store.set('lastUpdateCheck', Date.now()); State.update = u; if (u) render(); }).catch(function () {});
    }
  };
  if (!App.nickname) welcome(start); else start();
}
window.addEventListener('error', function (e) { console.error(e.message); });
window.addEventListener('unhandledrejection', function (e) { console.error('unhandled', e.reason); });
boot();
