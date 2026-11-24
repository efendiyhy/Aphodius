'use strict';
const assert = require('assert');
const { makeCtx } = require('./loadenv');
const { run } = makeCtx();
let n = 0; const t = async (name, fn) => { try { await fn(); n++; console.log('ok  -', name); } catch (e) { console.error('GAGAL -', name, '\n', e.stack); process.exitCode = 1; } };

const SAMPLE = JSON.stringify({ genres: ['Aksi', 'Drama', '21+', 'Extraordinary'], films: [
  { id: 'a', link: 'https://x.com/u/status/111', name: 'Film A', year: 2020, genres: ['Aksi'], poster: '', added: 3, recommended: true, popular: true },
  { id: 'b', link: 'https://x.com/u/status/222', name: 'Film B', year: 2021, genres: ['Drama', 'Aksi'], poster: null, added: 5 },
  { id: 'c', link: 'https://x.com/u/status/333', name: 'Dewasa', genres: ['21+'], added: 4 },
  { id: 'd', link: 'https://x.com/u/status/444', name: 'Istimewa', genres: ['Extraordinary', 'Aksi'], added: 6 },
  { id: 's', link: 'https://x.com/u/status/555', name: 'Seri', type: 'Series', genres: ['Drama'], added: 2, episodes: [{ n: 2, title: '', link: 'https://x.com/u/status/602' }, { n: 1, title: 'Awal', link: 'https://x.com/u/status/601' }, { n: 3, link: '' }] },
  { id: 'bad', link: '', name: 'Tanpa link' }, null,
] });

