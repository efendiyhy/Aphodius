'use strict';
const { app, BrowserWindow, session, shell, Menu, ipcMain, Tray, nativeImage, screen, dialog } = require('electron');
const path = require('path'), fs = require('fs'), os = require('os'), https = require('https'), http = require('http');
const { spawn } = require('child_process');

app.setAppUserModelId('id.sengkuni.aphodius');
let portable = false;
if (app.isPackaged) {
  try {
    const dir = path.join(path.dirname(process.execPath), 'AphodiusData');
    fs.mkdirSync(dir, { recursive: true }); fs.accessSync(dir, fs.constants.W_OK);
    app.setPath('userData', dir); portable = true;
  } catch (e) { /* lokasi tidak bisa ditulis: pakai bawaan */ }
}
const DATA = app.getPath('userData');

/* ---- log galat ---- */
const LOG = path.join(DATA, 'aphodius.log');
function log(line) {
  try {
    try { if (fs.statSync(LOG).size > 400 * 1024) fs.writeFileSync(LOG, fs.readFileSync(LOG).slice(-200 * 1024)); } catch (e) {}
    fs.appendFileSync(LOG, new Date().toISOString() + ' ' + line + '\n');
  } catch (e) { /* abaikan */ }
}
process.on('uncaughtException', (e) => log('UNCAUGHT ' + (e && e.stack || e)));
process.on('unhandledRejection', (e) => log('REJECTION ' + (e && e.stack || e)));
log('--- mulai v' + app.getVersion() + ' electron ' + process.versions.electron + ' ' + os.release());

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
const PROTO = 'aphodius';
const lock = app.requestSingleInstanceLock();
if (!lock) app.quit();

let win, tray, quitting = false, rendererReady = false, pendingInvite = null;
let cfg = { trayClose: false };
let playState = { playing: false, title: '', hasNext: false };

/* ---- undangan nobar: aphodius://join/KODE ---- */
function inviteFrom(argv) {
  for (const a of argv || []) {
    const m = /^aphodius:\/\/join\/([A-Za-z0-9]{6})\/?/i.exec(String(a));
    if (m) return m[1].toUpperCase();
  }
  return null;
}
function deliverInvite(code) {
  if (!code) return;
  if (rendererReady && win && !win.isDestroyed()) { win.webContents.send('invite', code); showWin(); } else pendingInvite = code;
}
function showWin() { if (!win) return; if (win.isMinimized()) win.restore(); win.show(); win.focus(); }

/* ---- jendela: ingat ukuran & posisi ---- */
const WS = path.join(DATA, 'window.json');
function loadWS() { try { return JSON.parse(fs.readFileSync(WS, 'utf8')); } catch (e) { return null; } }
function saveWS() {
  if (!win || win.isDestroyed()) return;
  try {
    const b = win.isMaximized() || win.isFullScreen() ? (win._normal || win.getNormalBounds()) : win.getBounds();
    fs.writeFileSync(WS, JSON.stringify({ bounds: b, max: win.isMaximized(), full: win.isFullScreen() }));
  } catch (e) {}
}

function create() {
  Menu.setApplicationMenu(null);
  const ws = loadWS();
  const opts = { width: 1280, height: 780, minWidth: 900, minHeight: 600, backgroundColor: '#050D16', title: 'Aphodius', autoHideMenuBar: true,
    icon: path.join(__dirname, 'icon.ico'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, webSecurity: false, spellcheck: false } };
  if (ws && ws.bounds) {
    const d = screen.getDisplayMatching(ws.bounds).workArea, b = ws.bounds;
    if (b.x + 100 < d.x + d.width && b.y + 50 < d.y + d.height && b.x + b.width > d.x + 100 && b.y >= d.y - 20) Object.assign(opts, { x: b.x, y: b.y, width: Math.max(900, b.width), height: Math.max(600, b.height) });
  }
  win = new BrowserWindow(opts);
  if (ws && ws.max) win.maximize();
  if (ws && ws.full) win.setFullScreen(true);
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/i.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); } });
  win.webContents.on('render-process-gone', (e, d) => log('RENDER-GONE ' + JSON.stringify(d)));
  win.webContents.on('did-fail-load', (e, c, d) => log('LOAD-FAIL ' + c + ' ' + d));
  win.on('resize', () => { if (!win.isMaximized() && !win.isFullScreen()) win._normal = win.getBounds(); });
  win.on('move', () => { if (!win.isMaximized() && !win.isFullScreen()) win._normal = win.getBounds(); });
  win.on('close', (e) => {
    saveWS();
    if (cfg.trayClose && !quitting) { e.preventDefault(); win.hide(); }
  });
  win.on('closed', () => { win = null; });
  setupThumbar();
}

