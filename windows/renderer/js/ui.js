'use strict';
/* Komponen dan layar utama. */
var UI = {
  go: function () {}, openFilm: function () {}, play: function () {}, back: function () {},
};

function meta(f) {
  var a = [];
  if (f.year) a.push(String(f.year));
  if (f.country) a.push(f.country);
  if (f.genres.length) a.push(f.genres.join(' • '));
  return a.join(' • ');
}
function kind(f) { return isSeries(f) ? 'Series' : 'Film'; }
function initials(n) { return String(n || '?').trim().slice(0, 1).toUpperCase() || '?'; }
function avatar(name, size) {
  return h('span', { class: 'avatar', style: { width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.42) + 'px' } }, initials(name));
}
function btn(text, iconName, primary, onclick, extra) {
  return h('button', { class: 'btn' + (primary ? ' primary' : '') + (extra ? ' ' + extra : ''), onclick: onclick }, iconName ? icon(iconName, 18) : null, text);
}
function chip(text, on, onclick) { return h('button', { class: 'chip' + (on ? ' on' : ''), onclick: onclick }, text); }
function toggle(on, onchange) {
  var el = h('button', { class: 'sw' + (on ? ' on' : ''), onclick: function () { on = !on; el.classList.toggle('on', on); onchange(on); } });
  return el;
}
function sectionTitle(t) { return h('h3', { class: 'sec' }, t); }

/* Poster: URL diperkecil, dimuat hanya saat mendekati layar, dibuang saat jauh (hemat memori & mulus saat scroll) */
function posterUrl(u, w) {
  var m = /^(https:\/\/m\.media-amazon\.com\/images\/.*?)\._V1_[^\/]*?(\.\w+)$/.exec(u || '');
  return m ? m[1] + '._V1_UX' + w + '_' + m[2] : u;
}

/* Cuplikan dari tengah video untuk film tanpa poster: satu per satu, hanya yang terlihat, disimpan di IndexedDB */
var Thumbs = {
  q: [], running: 0, mem: {},
  get: function (f, cb) {
    var key = 'th:' + f.id;
    if (this.mem[key]) return cb(this.mem[key]);
    var job = { f: f, key: key, cb: cb, dead: false };
    f._job = job;
    IDB.get(key).then(function (v) {
      if (job.dead) return;
      if (v) { Thumbs.mem[key] = v; return cb(v); }
      Thumbs.q.push(job); Thumbs.pump();
    });
  },
  cancel: function (f) { if (f._job) { f._job.dead = true; f._job = null; } },
  pump: function () {
    while (this.running < 1 && this.q.length) {
      var j = this.q.shift(); if (j.dead) continue;
      this.running++; this.work(j);
    }
  },
  work: function (j) {
    var done = function (url) { Thumbs.running--; if (url) { Thumbs.mem[j.key] = url; IDB.set(j.key, url); if (!j.dead) j.cb(url); } Thumbs.pump(); };
    Extractor.extract(j.f.link).then(function (list) {
      var mp4 = list.filter(function (v) { return !v.hls; });
      var v = mp4.length ? mp4[mp4.length - 1] : null; // kualitas terkecil
      if (!v) return done(null);
      var vid = document.createElement('video'); vid.muted = true; vid.preload = 'metadata'; vid.crossOrigin = 'anonymous';
      var fin = false, to = setTimeout(function () { end(null); }, 25000);
      function end(u) { if (fin) return; fin = true; clearTimeout(to); try { vid.removeAttribute('src'); vid.load(); } catch (e) {} done(u); }
      vid.onloadedmetadata = function () { vid.currentTime = Math.max(1, (vid.duration || 0) / 2); };
      vid.onseeked = function () {
        try {
          var c = document.createElement('canvas'); c.width = 240; c.height = 360;
          var vw = vid.videoWidth, vh = vid.videoHeight; if (!vw || !vh) return end(null);
          var scale = Math.max(c.width / vw, c.height / vh), dw = vw * scale, dh = vh * scale;
          c.getContext('2d').drawImage(vid, (c.width - dw) / 2, (c.height - dh) / 2, dw, dh);
          end(c.toDataURL('image/jpeg', 0.75));
        } catch (e) { end(null); }
      };
      vid.onerror = function () { end(null); };
      vid.src = v.url;
    }).catch(function () { done(null); });
  },
};

var Lazy = {
  io: null, extra: [],
  reset: function () {
    if (this.io) this.io.disconnect();
    this.extra.forEach(function (o) { o.disconnect(); }); this.extra = [];
    this.io = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) Lazy.show(e.target); else Lazy.hide(e.target); });
    }, { rootMargin: '600px 300px' });
  },
  show: function (b) {
    if (b._on) return; b._on = true;
    var f = b._f;
    var put = function (src) { if (!b._on) return; var img = h('img', { decoding: 'async', draggable: 'false' }); img.src = src; b.replaceChildren(img); };
    var thumb = function () { if (App.thumbPoster) Thumbs.get(f, put); };
    if (f.poster) {
      var img = h('img', { decoding: 'async', draggable: 'false' });
      img.onerror = function () { if (b._on) { b.replaceChildren(b._ph.cloneNode(true)); thumb(); } };
      img.src = posterUrl(f.poster, b._w); b.replaceChildren(img);
    } else thumb();
  },
  hide: function (b) {
    if (!b._on) return; b._on = false;
    Thumbs.cancel(b._f);
    b.replaceChildren(b._ph.cloneNode(true));
  },
};

