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

function startPlayback(f, o, room) {
  var back = function () { render(); };
  if (isSeries(f)) {
    var play = function (i) {
      var ef = App.epFilm(f, i);
      Player.open({
        film: ef, room: room, fromStart: o.fromStart, onBack: back,
        next: function () { return i + 1 < f.episodes.length ? App.epFilm(f, i + 1) : null; },
        onNext: function () { play(i + 1); },
      });
    };
    var idx = o.ep != null ? o.ep : App.resumeEp(f);
    if (idx >= 0) play(idx);
    return;
  }
  var run = function (film, fromStart) {
    Player.open({
      film: film, room: room, fromStart: fromStart, onBack: back,
      next: function () { return room ? null : App.nextFor(film); },
      onNext: function (n) { run(n, true); },
    });
  };
  run(f, !!o.fromStart);
}

function sideNav() {
  var cur = State.stack.length ? ({ detail: State.tab, search: State.tab, history: 'profile' }[route().t] || route().t) : State.tab;
  var side = h('div', { class: 'side' }, h('div', { class: 'brand' }, h('img', { src: 'icon.png' }), 'Aphodius'));
  TABS.forEach(function (t) {
    var key = t[0], label = t[1], ic = t[2];
    if (key === 'mylist' && App.extra) { key = 'extra'; label = 'Extraordinary'; ic = 'gem'; }
    side.append(h('button', { class: 'nav' + (cur === key ? ' on' : ''), onclick: function () { State.stack = []; State.film = null; State.tab = key; render(); } }, icon(ic, 20), label));
  });
  side.append(h('div', { class: 'grow' }), h('div', { style: { fontSize: '11px', color: 'var(--subtle)', padding: '0 10px' } }, 'v' + (State.version || '')));
  return side;
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
  }
  return h('div');
}

var renderQ = 0;
function render() {
  if (!$app) return;
  State.disposers.forEach(function (d) { d(); }); State.disposers = [];
  State.keyTarget = null;
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
var AUTO = { home: 1, new: 1, movie: 1, series: 1, mylist: 1, extra: 1, detail: 1, history: 1 };
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
  clear($app).append(h('div', { class: 'welcome' }, h('img', { src: 'icon.png', width: 96, height: 96, style: { borderRadius: '24px' } }), h('h2', {}, 'Selamat datang di Aphodius'), h('p', {}, 'Siapa nama sapaanmu?'), inp,
    h('div', { style: { marginTop: '16px' } }, btn('MULAI', null, true, go, 'block'))));
  setTimeout(function () { inp.focus(); }, 50);
}

async function boot() {
  $app = document.getElementById('app');
  try { var m = await aph.meta(); State.version = m.version; } catch (e) { State.version = '1.10.0'; }
  App.init();
  var start = function () {
    if (App.lockEnabled) showLock();
    render();
    App.refresh();
    setInterval(function () { if (!document.hidden && !Player.cur) App.refresh(); }, 30000);
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
