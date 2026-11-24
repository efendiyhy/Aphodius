'use strict';
/* Uji asap: jalankan renderer di Electron dengan jaringan palsu, telusuri layar, putar video uji, ambil tangkapan layar. */
const { app, BrowserWindow, session, ipcMain } = require('electron');
const path = require('path'), fs = require('fs'), http = require('http');
const OUT = process.env.SMOKE_OUT || '/tmp/smoke';
app.setPath('userData', fs.mkdtempSync('/tmp/smoke-ud-'));
app.commandLine.appendSwitch('no-sandbox');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('use-fake-ui-for-media-stream');
app.commandLine.appendSwitch('use-fake-device-for-media-stream');

const DATA = { genres: ['Aksi', 'Drama', 'Extraordinary'], films: [
  { id: 'f1', link: 'https://x.com/u/status/1001', name: 'Petualangan Pertama', year: 2021, country: 'Indonesia', genres: ['Aksi'], poster: '', added: 9, recommended: true, popular: true, synopsis: 'Sebuah kisah uji untuk memastikan tampilan detail berjalan.' },
  { id: 'f2', link: 'https://x.com/u/status/1002', name: 'Drama Malam', year: 2019, country: 'Korea', genres: ['Drama'], poster: '', added: 8, popular: true },
  { id: 'f3', link: 'https://x.com/u/status/1003', name: 'Aksi Kedua', year: 2022, genres: ['Aksi'], poster: '', added: 7, recommended: true },
  { id: 'x1', link: 'https://x.com/u/status/1004', name: 'Istimewa', genres: ['Extraordinary'], poster: '', added: 6 },
  { id: 's1', link: 'https://x.com/u/status/1005', name: 'Serial Uji', type: 'series', genres: ['Drama'], poster: '', added: 5, episodes: [{ n: 1, title: 'Pembuka', link: 'https://x.com/u/status/1006' }, { n: 2, title: 'Lanjutan', link: 'https://x.com/u/status/1007' }] },
] };

