'use strict';
const { contextBridge, ipcRenderer } = require('electron');
const on = (ch, cb) => { const f = (e, d) => cb(d); ipcRenderer.on(ch, f); return () => ipcRenderer.removeListener(ch, f); };
contextBridge.exposeInMainWorld('aph', {
  open: (url) => ipcRenderer.invoke('open', url),
  meta: () => ipcRenderer.invoke('meta'),
  log: (l) => ipcRenderer.send('log', l),
  readLog: () => ipcRenderer.invoke('log:read'), showLog: () => ipcRenderer.invoke('log:show'),
  clearCache: () => ipcRenderer.invoke('cache:clear'),
  setState: (s) => ipcRenderer.send('state', s),
  setCfg: (c) => ipcRenderer.send('cfg', c),
  onCmd: (cb) => on('cmd', cb),
  proto: (v) => ipcRenderer.invoke('proto', v),
  inviteReady: (cb) => { on('invite', cb); ipcRenderer.send('invite-ready'); },
  zoom: (f) => ipcRenderer.invoke('zoom', f),
  fullscreen: (v) => ipcRenderer.invoke('fullscreen', v),
  dlStart: (id, url) => ipcRenderer.invoke('dl:start', { id, url }), dlCancel: (id) => ipcRenderer.invoke('dl:cancel', id),
  dlRemove: (id) => ipcRenderer.invoke('dl:remove', id), dlExists: (id) => ipcRenderer.invoke('dl:exists', id), dlFolder: () => ipcRenderer.invoke('dl:folder'),
  onDl: (cb) => on('dl:progress', cb),
  updStart: (url) => ipcRenderer.invoke('upd:start', url), onUpd: (cb) => on('upd:progress', cb),
});
