'use strict';
/* Utilitas dasar: DOM, ikon, format, penyimpanan, hash. */
var C = { Ocean: '#0A84C6', Aqua: '#22C7EC', Navy: '#050D16', Surface: '#0D1A28', SurfaceHigh: '#15283B', Outline: '#223850', Muted: '#ABC0D4', Subtle: '#91AAC2', OnAqua: '#031019', Gold: '#F2A33A' };

function h(tag, attrs) {
  var el = document.createElement(tag);
  var a = attrs || {};
  Object.keys(a).forEach(function (k) {
    var v = a[k];
    if (v == null || v === false) return;
    if (k === 'class') el.className = v;
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k === 'style') el.setAttribute('style', v);
    else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'html') el.innerHTML = v;
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, v);
  });
  var kids = Array.prototype.slice.call(arguments, 2);
  (function add(list) {
    list.forEach(function (c) {
      if (c == null || c === false) return;
      if (Array.isArray(c)) return add(c);
      el.append(c.nodeType ? c : document.createTextNode(String(c)));
    });
  })(kids);
  return el;
}
function $(sel, root) { return (root || document).querySelector(sel); }
function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }

/* Ikon garis 24x24 */
var ICON_PATHS = {
  back: 'M15 5l-7 7 7 7', search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM16 16l5 5', play: 'M8 5v14l11-7z', pause: 'M8 5v14M16 5v14',
  plus: 'M12 5v14M5 12h14', check: 'M5 12l5 5L20 7', close: 'M6 6l12 12M18 6L6 18', lock: 'M6 11h12v9H6zM8 11V8a4 4 0 0 1 8 0v3', unlock: 'M6 11h12v9H6zM8 11V8a4 4 0 0 1 7-2',
  full: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5', home: 'M4 11l8-7 8 7v9h-5v-6H9v6H4z', news: 'M4 5h16v14H4zM8 9h8M8 13h5', film: 'M4 5h16v14H4zM8 5v14M16 5v14M4 9h4M4 15h4M16 9h4M16 15h4',
  series: 'M4 7h16v12H4zM8 3l4 4 4-4', list: 'M5 7h14M5 12h14M5 17h9', user: 'M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM4 20c0-4 4-6 8-6s8 2 8 6', gem: 'M6 4h12l3 5-9 11L3 9z',
  next: 'M6 5l9 7-9 7zM18 5v14', rew: 'M11 7L4 12l7 5zM20 7l-7 5 7 5z', fwd: 'M13 7l7 5-7 5zM4 7l7 5-7 5z', refresh: 'M4 12a8 8 0 0 1 14-5l2-2v6h-6l2.5-2.5A5 5 0 0 0 7 12M20 12a8 8 0 0 1-14 5l-2 2v-6h6l-2.5 2.5A5 5 0 0 0 17 12',
  mic: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3', micoff: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3M3 3l18 18',
  cam: 'M3 7h12v10H3zM15 11l6-4v10l-6-4', camoff: 'M3 7h12v10H3zM15 11l6-4v10l-6-4M3 3l18 18',
  chat: 'M4 5h16v11H9l-5 4z', people: 'M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2 20c0-3 3-5 6-5s6 2 6 5M14 15c4 0 8 1 8 5',
  copy: 'M9 9h11v11H9zM5 15V4h11', send: 'M3 11l18-8-8 18-2-8z', moon: 'M20 14A8 8 0 1 1 10 4a6.5 6.5 0 0 0 10 10z', skip: 'M12 5v14M5 12h14', star: 'M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z',
  quality: 'M4 6h16M4 12h16M4 18h16M8 4v4M15 10v4M10 16v4', speed: 'M12 4a9 9 0 1 0 9 9M12 13l5-5', crop: 'M7 3v14h14M3 7h14v14', sound: 'M4 9h4l5-4v14l-5-4H4zM17 9a4 4 0 0 1 0 6', vol: 'M4 9h4l5-4v14l-5-4H4zM17 8a5 5 0 0 1 0 8',
  aspect: 'M4 6h16v12H4zM8 10h8v4H8z', settings: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM4 12h2M18 12h2M12 4v2M12 18v2', history: 'M4 12a8 8 0 1 0 3-6M4 4v4h4M12 8v5l3 2', trash: 'M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z', ext: 'M14 4h6v6M20 4l-9 9M18 14v6H4V6h6', download: 'M12 4v11M7 11l5 5 5-5M5 20h14',
};
function icon(name, size, filled) {
  var s = size || 22;
  var d = ICON_PATHS[name] || '';
  var fill = filled || name === 'play' || name === 'next' || name === 'star' ? 'currentColor' : 'none';
  return h('span', { class: 'ic', style: { width: s + 'px', height: s + 'px' }, html: '<svg viewBox="0 0 24 24" width="' + s + '" height="' + s + '" fill="' + fill + '" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="' + d + '"/></svg>' });
}

function fmtTime(ms) {
  var t = Math.max(0, Math.floor(ms / 1000));
  var hh = Math.floor(t / 3600), mm = Math.floor((t % 3600) / 60), ss = t % 60;
  var p = function (n) { return (n < 10 ? '0' : '') + n; };
  return hh > 0 ? hh + ':' + p(mm) + ':' + p(ss) : mm + ':' + p(ss);
}
function fmtSpeed(v) { return (v === Math.floor(v) ? String(v) : String(v)).replace('.', ',') + 'x'; }

/* Penyimpanan lokal (localStorage, berada di folder data portabel) */
var Store = {
  get: function (k, d) { try { var v = localStorage.getItem('aph.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set: function (k, v) { try { localStorage.setItem('aph.' + k, JSON.stringify(v)); } catch (e) { /* penuh */ } },
  del: function (k) { try { localStorage.removeItem('aph.' + k); } catch (e) {} },
};

function hex(buf) { return Array.prototype.map.call(new Uint8Array(buf), function (b) { return ('0' + b.toString(16)).slice(-2); }).join(''); }
function sha256(text) { return crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)).then(hex); }
function uuid() { return (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random()); }
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

/* Toast singkat */
function toast(msg, ms) {
  var host = document.getElementById('toasts');
  if (!host) return;
  var t = h('div', { class: 'toast' }, msg);
  host.append(t);
  setTimeout(function () { t.classList.add('out'); setTimeout(function () { t.remove(); }, 300); }, ms || 2600);
}

/* IndexedDB kecil untuk gambar thumbnail */
var IDB = {
  db: null,
  open: function () {
    var self = this;
    if (self.db) return Promise.resolve(self.db);
    return new Promise(function (res, rej) {
      var r = indexedDB.open('aphodius', 1);
      r.onupgradeneeded = function () { r.result.createObjectStore('thumbs'); };
      r.onsuccess = function () { self.db = r.result; res(self.db); };
      r.onerror = function () { rej(r.error); };
    });
  },
  get: function (k) { return this.open().then(function (db) { return new Promise(function (res) { var q = db.transaction('thumbs').objectStore('thumbs').get(k); q.onsuccess = function () { res(q.result || null); }; q.onerror = function () { res(null); }; }); }).catch(function () { return null; }); },
  set: function (k, v) { return this.open().then(function (db) { return new Promise(function (res) { var tx = db.transaction('thumbs', 'readwrite'); tx.objectStore('thumbs').put(v, k); tx.oncomplete = function () { res(); }; tx.onerror = function () { res(); }; }); }).catch(function () {}); },
};
