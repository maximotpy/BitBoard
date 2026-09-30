'use strict';
/* Isolate which input type client.seed() actually accepts in Node. */
const path = require('path');
const fs = require('fs');

async function main() {
  const WebTorrent = (await import('webtorrent')).default;

  const client = new WebTorrent({ dht: false, lsd: false, utp: false, natUpnp: false, natPmp: false });
  client.on('error', (err) => console.log('CLIENT ERROR EVENT:', err.message));
  await new Promise(r => setTimeout(r, 300));

  // Case 1: seed a single FILE path (string)
  const dir = path.join(__dirname, 'tmp-iso');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  const filePath = path.join(dir, 'hello.txt');
  fs.writeFileSync(filePath, 'hello world');

  console.log('--- case 1: client.seed(file path string) ---');
  await new Promise((resolve) => {
    client.seed(filePath, { announce: [] }, (t) => {
      console.log('OK infoHash:', t.infoHash, 'files:', t.files.map(f => f.name));
      resolve();
    });
    setTimeout(() => { console.log('NEVER FIRED'); resolve(); }, 8000);
  });

  // Case 2: seed a FOLDER path (string)
  console.log('--- case 2: client.seed(folder path string) ---');
  await new Promise((resolve) => {
    client.seed(dir, { announce: [] }, (t) => {
      console.log('OK infoHash:', t.infoHash, 'files:', t.files.map(f => f.name));
      resolve();
    });
    setTimeout(() => { console.log('NEVER FIRED'); resolve(); }, 8000);
  });

  // Case 3: seed a Buffer with name
  console.log('--- case 3: client.seed(Buffer with .name) ---');
  await new Promise((resolve) => {
    const buf = Buffer.from('buffer content');
    buf.name = 'buf.txt';
    client.seed(buf, { announce: [] }, (t) => {
      console.log('OK infoHash:', t.infoHash, 'files:', t.files.map(f => f.name));
      resolve();
    });
    setTimeout(() => { console.log('NEVER FIRED'); resolve(); }, 8000);
  });

  client.destroy();
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(0);
}

main().catch(err => { console.error('FATAL:', err); process.exit(1); });