/* ---- baki sistem + tombol taskbar + tombol media ---- */
function send(cmd) { if (win && !win.isDestroyed()) win.webContents.send('cmd', cmd); }
function buildTray() {
  if (tray) return;
  tray = new Tray(nativeImage.createFromPath(path.join(__dirname, 'assets', 'tray.png')));
  tray.setToolTip('Aphodius');
  tray.on('click', showWin);
  refreshTray();
}
function refreshTray() {
  if (!tray) return;
  tray.setToolTip(playState.title ? 'Aphodius — ' + playState.title : 'Aphodius');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Buka Aphodius', click: showWin },
    { label: playState.playing ? 'Jeda' : 'Putar', enabled: !!playState.title, click: () => send('toggle') },
    { label: 'Berikutnya', enabled: playState.hasNext, click: () => send('next') },
    { type: 'separator' },
    { label: 'Keluar', click: () => { quitting = true; app.quit(); } },
  ]));
}
function icon(n) { return nativeImage.createFromPath(path.join(__dirname, 'assets', n + '.png')); }
function setupThumbar() {
  if (!win || process.platform !== 'win32') return;
  try {
    win.setThumbarButtons([
      { tooltip: 'Mundur', icon: icon('prev'), flags: playState.title ? [] : ['disabled'], click: () => send('back') },
      { tooltip: playState.playing ? 'Jeda' : 'Putar', icon: icon(playState.playing ? 'pause' : 'play'), flags: playState.title ? [] : ['disabled'], click: () => send('toggle') },
      { tooltip: 'Berikutnya', icon: icon('next'), flags: playState.hasNext ? [] : ['disabled'], click: () => send('next') },
    ]);
  } catch (e) { log('thumbar ' + e.message); }
}

/* ---- unduhan (mp4) & pembaruan: unduh dengan ikuti redirect ---- */
function download(url, dest, onProgress, holder) {
  return new Promise((resolve, reject) => {
    let redirects = 0;
    const go = (u) => {
      const mod = u.startsWith('https') ? https : http;
      const req = mod.get(u, { headers: { 'User-Agent': 'Aphodius' } }, (res) => {
        if ([301, 302, 303, 307, 308].includes(res.statusCode) && res.headers.location && redirects++ < 6) { res.resume(); return go(new URL(res.headers.location, u).toString()); }
        if (res.statusCode !== 200) { res.resume(); return reject(new Error('HTTP ' + res.statusCode)); }
        const total = Number(res.headers['content-length']) || 0; let got = 0, last = 0;
        const out = fs.createWriteStream(dest);
        res.on('data', (c) => { got += c.length; const n = Date.now(); if (n - last > 300) { last = n; onProgress(got, total); } });
        res.pipe(out);
        out.on('finish', () => { onProgress(got, total || got); resolve(got); });
        out.on('error', reject); res.on('error', reject);
        if (holder) holder.abort = () => { req.destroy(); out.destroy(); reject(new Error('dibatalkan')); };
      });
      req.on('error', reject); req.setTimeout(30000, () => req.destroy(new Error('waktu habis')));
    };
    go(url);
  });
}
const DL = path.join(DATA, 'Unduhan');
const active = {};
const safe = (id) => String(id).replace(/[^A-Za-z0-9_.-]/g, '_');

