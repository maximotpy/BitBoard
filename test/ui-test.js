'use strict';

/* Headless UI verification: drives the real renderer and asserts
   1) modal is hidden at startup
   2) clicking "New board" shows the modal
   3) OK closes it and creates the board
   Exits 0 on success, 1 on failure. */

const { app, BrowserWindow } = require('electron');
const path = require('path');

app.whenReady().then(async () => {
  const win = new BrowserWindow({
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'src', 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  await win.loadFile(path.join(__dirname, 'src', 'renderer', 'index.html'));

  const results = await win.webContents.executeJavaScript(`
    (async () => {
      const out = [];
      const backdrop = document.getElementById('modal-backdrop');
      const visible = () => !backdrop.classList.contains('hidden');

      out.push(['modal hidden at startup', !visible()]);

      document.getElementById('btn-add-board').click();
      await new Promise(r => setTimeout(r, 50));
      out.push(['modal opens on New board click', visible()]);

      const input = document.getElementById('modal-input');
      input.value = '__test_board__';
      document.getElementById('modal-ok').click();
      await new Promise(r => setTimeout(r, 50));
      out.push(['modal closes on OK', !visible()]);

      // Wait for the IPC round-trip and boards-changed event.
      await new Promise(r => setTimeout(r, 3000));
      const items = [...document.querySelectorAll('.board-item .board-name')].map(e => e.textContent);
      out.push(['board created and listed', items.includes('__test_board__')]);

      // Cancel path: open then cancel must close without creating.
      document.getElementById('btn-join-board').click();
      await new Promise(r => setTimeout(r, 50));
      out.push(['modal opens on Join board click', visible()]);
      document.getElementById('modal-cancel').click();
      await new Promise(r => setTimeout(r, 50));
      out.push(['modal closes on Cancel', !visible()]);

      return out;
    })()
  `);

  let failed = false;
  for (const [label, ok] of results) {
    console.log((ok ? 'PASS' : 'FAIL') + '  ' + label);
    if (!ok) failed = true;
  }
  app.exit(failed ? 1 : 0);
});