function posterEl(f, w, eager) {
  var box = h('div', { class: 'poster' });
  box._f = f; box._w = w || 300; box._ph = h('div', { class: 'ph' }, f.name);
  box.append(box._ph.cloneNode(true));
  if (eager || !Lazy.io) Lazy.show(box); else Lazy.io.observe(box);
  return box;
}
function card(f, opts) {
  opts = opts || {};
  var p = posterEl(f);
  var lp = App.lastProg(f);
  if (!isSeries(f) && App.frac(App.progress[f.id]) > 0.95) p.append(h('span', { class: 'done' }, 'Selesai'));
  if (opts.progress && lp) p.append(h('div', { class: 'prog' }, h('i', { style: { width: Math.round(App.frac(lp) * 100) + '%' } })));
  var el = h('div', { class: 'card', onclick: function () { opts.onclick ? opts.onclick(f) : UI.openFilm(f); } },
    p, h('div', { class: 't' }, f.name), h('div', { class: 'm' }, opts.sub || (kind(f) + (f.year ? ' • ' + f.year : ''))));
  if (opts.onhide) el.append(h('button', { class: 'xbtn', title: 'Sembunyikan dari Lanjutkan Menonton', onclick: function (e) { e.stopPropagation(); opts.onhide(f); } }, icon('close', 14)));
  return el;
}
function posterRow(list, opts) { return h('div', { class: 'row' }, list.slice(0, 24).map(function (f) { return card(f, opts); })); }
function grid(list, opts) {
  var g = h('div', { class: 'grid' }), i = 0, N = 36;
  var sent = h('div', { style: { height: '1px', gridColumn: '1 / -1' } });
  var io = null;
  var more = function () {
    for (var end = Math.min(list.length, i + N); i < end; i++) g.insertBefore(card(list[i], opts), sent);
    if (i >= list.length) { sent.remove(); if (io) io.disconnect(); }
    else if (io) { io.unobserve(sent); io.observe(sent); }
  };
  g.append(sent);
  if (list.length > N && window.IntersectionObserver) {
    io = new IntersectionObserver(function (es) { if (es.some(function (e) { return e.isIntersecting; })) more(); }, { rootMargin: '900px' });
    Lazy.extra.push(io); more(); io.observe(sent);
  } else { N = list.length; more(); }
  return g;
}
function emptyState(text) {
  return h('div', { class: 'empty' }, text, h('div', { style: { marginTop: '16px' } },
    App.netError ? [btn('Coba lagi', 'refresh', true, function () { App.refresh(); }), h('div', { class: 'err' }, App.lastError)] : null));
}
function searchBox(placeholder, value, oninput, autofocus) {
  var inp = h('input', { placeholder: placeholder, value: value || '' });
  inp.addEventListener('input', function () { oninput(inp.value); });
  if (autofocus) setTimeout(function () { inp.focus(); }, 30);
  return h('div', { class: 'search' }, icon('search', 18), inp);
}
function confirmBox(title, text, okLabel, onok, danger) {
  var m;
  var close = function () { m.remove(); };
  m = h('div', { class: 'modal', onclick: function (e) { if (e.target === m) close(); } },
    h('div', { class: 'box' }, h('h4', {}, title), h('p', {}, text),
      h('div', { class: 'acts' }, btn('BATAL', null, false, close), btn(okLabel, null, !danger, function () { close(); onok(); }, danger ? 'danger' : ''))));
  document.body.append(m);
}
function promptBox(title, text, placeholder, secret, onok, oncancel) {
  var inp = h('input', { class: 'field', type: secret ? 'password' : 'text', placeholder: placeholder });
  var err = h('div', { class: 'err' });
  var m, ok = false;
  var close = function () { m.remove(); if (!ok && oncancel) oncancel(); };
  var submit = async function () { var r = await onok(inp.value); if (r === true) { ok = true; close(); } else err.textContent = r || 'Salah'; };
  inp.addEventListener('keydown', function (e) { if (e.key === 'Enter') submit(); });
  m = h('div', { class: 'modal', onclick: function (e) { if (e.target === m) close(); } },
    h('div', { class: 'box' }, h('h4', {}, title), h('p', {}, text), inp, err,
      h('div', { class: 'acts' }, btn('BATAL', null, false, close), btn('OK', null, true, submit))));
  document.body.append(m); setTimeout(function () { inp.focus(); }, 30);
}

