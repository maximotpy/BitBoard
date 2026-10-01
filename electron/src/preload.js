'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('bitboard', {
  getBoards: () => ipcRenderer.invoke('bb:getBoards'),
  getThumbnail: (boardName, fileName) => ipcRenderer.invoke('bb:getThumbnail', boardName, fileName),
  getDiscovered: () => ipcRenderer.invoke('bb:getDiscovered'),
  createBoard: (name) => ipcRenderer.invoke('bb:createBoard', name),
  joinBoard: (name) => ipcRenderer.invoke('bb:joinBoard', name),
  addImage: (boardName) => ipcRenderer.invoke('bb:addImage', boardName),
  leaveBoard: (name, deleteFiles) => ipcRenderer.invoke('bb:leaveBoard', name, deleteFiles),
  setBoardIcon: (name, icon) => ipcRenderer.invoke('bb:setBoardIcon', name, icon),
  removeImage: (boardName, fileName) => ipcRenderer.invoke('bb:removeImage', boardName, fileName),
  imageHash: (boardName, fileName) => ipcRenderer.invoke('bb:imageHash', boardName, fileName),
  getSettings: () => ipcRenderer.invoke('bb:getSettings'),
  setSetting: (key, value) => ipcRenderer.invoke('bb:setSetting', key, value),
  addImageHash: (hash) => ipcRenderer.invoke('bb:addImageHash', hash),
  removeImageHash: (hash) => ipcRenderer.invoke('bb:removeImageHash', hash),
  addBoardBlacklist: (name) => ipcRenderer.invoke('bb:addBoardBlacklist', name),
  removeBoardBlacklist: (name) => ipcRenderer.invoke('bb:removeBoardBlacklist', name),
  addPeerBlacklist: (id) => ipcRenderer.invoke('bb:addPeerBlacklist', id),
  removePeerBlacklist: (id) => ipcRenderer.invoke('bb:removePeerBlacklist', id),
  getPeerId: () => ipcRenderer.invoke('bb:getPeerId'),
  onBoards: (cb) => ipcRenderer.on('bb:boards', (_e, snapshot) => cb(snapshot)),
  onDiscovered: (cb) => ipcRenderer.on('bb:discovered', (_e, names) => cb(names)),
  onLog: (cb) => ipcRenderer.on('bb:log', (_e, line) => cb(line))
});