/* ---- IPC ---- */
app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(['media', 'fullscreen', 'clipboard-sanitized-write', 'audioCapture', 'notifications', 'pictureInPicture'].includes(perm)));
  session.defaultSession.setPermissionCheckHandler(() => true);
  ipcMain.handle('open', (e, url) => { if (/^https?:\/\//i.test(String(url))) shell.openExternal(String(url)); });
  ipcMain.handle('meta', () => ({ version: app.getVersion(), portable, installed: app.isPackaged && !portable, data: DATA, packaged: app.isPackaged }));
  ipcMain.on('log', (e, line) => log('R ' + String(line).slice(0, 600)));
  ipcMain.handle('log:read', () => { try { return fs.readFileSync(LOG, 'utf8').slice(-20000); } catch (e) { return ''; } });
  ipcMain.handle('log:show', () => { try { shell.showItemInFolder(LOG); } catch (e) {} });
  ipcMain.handle('cache:clear', async () => { await session.defaultSession.clearCache(); await session.defaultSession.clearStorageData({ storages: ['serviceworkers', 'cachestorage'] }); return true; });
  ipcMain.on('state', (e, s) => {
    playState = Object.assign({ playing: false, title: '', hasNext: false }, s);
    refreshTray(); setupThumbar();
  });
  ipcMain.on('cfg', (e, c) => {
    cfg = Object.assign(cfg, c);
    if (cfg.trayClose) buildTray(); else if (tray) { tray.destroy(); tray = null; }
  });
  ipcMain.handle('proto', (e, on) => {
    try {
      if (!app.isPackaged) return false;
      if (on) return app.setAsDefaultProtocolClient(PROTO, process.execPath, []);
      return app.removeAsDefaultProtocolClient(PROTO, process.execPath, []);
    } catch (err) { log('proto ' + err.message); return false; }
  });
  ipcMain.on('invite-ready', () => { rendererReady = true; if (pendingInvite) { const c = pendingInvite; pendingInvite = null; deliverInvite(c); } });
  ipcMain.handle('zoom', (e, f) => { if (win) win.webContents.setZoomFactor(Math.min(2, Math.max(0.6, +f || 1))); });
  ipcMain.handle('fullscreen', (e, on) => { if (win) win.setFullScreen(!!on); });

  ipcMain.handle('dl:start', async (e, { id, url }) => {
    fs.mkdirSync(DL, { recursive: true });
    const dest = path.join(DL, safe(id) + '.mp4'), part = dest + '.part';
    const holder = {}; active[id] = holder;
    try {
      const size = await download(url, part, (got, total) => { if (win && !win.isDestroyed()) win.webContents.send('dl:progress', { id, got, total }); }, holder);
      fs.renameSync(part, dest); return { ok: true, path: dest, size };
    } catch (err) { try { fs.unlinkSync(part); } catch (x) {} return { ok: false, error: err.message }; }
    finally { delete active[id]; }
  });
  ipcMain.handle('dl:cancel', (e, id) => { if (active[id] && active[id].abort) active[id].abort(); });
  ipcMain.handle('dl:remove', (e, id) => { try { fs.unlinkSync(path.join(DL, safe(id) + '.mp4')); } catch (x) {} return true; });
  ipcMain.handle('dl:exists', (e, id) => { try { const s = fs.statSync(path.join(DL, safe(id) + '.mp4')); return { path: path.join(DL, safe(id) + '.mp4'), size: s.size }; } catch (x) { return null; } });
  ipcMain.handle('dl:folder', () => { fs.mkdirSync(DL, { recursive: true }); shell.openPath(DL); });

  ipcMain.handle('upd:start', async (e, url) => {
    if (!app.isPackaged) return { ok: false, error: 'hanya untuk versi terpasang' };
    const dir = path.dirname(process.execPath);
    try { fs.accessSync(dir, fs.constants.W_OK); } catch (x) { return { ok: false, error: 'folder aplikasi tidak bisa ditulis' }; }
    const zip = path.join(os.tmpdir(), 'aphodius-update.zip');
    try {
      await download(url, zip, (got, total) => { if (win && !win.isDestroyed()) win.webContents.send('upd:progress', { got, total }); });
      const ps = path.join(os.tmpdir(), 'aphodius-update.ps1');
      fs.writeFileSync(ps, [
        'param($P,$Zip,$Dir,$Exe)',
        'Wait-Process -Id $P -ErrorAction SilentlyContinue',
        'Start-Sleep -Seconds 1',
        '$t = Join-Path $env:TEMP "aphodius-upd"',
        'Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue',
        'Expand-Archive -Path $Zip -DestinationPath $t -Force',
        '$src = Join-Path $t "Aphodius"',
        'robocopy $src $Dir /E /XD AphodiusData /R:5 /W:1 | Out-Null',
        'Remove-Item $Zip -Force -ErrorAction SilentlyContinue',
        'Start-Process -FilePath $Exe',
      ].join('\r\n'));
      spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', ps, String(process.pid), zip, dir, process.execPath], { detached: true, stdio: 'ignore', windowsHide: true }).unref();
      log('update dimulai'); setTimeout(() => { quitting = true; app.quit(); }, 400);
      return { ok: true };
    } catch (err) { log('update gagal ' + err.message); return { ok: false, error: err.message }; }
  });

  app.on('child-process-gone', (e, d) => log('CHILD-GONE ' + JSON.stringify(d)));
  pendingInvite = inviteFrom(process.argv);
  create();
});
app.on('second-instance', (e, argv) => { showWin(); deliverInvite(inviteFrom(argv)); });
app.on('before-quit', () => { quitting = true; saveWS(); });
app.on('window-all-closed', () => { if (!cfg.trayClose || quitting) app.quit(); });
