'use strict';
/* Mengambil URL video langsung (video.twimg.com) dari link tweet X, sama seperti di Android. */
var Extractor = (function () {
  var idRe = /status(?:es)?\/(\d+)/;
  var videoRe = /https:\/\/video\.twimg\.com\/[^"'\s\\<>)]+/g;
  var resRe = /\/(\d{2,5})x(\d{2,5})\//;

  function tweetId(link) { var m = idRe.exec(link || ''); return m ? m[1] : null; }

  function parseVariants(raw) {
    var text = String(raw).replace(/\\\//g, '/').replace(/\\u0026/g, '&').replace(/&amp;/g, '&');
    var urls = (text.match(videoRe) || []).map(function (u) { return u.replace(/[,;.]+$/, ''); });
    var out = new Map();
    urls.forEach(function (u) {
      var path = u.split('?')[0];
      var hls = /\.m3u8$/.test(path), mp4 = /\.mp4$/.test(path);
      if (!hls && !mp4) return;
      var m = resRe.exec(path);
      var w = m ? parseInt(m[1], 10) : 0, hh = m ? parseInt(m[2], 10) : 0;
      if (!out.has(path)) out.set(path, { url: u, height: Math.min(w, hh), pixels: w * hh, hls: hls });
    });
    var list = Array.from(out.values());
    var mp4s = list.filter(function (v) { return !v.hls; }).sort(function (a, b) { return b.pixels - a.pixels; });
    var hls = list.filter(function (v) { return v.hls; });
    return mp4s.length ? mp4s : hls;
  }

  function syndicationToken(id) {
    var x = Number(id) / 1e15 * Math.PI;
    var ip = Math.floor(x);
    var fp = x - ip;
    var s = ip.toString(36);
    var n = 0;
    while (fp > 0 && n < 12) {
      fp *= 36;
      var d = Math.floor(fp);
      s += d.toString(36);
      fp -= d;
      n++;
    }
    return s.replace(/0/g, '').replace(/\./g, '');
  }

  function httpGet(url, ms) {
    var ctl = new AbortController();
    var t = setTimeout(function () { ctl.abort(); }, ms || 12000);
    return fetch(url, { signal: ctl.signal, cache: 'no-store' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.text();
    }).finally(function () { clearTimeout(t); });
  }

  /** onStage(i, n) dipanggil sebelum tiap sumber dicoba. Mengembalikan daftar varian atau melempar galat. */
  async function extract(link, onStage) {
    var id = tweetId(link);
    if (!id) throw new Error('Link X tidak valid');
    var sources = [
      'https://api.fxtwitter.com/status/' + id,
      'https://api.vxtwitter.com/Twitter/status/' + id,
      'https://cdn.syndication.twimg.com/tweet-result?id=' + id + '&lang=en&token=' + syndicationToken(id),
    ];
    var reached = false;
    var merged = new Map();
    for (var i = 0; i < sources.length; i++) {
      if (onStage) onStage(i + 1, sources.length);
      try {
        var body = await httpGet(sources[i], 12000);
        reached = true;
        parseVariants(body).forEach(function (v) { var k = v.url.split('?')[0]; if (!merged.has(k)) merged.set(k, v); });
        if (Array.from(merged.values()).filter(function (v) { return !v.hls; }).length >= 2) break;
      } catch (e) {
        if (String(e.message).indexOf('HTTP') === 0) reached = true;
      }
    }
    if (merged.size) {
      var all = Array.from(merged.values());
      var mp4 = all.filter(function (v) { return !v.hls; }).sort(function (a, b) { return b.pixels - a.pixels; });
      return mp4.length ? mp4 : all;
    }
    throw new Error(reached ? 'Tweet ini tidak berisi video (atau sudah dihapus/privat).' : 'Tidak bisa menghubungi server. Periksa koneksi internet.');
  }

  function chooseVariant(list, q) {
    var mp4 = list.filter(function (v) { return !v.hls; }).sort(function (a, b) { return b.pixels - a.pixels; });
    if (!mp4.length) return list[0] || null;
    if (q === 0) return mp4[0];
    return mp4.find(function (v) { return v.height <= q; }) || mp4[mp4.length - 1];
  }

  return { tweetId: tweetId, parseVariants: parseVariants, syndicationToken: syndicationToken, extract: extract, chooseVariant: chooseVariant, httpGet: httpGet };
})();

/** Ubah galat teknis menjadi kalimat sederhana. Detail aslinya ditampilkan kecil di bawahnya. */
function friendlyError(raw) {
  var r = String(raw || '').toLowerCase();
  if (/404|tidak berisi video|not found/.test(r)) return 'Video tidak ditemukan. Mungkin sudah dihapus dari X.';
  if (/403|401|privat/.test(r)) return 'Video ini tidak bisa dibuka karena dibatasi pemiliknya.';
  if (/429/.test(r)) return 'Terlalu banyak permintaan. Tunggu sebentar lalu coba lagi.';
  if (/network|unknownhost|timeout|menghubungi server|koneksi|failed to fetch|aborted/.test(r)) return 'Koneksi internet bermasalah. Periksa jaringanmu lalu coba lagi.';
  if (/decod|format|unsupported|src_not_supported/.test(r)) return 'Format video ini tidak didukung.';
  if (/^5\d\d/.test(r) || /http 5\d\d/.test(r)) return 'Server sedang bermasalah. Coba lagi nanti.';
  return 'Video tidak bisa diputar saat ini. Coba lagi sebentar.';
}