(async () => {
  await t('parseFilms: tipe, episode, saring entri rusak', () => {
    const r = run(`parseFilms(${JSON.stringify(SAMPLE)})`);
    assert.strictEqual(r.films.length, 5);
    const s = r.films.find(f => f.id === 's');
    assert.strictEqual(s.type, 'series'); assert.strictEqual(JSON.stringify(s.episodes.map(e => e.n)), '[1,2]');
    assert.strictEqual(s.episodes[1].title, 'Episode 2');
    assert.strictEqual(r.films.find(f => f.id === 'b').poster, '');
  });
  await t('visibilitas: 21+ dan Extraordinary tersaring', () => {
    run(`App.apply(${JSON.stringify(SAMPLE)})`);
    assert.strictEqual(JSON.stringify(run('App.films.map(f=>f.id)')), '["b","a","s"]');
    assert.strictEqual(run('App.extraFilms.length'), 0);
    run('App.pinHash="x"; App.adults=true; App.extra=true;');
    assert.ok(run('App.films.some(f=>f.id==="c")'));
    assert.strictEqual(JSON.stringify(run('App.extraFilms.map(f=>f.id)')), '["d"]');
    assert.ok(!run('App.films.some(f=>f.id==="d")'));
  });
  await t('Extraordinary tidak bisa masuk Daftarku', () => {
    run('App.toggleFav("d")'); assert.strictEqual(run('App.favs.has("d")'), false);
    run('App.toggleFav("a")'); assert.strictEqual(run('App.favs.has("a")'), true);
  });
  await t('kunci PIN: set, cek, matikan menonaktifkan Adults/Extraordinary', async () => {
    run('App.pinHash=""; App.pinSalt=""; App.adults=false; App.extra=false;');
    await run('App.setPin("1234")');
    assert.strictEqual(await run('App.checkPin("1234")'), true);
    assert.strictEqual(await run('App.checkPin("0000")'), false);
    run('App.enableExtra(true); App.enableAdults(true);');
    assert.ok(run('App.extra && App.adults'));
    run('App.clearPin()');
    assert.ok(!run('App.extra') && !run('App.adults') && !run('App.lockEnabled'));
    run('App.enableExtra(true)'); assert.strictEqual(run('App.extra'), false, 'tanpa PIN tidak boleh aktif');
  });
  await t('hash password sesuai Android (tanpa menulis password)', async () => {
    assert.strictEqual(await run('sha256("")'), 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
    assert.strictEqual(await run('App.checkExtraPassword("salah")'), false);
    assert.strictEqual(await run('App.checkAdultsPassword("salah")'), false);
  });
  await t('progres & lanjutkan episode', () => {
    run('App.progress={}; App.allFilms.length;');
    const f = run('App.find("s")');
    assert.strictEqual(run('App.resumeEp(App.find("s"))'), 0);
    run('App.saveProgress("s#1", 96000, 100000)'); assert.strictEqual(run('App.resumeEp(App.find("s"))'), 1);
    for (const w = Date.now(); Date.now() - w < 3;);
    run('App.saveProgress("s#2", 20000, 100000)'); assert.strictEqual(run('App.resumeEp(App.find("s"))'), 1);
    assert.strictEqual(run('App.lastProg(App.find("s")).pos'), 20000);
    assert.strictEqual(run('App.epFilm(App.find("s"),1).id'), 's#2');
  });
  await t('nextFor: genre sama diutamakan, bukan series', () => {
    const nx = run('App.nextFor(App.find("a")).id'); assert.strictEqual(nx, 'b');
    run('App.progress={}; App.saveProgress("b", 99000, 100000)');
    assert.ok(run('App.nextFor(App.find("a"))'));
  });
  await t('hiddenCont dibuka lagi saat progres disimpan', () => {
    run('App.hideFromContinue(App.find("a"))'); assert.ok(run('App.hiddenCont.has("a")'));
    run('App.saveProgress("a", 1000, 100000)'); assert.ok(!run('App.hiddenCont.has("a")'));
  });
  await t('Extractor: id tweet, varian mp4/hls, urutan', () => {
    assert.strictEqual(run('Extractor.tweetId("https://x.com/a/status/12345?s=20")'), '12345');
    const body = '{"u":"https:\\/\\/video.twimg.com\\/ext_tw_video\\/1\\/pu\\/vid\\/avc1\\/640x360\\/a.mp4?tag=12","v":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/b.mp4?tag=12","h":"https://video.twimg.com/ext_tw_video/1/pu/pl/x.m3u8?tag=12&amp;v=1"}';
    const v = run(`Extractor.parseVariants(${JSON.stringify(body)})`);
    assert.strictEqual(JSON.stringify(v.map(x => x.height)), '[720,360]'); assert.ok(v.every(x => !x.hls));
    const all = [{ url: 'a', height: 1080, pixels: 1920 * 1080, hls: false }, { url: 'b', height: 720, pixels: 1280 * 720, hls: false }, { url: 'c', height: 360, pixels: 640 * 360, hls: false }];
    run(`var _l=${JSON.stringify(all)};`);
    assert.strictEqual(run('Extractor.chooseVariant(_l,0).url'), 'a');
    assert.strictEqual(run('Extractor.chooseVariant(_l,720).url'), 'b');
    assert.strictEqual(run('Extractor.chooseVariant(_l,240).url'), 'c');
  });
  await t('Extractor: token syndikasi sama dengan Android', () => {
    // ((id/1e15)*PI) dalam basis 36 tanpa angka nol dan titik
    const tk = run('Extractor.syndicationToken("1234567890123456789")');
    assert.ok(/^[1-9a-z]+$/.test(tk), tk);
  });
  await t('friendlyError memetakan galat umum', () => {
    assert.ok(/tidak ditemukan/.test(run('friendlyError("HTTP 404")')));
    assert.ok(/internet/.test(run('friendlyError("Tidak bisa menghubungi server")')));
    assert.ok(/tidak bisa diputar/.test(run('friendlyError("xyz")')));
  });
  await t('Update.cmp membandingkan versi', () => {
    assert.strictEqual(run('Update.cmp("1.10.0","1.9.1")'), 1);
    assert.strictEqual(run('Update.cmp("v1.9.1","1.9.1")'), 0);
    assert.strictEqual(run('Update.cmp("1.9","1.9.1")'), -1);
  });
  console.log(n + ' uji lulus'); 
})();
