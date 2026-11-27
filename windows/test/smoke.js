'use strict';
/* Uji asap: jalankan renderer di Electron dengan jaringan palsu, telusuri layar, putar video uji, ambil tangkapan layar. */
const { app, BrowserWindow, session, ipcMain } = require('electron');
const path = require('path'), fs = require('fs'), http = require('http');
const OUT = path.resolve(process.env.SMOKE_OUT || '/tmp/smoke');
app.setPath('userData', fs.mkdtempSync(path.resolve('/tmp/smoke-ud-')));
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
  ipcMain.handle('open', () => {}); ipcMain.handle('zoom', () => {}); ipcMain.handle('fullscreen', () => {}); ipcMain.handle('proto', () => true); ipcMain.handle('log:read', () => ''); ipcMain.handle('log:show', () => {}); ipcMain.handle('cache:clear', () => true); ipcMain.handle('dl:exists', () => null); ipcMain.handle('dl:folder', () => {}); ipcMain.handle('dl:cancel', () => {}); ipcMain.handle('dl:remove', () => true); ipcMain.handle('upd:start', () => ({ ok: false, error: 'uji' })); ipcMain.on('log', (e, l) => console.log('     [log] ' + l)); ipcMain.on('state', (e, st) => { global.__state = st; }); ipcMain.on('cfg', () => {}); ipcMain.on('invite-ready', () => {}); ipcMain.handle('meta', () => ({ version: '1.10.0', portable: false, data: '' }));
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
    const t = await ev(`document.querySelector('.topbar2 .mebtn span:last-child') && document.querySelector('.topbar2 .mebtn span:last-child').textContent`);
    if (t !== 'Tester') throw new Error('sapaan tidak muncul: ' + t);
    const n = await ev(`document.querySelectorAll('.card').length`); if (n < 3) throw new Error('kartu film kurang: ' + n);
    const ex = await ev(`document.body.innerText.includes('Istimewa')`); if (ex) throw new Error('film Extraordinary bocor di beranda');
    log.push('     film: ' + await ev(`JSON.stringify(App.allFilms.map(f=>f.id))+' lastErr='+App.lastError+' net='+App.netError`)); await shot('02-home');
  });
  await step('banner pembaruan', async () => { await wait(800); await ev(`render()`); if (!(await ev(`!!document.querySelector('.banner.up')`))) throw new Error('tidak ada banner'); });
  await step('tab Movie/Series/Baru/Daftarku', async () => {
    for (const [i, nm] of [[2, 'movie'], [3, 'series'], [1, 'new'], [4, 'mylist']]) { await ev(`document.querySelectorAll('.rnav')[${i}].click()`); await wait(250); await shot('03-' + nm); }
    if (!(await ev(`document.body.innerText.includes('Serial Uji')`)) && false) throw new Error('x');
  });
  await step('detail film + daftarku', async () => {
    await ev(`document.querySelectorAll('.rnav')[0].click()`); await wait(200);
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
    const label = await ev(`document.querySelectorAll('.rnav')[4].textContent`); if (!/Extraordinary/.test(label)) throw new Error('menu tidak berganti: ' + label);
    await ev(`document.querySelectorAll('.rnav')[4].click()`); await wait(300);
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
  await step('video call: kamera sendiri & video teman tampil stabil di panel chat', async () => {
    const c = global.__code;
    await evp(`await Nobar.room.toggleCam(); return true`); await wait(900);
    if (!(await ev(`Nobar.room.camOn && !!document.querySelector('.dock .vtile[data-uid="uidA"] video')`))) throw new Error('pratinjau kamera tidak tampil: ' + await ev('Nobar.room.voiceError'));
    // "video teman": aliran kamera palsu kedua dipasang sebagai video uidB
    // "video teman": kanvas beranimasi (bukan kamera kedua, agar tidak berebut perangkat kamera palsu)
    await evp(`var c=document.createElement('canvas'); c.width=320; c.height=240; var x=c.getContext('2d'), k=0; window.__cv=setInterval(function(){ k=(k+7)%255; x.fillStyle='rgb('+k+',120,200)'; x.fillRect(0,0,320,240); }, 40); Nobar.room.videos.uidB = c.captureStream(25); return true`);
    setAt(`rooms/${c}/members/uidB/cam`, true); await wait(800);
    if (!(await ev(`(function(){ window.__tile = document.querySelector('.dock .vtile[data-uid="uidB"]'); return !!window.__tile })()`))) throw new Error('video teman tidak tampil di panel chat');
    // pembaruan ruangan beruntun (buffering bolak-balik, chat) tidak boleh membuat video teman berkedip / berhenti
    for (let i = 0; i < 8; i++) { setAt(`rooms/${c}/members/uidB/buf`, i % 2 === 0); if (i === 3) setAt(`rooms/${c}/chat/${Date.now()}_uidB`, { u: 'uidB', n: 'Teman', m: 'cek video' }); await wait(200); }
    await wait(400);
    const st = JSON.parse(await ev(`JSON.stringify({ same: document.querySelector('.dock .vtile[data-uid="uidB"]') === window.__tile, conn: window.__tile.isConnected, paused: window.__tile.querySelector('video').paused, w: window.__tile.querySelector('video').videoWidth })`));
    if (!st.same || !st.conn || st.paused || !st.w) throw new Error('video teman tidak stabil: ' + JSON.stringify(st));
    // pastikan gambar benar-benar mengalir (bukan kotak hitam): rata-rata kecerahan piksel kotak video
    const lum = await ev(`(function(){ var v=window.__tile.querySelector('video'), c=document.createElement('canvas'); c.width=64; c.height=48; var x=c.getContext('2d'); x.drawImage(v,0,0,64,48); var d=x.getImageData(0,0,64,48).data, s=0; for (var i=0;i<d.length;i+=4) s+=d[i]+d[i+1]+d[i+2]; return Math.round(s/(d.length/4)/3); })()`);
    const cam = await evp(`for (var i=0;i<20;i++){ var t=Nobar.room.voice.stream.getVideoTracks()[0], p=document.querySelector('.dock .vtile[data-uid="uidA"] video'); if (t.readyState==='live' && p && p.videoWidth) return t.readyState+' '+p.videoWidth+' ('+(i*200)+'ms)'; await new Promise(r=>setTimeout(r,200)); } return t.readyState+' '+(p&&p.videoWidth);`);
    log.push('     kecerahan video teman: ' + lum + ' · kamera sendiri: ' + cam);
    if (!(lum > 5)) throw new Error('video teman hitam');
    if (!/^live [1-9]/.test(cam)) throw new Error('kamera sendiri mati: ' + cam);
    await shot('16-videocall-dock');
  });
  await step('video call: video melayang saat chat ditutup, kembali ke panel saat dibuka', async () => {
    await ev(`document.dispatchEvent(new KeyboardEvent('keydown',{key:'c',bubbles:true}))`); await wait(500);
    const f = JSON.parse(await ev(`(function(){ var t = document.querySelector('.vfloat .vtile[data-uid="uidB"]'); return JSON.stringify({ ok: !!t, same: t === window.__tile, paused: t && t.querySelector('video').paused }) })()`));
    if (!f.ok || !f.same || f.paused) throw new Error('video melayang salah: ' + JSON.stringify(f));
    await shot('17-videocall-float');
    await ev(`document.dispatchEvent(new KeyboardEvent('keydown',{key:'c',bubbles:true}))`); await wait(500);
    if (!(await ev(`!!document.querySelector('.dock .vtile[data-uid="uidB"]') && !document.querySelector('.dock .vtile[data-uid="uidB"] video').paused`))) throw new Error('video tidak kembali ke panel');
  });
  await step('redam suara film saat mik hidup', async () => {
    await wait(1500);
    const r1 = JSON.parse(await ev(`(function(){ var v=document.querySelector('.player video'), u=document.querySelector('.player .vol').value/100; return JSON.stringify({v:v.volume,u:u,mic:Nobar.room.micOn}) })()`));
    if (!r1.mic || Math.abs(r1.v - r1.u * 0.3) > 0.05) throw new Error('tidak diredam: ' + JSON.stringify(r1));
    await ev(`Nobar.room.toggleMic()`); await wait(1800);
    const r2 = JSON.parse(await ev(`(function(){ var v=document.querySelector('.player video'), u=document.querySelector('.player .vol').value/100; return JSON.stringify({v:v.volume,u:u}) })()`));
    if (Math.abs(r2.v - r2.u) > 0.05) throw new Error('tidak kembali normal: ' + JSON.stringify(r2));
    if (await ev(`Store.get('vol', 1)`) < 0.5) throw new Error('volume teredam ikut tersimpan');
    await ev(`Nobar.room.toggleMic()`);
  });
  await step('video call: mode meeting di ruang tunggu', async () => {
    await ev(`Player.close(); State.stack=[]; State.tab='nobar'; render();`); await wait(600);
    if (!(await ev(`!!document.querySelector('.lobby .vtile[data-uid="uidB"]')`))) throw new Error('video tidak tampil di lobi');
    await ev(`[...document.querySelectorAll('.lobby .chip')].find(b=>/Perbesar/.test(b.textContent)).click()`); await wait(600);
    const n = await ev(`document.querySelectorAll('.meet .vtile').length`); if (n < 2) throw new Error('kotak meeting ' + n);
    await shot('18-meeting');
    await ev(`document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`); await wait(400);
    if (await ev(`!!document.querySelector('.meet')`)) throw new Error('meeting tidak tertutup');
    if (!(await ev(`!!document.querySelector('.lobby .vtile[data-uid="uidB"]') && !document.querySelector('.lobby .vtile[data-uid="uidB"] video').paused`))) throw new Error('video tidak kembali ke lobi');
    await shot('19-lobby-video');
    await ev(`UI.watchRoom(App.find('f2'))`); await wait(1500);
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

  await step('pemutar: slider volume & buka bisu saat volume 0', async () => {
    await ev(`Nobar.room && Nobar.leave(); State.stack=[]; State.tab='home'; render(); Store.set('vol', 0.05); UI.play(App.find('f2'),{fromStart:true})`); await wait(3000);
    const r = await evp(`var v=document.querySelector('.player video'); var sl=document.querySelector('.player input.vol'); if(!sl) return 'tak ada slider'; v.muted=true; v.volume=0; v.dispatchEvent(new Event('volumechange')); await new Promise(r=>setTimeout(r,200)); var mb=document.querySelector('.player .pbtn'); var all=[...document.querySelectorAll('.player .pbtn')]; return JSON.stringify({vol:v.volume, muted:v.muted, saved:Store.get('vol',1)})`);
    if (/tak ada/.test(r)) throw new Error(r);
    const j = JSON.parse(r); if (j.saved < 0.2 && j.saved !== 0.05) throw new Error('vol tersimpan ' + r);
    const v2 = await evp(`var v=document.querySelector('.player video'); v.muted=true; v.volume=0; var btns=[...document.querySelectorAll('.player .pbtn')]; var m=btns.find(b=>b.nextElementSibling&&b.nextElementSibling.classList.contains('vol')); m.click(); await new Promise(r=>setTimeout(r,150)); return {m:v.muted, v:v.volume}`);
    if (v2.m || v2.v < 0.05) throw new Error('bisu tidak terbuka: ' + JSON.stringify(v2));
  });
  await step('pemutar: volume awal minimal 20%', async () => {
    const v = await ev(`Math.round(document.querySelector('.player video').volume*100)`); if (v < 20) throw new Error('volume awal ' + v);
  });
  await step('pemutar: subtitle .srt tampil sesuai waktu', async () => {
    const r = await evp(`var v=document.querySelector('.player video'); var st=document.querySelector('.player .stage'); var dt=new DataTransfer(); dt.items.add(new File(['1\\n00:00:00,000 --> 00:00:30,000\\nHalo dunia <i>uji</i>\\n\\n2\\n00:00:40,000 --> 00:00:50,000\\nTidak tampil\\n'],'a.srt')); st.dispatchEvent(new DragEvent('drop',{dataTransfer:dt,bubbles:true,cancelable:true})); await new Promise(r=>setTimeout(r,400)); v.currentTime=1; await new Promise(r=>setTimeout(r,500)); return document.querySelector('.player .subs').textContent`);
    if (r !== 'Halo dunia uji') throw new Error('subtitle: ' + JSON.stringify(r));
  });
  await step('pemutar: tombol media melapor ke proses utama', async () => {
    await ev(`document.querySelector('.player video').play()`); await wait(500);
    if (!global.__state || global.__state.title !== 'Drama Malam') throw new Error('state: ' + JSON.stringify(global.__state));
  });
  await step('pemutar: lewati intro hanya untuk series (tanda per series)', async () => {
    await ev(`App.setIntro('s1',[0,20]); UI.back&&0; Player.cur.destroy(); State.stack=[]; render(); UI.play(App.find('s1'),{ep:0,fromStart:true})`); await wait(3500);
    const shown = await ev(`getComputedStyle(document.querySelector('.player .skipintro')).display`);
    if (shown !== 'block') throw new Error('tombol intro ' + shown);
    await ev(`document.querySelector('.player .skipintro').click()`); await wait(400);
    if (!(await ev(`document.querySelector('.player video').currentTime`) >= 19)) throw new Error('tidak melompat');
  });
  await step('pemutar: berikutnya otomatis (hitung mundur) pada episode', async () => {
    await ev(`var v=document.querySelector('.player video'); v.currentTime=Math.max(0,v.duration-0.3)`); await wait(2500);
    const box = await ev(`!!document.querySelector('.player .nextbox')`); if (!box) throw new Error('kotak berikutnya tidak tampil');
    await ev(`[...document.querySelectorAll('.nextbox button')][0].click()`); await wait(3500);
    if (!/Ep 2/.test(await ev(`document.title`))) throw new Error('belum pindah ke ep 2: ' + await ev('document.title'));
    await ev(`Player.cur.destroy(); State.stack=[]; render()`);
  });
  await step('antrean: tambah, urut, putar dari antrean', async () => {
    await ev(`App.enqueue('f3'); App.enqueue('f1'); App.moveQueue(1,-1)`);
    if ((await ev(`JSON.stringify(App.queue)`)) !== '["f1","f3"]') throw new Error('urutan ' + await ev('JSON.stringify(App.queue)'));
    await ev(`UI.go({t:'queue'})`); await wait(300);
    if (!/Antrean tonton/.test(await ev(`document.body.innerText`))) throw new Error('layar antrean tidak tampil');
    await ev(`UI.back(); UI.play(App.find('f2'),{fromStart:true})`); await wait(2500);
    if ((await ev(`!!Player.cur`)) !== true) throw new Error('tidak memutar');
    if ((await ev(`(function(){ var n=queueNext(null); return n&&n.id })()`)) !== 'f1') throw new Error('queueNext');
    await ev(`Player.cur.destroy(); State.stack=[]; render(); App.dequeue('f1'); App.dequeue('f3')`);
  });
  await step('undangan: link aphodius://join masuk otomatis', async () => {
    setAt('rooms/INV234', { info: { host: 'uidH', film: 'f1', filmName: 'Petualangan Pertama', created: Date.now(), ctl: 'host' }, pb: { playing: false, pos: 0, at: Date.now(), seq: 0, by: 'uidH' }, members: { uidH: { name: 'Host', seen: Date.now(), voice: false, mic: false } } });
    await ev(`joinInvite('inv234')`); await wait(1500);
    if ((await ev(`Nobar.room && Nobar.room.code`)) !== 'INV234') throw new Error('tidak bergabung: ' + await ev('Nobar.error'));
    await ev(`Nobar.leave(); State.stack=[]; render()`);
  });
  await step('mode TV & hemat memori menyalakan kelas', async () => {
    await ev(`App.set('tvMode', true); applyMode()`); if (!(await ev(`document.body.classList.contains('tv')`))) throw new Error('tv');
    await ev(`App.set('tvMode', false); App.set('saver', true); applyMode()`); if (!(await ev(`document.body.classList.contains('saver')`))) throw new Error('saver');
    await ev(`App.set('saver', false); applyMode()`);
  });
  await step('profil: bagian Windows, antrean & unduhan, pembaruan', async () => {
    await ev(`State.stack=[]; State.tab='profile'; render()`); await wait(400);
    const t = await ev(`document.body.innerText`);
    for (const w of ['Tetap di baki', 'Mode TV', 'Hemat memori', 'Bersihkan cache', 'Antrean & Unduhan']) if (t.indexOf(w) < 0) throw new Error('hilang: ' + w);
  });
  await step('beranda baru: hero, rail, rating, lihat semua (cek akhir)', async () => {
    await ev(`State.stack=[]; State.tab='home'; render()`); await wait(500);
    const r = JSON.parse(await ev(`JSON.stringify({ hero: !!document.querySelector('.hero2 h2'), rail: document.querySelectorAll('.rail .rnav').length, dots: document.querySelectorAll('.hdots button').length, rows: document.querySelectorAll('.rowh').length })`));
    if (!r.hero || r.rail < 5 || r.rows < 2) throw new Error('beranda: ' + JSON.stringify(r));
    await shot('20-home-new');
    await ev(`UI.go({t:'all', title:'Uji', ids:['f1','f2','f3']})`); await wait(300);
    if ((await ev(`document.querySelectorAll('.grid .card').length`)) !== 3) throw new Error('lihat semua');
    await ev(`State.stack=[]; render()`);
  });
  await step('EQ/Bioskop tidak error', async () => { await ev(`App.set('surround', true)`); });
  await step('mikrofon WebRTC tersedia', async () => { const r = await ev(`navigator.mediaDevices.getUserMedia({audio:true}).then(s=>{var n=s.getAudioTracks().length; s.getTracks().forEach(t=>t.stop()); return n;}).catch(e=>'err '+e.name)`); if (r !== 1) throw new Error('getUserMedia: ' + r); });

  console.log('\n===== HASIL ASAP ====='); log.forEach(l => console.log(l));
  console.log('--- galat konsol (' + errors.length + ') ---'); errors.slice(0, 20).forEach(e => console.log(e));
  const bad = log.filter(l => l.startsWith('GAGAL')).length;
  process.exit(bad ? 1 : 0);
});
