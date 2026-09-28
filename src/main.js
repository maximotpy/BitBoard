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
  engine = new BitBoardEngine();
  await engine.ready();

  engine.on('log', (line) => {
    if (win && !win.isDestroyed()) win.webContents.send('bb:log', line);
  });
  engine.on('boards-changed', (snapshot) => {
    if (win && !win.isDestroyed()) win.webContents.send('bb:boards', snapshot);
  });

  engine.startDiscovery();

  ipcMain.handle('bb:getBoards', async () => { await engine.ready(); return engine.getBoardsSnapshot(); });
  ipcMain.handle('bb:getThumbnail', (_e, boardName, fileName) => {
    try {
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
  ipcMain.handle('bb:createBoard', async (_e, name) => { await engine.createBoard(name); return { ok: true }; });
  ipcMain.handle('bb:joinBoard', async (_e, name) => { await engine.joinBoard(name); return { ok: true }; });
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

  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (engine) engine.destroy();
  app.quit();
});