/* Papan angka PIN */
function pinPad(opts) {
  var pin = '';
  var dots = h('div', { class: 'dots' }, Array.from({ length: PIN_LEN }, function () { return h('i'); }));
  var err = h('div', { class: 'err', style: { textAlign: 'center' } }, opts.error || '');
  var sync = function () { Array.prototype.forEach.call(dots.children, function (d, i) { d.classList.toggle('f', i < pin.length); }); };
  var press = function (d) {
    if (pin.length >= PIN_LEN) return;
    pin += d; sync();
    if (pin.length === PIN_LEN) { var p = pin; opts.onComplete(p, { error: function (m) { err.textContent = m; pin = ''; sync(); }, reset: function () { pin = ''; sync(); } }); }
  };
  var back = function () { pin = pin.slice(0, -1); sync(); };
  var keys = h('div', { class: 'keys' });
  ['1', '2', '3', '4', '5', '6', '7', '8', '9'].forEach(function (d) { keys.append(h('button', { onclick: function () { press(d); } }, d)); });
  keys.append(h('span'), h('button', { onclick: function () { press('0'); } }, '0'), h('button', { onclick: back }, '⌫'));
  var el = h('div', { class: 'pinbox' }, h('h2', {}, opts.title), h('div', { class: 'sub' }, opts.subtitle || ''), dots, err, keys);
  el._key = function (e) { if (/^\d$/.test(e.key)) press(e.key); else if (e.key === 'Backspace') back(); };
  el.setError = function (m) { err.textContent = m; };
  return el;
}

/* ---------- BERANDA ---------- */
function homeScreen(state) {
  var films = App.films;
  var page = h('div', { class: 'page' });
  var top = h('div', { class: 'topbar' },
    h('div', { class: 'hello' }, App.nickname ? [h('small', {}, 'Halo,'), h('b', {}, App.nickname), h('span', {}, 'Mau nonton apa hari ini?')] : null),
    h('div', { class: 'sp' }),
    h('button', { class: 'iconbtn refbtn' + (App.refreshing ? ' spin' : ''), title: 'Muat ulang', onclick: function () { App.refresh(); } }, icon('refresh', 20)),
    h('button', { class: 'iconbtn', title: 'Nobar', onclick: function () { UI.go({ t: 'nobar' }); } }, icon('people', 20)),
    h('button', { class: 'iconbtn', title: 'Cari', onclick: function () { UI.go({ t: 'search' }); } }, icon('search', 20)),
    h('button', { class: 'iconbtn', title: 'Profil', onclick: function () { UI.go({ t: 'profile' }); } }, avatar(App.nickname, 36)));
  page.append(top);
  if (App.netError && films.length) page.append(h('div', { class: 'banner' }, h('span', { class: 'grow' }, 'Tidak ada koneksi. Menampilkan data tersimpan terakhir.'), btn('Coba lagi', null, false, function () { App.refresh(); })));
  if (state.update) page.append(h('div', { class: 'banner up', onclick: function () { aph.open(state.update.url); } }, icon('ext', 18), h('span', { class: 'grow' }, 'Versi ' + state.update.version + ' tersedia. Klik untuk mengunduh.')));
  var used = App.genres.filter(function (g) { return films.some(function (f) { return f.genres.indexOf(g) >= 0; }) && g.trim().toLowerCase() !== '21+' && g.trim().toLowerCase() !== 'extraordinary'; });
  if (used.length) {
    var chips = h('div', { class: 'chips' }, chip('Semua', !state.genre, function () { state.genre = null; state.rerender(); }));
    used.forEach(function (g) { chips.append(chip(g, state.genre === g, function () { state.genre = g; state.rerender(); })); });
    page.append(chips);
  }
  if (!films.length) { page.append(emptyState(App.loaded ? 'Belum ada film.\nTambahkan lewat web Master Stream, lalu tekan Muat ulang.' : 'Memuat data…')); return page; }
  if (state.genre) { page.append(grid(films.filter(function (f) { return f.genres.indexOf(state.genre) >= 0; }))); return page; }
  var hero = films.find(function (f) { return f.recommended; }) || films[0];
  page.append(h('div', { class: 'hero', onclick: function () { UI.openFilm(hero); } },
    posterEl(hero, 640, true), h('div', { class: 'shade' }), h('div', { class: 'tag' }, '◆ SOROTAN'),
    h('div', { class: 'info' }, h('h2', {}, hero.name), h('p', {}, meta(hero)),
      h('div', { class: 'acts', onclick: function (e) { e.stopPropagation(); } },
        btn('PUTAR', 'play', true, function () { UI.play(hero); }),
        isExtra(hero) ? null : btn(App.favs.has(hero.id) ? 'DI DAFTARKU' : 'DAFTARKU', App.favs.has(hero.id) ? 'check' : 'plus', false, function () { App.toggleFav(hero.id); })))));
  var cont = films.filter(function (f) { var fr = App.frac(App.lastProg(f)); return !App.hiddenCont.has(f.id) && fr >= 0.02 && fr <= 0.95; })
    .sort(function (a, b) { return (App.lastProg(b).ts) - (App.lastProg(a).ts); });
  if (cont.length) { page.append(sectionTitle('Lanjutkan Menonton'), posterRow(cont, { progress: true, onclick: function (f) { UI.play(f); }, onhide: function (f) { App.hideFromContinue(f); }, sub: 'Lanjutkan' })); }
  var rec = films.filter(function (f) { return f.recommended; }); if (rec.length) page.append(sectionTitle('Rekomendasi untukmu'), posterRow(rec));
  var pop = films.filter(function (f) { return f.popular; }); if (pop.length) page.append(sectionTitle('Populer'), posterRow(pop));
  page.append(sectionTitle('Baru Ditambah'), posterRow(films.slice(0, 20)));
  used.forEach(function (g) { var l = films.filter(function (f) { return f.genres.indexOf(g) >= 0; }); if (l.length >= 2) page.append(sectionTitle(g), posterRow(l)); });
  return page;
}

