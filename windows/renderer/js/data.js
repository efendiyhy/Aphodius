'use strict';
/* Data film, status aplikasi, PIN, progres. Setara AppState/Store di Android. */
var DATA_RAW = 'https://raw.githubusercontent.com/efendiyhy/master-stream-data/main/data.json';
var API_URL = 'https://api.github.com/repos/efendiyhy/master-stream-data/contents/data.json';
var EXTRA_HASH = '9b9ea8122c0bc2f76051bf30aab683d4639115251e2348fbb1b059032aaa0577';
var ADULTS_HASH = 'f9df6085f1c1e0a24a806a19428ff92a5f5ad23dd4fe1ada92275c470b04bfb0';
var PIN_LEN = 4;
var EQ_FLAT = [0, 0, 0, 0, 0];
var EQ_LABELS = ['60 Hz', '230 Hz', '910 Hz', '3,6 kHz', '14 kHz'];
var EQ_PRESETS = [['Normal', EQ_FLAT], ['Bioskop', [0.45, 0.2, -0.1, 0.25, 0.4]], ['Bass', [0.8, 0.5, 0, 0, 0]], ['Vokal', [-0.3, -0.1, 0.5, 0.4, 0]]];

function parseFilms(text) {
  var o = JSON.parse(text);
  var arr = Array.isArray(o.films) ? o.films : [];
  var films = [];
  arr.forEach(function (f, i) {
    if (!f || typeof f !== 'object') return;
    var link = String(f.link || ''), name = String(f.name || '');
    if (!link.trim() || !name.trim()) return;
    var eps = [];
    if (Array.isArray(f.episodes)) {
      f.episodes.forEach(function (e, j) {
        if (!e) return;
        var el = String(e.link || '');
        if (!el.trim()) return;
        var n = Number.isFinite(+e.n) && e.n != null ? +e.n : j + 1;
        eps.push({ n: n, title: String(e.title || '').trim() || 'Episode ' + n, link: el });
      });
      eps.sort(function (a, b) { return a.n - b.n; });
    }
    var yr = f.year == null ? null : (+f.year > 0 ? +f.year : null);
    var rating = Number.isFinite(+f.rating) ? +f.rating : 0;
    films.push({
      id: String(f.id || '').trim() || link, link: link, name: name, year: yr,
      genres: Array.isArray(f.genres) ? f.genres.map(String) : [],
      poster: f.poster == null ? '' : String(f.poster), added: Number(f.added) || 0,
      synopsis: f.synopsis == null ? '' : String(f.synopsis).trim(),
      country: f.country == null ? '' : String(f.country).trim(),
      type: f.type == null ? 'film' : String(f.type).trim().toLowerCase(),
      popular: f.popular === true, recommended: f.recommended === true, rating: rating, episodes: eps,
    });
  });
  var genres = Array.isArray(o.genres) ? o.genres.map(String) : [];
  return { films: films, genres: genres };
}
function isSeries(f) { return String(f.type).toLowerCase() === 'series'; }
function isExtra(f) { return f.genres.some(function (g) { return g.trim().toLowerCase() === 'extraordinary'; }); }
function isAdult(f) { return f.genres.some(function (g) { return g.trim().toLowerCase() === '21+'; }); }

