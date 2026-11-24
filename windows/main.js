'use strict';
const { app, BrowserWindow, session, shell, Menu, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');

// Mode portabel: semua data disimpan di folder "AphodiusData" di samping Aphodius.exe
if (app.isPackaged) {
  try {
    const dir = path.join(path.dirname(process.execPath), 'AphodiusData');
    fs.mkdirSync(dir, { recursive: true });
    fs.accessSync(dir, fs.constants.W_OK);
    app.setPath('userData', dir);
  } catch (e) { /* folder tidak bisa ditulis: pakai lokasi bawaan */ }
}

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

if (!app.requestSingleInstanceLock()) { app.quit(); }

let win;
function create() {
  Menu.setApplicationMenu(null);
  win = new BrowserWindow({
    width: 1280, height: 780, minWidth: 900, minHeight: 600,
    backgroundColor: '#050D16', title: 'Aphodius', autoHideMenuBar: true,
    icon: path.join(__dirname, 'icon.ico'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // Aplikasi memuat konten lokal dan memanggil layanan publik (X, GitHub, Firebase); tanpa batasan CORS.
      webSecurity: false,
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/i.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); } });
}

app.whenReady().then(() => {
  session.defaultSession.setPermissionRequestHandler((wc, perm, cb) => cb(['media', 'fullscreen', 'clipboard-sanitized-write', 'audioCapture'].includes(perm)));
  session.defaultSession.setPermissionCheckHandler(() => true);
  ipcMain.handle('open', (e, url) => { if (/^https?:\/\//i.test(String(url))) shell.openExternal(String(url)); });
  ipcMain.handle('meta', () => ({ version: app.getVersion(), portable: app.isPackaged, data: app.getPath('userData') }));
  create();
});
app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.on('window-all-closed', () => app.quit());