/* ---------- BARU ---------- */
function newScreen() {
  var page = h('div', { class: 'page' }, h('h1', {}, 'Baru Ditambah'));
  var films = App.films;
  if (!films.length) { page.append(emptyState('Belum ada film.')); return page; }
  var now = Date.now(); var months = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];
  var groups = {}, order = [];
  films.forEach(function (f) {
    var d = f.added ? new Date(f.added) : null;
    var label = !d ? 'Sebelumnya' : (now - f.added < 7 * 86400000 ? 'Minggu ini' : (now - f.added < 30 * 86400000 ? 'Bulan ini' : months[d.getMonth()] + ' ' + d.getFullYear()));
    if (!groups[label]) { groups[label] = []; order.push(label); }
    groups[label].push(f);
  });
  order.forEach(function (l) {
    page.append(sectionTitle(l), grid(groups[l], { sub: function () { return ''; }() }));
  });
  return page;
}

/* ---------- MOVIE / SERIES ---------- */
function browseScreen(isSer, state) {
  var page = h('div', { class: 'page' }, h('h1', {}, isSer ? 'Series' : 'Movie'));
  page.append(searchBox(isSer ? 'Cari series…' : 'Cari film…', '', function () {}, false));
  page.lastChild.querySelector('input').addEventListener('focus', function () { UI.go({ t: 'search' }); });
  var base = App.films.filter(function (f) { return isSeries(f) === isSer; });
  if (!base.length) { page.append(isSer ? h('div', { class: 'empty' }, 'Belum ada series.\nTambahkan lewat web Master Stream dengan tipe Series.') : emptyState('Belum ada film.')); return page; }
  var mode = state.mode || 'Genre';
  page.append(h('div', { class: 'chips' }, ['Genre', 'Negara', 'Tahun'].map(function (m) { return chip(m, mode === m, function () { state.mode = m; state.rerender(); }); })));
  var map = {}, keys = [];
  var add = function (k, f) { if (!map[k]) { map[k] = []; keys.push(k); } map[k].push(f); };
  base.forEach(function (f) {
    if (mode === 'Negara') add(f.country || 'Lainnya', f);
    else if (mode === 'Tahun') add(f.year ? String(f.year) : 'Tanpa tahun', f);
    else if (!f.genres.length) add('Lainnya', f); else f.genres.forEach(function (g) { add(g, f); });
  });
  if (mode === 'Negara') keys.sort(function (a, b) { return ((a === 'Lainnya') - (b === 'Lainnya')) || (map[b].length - map[a].length); });
  else if (mode === 'Tahun') keys.sort(function (a, b) { return ((a === 'Tanpa tahun') - (b === 'Tanpa tahun')) || ((+b || 0) - (+a || 0)); });
  else keys.sort(function (a, b) { return ((a === 'Lainnya') - (b === 'Lainnya')) || a.localeCompare(b); });
  keys.forEach(function (k) { page.append(sectionTitle(k + ' (' + map[k].length + ')'), posterRow(map[k])); });
  return page;
}

/* ---------- CARI ---------- */
function searchScreen(state) {
  var page = h('div', { class: 'page' });
  var res = h('div');
  var draw = function () {
    clear(res);
    var k = (state.q || '').trim().toLowerCase();
    var list = !k ? App.films : App.films.filter(function (f) { return (f.name + ' ' + (f.year || '') + ' ' + f.country + ' ' + f.genres.join(' ')).toLowerCase().indexOf(k) >= 0; });
    if (!k) {
      var rec = App.films.filter(function (f) { return f.recommended; }); var pop = App.films.filter(function (f) { return f.popular; });
      if (rec.length) res.append(sectionTitle('Rekomendasi'), posterRow(rec));
      if (pop.length) res.append(sectionTitle('Populer'), posterRow(pop));
      if (!rec.length && !pop.length) res.append(grid(list));
    } else if (!list.length) res.append(h('div', { class: 'empty' }, 'Tidak ada hasil untuk "' + state.q + '".'));
    else res.append(h('div', { class: 'badge', style: { display: 'inline-block', margin: '12px 0' } }, list.length + ' hasil'), grid(list));
  };
  page.append(h('div', { class: 'topbar' }, h('button', { class: 'iconbtn', onclick: UI.back }, icon('back', 20)), searchBox('Cari judul, tahun, negara, genre…', state.q, function (v) { state.q = v; draw(); }, true)), res);
  draw();
  return page;
}

