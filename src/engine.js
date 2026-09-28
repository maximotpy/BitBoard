'use strict';

/**
 * BitBoard P2P engine.
 *
 * Every "board" is a folder of images that is published as a BitTorrent
 * (v1/v2 hybrid, via WebTorrent). Every device that joins the same board
 * seeds and leeches simultaneously, so files replicate automatically
 * between devices — like a cloud folder, but fully peer-to-peer.
 *
 * Discovery:
 *  - DHT + WebTorrent trackers (public swarm, works across the internet)
 *  - Local UDP multicast beacon so devices on the same LAN find each other
 *    instantly and exchange infohashes of their boards.
 */

const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const { EventEmitter } = require('events');

// webtorrent and create-torrent are ESM-only; load them via dynamic import.
let WebTorrent = null;
let createTorrent = null;
async function loadEsmDeps() {
  if (WebTorrent) return;
  WebTorrent = (await import('webtorrent')).default;
  createTorrent = (await import('create-torrent')).default;
}
const dgram = require('dgram');

const DATA_DIR = path.join(__dirname, '..', 'data');
const BOARDS_DIR = path.join(DATA_DIR, 'boards');
const STATE_FILE = path.join(DATA_DIR, 'state.json');
const TORRENTS_DIR = path.join(DATA_DIR, 'torrents');

const MULTICAST_ADDR = '239.255.66.66';
const MULTICAST_PORT = 45666;
const BEACON_INTERVAL_MS = 3000;

// NOTE: must NOT start with a dot. create-torrent silently drops hidden files,
// so a board that only contained ".bitboard-manifest.json" produced an empty
// torrent that WebTorrent refuses to seed.
const MANIFEST_NAME = 'bitboard-manifest.json';
const LEGACY_MANIFEST_NAME = '.bitboard-manifest.json';
const TRACKERS = [
  'wss://tracker.openwebtorrent.com',
  'wss://tracker.webtorrent.dev',
  'udp://tracker.opentrackr.org:1337/announce',
  'udp://open.tracker.cl:1337/announce'
];
const PUBLISH_TIMEOUT_MS = 30000;

const IMAGE_EXT = new Set([
  '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg', '.avif', '.ico', '.tif', '.tiff'
]);

function ensureDirs() {
  for (const d of [DATA_DIR, BOARDS_DIR, TORRENTS_DIR]) {
    try { fs.mkdirSync(d, { recursive: true }); } catch (_) { /* exists */ }
  }
}

function isImageFile(name) {
  return IMAGE_EXT.has(path.extname(name).toLowerCase());
}

function sha1(s) {
  return crypto.createHash('sha1').update(s).digest('hex');
}

/**
 * Deterministic info-hash for a board name: every device that wants to join
 * "Vacation" computes the same infohash and finds the swarm, even before it
 * has ever seen a .torrent file. The actual torrent payload is a single
 * manifest file; image bytes are exchanged as torrent files inside the
 * per-board swarm.
 */
function boardInfoHash(boardName) {
  return sha1('bitboard-board-v1:' + boardName.trim().toLowerCase());
}

/**
 * Magnet URI for a board swarm. Includes tracker hints (`tr=`) so a joining
 * peer can rendezvous via the trackers even when DHT is slow or blocked.
 */
function boardMagnet(boardName, infoHash) {
  const tr = TRACKERS.map(t => '&tr=' + encodeURIComponent(t)).join('');
  return `magnet:?xt=urn:btih:${infoHash}&dn=bitboard-${encodeURIComponent(boardName)}${tr}`;
}

class BitBoardEngine extends EventEmitter {
  constructor() {
    super();
    ensureDirs();
    this.client = null;
    this.boards = new Map();      // name -> { name, dir, torrent, infoHash, files: Map(path->meta) }
    this.state = this._loadState();
    this.socket = null;
    this.beaconTimer = null;
    this._destroyed = false;

    // Async bootstrap: load ESM deps, create the torrent client, then
    // restore the boards saved from the previous run.
    this._ready = (async () => {
      await loadEsmDeps();
      // lsd: true so LAN peers find each other via Local Service Discovery
      // even when trackers/DHT are unavailable (Android enables LSD too).
      this.client = new WebTorrent({ dht: true, lsd: true, maxConns: 200 });
      this.client.on('error', (err) => this.emit('log', 'client error: ' + err.message));
      this.client.on('torrent', () => this._emitBoardsChanged());
      await this._restoreBoards();
    })();
  }

  /** Resolves once the ESM deps and torrent client are available. */
  ready() { return this._ready; }

  /* ---------------- persistence ---------------- */

  _loadState() {
    try {
      return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    } catch (_) {
      return { boards: {} };
    }
  }

