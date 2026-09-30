'use strict';
/**
 * End-to-end test of INTERNET rendezvous, with no LAN beacon involved.
 *
 *  - a mock ntfy-style relay runs on localhost;
 *  - two engines (separate data dirs, discovery NOT started) share only that relay;
 *  - the test plays the role of "tracker/DHT" by handing B the address of A's
 *    torrent port once B is looking for A's infohash (in real life the
 *    trackers / DHT do that).
 *
 * Run: node test/signal-test.js
 */
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

process.env.BITBOARD_SIGNAL = process.env.BITBOARD_SIGNAL || 'on';
const { BitBoardEngine } = require('../src/engine');
const { signalTopic } = require('../src/signal');

function startRelay() {
  const topics = new Map(); // topic -> { msgs: [], subs: Set<res> }
  const get = (t) => { if (!topics.has(t)) topics.set(t, { msgs: [], subs: new Set() }); return topics.get(t); };
  let seq = 0;
  const server = http.createServer((req, res) => {
    const m = /^\/([^/?]+)(\/json)?/.exec(req.url);
    if (!m) { res.writeHead(404).end(); return; }
    const t = get(m[1]);
    if (req.method === 'POST') {
      let body = '';
      req.on('data', (c) => body += c);
      req.on('end', () => {
        const ev = { id: 'm' + (++seq), time: Math.floor(Date.now() / 1000), event: 'message', topic: m[1], message: body };
        t.msgs.push(ev);
        for (const s of t.subs) s.write(JSON.stringify(ev) + '\n');
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(ev));
      });
    } else if (m[2]) {
      res.writeHead(200, { 'Content-Type': 'application/x-ndjson' });
      res.write(JSON.stringify({ id: 'o', event: 'open', topic: m[1] }) + '\n');
      for (const ev of t.msgs) res.write(JSON.stringify(ev) + '\n');
      t.subs.add(res);
      req.on('close', () => t.subs.delete(res));
    } else res.writeHead(404).end();
  });
  return new Promise((r) => server.listen(0, '127.0.0.1', () => r({ server, url: 'http://127.0.0.1:' + server.address().port, topics })));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function waitFor(fn, ms, what) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) { if (await fn()) return; await sleep(250); }
  throw new Error('timeout waiting for ' + what);
}

(async () => {
  const relay = await startRelay();
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'bb-sig-'));
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
  const img = path.join(tmp, 'hello.png');
  fs.writeFileSync(img, png);

  const A = new BitBoardEngine({ dataDir: path.join(tmp, 'A'), signalUrl: relay.url });
  const B = new BitBoardEngine({ dataDir: path.join(tmp, 'B'), signalUrl: relay.url });
  A.on('log', (l) => console.log('[A]', l));
  B.on('log', (l) => console.log('[B]', l));
  await A.ready(); await B.ready();

  await A.createBoard('Vacation');
  await A.addImage('Vacation', img);
  await B.joinBoard('Vacation');           // same name, different "network"

  // Stand-in for tracker/DHT peer exchange.
  const matchmaker = setInterval(() => {
    const hashA = A.boards.get('Vacation').infoHash;
    for (const t of B.client.torrents) {
      if (t.infoHash === hashA && t.numPeers === 0) { try { t.addPeer('127.0.0.1:' + A.client.torrentPort); } catch (_) { } }
    }
  }, 500);

  try {
    await waitFor(() => fs.existsSync(path.join(B.boards.get('Vacation').dir, 'hello.png')), 60000, 'B to receive hello.png over the relay');
    assert.ok(relay.topics.has(signalTopic('Vacation')), 'announcements used the derived topic');
    console.log('\nPASS: B received the image from A via internet rendezvous (no LAN beacon)');
  } finally {
    clearInterval(matchmaker);
    A.destroy(); B.destroy(); relay.server.closeAllConnections?.(); relay.server.close();
    fs.rmSync(tmp, { recursive: true, force: true });
    setTimeout(() => process.exit(process.exitCode || 0), 300);
  }
})().catch((e) => { console.error('FAIL:', e.message); process.exit(1); });
