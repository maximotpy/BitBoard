'use strict';
/* Reproduce engine._publishBoard's exact client.seed() call. */
const path = require('path');
const fs = require('fs');

async function main() {
  const WebTorrent = (await import('webtorrent')).default;
  const createTorrent = (await import('create-torrent')).default;

  // Simulate a fresh board dir: only the manifest dotfile.
  const dir = path.join(__dirname, 'tmp-board');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, '.bitboard-manifest.json'), JSON.stringify({ board: 'Test', files: [] }));

  const client = new WebTorrent({ dht: true, maxConns: 200 });
  client.on('error', (err) => console.log('CLIENT ERROR EVENT:', err.message));
  client.on('warning', (err) => console.log('client warning:', err.message));

  await new Promise(r => setTimeout(r, 500)); // let client become ready

  console.log('--- calling client.seed(dir, {name}) exactly like engine ---');
  await new Promise((resolve) => {
    try {
      client.seed(dir, { name: 'bitboard-Test' }, (torrent) => {
        console.log('SEED CALLBACK FIRED. infoHash:', torrent.infoHash, 'files:', torrent.files.map(f => f.name));
        resolve();
      });
    } catch (err) {
      console.log('SEED THREW SYNC:', err.message);
      resolve();
    }
    setTimeout(() => { console.log('SEED CALLBACK NEVER FIRED (10s timeout)'); resolve(); }, 10000);
  });

  console.log('--- now without opts.name (control) ---');
  await new Promise((resolve) => {
    client.seed(dir, {}, (torrent) => {
      console.log('SEED CALLBACK FIRED. infoHash:', torrent.infoHash, 'files:', torrent.files.map(f => f.name));
      resolve();
    });
    setTimeout(() => { console.log('SEED CALLBACK NEVER FIRED (10s timeout)'); resolve(); }, 10000);
  });

  client.destroy();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(0);
}

main().catch(err => { console.error('FATAL:', err); process.exit(1); });