/* ---------- DAFTARKU / EXTRAORDINARY ---------- */
function myListScreen(state) {
  var page = h('div', { class: 'page' });
  var sort = state.sort || 0;
  var list = App.films.filter(function (f) { return App.favs.has(f.id); });
  if (sort === 1) list.sort(function (a, b) { return a.name.toLowerCase().localeCompare(b.name.toLowerCase()); });
  else if (sort === 2) list.sort(function (a, b) { return ((App.lastProg(b) || {}).ts || 0) - ((App.lastProg(a) || {}).ts || 0); });
  page.append(h('h1', {}, 'Daftarku', list.length ? h('span', { class: 'badge' }, list.length + ' judul') : null));
  if (list.length > 1) page.append(h('div', { class: 'sorter' }, ['Terbaru', 'Abjad', 'Terakhir ditonton'].map(function (n, i) { return chip(n, sort === i, function () { state.sort = i; state.rerender(); }); })));
  if (!list.length) page.append(h('div', { class: 'empty' }, 'Daftarkumu masih kosong.\nTekan DAFTARKU pada film untuk menyimpannya di sini.'));
  else page.append(grid(list));
  return page;
}
function extraScreen() {
  var list = App.extraFilms;
  var page = h('div', { class: 'page' }, h('h1', {}, 'Extraordinary', list.length ? h('span', { class: 'badge' }, list.length + ' judul') : null));
  page.append(list.length ? grid(list) : h('div', { class: 'empty' }, 'Belum ada film bergenre Extraordinary.'));
  return page;
}

/* ---------- DETAIL ---------- */
function detailScreen(id) {
  var f = App.find(id);
  var page = h('div', { class: 'page' });
  page.append(h('div', { class: 'topbar' }, h('button', { class: 'iconbtn', onclick: UI.back }, icon('back', 20))));
  if (!f) { page.append(h('div', { class: 'empty' }, 'Judul ini tidak tersedia.')); return page; }
  var lp = App.lastProg(f); var fr = App.frac(lp);
  var body = h('div', { class: 'body' });
  body.append(h('h1', {}, f.name), h('div', { class: 'meta' }, kind(f) + ' • ' + meta(f) + (f.rating > 0 ? '  ★ ' + f.rating.toFixed(1) : '')));
  var acts = h('div', { class: 'acts' });
  var resumable = lp && fr >= 0.02 && fr <= 0.95;
  if (isSeries(f)) {
    var ri = App.resumeEp(f);
    if (ri >= 0) acts.append(btn(lp ? 'LANJUTKAN EP ' + f.episodes[ri].n : 'MULAI NONTON', 'play', true, function () { UI.play(f); }));
  } else {
    acts.append(btn(resumable ? 'LANJUTKAN · ' + fmtTime(lp.pos) : (fr > 0.95 ? 'TONTON LAGI' : 'PUTAR'), 'play', true, function () { UI.play(f, resumable ? undefined : { fromStart: true }); }));
    if (resumable) acts.append(btn('MULAI DARI AWAL', null, false, function () { UI.play(f, { fromStart: true }); }));
  }
  if (!isExtra(f)) acts.append(btn(App.favs.has(f.id) ? 'DI DAFTARKU' : 'DAFTARKU', App.favs.has(f.id) ? 'check' : 'plus', false, function () { App.toggleFav(f.id); }));
  if (!isSeries(f)) acts.append(btn('NOBAR', 'people', false, function () { UI.go({ t: 'nobar', film: f.id }); }));
  var inQ = App.queue.indexOf(f.id) >= 0;
  acts.append(btn(inQ ? 'DI ANTREAN' : 'ANTREAN', inQ ? 'check' : 'plus', false, function () { inQ ? App.dequeue(f.id) : (App.enqueue(f.id), toast('Ditambah ke antrean')); }));
  if (!isSeries(f) && window.aph) {
    if (Dl.has(f.id)) acts.append(btn('HAPUS UNDUHAN', 'trash', false, function () { Dl.remove(f.id); }));
    else if (Dl.busy(f.id)) acts.append(btn('BATAL UNDUH', 'close', false, function () { Dl.cancel(f.id); }), h('span', { class: 'badge', 'data-dl': f.id }, '…'));
    else acts.append(btn('UNDUH', 'download', false, function () { Dl.start(f); }));
  }
  if (fr > 0.95 && !isSeries(f)) body.append(h('span', { class: 'badge' }, 'Selesai ditonton'));
  body.append(acts);
  body.append(h('div', { class: 'syn' }, f.synopsis || 'Belum ada sinopsis.'));
  if (isSeries(f)) {
    body.append(sectionTitle('Episode (' + f.episodes.length + ')'));
    var eps = h('div', { class: 'eps' });
    f.episodes.forEach(function (e, i) {
      var ef = App.epFilm(f, i); var p = App.progress[ef.id]; var pf = App.frac(p);
      var dlb = !window.aph ? null : Dl.has(ef.id) ? h('button', { class: 'iconbtn', title: 'Hapus unduhan', onclick: function () { Dl.remove(ef.id); } }, icon('check', 16))
        : Dl.busy(ef.id) ? h('span', { class: 'badge', 'data-dl': ef.id, onclick: function () { Dl.cancel(ef.id); }, title: 'Klik untuk batal' }, '…')
        : h('button', { class: 'iconbtn', title: 'Unduh episode', onclick: function () { Dl.start(ef); } }, icon('download', 16));
      eps.append(h('div', { class: 'eprow' }, h('button', { class: 'ep', onclick: function () { UI.play(f, { ep: i }); } },
        h('span', { class: 'n' }, e.n), h('span', { class: 'tt' }, e.title),
        h('span', { class: 'st' }, pf > 0.95 ? 'Selesai' : pf >= 0.02 ? fmtTime(p.pos) + ' / ' + fmtTime(p.dur) : ''),
        pf > 0 ? h('div', { class: 'prog' }, h('i', { style: { width: Math.round(pf * 100) + '%' } })) : null), dlb));
    });
    body.append(eps);
  }
  page.append(h('div', { class: 'detail' }, posterEl(f, 500, true), body));
  return page;
}