  _saveState() {
    const out = { boards: {} };
    for (const [name, b] of this.boards) {
      out.boards[name] = {
        name: b.name,
        infoHash: b.infoHash,
        createdAt: b.createdAt || Date.now()
      };
    }
    try { fs.writeFileSync(STATE_FILE, JSON.stringify(out, null, 2)); } catch (_) {}
  }

  /**
   * Re-open the boards saved in state.json from the previous run so they
   * reappear in the UI after a restart. Boards whose folder still exists are
   * re-seeded locally; the rest are re-joined from the swarm (or kept as
   * "searching for peers" entries so the user doesn't lose them).
   */
  async _restoreBoards() {
    const saved = (this.state && this.state.boards) || {};
    const names = Object.keys(saved);
    if (!names.length) return;
    this.emit('log', `restoring ${names.length} saved board(s)…`);

    for (const name of names) {
      if (this.boards.has(name)) continue;
      const dir = this.boardDir(name);
      fs.mkdirSync(dir, { recursive: true });

      const board = {
        name,
        dir,
        infoHash: saved[name].infoHash || boardInfoHash(name),
        torrent: null,
        files: new Map(),
        createdAt: saved[name].createdAt || Date.now()
      };
      this.boards.set(name, board);
      this._scanFiles(board);
      this._emitBoardsChanged();

      try {
        // If the folder has content (or even just needs a manifest), seed it.
        // Otherwise fetch the torrent from the swarm like a fresh join.
        const hasContent = fs.readdirSync(dir).length > 0;
        if (hasContent) {
          await this._publishBoard(board);
          this.emit('log', `restored board "${name}" — seeding`);
        } else {
          // joinBoard() returns early for boards already in the map, so
          // remove the placeholder and let it do the full swarm join.
          this.boards.delete(name);
          await this.joinBoard(name);
        }
      } catch (err) {
        // Keep the board listed even if the swarm is unreachable right now;
        // the user's data (state.json) is preserved for the next attempt.
        this.emit('log', `could not fully restore "${name}": ${err.message}`);
      }
    }
    this._saveState();
  }

  /* ---------------- board lifecycle ---------------- */

  boardDir(name) {
    return path.join(BOARDS_DIR, sha1(name.trim().toLowerCase()).slice(0, 16));
  }

  async createBoard(name) {
    await this._ready;
    name = String(name || '').trim();
    if (!name) throw new Error('Board name required');
    if (this.boards.has(name)) return this.boards.get(name);

    const dir = this.boardDir(name);
    fs.mkdirSync(dir, { recursive: true });

    const board = {
      name,
      dir,
      infoHash: boardInfoHash(name),
      torrent: null,
      files: new Map(),          // relPath -> { length, downloadedAt }
      createdAt: Date.now()
    };
    this.boards.set(name, board);
    this._emitBoardsChanged();   // show the board in the UI right away

    // Seed the board's folder as a torrent. If that fails, roll the board
    // back so the UI never shows a half-created board and the user can retry.
    try {
      await this._publishBoard(board);
    } catch (err) {
      this.boards.delete(name);
      this._emitBoardsChanged();
      this.emit('log', `failed to create board "${name}": ${err.message}`);
      throw err;
    }
    this._saveState();
    this._emitBoardsChanged();
    this.emit('log', `board "${name}" created (${board.infoHash.slice(0, 12)}…)`);
    return board;
  }

  /**
   * Publish (or re-publish) the board torrent from the local folder.
   * The torrent is rebuilt whenever files change so peers receive updates.
   */
  async _publishBoard(board) {
    const entries = fs.readdirSync(board.dir).filter(f => isImageFile(f));

    // Drop the old hidden manifest written by earlier versions.
    try { fs.rmSync(path.join(board.dir, LEGACY_MANIFEST_NAME), { force: true }); } catch (_) {}

    // Visible manifest: guarantees the torrent always has at least one file,
    // even for a brand-new board with no images yet.
    const manifest = {
      board: board.name,
      createdAt: board.createdAt,
      files: entries.map(f => {
        const st = fs.statSync(path.join(board.dir, f));
        return { name: f, size: st.size, mtime: st.mtimeMs };
      })
    };
    fs.writeFileSync(path.join(board.dir, MANIFEST_NAME), JSON.stringify(manifest, null, 2));

    // Re-seed: drop the previous torrent first (waits until it is really gone,
    // otherwise WebTorrent sees a duplicate torrent for the same folder).
    if (board.torrent) {
      const old = board.torrent;
      board.torrent = null;
      await new Promise((res) => { try { old.destroy(() => res()); } catch (_) { res(); } });
    }

    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (err, torrent) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (err) {
          if (board.torrent === torrent) board.torrent = null;
          try { if (torrent) torrent.destroy(); } catch (_) {}
          reject(err);
        } else {
          resolve(torrent);
        }
      };
      const timer = setTimeout(
        () => finish(new Error('Timed out while publishing the board torrent'), torrent),
        PUBLISH_TIMEOUT_MS
      );

