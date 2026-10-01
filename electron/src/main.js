'use strict';

const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const { BitBoardEngine } = require('./engine');

let win = null;
let engine = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#1e1e1e',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  // Surface renderer console errors in the terminal so failures aren't silent.
  win.webContents.on('console-message', (_e, level, message, line, sourceId) => {
    if (level >= 2) console.error(`[renderer] ${message} (${sourceId}:${line})`);
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

app.whenReady().then(async () => {
  // Create the window FIRST so the app is visibly alive even if engine
  // startup is slow or fails — a rejected promise here used to leave the
  // process running with no window at all (looked like "app won't start").
  createWindow();

  // Register ALL IPC handlers synchronously, before any engine await: the
  // renderer starts calling bb:getBoards as soon as the page loads, and a
  // handler registered too late makes the UI throw "No handler registered".
  ipcMain.handle('bb:getBoards', async () => {
    if (!engine) return [];
    await engine.ready();
    return engine.getBoardsSnapshot();
  });
  ipcMain.handle('bb:getDiscovered', async () => {
    if (!engine) return [];
    await engine.ready();
    return engine.getDiscoveredSnapshot();
  });
  ipcMain.handle('bb:getThumbnail', (_e, boardName, fileName) => {
    try {
      if (!engine) return null;
      const board = engine.boards.get(boardName);
      if (!board) return null;
      const p = path.join(board.dir, path.basename(fileName)); // basename: no traversal
      if (!require('fs').existsSync(p)) return null;
      const ext = path.extname(p).slice(1).toLowerCase() || 'png';
      const mime = ext === 'jpg' ? 'jpeg' : ext === 'svg' ? 'svg+xml' : ext;
      return 'data:image/' + mime + ';base64,' + require('fs').readFileSync(p).toString('base64');
    } catch (_) { return null; }
  });
  // The raw board objects hold a Map and a live WebTorrent instance, which
  // are not structured-cloneable, so they must never cross IPC. Return a
  // plain serializable ack instead; the UI updates via the 'bb:boards' push.
  ipcMain.handle('bb:createBoard', async (_e, name) => { await engine.ready(); await engine.createBoard(name); return { ok: true }; });
  ipcMain.handle('bb:joinBoard', async (_e, name) => {
    await engine.ready();
    if (engine.settings.isBoardBlocked(name)) {
      throw new Error('Board "' + name + '" is blacklisted (unblock it in Settings)');
    }
    await engine.joinBoard(name);
    return { ok: true };
  });
  ipcMain.handle('bb:leaveBoard', async (_e, name, deleteFiles) => {
    await engine.ready();
    await engine.leaveBoard(name, !!deleteFiles);
    return { ok: true };
  });
  ipcMain.handle('bb:setBoardIcon', (_e, name, icon) => {
    if (!engine) return { ok: false };
    engine.setBoardIcon(name, icon);
    return { ok: true };
  });
  ipcMain.handle('bb:removeImage', async (_e, boardName, fileName) => {
    await engine.ready();
    await engine.removeImage(boardName, fileName);
    return { ok: true };
  });
  ipcMain.handle('bb:imageHash', (_e, boardName, fileName) => {
    if (!engine) return null;
    return engine.imageHash(boardName, fileName);
  });

  // Settings + blacklists (plain serializable objects only).
  ipcMain.handle('bb:getSettings', () => {
    if (!engine) return {};
    const s = engine.settings.get();
    return {
      showLog: s.showLog,
      confirmLeaveBoard: s.confirmLeaveBoard,
      imageHashes: [...s.imageHashes],
      boards: [...s.boards],
      peers: [...s.peers]
    };
  });
  ipcMain.handle('bb:setSetting', (_e, key, value) => {
    if (!engine) return { ok: false };
    if (key === 'showLog' || key === 'confirmLeaveBoard') {
      engine.settings.set(key, !!value);
    }
    return { ok: true };
  });
  ipcMain.handle('bb:addImageHash', (_e, hash) => { engine && engine.settings.add('imageHashes', hash); return { ok: true }; });
  ipcMain.handle('bb:removeImageHash', (_e, hash) => { engine && engine.settings.remove('imageHashes', hash); return { ok: true }; });
  ipcMain.handle('bb:addBoardBlacklist', (_e, name) => { engine && engine.settings.add('boards', name); return { ok: true }; });
  ipcMain.handle('bb:removeBoardBlacklist', (_e, name) => { engine && engine.settings.remove('boards', name); return { ok: true }; });
  ipcMain.handle('bb:addPeerBlacklist', (_e, id) => { engine && engine.settings.add('peers', id); return { ok: true }; });
  ipcMain.handle('bb:removePeerBlacklist', (_e, id) => { engine && engine.settings.remove('peers', id); return { ok: true }; });
  ipcMain.handle('bb:getPeerId', () => (engine ? engine.peerId() : ''));
  ipcMain.handle('bb:addImage', async (_e, boardName) => {
    await engine.ready();
    const res = await dialog.showOpenDialog(win, {
      title: 'Add image to "' + boardName + '"',
      properties: ['openFile'],
      filters: [
        { name: 'Images', extensions: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'avif', 'ico', 'tif', 'tiff'] }
      ]
    });
    if (res.canceled || !res.filePaths.length) return false;
    await engine.addImage(boardName, res.filePaths[0]);
    return true;
  });

  try {
    engine = new BitBoardEngine();
    engine.on('log', (line) => {
      if (win && !win.isDestroyed()) win.webContents.send('bb:log', line);
    });
    engine.on('boards-changed', (snapshot) => {
      if (win && !win.isDestroyed()) win.webContents.send('bb:boards', snapshot);
    });
    engine.on('discovered-changed', (names) => {
      if (win && !win.isDestroyed()) win.webContents.send('bb:discovered', names);
    });
    // Start LAN discovery immediately: restoring saved boards can take a while
    // and beacons must not wait for it (merges are deferred until ready).
    engine.startDiscovery();
    await engine.ready();
  } catch (err) {
    console.error('[main] engine startup failed:', err);
    if (win && !win.isDestroyed()) {
      win.webContents.send('bb:log', 'engine startup failed: ' + err.message);
    }
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (engine) engine.destroy();
  app.quit();
});