/* ---------- PROFIL ---------- */
function profileScreen(state) {
  var page = h('div', { class: 'page' });
  page.append(h('div', { class: 'topbar' }, h('button', { class: 'iconbtn', onclick: UI.back }, icon('back', 20))), h('h1', {}, 'Profil'));
  var rer = state.rerender;
  /* nama */
  var nm = h('input', { class: 'field', value: App.nickname, maxlength: 24, placeholder: 'Nama sapaan' });
  page.append(h('div', { class: 'panel' }, h('div', { style: { display: 'flex', gap: '14px', alignItems: 'center' } }, avatar(App.nickname, 56),
    h('div', { style: { flex: 1 } }, h('h4', {}, 'Nama sapaan'), nm)),
    h('div', { class: 'acts', style: { marginTop: '12px' } }, btn('SIMPAN', null, true, function () { App.set('nickname', nm.value.trim()); toast('Nama disimpan'); rer(); }))));

  /* kunci aplikasi */
  var lockPanel = h('div', { class: 'panel' }, h('h4', {}, 'Kunci aplikasi (PIN)'));
  var pinArea = h('div');
  var startPin = function (after) {
    var first = null;
    var show = function (mode) {
      clear(pinArea);
      var pad = pinPad({
        title: mode === 'set1' ? 'Buat PIN ' + PIN_LEN + ' angka' : 'Ulangi PIN', subtitle: '',
        onComplete: async function (pin, ctl) {
          if (mode === 'set1') { first = pin; show('set2'); return; }
          if (pin !== first) { ctl.error('PIN tidak sama, ulangi'); first = null; show('set1'); return; }
          await App.setPin(pin); clear(pinArea); toast('Kunci aplikasi aktif'); if (after) await after(); rer();
        },
      });
      pinArea.append(pad);
      pad.style.margin = '10px 0'; State.keyTarget = pad;
    };
    show('set1');
  };
  if (!App.lockEnabled) {
    lockPanel.append(h('p', {}, 'Aktifkan PIN agar Aphodius terkunci saat dibuka. PIN juga dibutuhkan untuk fitur Adults dan Extraordinary.'), btn('AKTIFKAN PIN', 'lock', true, function () { startPin(); }));
  } else {
    lockPanel.append(h('p', {}, 'Aphodius meminta PIN saat dibuka, atau setelah ditinggalkan lebih dari 45 detik. Mematikan PIN juga mematikan Adults dan Extraordinary.'),
      h('div', { class: 'acts' }, btn('UBAH PIN', null, false, function () {
        promptBox('PIN lama', 'Masukkan PIN saat ini.', 'PIN', true, async function (v) { if (!(await App.checkPin(v))) return 'PIN salah'; setTimeout(function () { startPin(); }, 50); return true; });
      }), btn('MATIKAN PIN', null, false, function () {
        promptBox('Matikan PIN', 'Masukkan PIN untuk mematikan kunci. Adults dan Extraordinary ikut mati.', 'PIN', true, async function (v) { if (!(await App.checkPin(v))) return 'PIN salah'; App.clearPin(); toast('Kunci dimatikan'); rer(); return true; });
      })));
  }
  lockPanel.append(pinArea);
  page.append(lockPanel);

  /* Adults & Extraordinary */
  var ae = h('div', { class: 'panel' }, h('h4', {}, 'Konten khusus'), h('p', {}, 'Memerlukan kunci aplikasi (PIN) aktif.'));
  ae.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Adults (21+)', h('small', {}, 'Tampilkan film bergenre 21+')),
    toggle(App.adults, function (on) {
      if (!on) { App.enableAdults(false); toast('Adults dimatikan'); return rer(); }
      if (!App.lockEnabled) { toast('Aktifkan PIN dulu, lalu coba lagi'); return rer(); }
      promptBox('Aktifkan Adults', 'Masukkan password Adults.', 'Password', true, async function (v) { if (!(await App.checkAdultsPassword(v))) return 'Password salah'; App.enableAdults(true); toast('Adults aktif'); rer(); return true; }, rer);
    })));
  ae.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Extraordinary', h('small', {}, 'Menu khusus (menggantikan Daftarku). Tidak bisa ditambahkan ke Daftarku. Wajib kunci aplikasi aktif.')),
    toggle(App.extra, function (on) {
      if (!on) { App.enableExtra(false); toast('Extraordinary dimatikan'); return rer(); }
      var enable = async function () { App.enableExtra(true); toast('Extraordinary aktif'); rer(); };
      promptBox('Aktifkan Extraordinary', 'Masukkan password Extraordinary.', 'Password', true, async function (v) {
        if (!(await App.checkExtraPassword(v))) return 'Password salah';
        if (App.lockEnabled) { await enable(); return true; }
        // belum ada PIN: buat PIN dulu, lalu aktifkan
        setTimeout(function () { toast('Buat PIN dulu untuk mengaktifkan Extraordinary', 4000); startPin(enable); }, 50);
        return true;
      }, rer);
    })));
  page.append(ae);
  /* pengaturan pemutar */
  var set = h('div', { class: 'panel' }, h('h4', {}, 'Pemutar'));
  set.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Lompat maju/mundur', h('small', {}, 'Tombol ← → di keyboard dan tombol di pemutar')),
    h('div', { class: 'acts' }, [5, 10, 30].map(function (v) { return chip(v + ' dtk', App.skipSec === v, function () { App.set('skipSec', v); rer(); }); }))));
  set.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Poster dari video', h('small', {}, 'Bila film tanpa poster, pakai cuplikan dari tengah video')),
    toggle(App.thumbPoster, function (on) { App.set('thumbPoster', on); })));
  set.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Chat melayang saat nobar', h('small', {}, 'Pesan baru tampil transparan di atas video')),
    toggle(App.floatChat, function (on) { App.set('floatChat', on); })));
  set.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Suara Bioskop', h('small', {}, 'Penguat bass dan lebar suara (perkiraan)')),
    toggle(App.surround, function (on) { App.set('surround', on); })));
  var eqRow = h('div', { class: 'acts', style: { marginTop: '8px' } }, EQ_PRESETS.map(function (p) {
    var on = JSON.stringify(App.eq) === JSON.stringify(p[1]);
    return chip(p[0], on, function () { App.set('eq', p[1].slice()); rer(); });
  }));
  set.append(h('div', { class: 'line', style: { display: 'block' } }, 'Equalizer', h('small', {}, 'Pilih preset suara'), eqRow));
  page.append(set);
  /* Windows: sistem */
  var win = h('div', { class: 'panel' }, h('h4', {}, 'Windows'));
  win.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Tetap di baki saat ditutup', h('small', {}, 'Tombol X menyembunyikan jendela; film dan suara tetap jalan. Keluar lewat ikon baki.')),
    toggle(App.trayClose, function (on) { App.set('trayClose', on); aph.setCfg({ trayClose: on }); })));
  win.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Mode TV', h('small', {}, 'Tampilan lebih besar, navigasi dengan tombol panah, Enter, dan Esc')),
    toggle(App.tvMode, function (on) { App.set('tvMode', on); applyMode(); })));
  win.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Hemat memori', h('small', {}, 'Poster lebih kecil dan cache dibersihkan berkala')),
    toggle(App.saver, function (on) { App.set('saver', on); Thumbs.clear && Thumbs.clear(); applyMode(); })));
  win.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Buka lewat link undangan', h('small', {}, 'Daftarkan aphodius://join/KODE ke Windows (hanya versi terpasang/portabel di folder yang sama)')),
    h('div', { class: 'acts' }, chip('Daftarkan', false, async function () { toast((await aph.proto(true)) ? 'Link undangan terdaftar' : 'Tidak bisa mendaftarkan link', 3000); }))));
  var cacheMsg = h('small', {});
  win.append(h('div', { class: 'line' }, h('div', { class: 'grow' }, 'Cache & log', cacheMsg),
    h('div', { class: 'acts' }, chip('Bersihkan cache', false, async function () { await aph.clearCache(); Thumbs.clear && Thumbs.clear(); toast('Cache dibersihkan'); }), chip('Buka log', false, function () { aph.showLog(); }))));
  page.append(win);
  /* antrean & unduhan */
  var ids = Object.keys(App.downloads);
  var dp = h('div', { class: 'panel' }, h('h4', {}, 'Antrean & Unduhan'));
  dp.append(h('div', { class: 'acts' }, btn('ANTREAN (' + App.queue.length + ')', 'list', false, function () { UI.go({ t: 'queue' }); }), btn('BUKA FOLDER UNDUHAN', 'ext', false, function () { aph.dlFolder(); })));
  if (!ids.length) dp.append(h('p', {}, 'Belum ada unduhan. Unduh dari halaman detail untuk menonton tanpa internet.'));
  ids.forEach(function (id) {
    var d = App.downloads[id];
    dp.append(h('div', { class: 'hist' }, h('div', { class: 'grow' }, h('b', {}, d.name), h('small', {}, Math.round(d.size / 1048576) + ' MB')),
      h('button', { class: 'iconbtn', title: 'Hapus', onclick: function () { Dl.remove(id); } }, icon('trash', 18))));
  });
  page.append(dp);
  page.append(h('div', { class: 'panel' }, h('h4', {}, 'Riwayat tontonan'), h('p', {}, 'Lihat dan hapus riwayat menonton.'), btn('BUKA RIWAYAT', 'history', false, function () { UI.go({ t: 'history' }); })));
  /* pembaruan */
  var upd = h('div', { class: 'panel' }, h('h4', {}, 'Pembaruan'), h('p', {}, 'Versi terpasang: ' + (state.version || '?')));
  var msg = h('div', { class: 'err', style: { color: 'var(--muted)' } });
  upd.append(btn('CEK PEMBARUAN', 'refresh', false, async function () {
    msg.textContent = 'Memeriksa…';
    try { var u = await Update.check(state.version); state.update = u; msg.textContent = u ? 'Versi ' + u.version + ' tersedia.' : 'Sudah versi terbaru.'; if (u) {
        upd.append(btn('PERBARUI SEKARANG', 'download', true, async function () {
          msg.textContent = 'Mengunduh…'; var off = aph.onUpd(function (p) { msg.textContent = 'Mengunduh… ' + (p.total ? Math.round(p.got * 100 / p.total) + '%' : Math.round(p.got / 1048576) + ' MB'); });
          var r = await aph.updStart(u.url); off();
          if (!r.ok) { msg.textContent = 'Pembaruan otomatis gagal (' + r.error + '). Membuka halaman unduhan…'; aph.open(u.page || u.url); }
        }), btn('UNDUH MANUAL', 'ext', false, function () { aph.open(u.page || u.url); }));
      } }
    catch (e) { msg.textContent = 'Gagal memeriksa: ' + (e.message || e); }
  }), msg);
  page.append(upd);
  return page;
}

