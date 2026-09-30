'use strict';
/* Test whether parse-torrent can parse a torrent buffer created from a folder. */
import parseTorrent from 'parse-torrent';
import createTorrent from 'create-torrent';
import fs from 'fs';

const dir = 'tmp-parsetest';
fs.rmSync(dir, { recursive: true, force: true });
fs.mkdirSync(dir);
fs.writeFileSync(dir + '/photo.png', Buffer.alloc(1024, 7));

createTorrent(dir, {}, async (err, buf) => {
  if (err) { console.log('create err:', err.message); process.exit(1); }
  console.log('torrent buf:', buf.length, 'bytes');
  try {
    const parsed = await parseTorrent(buf);
    console.log('PARSE OK, infoHash:', parsed.infoHash, 'name:', parsed.name, 'files:', (parsed.files || []).length);
  } catch (e) {
    console.log('PARSE FAILED:', e.message);
    console.log(e.stack.split('\n').slice(0, 4).join('\n'));
  }
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(0);
});