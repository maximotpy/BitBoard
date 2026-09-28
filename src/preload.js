'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('bitboard', {
  getBoards: () => ipcRenderer.invoke('bb:getBoards'),
  getThumbnail: (boardName, fileName) => ipcRenderer.invoke('bb:getThumbnail', boardName, fileName),
  createBoard: (name) => ipcRenderer.invoke('bb:createBoard', name),
  joinBoard: (name) => ipcRenderer.invoke('bb:joinBoard', name),
  addImage: (boardName) => ipcRenderer.invoke('bb:addImage', boardName),
  onBoards: (cb) => ipcRenderer.on('bb:boards', (_e, snapshot) => cb(snapshot)),
  onLog: (cb) => ipcRenderer.on('bb:log', (_e, line) => cb(line))
});