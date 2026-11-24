'use strict';
/* Memuat skrip renderer (global biasa) ke satu konteks Node untuk pengujian logika. */
const vm = require('vm'), fs = require('fs'), path = require('path');
function makeCtx(extra) {
  const store = {};
  const ctx = {
    console, setTimeout, clearTimeout, setInterval, clearInterval, URL, TextEncoder, TextDecoder, AbortController, Promise,
    crypto: require('crypto').webcrypto,
    localStorage: { getItem: k => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: k => { delete store[k]; } },
    document: { getElementById: () => null, createElement: () => ({}) }, indexedDB: undefined,
    fetch: () => Promise.reject(new Error('no network')), navigator: {}, window: {},
  };
  Object.assign(ctx, extra || {});
  vm.createContext(ctx);
  for (const f of ['core', 'extractor', 'data']) vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'renderer', 'js', f + '.js'), 'utf8') + '\n', ctx, { filename: f + '.js' });
  // var di top-level skrip ikut menjadi properti konteks; 'const/let' tidak, jadi ekspos lewat evaluasi
  return { ctx, run: (code) => vm.runInContext(code, ctx) };
}
module.exports = { makeCtx };
