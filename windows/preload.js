'use strict';
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('aph', {
  open: (url) => ipcRenderer.invoke('open', url),
  meta: () => ipcRenderer.invoke('meta'),
});