var App = {
  allFilms: [], genres: [], loaded: false, refreshing: false, netError: false, lastError: '', lastRefresh: 0, refreshNote: '',
  progress: Store.get('progress', {}), favs: new Set(Store.get('favs', [])), hiddenCont: new Set(Store.get('hiddenCont', [])),
  nickname: Store.get('nickname', ''), pinSalt: Store.get('pinSalt', ''), pinHash: Store.get('pinHash', ''),
  adults: false, extra: false,
  quality: Store.get('quality', 0), speed: Store.get('speed', 1), skipSec: Store.get('skipSec', 10),
  cropOn: Store.get('cropOn', false), crop: Store.get('crop', [0, 0, 0, 0]),
  floatChat: Store.get('floatChat', true), duck: Store.get('duck', true), thumbPoster: Store.get('thumbPoster', true), surround: Store.get('surround', false),
  autoNext: Store.get('autoNext', true), subSize: Store.get('subSize', 24), outDev: Store.get('outDev', ''), intro: Store.get('intro', {}),
  downloads: Store.get('downloads', {}), queue: Store.get('queue', []), trayClose: Store.get('trayClose', false), tvMode: Store.get('tvMode', false), saver: Store.get('saver', false),
  eq: Store.get('eq', EQ_FLAT), hintSeen: Store.get('hintSeen', false), sort: Store.get('sort', 'new'),
  etag: null, listeners: new Set(),

  init: function () {
    this.adults = Store.get('adults', false) && !!this.pinHash;
    this.extra = Store.get('extra', false) && !!this.pinHash;
    var c = Store.get('cache', null);
    if (c) this.apply(c);
  },
  on: function (fn) { this.listeners.add(fn); return function () { App.listeners.delete(fn); }; },
  emit: function () { Array.from(this.listeners).forEach(function (fn) { try { fn(); } catch (e) { console.error(e); } }); },

  get lockEnabled() { return !!this.pinHash; },
  get visible() { var a = this.adults; return this.allFilms.filter(function (f) { return a || !isAdult(f); }); },
  get films() { return this.visible.filter(function (f) { return !isExtra(f); }); },
  get extraFilms() { return this.extra ? this.visible.filter(isExtra) : []; },
  find: function (id) { return this.films.concat(this.extraFilms).find(function (f) { return f.id === id; }) || null; },

  lastProg: function (f) {
    if (isSeries(f)) {
      var best = null;
      Object.keys(this.progress).forEach(function (k) { if (k.indexOf(f.id + '#') === 0) { var p = App.progress[k]; if (!best || p.ts > best.ts) best = p; } });
      return best;
    }
    return this.progress[f.id] || null;
  },
  frac: function (p) { return p && p.dur > 0 ? Math.min(1, Math.max(0, p.pos / p.dur)) : 0; },
  byId: function (id) { return this.find(id); },
  epFilm: function (f, i) {
    var e = f.episodes[i];
    return Object.assign({}, f, { id: f.id + '#' + e.n, link: e.link, name: f.name + ' • Ep ' + e.n, type: 'film', episodes: [] });
  },
  resumeEp: function (f) {
    if (!f.episodes.length) return -1;
    var best = null;
    for (var i = 0; i < f.episodes.length; i++) {
      var p = this.progress[this.epFilm(f, i).id];
      if (p && (!best || p.ts > best.p.ts)) best = { i: i, p: p };
    }
    if (!best) return 0;
    return this.frac(best.p) > 0.95 ? Math.min(best.i + 1, f.episodes.length - 1) : best.i;
  },

  lastText: '', changed: false,
  apply: function (text) {
    if (text === this.lastText) return true;
    try {
      var r = parseFilms(text);
      var before = new Set(this.allFilms.map(function (f) { return f.id; }));
      this.allFilms = r.films.slice().sort(function (a, b) { return b.added - a.added; });
      if (before.size) {
        var n = this.allFilms.filter(function (f) { return !before.has(f.id); }).length;
        if (n > 0) this.refreshNote = n + ' film baru masuk';
      }
      this.genres = r.genres; this.loaded = true; this.lastText = text; this.changed = true;
      return true;
    } catch (e) { return false; }
  },

  async fetchApi() {
    var headers = { Accept: 'application/vnd.github.raw+json' };
    if (this.etag) headers['If-None-Match'] = this.etag;
    var ctl = new AbortController(); var t = setTimeout(function () { ctl.abort(); }, 15000);
    try {
      var r = await fetch(API_URL, { headers: headers, cache: 'no-store', signal: ctl.signal });
      if (r.status === 304) return null;
      if (!r.ok) throw new Error('HTTP ' + r.status);
      this.etag = r.headers.get('ETag');
      return await r.text();
    } finally { clearTimeout(t); }
  },

  async refresh() {
    if (this.refreshing) return;
    this.refreshing = true;
    var btn0 = typeof document !== 'undefined' && document.querySelector && document.querySelector('.refbtn'); if (btn0) btn0.classList.add('spin');
    var prevNet = this.netError, err = '';
    try {
      try {
        var text = await this.fetchApi();
        if (text == null) { this.netError = false; this.lastError = ''; this.lastRefresh = Date.now(); return; }
        text = text.trim();
        if (text[0] === '{' && this.apply(text)) { Store.set('cache', text); this.netError = false; this.lastError = ''; this.lastRefresh = Date.now(); return; }
      } catch (e) { err = 'api.github.com: ' + (e.message || e); }
      try {
        var raw = (await Extractor.httpGet(DATA_RAW + '?t=' + Date.now(), 15000)).trim();
        if (raw[0] !== '{') err = 'Respons bukan JSON';
        else if (this.apply(raw)) { Store.set('cache', raw); this.netError = false; this.lastError = ''; this.lastRefresh = Date.now(); return; }
        else err = 'Format data tidak dikenali';
      } catch (e) { err = 'raw.githubusercontent.com: ' + (e.message || e); }
      this.netError = true; this.lastError = err;
    } finally {
      this.refreshing = false;
      var btn1 = typeof document !== 'undefined' && document.querySelector && document.querySelector('.refbtn'); if (btn1) btn1.classList.remove('spin');
      var ch = this.changed || prevNet !== this.netError; this.changed = false;
      if (ch) this.emit();
    }
  },

  toggleFav: function (id) {
    var f = this.find(id);
    if (f && isExtra(f)) return; // Extraordinary tidak masuk Daftarku
    if (this.favs.has(id)) this.favs.delete(id); else this.favs.add(id);
    Store.set('favs', Array.from(this.favs)); this.emit();
  },
  saveProgress: function (id, pos, dur) {
    if (!(dur > 0)) return;
    this.progress[id] = { pos: Math.floor(pos), dur: Math.floor(dur), ts: Date.now() };
    var base = id.split('#')[0];
    if (this.hiddenCont.has(base)) { this.hiddenCont.delete(base); Store.set('hiddenCont', Array.from(this.hiddenCont)); }
    Store.set('progress', this.progress);
  },
  removeHistory: function (k) { delete this.progress[k]; Store.set('progress', this.progress); this.emit(); },
  clearHistory: function () { this.progress = {}; Store.set('progress', this.progress); this.emit(); },
  hideFromContinue: function (f) { this.hiddenCont.add(f.id); Store.set('hiddenCont', Array.from(this.hiddenCont)); this.emit(); },
  set: function (k, v) { this[k] = v; Store.set(k, v); this.emit(); },
  setIntro: function (base, r) { if (r) this.intro[base] = [Math.max(0, r[0]), r[1]]; else delete this.intro[base]; Store.set('intro', this.intro); },
  enqueue: function (id) { if (this.queue.indexOf(id) < 0) { this.queue.push(id); Store.set('queue', this.queue); this.emit(); } },
  dequeue: function (id) { this.queue = this.queue.filter(function (x) { return x !== id; }); Store.set('queue', this.queue); this.emit(); },
  moveQueue: function (i, d) { var j = i + d; if (j < 0 || j >= this.queue.length) return; var q = this.queue.slice(); var t = q[i]; q[i] = q[j]; q[j] = t; this.queue = q; Store.set('queue', q); this.emit(); },

  nextFor: function (f) {
    var all = isExtra(f) ? this.extraFilms : this.films;
    var pool = all.filter(function (x) { return !isSeries(x) && x.id !== f.id; });
    if (!pool.length) return null;
    var done = function (x) { return App.frac(App.progress[x.id]) > 0.95; };
    var skip = ['21+', 'extraordinary'];
    var g = new Set(f.genres.map(function (x) { return x.trim().toLowerCase(); }).filter(function (x) { return skip.indexOf(x) < 0; }));
    var same = pool.map(function (x) { return [x, x.genres.filter(function (y) { return g.has(y.trim().toLowerCase()); }).length]; })
      .filter(function (p) { return p[1] > 0; })
      .sort(function (a, b) { return (done(a[0]) - done(b[0])) || (b[1] - a[1]); });
    if (same.length) return same[0][0];
    var fav = pool.filter(function (x) { return App.favs.has(x.id); }).sort(function (a, b) { return done(a) - done(b); })[0];
    if (fav) return fav;
    var idx = all.findIndex(function (x) { return x.id === f.id; });
    return all.slice(idx + 1).find(function (x) { return !isSeries(x); }) || pool[0];
  },

  /* PIN / kunci aplikasi */
  hashPin: function (salt, pin) { return sha256(salt + ':' + pin); },
  async setPin(pin) {
    var salt = uuid();
    this.pinSalt = salt; this.pinHash = await this.hashPin(salt, pin);
    Store.set('pinSalt', salt); Store.set('pinHash', this.pinHash); this.emit();
  },
  async checkPin(pin) { return !!this.pinHash && (await this.hashPin(this.pinSalt, pin)) === this.pinHash; },
  clearPin: function () {
    this.pinHash = ''; this.pinSalt = ''; Store.del('pinHash'); Store.del('pinSalt');
    this.enableAdults(false); this.enableExtra(false); this.emit();
  },
  enableExtra: function (on) { this.extra = !!on && this.lockEnabled; Store.set('extra', this.extra); this.emit(); },
  enableAdults: function (on) { this.adults = !!on && this.lockEnabled; Store.set('adults', this.adults); this.emit(); },
  async checkExtraPassword(pw) { return (await sha256(String(pw).trim())) === EXTRA_HASH; },
  async checkAdultsPassword(pw) { return (await sha256(String(pw).trim())) === ADULTS_HASH; },
};

