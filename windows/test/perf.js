'use strict';
const path = require('path'), fs = require('fs');
/* Ukur memori & kelancaran scroll dengan data asli (jaringan nyata). */
const { app, BrowserWindow, session } = require('electron');
const http = require('http');
const APP = process.env.APP_DIR || path.join(__dirname, '..');

app.setPath('userData', fs.mkdtempSync('/tmp/perf-ud-'));
app.commandLine.appendSwitch('no-sandbox'); app.commandLine.appendSwitch('disable-gpu');
const wait = (ms) => new Promise(r => setTimeout(r, ms));
const mem = () => { const m = app.getAppMetrics(); const mb = (k) => Math.round(m.filter(x => x.type === k).reduce((a, x) => a + x.memory.workingSetSize, 0) / 1024); return { renderer: mb('Tab'), total: Math.round(m.reduce((a, x) => a + x.memory.workingSetSize, 0) / 1024) }; };
const REAL = fs.readFileSync('/tmp/real.json');
const srv = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (req.url.startsWith('/data')) { res.setHeader('Content-Type', 'application/json'); return res.end(REAL); }
  const small = req.url.includes('_UX'); res.setHeader('Content-Type', 'image/jpeg'); res.setHeader('Cache-Control', 'max-age=3600');
  fs.createReadStream(small ? '/tmp/smoke/small.jpg' : '/tmp/smoke/big.jpg').pipe(res);
}).listen(0, '127.0.0.1');
app.whenReady().then(async () => {
  const port = srv.address().port;
  session.defaultSession.webRequest.onBeforeRequest((d, cb) => {
    if (/api\.github\.com\/repos\/efendiyhy\/master-stream-data|raw\.githubusercontent/.test(d.url)) return cb({ redirectURL: `http://127.0.0.1:${port}/data.json` });
    if (/m\.media-amazon\.com|gstatic\.com/.test(d.url)) return cb({ redirectURL: `http://127.0.0.1:${port}/img` + (d.url.includes('_UX') ? '_UX' : '') + '.jpg' });
    if (/api\.fxtwitter|vxtwitter|syndication|identitytoolkit|releases\/latest/.test(d.url)) return cb({ cancel: true });
    cb({});
  });
  const { ipcMain } = require('electron');
  ipcMain.handle('open', () => {}); ipcMain.handle('meta', () => ({ version: '1.10.0' }));
  const win = new BrowserWindow({ width: 1280, height: 780, webPreferences: { preload: path.join(APP, 'preload.js'), contextIsolation: true, webSecurity: false } });
  const ev = (c) => win.webContents.executeJavaScript(c, true);
  await win.loadFile(path.join(APP, 'renderer', 'index.html'));
  await wait(500);
  await ev(`Store.set('nickname','Perf'); location.reload()`); await wait(8000);
  const out = { films: await ev('App.films.length'), cards: await ev(`document.querySelectorAll('.card').length`), imgs: await ev(`document.querySelectorAll('.poster img').length`), after_load: mem() };
  // scroll beranda ke bawah pelan-pelan, hitung frame time
  const r = await ev(`new Promise(res=>{var m=document.querySelector('.main'); var t0=performance.now(), last=t0, worst=0, n=0; function f(t){ worst=Math.max(worst,t-last); last=t; n++; m.scrollTop+=40; if(m.scrollTop+m.clientHeight<m.scrollHeight-5 && t-t0<8000) requestAnimationFrame(f); else res({frames:n, worstMs:Math.round(worst), sec:Math.round((t-t0)/100)/10, height:m.scrollHeight}); } requestAnimationFrame(f); })`);
  out.scroll_home = r; await wait(1500); out.after_scroll = mem();
  // buka Movie & Baru (grid besar)
  await ev(`document.querySelectorAll('.nav')[1].click()`); await wait(300);
  out.scroll_new = await ev(`new Promise(res=>{var m=document.querySelector('.main'); var t0=performance.now(), last=t0, worst=0, n=0; function f(t){ worst=Math.max(worst,t-last); last=t; n++; m.scrollTop+=60; if(m.scrollTop+m.clientHeight<m.scrollHeight-5 && t-t0<8000) requestAnimationFrame(f); else res({frames:n, worstMs:Math.round(worst), height:m.scrollHeight}); } requestAnimationFrame(f); })`);
  await wait(6000); out.after_new = mem(); out.imgsNow = await ev(`document.querySelectorAll('.poster img').length`);
  console.log('PERF ' + JSON.stringify(out, null, 1)); process.exit(0);
});