/* ---------- RIWAYAT ---------- */
function historyScreen(state) {
  var page = h('div', { class: 'page' });
  page.append(h('div', { class: 'topbar' }, h('button', { class: 'iconbtn', onclick: UI.back }, icon('back', 20))), h('h1', {}, 'Riwayat tontonan'));
  var keys = Object.keys(App.progress).sort(function (a, b) { return App.progress[b].ts - App.progress[a].ts; });
  if (!keys.length) { page.append(h('div', { class: 'empty' }, 'Belum ada riwayat.')); return page; }
  page.append(btn('HAPUS SEMUA', 'trash', false, function () { confirmBox('Hapus semua riwayat?', 'Posisi terakhir semua tontonan akan hilang.', 'HAPUS', function () { App.clearHistory(); state.rerender(); }, true); }, 'danger'));
  var box = h('div', { class: 'panel', style: { marginTop: '14px', maxWidth: '900px' } });
  keys.forEach(function (k) {
    var base = k.split('#')[0]; var f = App.allFilms.find(function (x) { return x.id === base; });
    var name = f ? f.name : base; var ep = k.indexOf('#') > 0 ? ' • Ep ' + k.split('#')[1] : '';
    var p = App.progress[k];
    box.append(h('div', { class: 'hist' }, h('div', { class: 'grow' }, h('b', {}, name + ep), h('small', {}, new Date(p.ts).toLocaleString('id-ID') + ' • ' + fmtTime(p.pos) + ' / ' + fmtTime(p.dur) + ' (' + Math.round(App.frac(p) * 100) + '%)')),
      f ? btn('BUKA', null, false, function () { UI.openFilm(f); }) : null,
      h('button', { class: 'iconbtn', title: 'Hapus', onclick: function () { App.removeHistory(k); state.rerender(); } }, icon('trash', 18))));
  });
  page.append(box);
  return page;
}