      let torrent;
      try {
        torrent = this.client.seed(board.dir, {
          name: 'bitboard-' + board.name,
          createdBy: 'BitBoard 1.0',
          announce: TRACKERS
        });
      } catch (err) {
        return finish(err, null);
      }
      board.torrent = torrent;

      // Use 'ready' rather than seed()'s callback: with DHT enabled that
      // callback waits for a DHT announce, which can take very long (or never
      // happen) on restricted networks. The board is usable as soon as the
      // torrent is ready; announcing continues in the background.
      torrent.once('error', (err) => finish(err, torrent));
      torrent.once('ready', () => {
        board.infoHash = torrent.infoHash;
        try {
          fs.writeFileSync(path.join(TORRENTS_DIR, torrent.infoHash + '.torrent'), torrent.torrentFile);
        } catch (_) {}
        this._scanFiles(board);
        torrent.on('wire', () => this.emit('log', `peer connected to "${board.name}" swarm`));
        this._emitBoardsChanged();
        finish(null, torrent);
      });
    });
  }

  /**
   * Join a board by name. Computes the deterministic infohash, asks the
   * swarm for the manifest torrent, downloads everything into the local
   * folder, then keeps seeding.
   *
   * `knownInfoHash` (optional) is the REAL content-derived infohash learned
   * from a LAN beacon; when present it is used instead of the deterministic
   * hash, because the seeded torrent's infohash is derived from its contents
   * and never equals the deterministic name-hash.
   */
  async joinBoard(name, knownInfoHash) {
    await this._ready;
    name = String(name || '').trim();
    if (!name) throw new Error('Board name required');
    if (this.boards.has(name)) return this.boards.get(name);

    const dir = this.boardDir(name);
    fs.mkdirSync(dir, { recursive: true });

    const board = {
      name,
      dir,
      infoHash: knownInfoHash || boardInfoHash(name),
      torrent: null,
      files: new Map(),
      createdAt: Date.now()
    };
    this.boards.set(name, board);

    // Try to fetch the live torrent from the swarm by magnet.
    const magnet = boardMagnet(name, board.infoHash);
    await new Promise((resolve) => {
      this.client.add(magnet, { path: dir }, (torrent) => {
        board.torrent = torrent;
        this._scanFiles(board);
        torrent.on('done', () => {
          this.emit('log', `board "${name}" fully synced`);
          this._scanFiles(board);
          this._emitBoardsChanged();
        });
        torrent.on('wire', () => this.emit('log', `peer connected to "${board.name}" swarm`));
        resolve();
      });
      // If nothing is found quickly, keep waiting in background (DHT may
      // take a while); the UI shows the board as "searching for peers".
      setTimeout(resolve, 15000);
    });

    this._saveState();
    this._emitBoardsChanged();
    this.emit('log', `joined board "${name}"`);
    return board;
  }

  /** Add an image file into a board folder and re-publish the torrent. */
  async addImage(boardName, srcPath) {
    await this._ready;
    const board = this.boards.get(boardName);
    if (!board) throw new Error('Unknown board: ' + boardName);
    const base = path.basename(srcPath);
    if (!isImageFile(base)) throw new Error('Not an image file: ' + base);
    const dest = path.join(board.dir, base);
    fs.copyFileSync(srcPath, dest);
    const st = fs.statSync(dest);
    board.files.set(base, { length: st.size, downloadedAt: Date.now(), local: true });
    await this._publishBoard(board);
    this._saveState();
    this._emitBoardsChanged();
    this.emit('log', `added ${base} to "${boardName}" — replicating to peers`);
    return true;
  }

  _scanFiles(board) {
    try {
      const entries = fs.readdirSync(board.dir).filter(f => isImageFile(f));
      const seen = new Set();
      for (const f of entries) {
        seen.add(f);
        if (!board.files.has(f)) {
          const st = fs.statSync(path.join(board.dir, f));
          board.files.set(f, { length: st.size, downloadedAt: st.birthtimeMs || Date.now() });
        }
      }
      for (const f of [...board.files.keys()]) {
        if (!seen.has(f)) board.files.delete(f);
      }
    } catch (_) {}
  }

  /* ---------------- LAN discovery (UDP multicast) ---------------- */

  startDiscovery() {
    if (this.socket) return;
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.socket = sock;

    sock.on('error', (err) => this.emit('log', 'discovery error: ' + err.message));

    sock.on('message', (buf, rinfo) => {
      try {
        const msg = JSON.parse(buf.toString('utf8'));
        if (msg.app !== 'bitboard') return;
        if (msg.peerId === this.peerId()) return; // our own beacon
        // A peer announced boards it has. If we don't know one of them,
        // join it automatically — that is the "cloud-like" replication.
        // The beacon carries the peer's REAL content-derived infohash, which
        // is the only reliable way to find the swarm (the deterministic
        // name-hash never matches a seeded torrent's infohash).
        for (const b of (msg.boards || [])) {
          if (!this.boards.has(b.name)) {
            this.emit('log', `discovered board "${b.name}" on LAN (${rinfo.address}) — joining`);
            this.joinBoard(b.name, b.infoHash).catch(() => {});
          } else {
            this._followInfoHashUpdate(b.name, b.infoHash);
          }
        }
      } catch (_) {}
    });

    sock.bind(MULTICAST_PORT, () => {
      try { sock.addMembership(MULTICAST_ADDR); } catch (_) {}
      sock.setBroadcast(true);
      this.emit('log', 'LAN discovery active on ' + MULTICAST_ADDR + ':' + MULTICAST_PORT);
    });

    this.beaconTimer = setInterval(() => this._sendBeacon(), BEACON_INTERVAL_MS);
    this._sendBeacon();
  }

  peerId() {
    if (!this._peerId) {
      const idFile = path.join(DATA_DIR, 'peer-id');
      try { this._peerId = fs.readFileSync(idFile, 'utf8').trim(); } catch (_) {
        this._peerId = crypto.randomBytes(8).toString('hex');
        try { fs.writeFileSync(idFile, this._peerId); } catch (_) {}
      }
    }
    return this._peerId;
  }

  _sendBeacon() {
    if (!this.socket || this._destroyed) return;
    const msg = Buffer.from(JSON.stringify({
      app: 'bitboard',
      peerId: this.peerId(),
      host: os.hostname(),
      boards: [...this.boards.keys()].map(n => ({ name: n, infoHash: this.boards.get(n).infoHash }))
    }));
    try { this.socket.send(msg, 0, msg.length, MULTICAST_PORT, MULTICAST_ADDR); } catch (_) {}
  }

  /**
   * A LAN peer announced a board we already have, but with a DIFFERENT
   * infohash — meaning the board was re-published (files added/removed) and
   * the swarm moved. Leave our stale swarm and join the new one so the two
   * devices converge again instead of seeding disjoint swarms forever.
   */
  async _followInfoHashUpdate(name, newInfoHash) {
    if (!newInfoHash || this._destroyed) return;
    const board = this.boards.get(name);
    if (!board || board.infoHash === newInfoHash) return;
    if (board._migrating) return; // already switching swarms
    board._migrating = true;
    this.emit('log', `board "${name}" updated on a peer — switching to the new swarm`);
    try {
      if (board.torrent) {
        const old = board.torrent;
        board.torrent = null;
        await new Promise((res) => { try { old.destroy(() => res()); } catch (_) { res(); } });
      }
      board.infoHash = newInfoHash;
      this._saveState();
      this._emitBoardsChanged();
      this.client.add(boardMagnet(name, newInfoHash), { path: board.dir }, (torrent) => {
        board.torrent = torrent;
        this._scanFiles(board);
        torrent.on('done', () => {
          this.emit('log', `board "${name}" fully synced`);
          this._scanFiles(board);
          this._emitBoardsChanged();
        });
        torrent.on('wire', () => this.emit('log', `peer connected to "${name}" swarm`));
        this._emitBoardsChanged();
      });
    } finally {
      board._migrating = false;
    }
  }

  /* ---------------- reporting to the UI ---------------- */

  _emitBoardsChanged() {
    this.emit('boards-changed', this.getBoardsSnapshot());
  }

  getBoardsSnapshot() {
    const list = [];
    for (const [name, b] of this.boards) {
      this._scanFiles(b);
      const files = [...b.files.entries()]
        .map(([rel, meta]) => ({
          name: rel,
          size: meta.length,
          downloadedAt: meta.downloadedAt || Date.now(),
          progress: b.torrent ? b.torrent.progress : 1
        }))
        .sort((a, z) => z.downloadedAt - a.downloadedAt); // newest first
      list.push({
        name,
        infoHash: b.infoHash,
        fileCount: files.length,
        totalBytes: files.reduce((s, f) => s + f.size, 0),
        peers: b.torrent ? b.torrent.numPeers : 0,
        progress: b.torrent ? b.torrent.progress : 1,
        files
      });
    }
    return list;
  }

  destroy() {
    this._destroyed = true;
    if (this.beaconTimer) clearInterval(this.beaconTimer);
    if (this.socket) try { this.socket.close(); } catch (_) {}
    if (this.client) try { this.client.destroy(); } catch (_) {}
  }
}

module.exports = { BitBoardEngine, boardInfoHash, isImageFile, DATA_DIR, BOARDS_DIR };