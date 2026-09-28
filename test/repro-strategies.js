'use strict';
/* Instrumented repro: capture full errors from client.seed with path input,
   and test the alternative "add(torrentBuf, {path})" strategy. */
const path = require('path');
const fs = require('fs');

process.on('uncaughtException', (err) => {
  console.log('UNCAUGHT:', err.message);
  console.log(err.stack.split('\n').slice(0, 6).join('\n'));
});

async function main() {
  const WebTorrent = (await import('webtorrent')).default;
  const createTorrent = (await import('create-torrent')).default;

  const dir = path.join(__dirname, 'tmp-iso2');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  // A real image-like file (not a dotfile).
  fs.writeFileSync(path.join(dir, 'photo.png'), Buffer.alloc(1024, 7));

  const client = new WebTorrent({ dht: false, lsd: false, utp: false, natUpnp: false, natPmp: false });
  client.on('error', (err) => console.log('CLIENT ERROR EVENT:', err.message, '| stack:', (err.stack || '').split('\n')[1]));
  client.on('warning', (err) => console.log('client warning:', err.message));
  await new Promise(r => setTimeout(r, 300));

  // Strategy A: client.seed(dir) — what the engine does today
  console.log('--- A: client.seed(dir) ---');
  await new Promise((resolve) => {
    client.seed(dir, { announce: [] }, (t) => {
      console.log('A OK infoHash:', t.infoHash, 'files:', t.files.map(f => f.name));
      resolve();
    });
    setTimeout(() => { console.log('A: callback never fired'); resolve(); }, 8000);
  });

  // Strategy B: createTorrent(dir) then client.add(buf, { path: dir })
  console.log('--- B: createTorrent + client.add(buf, {path}) ---');
  await new Promise((resolve) => {
    createTorrent(dir, { announce: [] }, (err, buf) => {
      if (err) { console.log('B createTorrent err:', err.message); return resolve(); }
      console.log('B torrent created,', buf.length, 'bytes');
      client.add(buf, { path: dir }, (t) => {
        console.log('B OK infoHash:', t.infoHash, 'files:', t.files.map(f => f.name), 'ready:', t.ready);
        resolve();
      });
      setTimeout(() => { console.log('B: callback never fired'); resolve(); }, 8000);
    });
  });

  client.destroy();
  fs.rmSync(dir, { recursive: true, force: true });
  setTimeout(() => process.exit(0), 500);
}

main().catch(err => { console.error('FATAL:', err.message); process.exit(1); });