/* ---------- ANTREAN ---------- */
function queueScreen(state) {
  var page = h('div', { class: 'page' });
  page.append(h('div', { class: 'topbar' }, h('button', { class: 'iconbtn', onclick: UI.back }, icon('back', 20))), h('h1', {}, 'Antrean tonton'));
  var items = App.queue.map(function (id) { return App.find(id); }).filter(Boolean);
  if (!items.length) { page.append(h('div', { class: 'empty' }, 'Antrean kosong. Tambahkan dari halaman detail film.')); return page; }
  page.append(h('div', { class: 'acts' }, btn('PUTAR SEMUA', 'play', true, function () { var f = items[0]; App.dequeue(f.id); UI.play(f, { fromStart: true }); }), btn('KOSONGKAN', 'trash', false, function () { App.queue.slice().forEach(function (i) { App.dequeue(i); }); state.rerender(); })));
  var box = h('div', { class: 'panel', style: { marginTop: '14px', maxWidth: '900px' } });
  App.queue.forEach(function (id, i) {
    var f = App.find(id); if (!f) return;
    box.append(h('div', { class: 'hist' }, h('div', { class: 'grow' }, h('b', {}, (i + 1) + '. ' + f.name), h('small', {}, kind(f) + ' • ' + meta(f))),
      btn('BUKA', null, false, function () { UI.openFilm(f); }),
      h('button', { class: 'iconbtn', title: 'Naik', onclick: function () { App.moveQueue(i, -1); state.rerender(); } }, icon('back', 16)),
      h('button', { class: 'iconbtn', title: 'Turun', onclick: function () { App.moveQueue(i, 1); state.rerender(); } }, icon('fwd', 16)),
      h('button', { class: 'iconbtn', title: 'Hapus', onclick: function () { App.dequeue(id); state.rerender(); } }, icon('close', 16))));
  });
  page.append(box);
  return page;
}