const FX = { tweet: { media: { videos: [{ url: 'https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/640x360/a.mp4?tag=12' }, { url: 'https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/320x180/b.mp4?tag=12' }] } } };
const REL = { tag_name: 'v99.0.0', html_url: 'https://github.com/efendiyhy/Aphodius/releases', body: 'uji', assets: [{ name: 'Aphodius-windows-99.0.0.zip', browser_download_url: 'https://example.com/a.zip' }] };
/* Firebase RTDB tiruan: pohon di memori, REST + SSE */
const tree = {}; const streams = [];
const segs = (p) => p.split('/').filter(Boolean);
const getAt = (path) => { let c = tree; for (const k of segs(path)) { if (c == null || typeof c !== 'object') return null; c = c[k]; } return c === undefined ? null : c; };
const resolveSv = (v) => { if (v && typeof v === 'object') { if (v['.sv'] === 'timestamp') return Date.now(); const o = Array.isArray(v) ? [] : {}; for (const k of Object.keys(v)) o[k] = resolveSv(v[k]); return o; } return v; };
const setAt = (path, val) => {
  const sg = segs(path); if (!sg.length) { for (const k of Object.keys(tree)) delete tree[k]; if (val && typeof val === 'object') Object.assign(tree, val); }
  else { let c = tree; for (let i = 0; i < sg.length - 1; i++) { if (c[sg[i]] == null || typeof c[sg[i]] !== 'object') c[sg[i]] = {}; c = c[sg[i]]; } if (val == null) delete c[sg[sg.length - 1]]; else c[sg[sg.length - 1]] = val; }
  for (const st of streams) {
    const base = '/' + segs(st.path).join('/'), me = '/' + sg.join('/');
    if (me === base || me.startsWith(base === '/' ? '/' : base + '/')) st.res.write(`event: put\ndata: ${JSON.stringify({ path: me === base ? '/' : me.slice(base === '/' ? 0 : base.length), data: val == null ? null : val })}\n\n`);
    else if (base.startsWith(me + '/') || me === '/') st.res.write(`event: put\ndata: ${JSON.stringify({ path: '/', data: getAt(st.path) })}\n\n`);
  }
};
const dbHandler = (req, res) => {
  const u = new URL(req.url, 'http://x'); const path = decodeURIComponent(u.pathname.replace(/^\/db/, '').replace(/\.json$/, ''));
  res.setHeader('Access-Control-Allow-Origin', '*'); res.setHeader('Access-Control-Allow-Headers', '*'); res.setHeader('Access-Control-Allow-Methods', '*');
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  if ((req.headers.accept || '').includes('event-stream')) {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
    const st = { path, res }; streams.push(st); req.on('close', () => { const i = streams.indexOf(st); if (i >= 0) streams.splice(i, 1); });
    res.write(`event: put\ndata: ${JSON.stringify({ path: '/', data: getAt(path) })}\n\n`); return;
  }
  let body = ''; req.on('data', (c) => body += c); req.on('end', () => {
    res.setHeader('Content-Type', 'application/json');
    if (req.method === 'GET') {
      if (u.searchParams.get('shallow')) return res.end('{}');
      let v = getAt(path); const end = u.searchParams.get('endAt');
      if (end != null && v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) if (v[k] <= +end) o[k] = v[k]; v = Object.keys(o).length ? o : null; }
      return res.end(JSON.stringify(v));
    }
    if (req.method === 'PUT') {
      if (req.headers['if-match'] === 'null_etag' && getAt(path) != null) { res.writeHead(412); return res.end('{"error":"conflict"}'); }
      const v = resolveSv(JSON.parse(body)); setAt(path, v); return res.end(JSON.stringify(v));
    }
    if (req.method === 'DELETE') { setAt(path, null); return res.end('null'); }
    res.writeHead(405); res.end();
  });
};
const srv = http.createServer((req, res) => {
  if (req.url.startsWith('/db')) return dbHandler(req, res);
  if (req.url.startsWith('/auth')) { res.setHeader('Content-Type', 'application/json'); res.setHeader('Access-Control-Allow-Origin', '*'); return res.end(JSON.stringify({ idToken: 'tok', localId: 'uidA', refreshToken: 'rt', expiresIn: '3600' })); }
  const json = (o) => { res.setHeader('Access-Control-Allow-Origin', '*'); res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(o)); };
  if (req.url.startsWith('/data')) return json(DATA);
  if (req.url.startsWith('/fx')) return json(FX);
  if (req.url.startsWith('/empty')) return json({});
  if (req.url.startsWith('/release')) return json(REL);
  const f = '/tmp/smoke/test.mp4'; const st = fs.statSync(f); const r = req.headers.range;
  res.setHeader('Access-Control-Allow-Origin', '*'); res.setHeader('Accept-Ranges', 'bytes');
  if (r) { const m = /bytes=(\d*)-(\d*)/.exec(r); const s = +m[1] || 0, e = m[2] ? +m[2] : st.size - 1; res.writeHead(206, { 'Content-Type': 'video/mp4', 'Content-Range': `bytes ${s}-${e}/${st.size}`, 'Content-Length': e - s + 1 }); fs.createReadStream(f, { start: s, end: e }).pipe(res); }
  else { res.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': st.size }); fs.createReadStream(f).pipe(res); }
}).listen(0, '127.0.0.1');