/* Cek pembaruan lewat rilis GitHub */
var Update = {
  API: 'https://api.github.com/repos/efendiyhy/Aphodius/releases/latest',
  cmp: function (a, b) {
    var x = String(a).replace(/^v/i, '').split('.').map(Number), y = String(b).replace(/^v/i, '').split('.').map(Number);
    for (var i = 0; i < Math.max(x.length, y.length); i++) { var d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0 ? 1 : -1; }
    return 0;
  },
  async check(current) {
    var r = await fetch(this.API, { headers: { Accept: 'application/vnd.github+json' }, cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    var j = await r.json();
    var ver = String(j.tag_name || '').replace(/^v/i, '');
    if (!ver || this.cmp(ver, current) <= 0) return null;
    var zip = (j.assets || []).find(function (a) { return /windows.*\.zip$/i.test(a.name); });
    return { version: ver, notes: String(j.body || '').slice(0, 600), url: zip ? zip.browser_download_url : j.html_url, page: j.html_url };
  },
};

/* ---- unduhan offline (mp4) ---- */
var Dl = {
  prog: {},
  init: function () {
    if (!window.aph) return;
    aph.onDl(function (p) {
      Dl.prog[p.id] = p;
      var t = p.total ? Math.round(p.got * 100 / p.total) + '%' : Math.round(p.got / 1048576) + ' MB';
      Array.prototype.forEach.call(document.querySelectorAll('[data-dl="' + String(p.id).replace(/"/g, '') + '"]'), function (el) { el.textContent = t; });
    });
  },
  has: function (id) { return !!App.downloads[id]; },
  busy: function (id) { return !!Dl.prog[id]; },
  start: async function (f) {
    if (!window.aph || Dl.prog[f.id]) return;
    Dl.prog[f.id] = { got: 0, total: 0 }; App.emit();
    try {
      var vs = await Extractor.extract(f.link, function () {});
      var mp4 = vs.filter(function (v) { return !v.hls; });
      if (!mp4.length) throw new Error('video ini hanya tersedia sebagai stream (HLS)');
      var v = Extractor.chooseVariant(mp4, App.quality) || mp4[0];
      var r = await aph.dlStart(f.id, v.url);
      if (!r.ok) throw new Error(r.error);
      App.downloads[f.id] = { name: f.name, size: r.size, ts: Date.now() }; Store.set('downloads', App.downloads);
      toast('Unduhan selesai: ' + f.name, 3000);
    } catch (e) { toast('Unduhan gagal: ' + (e.message || e), 4500); }
    delete Dl.prog[f.id]; App.emit();
  },
  cancel: function (id) { aph.dlCancel(id); },
  remove: async function (id) { await aph.dlRemove(id); delete App.downloads[id]; Store.set('downloads', App.downloads); App.emit(); },
};