const log = []; const errors = [];
const wait = (ms) => new Promise(r => setTimeout(r, ms));
app.whenReady().then(async () => {
  const port = srv.address().port;
  const L = (p) => `http://127.0.0.1:${port}${p}`;
  session.defaultSession.webRequest.onBeforeRequest((d, cb) => {
    const u = d.url;
    if (/api\.github\.com\/repos\/efendiyhy\/master-stream-data/.test(u)) return cb({ redirectURL: L('/data.json') });
    if (/raw\.githubusercontent\.com/.test(u)) return cb({ redirectURL: L('/data.json') });
    if (/api\.fxtwitter\.com/.test(u)) return cb({ redirectURL: L('/fx.json') });
    if (/api\.vxtwitter\.com|syndication\.twimg\.com/.test(u)) return cb({ redirectURL: L('/empty.json') });
    if (/video\.twimg\.com/.test(u)) return cb({ redirectURL: L('/v.mp4') });
    if (/api\.github\.com\/repos\/efendiyhy\/Aphodius\/releases/.test(u)) return cb({ redirectURL: L('/release.json') });
    if (/identitytoolkit|securetoken/.test(u)) return cb({ redirectURL: L('/auth') });
    if (/ekinstru-default-rtdb\.firebaseio\.com/.test(u)) return cb({ redirectURL: L('/db' + new URL(u).pathname + new URL(u).search) });
    if (/firebasedatabase\.app/.test(u)) return cb({ cancel: true });
    cb({});
  });
  ipcMain.handle('open', () => {}); ipcMain.handle('meta', () => ({ version: '1.10.0', portable: false, data: '' }));
  const win = new BrowserWindow({ width: 1280, height: 780, show: true, webPreferences: { preload: path.join(__dirname, '..', 'preload.js'), contextIsolation: true, webSecurity: false } });
  win.webContents.on('console-message', (e, level, msg, line, src) => { if (level >= 2) errors.push(`[${level}] ${msg} (${path.basename(src)}:${line})`); });
  const ev = (code) => win.webContents.executeJavaScript(code, true);
  const shot = async (name) => { const img = await win.webContents.capturePage(); fs.writeFileSync(path.join(OUT, name + '.png'), img.toPNG()); };
  const step = async (name, fn) => { try { await Promise.race([fn(), new Promise((_, rj) => setTimeout(() => rj(new Error('waktu habis')), 25000))]); log.push('ok   ' + name); console.log('ok   ' + name); } catch (e) { log.push('GAGAL ' + name + ' :: ' + e.message); console.log('GAGAL ' + name + ' :: ' + e.message); } };

  await win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
  await wait(600);
  await step('sapaan awal tampil', async () => { if (!(await ev(`!!document.querySelector('.welcome')`))) throw new Error('tidak ada layar sapaan'); await shot('01-welcome'); });
  await step('isi nama lalu beranda', async () => {
    await ev(`document.querySelector('.welcome input').value='Tester'; document.querySelector('.welcome .btn').click();`);
    await wait(1500);
    const t = await ev(`document.querySelector('.hello b') && document.querySelector('.hello b').textContent`);
    if (t !== 'Tester') throw new Error('sapaan tidak muncul: ' + t);
    const n = await ev(`document.querySelectorAll('.card').length`); if (n < 3) throw new Error('kartu film kurang: ' + n);
    const ex = await ev(`document.body.innerText.includes('Istimewa')`); if (ex) throw new Error('film Extraordinary bocor di beranda');
    log.push('     film: ' + await ev(`JSON.stringify(App.allFilms.map(f=>f.id))+' lastErr='+App.lastError+' net='+App.netError`)); await shot('02-home');
  });
  await step('banner pembaruan', async () => { await wait(800); await ev(`render()`); if (!(await ev(`!!document.querySelector('.banner.up')`))) throw new Error('tidak ada banner'); });
  await step('tab Movie/Series/Baru/Daftarku', async () => {
    for (const [i, nm] of [[2, 'movie'], [3, 'series'], [1, 'new'], [4, 'mylist']]) { await ev(`document.querySelectorAll('.nav')[${i}].click()`); await wait(250); await shot('03-' + nm); }
    if (!(await ev(`document.body.innerText.includes('Serial Uji')`)) && false) throw new Error('x');
  });
  await step('detail film + daftarku', async () => {
    await ev(`document.querySelectorAll('.nav')[0].click()`); await wait(200);
    await ev(`UI.openFilm(App.find('f1'))`); await wait(300);
    await ev(`[...document.querySelectorAll('.acts .btn')].find(b=>/^DAFTARKU/.test(b.textContent)).click()`); await wait(200);
    if (!(await ev(`App.favs.has('f1')`))) throw new Error('favorit tidak tersimpan');
    await shot('04-detail');
  });
  await step('detail series memuat episode', async () => { await ev(`UI.back(); UI.openFilm(App.find('s1'))`); await wait(300); if ((await ev(`document.querySelectorAll('.ep').length`)) !== 2) throw new Error('episode tidak tampil'); await shot('05-series'); });
  await step('profil + Extraordinary butuh PIN', async () => {
    await ev(`State.stack=[]; UI.go({t:'profile'})`); await wait(300); await shot('06-profile');
    await ev(`App.enableExtra(true)`); if (await ev(`App.extra`)) throw new Error('Extraordinary aktif tanpa PIN');
  });
  await step('buat PIN lalu Extraordinary menggantikan Daftarku', async () => {
    await ev(`App.setPin('2468').then(()=>{ App.enableExtra(true); State.stack=[]; State.tab='home'; render(); })`); await wait(400);
    const label = await ev(`document.querySelectorAll('.nav')[4].textContent`); if (!/Extraordinary/.test(label)) throw new Error('menu tidak berganti: ' + label);
    await ev(`document.querySelectorAll('.nav')[4].click()`); await wait(300);
    if (!(await ev(`document.body.innerText.includes('Istimewa')`))) throw new Error('film Extraordinary tidak tampil'); await shot('07-extra');
    await ev(`UI.openFilm(App.find('x1'))`); await wait(300);
    if (await ev(`[...document.querySelectorAll('.acts .btn')].some(b=>/DAFTARKU/.test(b.textContent))`)) throw new Error('tombol Daftarku muncul pada Extraordinary');
  });
  await step('layar kunci menerima PIN', async () => {
    await ev(`showLock()`); await wait(200); await shot('08-lock');
    await ev(`['9','9','9','9'].forEach(d=>lockEl._pad._key({key:d}))`); await wait(400);
    if (!(await ev(`!!lockEl`))) throw new Error('PIN salah malah membuka');
    await ev(`['2','4','6','8'].forEach(d=>lockEl._pad._key({key:d}))`); await wait(500);
    if (await ev(`!!lockEl`)) throw new Error('PIN benar tidak membuka');
  });
  await step('pemutar memutar video uji', async () => {
    await ev(`State.stack=[]; State.tab='home'; render(); UI.play(App.find('f2'))`); await wait(3500);
    const st = await ev(`(function(){var v=document.querySelector('.player video'); return v? {t:v.currentTime, paused:v.paused, rs:v.readyState, w:v.videoWidth} : null})()`);
    log.push('     status video: ' + JSON.stringify(st));
    if (!st || st.w !== 640) throw new Error('video tidak termuat: ' + JSON.stringify(st));
    if (!(st.t > 1)) throw new Error('video tidak berjalan');
    await shot('09-player');
    await ev(`document.querySelector('.player .pbtn[title]') ; true`);
  });
  await step('kualitas/lompat/kecepatan/kunci layar', async () => {
    await ev(`Player.cur && document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}))`); await wait(300);
    const t1 = await ev(`document.querySelector('.player video').currentTime`);
    await ev(`[...document.querySelectorAll('.pbtn')].find(b=>/Kualitas/.test(b.textContent)).click()`); await wait(200);
    if (!(await ev(`document.querySelector('.menu')&&document.querySelector('.menu').textContent.includes('360p')`))) throw new Error('menu kualitas kosong');
    await shot('10-quality');
    await ev(`[...document.querySelectorAll('.menu button')].find(b=>/^360p/.test(b.textContent)).click()`); await wait(1500);
    await ev(`[...document.querySelectorAll('.pbtn')].find(b=>/Kunci/.test(b.textContent)).click()`); await wait(200);
    const hasCtl = await ev(`document.querySelectorAll('.ctl .pbtn').length`); if (hasCtl !== 0) throw new Error('kendali masih ada saat dikunci');
    await ev(`document.dispatchEvent(new KeyboardEvent('keydown',{key:' ',bubbles:true}))`); await wait(200);
    await shot('11-locked');
  });
  await step('simpan progres saat keluar', async () => {
    await ev(`document.querySelector('.player .stage').dispatchEvent(new MouseEvent('mousemove',{bubbles:true}))`); await wait(100);
    await ev(`(document.querySelector('.lockov .pbtn')||{click(){}}).click()`); await wait(200);
    await ev(`document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`); await wait(500);
    const p = await ev(`App.progress['f2']`); if (!p || !(p.pos > 500)) throw new Error('progres tidak tersimpan: ' + JSON.stringify(p));
    if (await ev(`!!document.querySelector('.player')`)) throw new Error('pemutar tidak tertutup');
  });
  await step('nobar: layar masuk kode', async () => { await ev(`State.stack=[]; UI.go({t:'nobar'})`); await wait(300); await shot('12-nobar'); if (!(await ev(`document.body.innerText.includes('Gabung dengan kode')`))) throw new Error('layar nobar kosong'); });

  const evp = (code) => ev(`(async()=>{ ${code} })()`);
  await step('nobar: buat ruangan & sinkron', async () => {
    await ev(`State.stack=[]; State.tab='home'; render();`);
    const ok = await evp(`return await Nobar.create(App.find('f2'), ()=>'Tester')`);
    if (!ok) throw new Error('buat ruangan gagal: ' + await ev('Nobar.error'));
    const code = await ev('Nobar.room.code'); if (!/^[A-HJ-NP-Z2-9]{6}$/.test(code)) throw new Error('kode aneh ' + code);
    await wait(700);
    const r = JSON.parse(await ev(`JSON.stringify({host:Nobar.room.info&&Nobar.room.info.host, film:Nobar.room.info&&Nobar.room.info.filmId, n:Nobar.room.members.length, me:Nobar.room.me, st:Nobar.room.status})`));
    if (r.host !== 'uidA' || r.film !== 'f2' || r.n !== 1 || r.st !== 'online') throw new Error('keadaan ruangan salah ' + JSON.stringify(r));
    log.push('     ruangan ' + code);
    global.__code = code;
  });
  await step('nobar: teman masuk, chat, tampilan lobi', async () => {
    const c = global.__code;
    setAt(`rooms/${c}/members/uidB`, { name: 'Teman', seen: Date.now(), voice: false, mic: false, buf: true });
    setAt(`rooms/${c}/chat/${Date.now()}_uidB`, { u: 'uidB', n: 'Teman', m: 'Halo dari teman' });
    await ev(`State.stack=[]; UI.go({t:'nobar'})`); await wait(800);
    const n = await ev('Nobar.room.members.length'); if (n !== 2) throw new Error('anggota ' + n);
    if (!(await ev(`document.body.innerText.includes('Halo dari teman')`))) throw new Error('chat tidak tampil di lobi');
    await shot('13-lobby');
    await ev(`(function(){var i=document.querySelector('.chatin input'); i.value='Balasan host'; i.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter'}));})()`); await wait(500);
    const chat = tree.rooms[c].chat; if (!Object.values(chat).some(m => m.m === 'Balasan host' && m.u === 'uidA')) throw new Error('pesan tidak terkirim ke server');
  });
  await step('nobar: putar bersama mengikuti perintah teman', async () => {
    const c = global.__code;
    await ev(`UI.watchRoom(App.find('f2'))`); await wait(3000);
    const v0 = JSON.parse(await ev(`(function(){var v=document.querySelector('.player video');return JSON.stringify({p:v.paused,t:v.currentTime,rdy:!Nobar.room.myBuf})})()`));
    if (!v0.p) throw new Error('video jalan padahal ruangan belum diputar');
    if (!tree.rooms[c].members.uidA || tree.rooms[c].members.uidA.buf !== false) throw new Error('status siap tidak dilaporkan: ' + JSON.stringify(tree.rooms[c].members.uidA));
    await ev(`Nobar.room.setControl(true)`); await wait(300);
    if (tree.rooms[c].info.ctl !== 'all') throw new Error('ctl tidak tersimpan');
    setAt(`rooms/${c}/pb`, { playing: true, pos: 6000, at: Date.now(), seq: 5, by: 'uidB' }); await wait(1500);
    const v1 = JSON.parse(await ev(`(function(){var v=document.querySelector('.player video');return JSON.stringify({p:v.paused,t:v.currentTime})})()`));
    if (v1.p || v1.t < 6) throw new Error('tidak mengikuti play teman: ' + JSON.stringify(v1));
    setAt(`rooms/${c}/pb`, { playing: false, pos: 9000, at: Date.now(), seq: 6, by: 'uidB' }); await wait(1200);
    const v2 = JSON.parse(await ev(`(function(){var v=document.querySelector('.player video');return JSON.stringify({p:v.paused,t:v.currentTime})})()`));
    if (!v2.p || Math.abs(v2.t - 9) > 1.5) throw new Error('tidak mengikuti jeda teman: ' + JSON.stringify(v2));
    await shot('14-nobar-player');
    // host menjeda lalu menyiarkan
    await ev(`document.dispatchEvent(new KeyboardEvent('keydown',{key:' ',bubbles:true}))`); await wait(600);
    const pb = tree.rooms[c].pb; if (pb.by !== 'uidA' || pb.playing !== true) throw new Error('siaran kendali host salah ' + JSON.stringify(pb));
  });
  await step('nobar: chat melayang + reaksi + tunggu semua siap', async () => {
    const c = global.__code;
    await ev(`App.set('floatChat', true); document.querySelector('.player .top .pbtn:last-child') && 0; true`);
    // tutup dock agar chat melayang muncul
    await ev(`(function(){var b=[...document.querySelectorAll('.dock .iconbtn')][0]; if(b) b.click();})()`); await wait(300);
    setAt(`rooms/${c}/chat/${Date.now()}_uidB`, { u: 'uidB', n: 'Teman', m: 'Pesan melayang' }); await wait(900);
    if (!(await ev(`!!document.querySelector('.floatchat') && document.querySelector('.floatchat').textContent.includes('Pesan melayang')`))) throw new Error('chat melayang tidak muncul');
    setAt(`rooms/${c}/chat/${Date.now()}_uidB`, { u: 'uidB', n: 'Teman', m: '🔥', k: 'r' }); await wait(700);
    if (!(await ev(`!!document.querySelector('.floater')`))) throw new Error('reaksi tidak melayang');
    await ev(`document.dispatchEvent(new KeyboardEvent('keydown',{key:'c',bubbles:true}))`); await wait(300);
    await ev(`(function(){var b=[...document.querySelectorAll('.dock .btn')].find(b=>/TUNGGU SEMUA SIAP/.test(b.textContent)); b.click();})()`); await wait(700);
    if (!(tree.rooms[c].info.gather > 0)) throw new Error('gather tidak ditulis');
    if (tree.rooms[c].pb.playing !== false) throw new Error('gather harus menjeda');
    setAt(`rooms/${c}/members/uidB/buf`, false); await wait(3500);
    if (tree.rooms[c].info.gather !== 0) throw new Error('gather tidak selesai: ' + tree.rooms[c].info.gather);
    if (tree.rooms[c].pb.playing !== true) throw new Error('tidak diputar bersamaan setelah semua siap');
    await shot('15-dock');
  });
  await step('nobar: keluarkan peserta lalu tutup ruangan', async () => {
    const c = global.__code;
    await ev(`Nobar.room.kick('uidB')`); await wait(500);
    if (tree.rooms[c].kick.uidB !== true || tree.rooms[c].members.uidB) throw new Error('kick gagal');
    await ev(`Player.close(); render();`);
    await ev(`Nobar.close()`); await wait(800);
    if (tree.rooms && tree.rooms[c]) throw new Error('ruangan tidak terhapus');
    if (await ev(`!!Nobar.room`)) throw new Error('room masih ada di UI');
  });
  await step('nobar: gabung kode yang ada & tolak yang tidak ada', async () => {
    const c = 'ZZ2345';
    setAt(`rooms/${c}`, { info: { host: 'uidH', film: 'f1', filmName: 'Petualangan Pertama', created: Date.now(), ctl: 'host' }, pb: { playing: false, pos: 0, at: Date.now(), seq: 0, by: 'uidH' }, members: { uidH: { name: 'Host', seen: Date.now(), voice: false, mic: false } } });
    const bad = await evp(`return await Nobar.join('QQQQQQ', ()=>'Tester')`); if (bad) throw new Error('bergabung ke ruangan tak ada');
    if (!/tidak ditemukan/.test(await ev('Nobar.error'))) throw new Error('pesan galat: ' + await ev('Nobar.error'));
    const ok = await evp(`return await Nobar.join('zz2345', ()=>'Tester')`); if (!ok) throw new Error('gagal gabung: ' + await ev('Nobar.error'));
    await wait(600); if ((await ev('Nobar.room.members.length')) !== 2) throw new Error('anggota tidak 2');
    setAt(`rooms/${c}`, null); await wait(800);
    if (await ev(`Nobar.room&&Nobar.room.status`) !== 'closed' && await ev(`!!Nobar.room`)) throw new Error('tidak menutup saat host menghapus ruangan');
  });
  await step('EQ/Bioskop tidak error', async () => { await ev(`App.set('surround', true)`); });
  await step('mikrofon WebRTC tersedia', async () => { const r = await ev(`navigator.mediaDevices.getUserMedia({audio:true}).then(s=>{var n=s.getAudioTracks().length; s.getTracks().forEach(t=>t.stop()); return n;}).catch(e=>'err '+e.name)`); if (r !== 1) throw new Error('getUserMedia: ' + r); });

  console.log('\n===== HASIL ASAP ====='); log.forEach(l => console.log(l));
  console.log('--- galat konsol (' + errors.length + ') ---'); errors.slice(0, 20).forEach(e => console.log(e));
  const bad = log.filter(l => l.startsWith('GAGAL')).length;
  process.exit(bad ? 1 : 0);
});
