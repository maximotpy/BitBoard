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

// BITBOARD_DATA_DIR (or the `dataDir` constructor option) lets tests and
// multiple instances on one machine use separate data folders.
const DATA_DIR = process.env.BITBOARD_DATA_DIR || path.join(__dirname, '..', 'data');
const BOARDS_DIR = path.join(DATA_DIR, 'boards');

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
const METADATA_TIMEOUT_MS = 45000;   // give up fetching a peer's torrent metadata
const STALL_TIMEOUT_MS = 90000;      // give up when a transfer makes no progress
const RETRY_COOLDOWN_MS = 20000;     // don't hammer a peer whose fetch just failed
const LAN_PEER_TTL_MS = 10000;       // a LAN peer counts as present for this long
const DISCOVERED_TTL_MS = 30000;     // a discovered (not joined) board stays offered this long

const IMAGE_EXT = new Set([
  '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.svg', '.avif', '.ico', '.tif', '.tiff'
]);


function isImageFile(name) {
  return IMAGE_EXT.has(path.extname(name).toLowerCase());
}

function sha1(s) {
  return crypto.createHash('sha1').update(s).digest('hex');
}

/**
 * IPv4 interfaces that can carry LAN traffic. Multicast/broadcast must be
 * joined and sent PER interface: on a PC with Hyper-V / WSL / VPN / VirtualBox
 * adapters the OS default interface is often a virtual one, so a beacon sent
 * or a group joined "on the default" never touches the real Wi-Fi/Ethernet.
 */
function lanInterfaces() {
  const out = [];
  const all = os.networkInterfaces();
  for (const name of Object.keys(all)) {
    for (const a of all[name] || []) {
      const v4 = a.family === 'IPv4' || a.family === 4;
      if (!v4 || a.internal) continue;
      if (a.address.startsWith('169.254.')) continue; // link-local, no DHCP
      out.push({ name, address: a.address, netmask: a.netmask });
    }
  }
  return out;
}

/** Directed broadcast address (e.g. 192.168.1.255) for an address/netmask. */
function broadcastOf(address, netmask) {
  try {
    const a = address.split('.').map(Number);
    const m = netmask.split('.').map(Number);
    if (a.length !== 4 || m.length !== 4) return null;
    return a.map((o, i) => (o | (~m[i] & 255)) & 255).join('.');
  } catch (_) { return null; }
}

function sha1Buf(buf) {
  return crypto.createHash('sha1').update(buf).digest('hex');
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
  /**
   * @param {{dataDir?: string}} [opts] dataDir overrides the default data
   *   folder (used by tests and to run several instances on one machine).
   */
  constructor(opts = {}) {
    super();
    this.dataDir = opts.dataDir || DATA_DIR;
    this.boardsDir = path.join(this.dataDir, 'boards');
    this.torrentsDir = path.join(this.dataDir, 'torrents');
    this.stagingDir = path.join(this.dataDir, 'staging');
    this.stateFile = path.join(this.dataDir, 'state.json');
    for (const d of [this.dataDir, this.boardsDir, this.torrentsDir]) {
      try { fs.mkdirSync(d, { recursive: true }); } catch (_) { /* exists */ }
    }
    // Leftovers from a crash / previous run.
    try { fs.rmSync(this.stagingDir, { recursive: true, force: true }); } catch (_) { }
    fs.mkdirSync(this.stagingDir, { recursive: true });

    this.client = null;
    this.boards = new Map();      // name -> board (see _newBoard)
    this.state = this._loadState();
    this.socket = null;
    this.beaconTimer = null;
    this._destroyed = false;
    this._pending = new Map();    // name -> Promise (dedupes concurrent create/join)
    this._discovered = new Map(); // name -> last beacon time (boards seen on the LAN, NOT joined)
    this._joinedIfaces = new Set();
    this._repliedAt = new Map();  // ip -> last unicast reply time
    this._beaconBusy = false;
    this._bound = false;

    // Async bootstrap: load ESM deps, create the torrent client, then
    // restore the boards saved from the previous run.
    this._ready = (async () => {
      await loadEsmDeps();
      // lsd: LAN peers find each other via Local Service Discovery.
      // utp:false: TCP only, so the firewall only has to allow ONE port/protocol.
      this.client = new WebTorrent({ dht: true, lsd: true, utp: false, maxConns: 200 });
      this.client.on('error', (err) => this.emit('log', 'client error: ' + err.message));
      this.client.on('torrent', () => this._emitBoardsChanged());
      await this._restoreBoards();
    })();
  }

  /** Resolves once the ESM deps and torrent client are available. */
  ready() { return this._ready; }

  _newBoard(name, createdAt) {
    return {
      name,
      dir: this.boardDir(name),
      infoHash: boardInfoHash(name), // replaced by the real hash after publishing
      torrent: null,                 // OUR torrent: the folder we seed
      files: new Map(),
      createdAt: createdAt || Date.now(),
      seen: new Set(),               // peer infohashes already merged (or identical)
      merging: new Set(),            // peer infohashes being fetched right now
      retryAt: new Map(),            // peer infohash -> earliest retry time
      lanPeers: new Map(),           // LAN peerId -> last beacon time
      _chain: Promise.resolve()      // serialises (re)publishing
    };
  }

  /* ---------------- persistence ---------------- */

  _loadState() {
    try {
      return JSON.parse(fs.readFileSync(this.stateFile, 'utf8'));
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
    try { fs.writeFileSync(this.stateFile, JSON.stringify(out, null, 2)); } catch (_) { }
  }

  /**
   * Re-open the boards saved in state.json so they reappear after a restart.
   * Every board is re-seeded from its folder; missing images arrive through
   * the normal LAN-beacon merge, so there is no separate "join" path.
   */
  async _restoreBoards() {
    const saved = (this.state && this.state.boards) || {};
    const names = Object.keys(saved);
    if (!names.length) return;
    this.emit('log', `restoring ${names.length} saved board(s)…`);

    for (const name of names) {
      if (this.boards.has(name)) continue;
      fs.mkdirSync(this.boardDir(name), { recursive: true });
      const board = this._newBoard(name, saved[name].createdAt);
      this.boards.set(name, board);
      this._scanFiles(board);
      this._emitBoardsChanged();
      try {
        await this._publishLocked(board);
        this.emit('log', `restored board "${name}" — seeding`);
      } catch (err) {
        // Keep the board listed; the next image / beacon retries.
        this.emit('log', `could not fully restore "${name}": ${err.message}`);
      }
    }
    this._saveState();
  }

  /* ---------------- board lifecycle ---------------- */

  boardDir(name) {
    return path.join(this.boardsDir, sha1(name.trim().toLowerCase()).slice(0, 16));
  }

  /**
   * Create OR join a board. They are the same operation: make the local
   * folder, seed it, and let LAN beacons bring in whatever peers already have
   * (see _handlePeerBoard). Concurrent calls for one name share one promise.
   */
  _ensureBoard(name, verb) {
    name = String(name || '').trim();
    if (!name) return Promise.reject(new Error('Board name required'));
    if (this._pending.has(name)) return this._pending.get(name);

    const p = (async () => {
      await this._ready;
      if (this.boards.has(name)) return this.boards.get(name);

      fs.mkdirSync(this.boardDir(name), { recursive: true });
      const board = this._newBoard(name);
      this.boards.set(name, board);
      this._emitBoardsChanged();   // show the board in the UI right away

      try {
        await this._publishLocked(board);
      } catch (err) {
        this.boards.delete(name);
        this._emitBoardsChanged();
        this.emit('log', `failed to ${verb === 'joined' ? 'join' : 'create'} board "${name}": ${err.message}`);
        throw err;
      }
      this._saveState();
      this._emitBoardsChanged();
      this._discovered.delete(name);   // no longer just "discovered" — we have it
      this._emitDiscoveredChanged();
      this.emit('log', `board "${name}" ${verb} (${board.infoHash.slice(0, 12)}…)`);
      return board;
    })().finally(() => this._pending.delete(name));

    this._pending.set(name, p);
    return p;
  }

  createBoard(name) { return this._ensureBoard(name, 'created'); }
  joinBoard(name) { return this._ensureBoard(name, 'joined'); }

  /** Serialise publishes per board so two callers never race the re-seed. */
  _publishLocked(board) {
    const run = () => this._publishBoard(board);
    board._chain = board._chain.then(run, run);
    return board._chain;
  }

  /**
   * Publish (or re-publish) the board torrent from the local folder.
   * The torrent is rebuilt whenever files change so peers receive updates.
   */
  async _publishBoard(board) {
    const entries = fs.readdirSync(board.dir).filter(f => isImageFile(f));

    // Drop the old hidden manifest written by earlier versions.
    try { fs.rmSync(path.join(board.dir, LEGACY_MANIFEST_NAME), { force: true }); } catch (_) { }

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
      let torrent;
      const finish = (err, t) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (err) {
          if (board.torrent === t) board.torrent = null;
          try { if (t) t.destroy(); } catch (_) { }
          reject(err);
        } else {
          resolve(t);
        }
      };
      const timer = setTimeout(
        () => finish(new Error('Timed out while publishing the board torrent'), torrent),
        PUBLISH_TIMEOUT_MS
      );

      try {
        // The torrent's `name` MUST equal the board folder's basename:
        // WebTorrent resolves seeded files as dirname(boardDir)/<info.name>/...
        torrent = this.client.seed(board.dir, {
          name: path.basename(board.dir),
          createdBy: 'BitBoard 1.0',
          announce: TRACKERS
        });
      } catch (err) {
        return finish(err, null);
      }
      board.torrent = torrent;

      // 'ready' instead of seed()'s callback: with DHT on, that callback waits
      // for a DHT announce that may never come on restricted networks.
      torrent.once('error', (err) => finish(err, torrent));
      torrent.once('ready', () => {
        board.infoHash = torrent.infoHash;
        board.seen.add(torrent.infoHash);
        try {
          fs.writeFileSync(path.join(this.torrentsDir, torrent.infoHash + '.torrent'), torrent.torrentFile);
        } catch (_) { }
        this._scanFiles(board);
        torrent.on('wire', () => this.emit('log', `peer connected to "${board.name}" swarm`));
        this._emitBoardsChanged();
        finish(null, torrent);
      });
    });
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
    await this._publishLocked(board);
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
    } catch (_) { }
  }

  /* ---------------- merging a peer's board ---------------- */

  /**
   * A peer announced board `name` with torrent `hash` at addr:port.
   *
   * Two devices NEVER share an infohash for "the same" board (the hash covers
   * file mtimes, the creation date, the torrent creator, …), so the old
   * "switch to the peer's hash" logic made both sides drop their own torrent
   * and swap forever. Now every device keeps seeding ITS OWN folder and pulls
   * only the images it is missing from the peer's torrent (a MERGE), then
   * re-publishes. Each distinct peer hash is merged at most once, so the
   * exchange always converges.
   */
  _handlePeerBoard(name, hash, addr, port, peerId) {
    const board = this.boards.get(name);
    if (!board) {
      // Board we don't have: remember it so the UI can offer it, but NEVER
      // auto-join — a board only lands on this device when the user asks for
      // it (otherwise every board on the LAN would appear here on first run).
      const isNew = !this._discovered.has(name);
      this._discovered.set(name, Date.now());
      if (isNew) this._emitDiscoveredChanged();
      return;
    }
    if (peerId) board.lanPeers.set(peerId, Date.now());

    // Same swarm already: just make sure we are directly connected.
    if (hash === board.infoHash) {
      this._connectDirect(board.torrent, addr, port);
      return;
    }
    if (board.seen.has(hash) || board.merging.has(hash)) return;
    if ((board.retryAt.get(hash) || 0) > Date.now()) return;

    this._mergeFromPeer(board, hash, addr, port).catch((err) => {
      board.retryAt.set(hash, Date.now() + RETRY_COOLDOWN_MS);
      this.emit('log', `sync of "${board.name}" from ${addr} failed: ${err.message}`);
    });
  }

  /** Connect straight to a peer we discovered on the LAN (no tracker/DHT). */
  _connectDirect(torrent, addr, port) {
    if (!torrent || torrent.destroyed || !(port > 0)) return;
    const go = () => { try { torrent.addPeer(`${addr}:${port}`); } catch (_) { } };
    if (torrent.infoHash) go(); else torrent.once('infoHash', go);
  }

  async _mergeFromPeer(board, hash, addr, port) {
    board.merging.add(hash);
    this._emitBoardsChanged();
    const stageRoot = path.join(this.stagingDir, hash);
    try {
      fs.rmSync(stageRoot, { recursive: true, force: true });
      fs.mkdirSync(stageRoot, { recursive: true });

      this.emit('log', `syncing "${board.name}" from ${addr}…`);
      const staged = await this._fetchStaged(board, hash, stageRoot, addr, port);
      const added = this._importStaged(board, staged);
      board.seen.add(hash);

      if (added > 0) {
        this._scanFiles(board);
        await this._publishLocked(board);   // new content => new hash of our own
        this._saveState();
        this.emit('log', `board "${board.name}": received ${added} new image(s)`);
      }
    } finally {
      board.merging.delete(hash);
      try { fs.rmSync(stageRoot, { recursive: true, force: true }); } catch (_) { }
      this._emitBoardsChanged();
    }
  }

  /**
   * Download ONLY the images we don't have from the peer's torrent into a
   * staging folder. Resolves with [{ name, full }] once they are complete.
   */
  _fetchStaged(board, hash, stageRoot, addr, port) {
    return new Promise((resolve, reject) => {
      let t = null;
      let poll = null;
      let settled = false;
      let metaTimer = null;

      const cleanup = () => {
        clearInterval(poll);
        clearTimeout(metaTimer);
        return new Promise((res) => {
          try { if (t && !t.destroyed) t.destroy(() => res()); else res(); } catch (_) { res(); }
        });
      };
      const fail = (err) => {
        if (settled) return;
        settled = true;
        cleanup().then(() => reject(err));
      };
      const succeed = (list) => {
        if (settled) return;
        settled = true;
        cleanup().then(() => resolve(list));   // files are closed before we copy
      };

      try {
        // deselect:true => nothing downloads until we choose the files below.
        t = this.client.add(boardMagnet(board.name, hash), { path: stageRoot, deselect: true });
      } catch (err) { return reject(err); }

      t.once('error', fail);
      metaTimer = setTimeout(() => fail(new Error('timed out fetching torrent metadata')), METADATA_TIMEOUT_MS);

      const connect = () => this._connectDirect(t, addr, port);
      if (t.infoHash) connect(); else t.once('infoHash', connect);

      t.once('metadata', () => {
        clearTimeout(metaTimer);
        const needed = [];
        for (const f of t.files) {
          const parts = f.path.split(/[\\/]/);          // [<torrent name>, <file>]
          if (parts.length !== 2) continue;             // board folders are flat
          const fname = parts[1];
          if (!isImageFile(fname)) continue;            // skips the manifest too
          let have = false;
          try { have = fs.statSync(path.join(board.dir, fname)).size === f.length; } catch (_) { }
          if (!have) needed.push(f);
        }
        if (!needed.length) return succeed([]);

        needed.forEach(f => f.select());
        let lastBytes = 0;
        let lastMove = Date.now();
        poll = setInterval(() => {
          if (t.destroyed) return;
          if (needed.every(f => f.done)) {
            return succeed(needed.map(f => ({
              name: path.basename(f.path),
              full: path.join(stageRoot, f.path)
            })));
          }
          // A dropped connection is retried by WebTorrent, but re-adding the
          // LAN peer is free and helps if the first attempt raced its startup.
          if (t.numPeers === 0) this._connectDirect(t, addr, port);
          if (t.downloaded > lastBytes) { lastBytes = t.downloaded; lastMove = Date.now(); }
          else if (Date.now() - lastMove > STALL_TIMEOUT_MS) fail(new Error('transfer stalled'));
        }, 500);
      });
    });
  }

  /** Copy staged images into the board folder; returns how many were new. */
  _importStaged(board, staged) {
    let added = 0;
    for (const item of staged) {
      let data;
      try { data = fs.readFileSync(item.full); } catch (_) { continue; }
      const digest = sha1Buf(data);

      // Already have these exact bytes (under any name)? Nothing to do.
      const local = fs.readdirSync(board.dir).filter(isImageFile);
      const dup = local.some((n) => {
        try {
          const p = path.join(board.dir, n);
          return fs.statSync(p).size === data.length && sha1Buf(fs.readFileSync(p)) === digest;
        } catch (_) { return false; }
      });
      if (dup) continue;

      let name = path.basename(item.name);
      let dest = path.join(board.dir, name);
      if (fs.existsSync(dest)) {
        // Same name, different content: keep BOTH. The suffix comes from the
        // content hash so every device names the copy identically.
        const ext = path.extname(name);
        const stem = ext ? name.slice(0, -ext.length) : name;
        name = `${stem}-${digest.slice(0, 6)}${ext}`;
        dest = path.join(board.dir, name);
        if (fs.existsSync(dest)) continue;
      }
      fs.writeFileSync(dest, data);
      added++;
    }
    return added;
  }

  /* ---------------- LAN discovery (UDP multicast + broadcast) ---------------- */

  startDiscovery() {
    if (this.socket) return;
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.socket = sock;

    sock.on('error', (err) => this.emit('log', 'discovery error: ' + err.message));
    sock.on('message', (buf, rinfo) => {
      try { this._onBeacon(JSON.parse(buf.toString('utf8')), rinfo); } catch (_) { }
    });

    // IMPORTANT: everything that touches multicast state (addMembership,
    // setMulticastInterface, send) must wait for the bind to finish. Joining a
    // group on a not-yet-bound socket makes libuv silently bind it to a random
    // port, and the real bind then fails with EINVAL.
    sock.bind(MULTICAST_PORT, () => {
      if (this._destroyed) return;
      this._bound = true;
      try { sock.setBroadcast(true); } catch (_) { }
      try { sock.setMulticastTTL(1); } catch (_) { }
      this._ensureMemberships(lanInterfaces());
      const names = lanInterfaces().map(i => `${i.name} ${i.address}`).join(', ') || 'no LAN interface found';
      this.emit('log', `LAN discovery active on ${MULTICAST_ADDR}:${MULTICAST_PORT} [${names}]`);
      this.beaconTimer = setInterval(() => this._sendBeacon(), BEACON_INTERVAL_MS);
      this._sendBeacon();
    });
  }

  /** Join the multicast group on EVERY LAN interface (and re-check each tick,
   *  so Wi-Fi that connects after start-up, or a changed IP, is picked up). */
  _ensureMemberships(ifaces) {
    if (!this.socket) return;
    for (const i of ifaces) {
      if (this._joinedIfaces.has(i.address)) continue;
      try {
        this.socket.addMembership(MULTICAST_ADDR, i.address);
        this._joinedIfaces.add(i.address);
      } catch (e) {
        if (e && e.code === 'EADDRINUSE') this._joinedIfaces.add(i.address); // already a member
      }
    }
    if (!ifaces.length && !this._joinedIfaces.has('default')) {
      try { this.socket.addMembership(MULTICAST_ADDR); this._joinedIfaces.add('default'); } catch (_) { }
    }
  }

  peerId() {
    if (!this._peerId) {
      const idFile = path.join(this.dataDir, 'peer-id');
      try { this._peerId = fs.readFileSync(idFile, 'utf8').trim(); } catch (_) { }
      if (!this._peerId) {
        this._peerId = crypto.randomBytes(8).toString('hex');
        try { fs.writeFileSync(idFile, this._peerId); } catch (_) { }
      }
    }
    return this._peerId;
  }

  _beaconBuffer(isReply) {
    return Buffer.from(JSON.stringify({
      app: 'bitboard',
      v: 2,
      peerId: this.peerId(),
      host: os.hostname(),
      // TCP port our torrent client listens on: lets the receiver connect
      // straight to us instead of depending on trackers / DHT / hairpin NAT.
      port: (this.client && this.client.torrentPort) || 0,
      reply: !!isReply,
      boards: [...this.boards.values()].map(b => ({ name: b.name, infoHash: b.infoHash }))
    }));
  }

  _udpSend(buf, port, address) {
    return new Promise((resolve) => {
      try { this.socket.send(buf, 0, buf.length, port, address, () => resolve()); }
      catch (_) { resolve(); }
    });
  }

  /**
   * Send the beacon on every interface, both to the multicast group and to the
   * subnet broadcast address. Routers/APs that filter multicast to Wi-Fi
   * clients usually still pass broadcasts. Sends are sequential because
   * setMulticastInterface() is socket-wide state.
   */
  async _sendBeacon() {
    if (!this.socket || !this._bound || this._destroyed || this._beaconBusy) return;
    this._beaconBusy = true;
    try {
      const buf = this._beaconBuffer(false);
      const ifaces = lanInterfaces();
      this._ensureMemberships(ifaces);
      if (!ifaces.length) {
        await this._udpSend(buf, MULTICAST_PORT, MULTICAST_ADDR);
        return;
      }
      for (const i of ifaces) {
        try { this.socket.setMulticastInterface(i.address); } catch (_) { continue; }
        await this._udpSend(buf, MULTICAST_PORT, MULTICAST_ADDR);
        const bc = broadcastOf(i.address, i.netmask);
        if (bc) await this._udpSend(buf, MULTICAST_PORT, bc);
      }
    } finally {
      this._beaconBusy = false;
    }
  }

  _onBeacon(msg, rinfo) {
    if (!msg || msg.app !== 'bitboard') return;
    if (msg.peerId === this.peerId()) return; // our own beacon

    // Expire discovered boards we haven't joined (peer may have left).
    const now = Date.now();
    for (const [n, ts] of this._discovered) {
      if (now - ts > DISCOVERED_TTL_MS) { this._discovered.delete(n); this._emitDiscoveredChanged(); }
    }

    // Answer with a direct UNICAST beacon. If multicast only works in one
    // direction (common: PC -> phone is filtered, phone -> PC passes) this
    // still lets the other side discover us. Replies are never replied to.
    if (!msg.reply && this.socket && this._bound && !this._destroyed) {
      const now = Date.now();
      if (now - (this._repliedAt.get(rinfo.address) || 0) > 2000) {
        this._repliedAt.set(rinfo.address, now);
        this._udpSend(this._beaconBuffer(true), MULTICAST_PORT, rinfo.address);
      }
    }

    const port = Number(msg.port) || 0;
    for (const b of (msg.boards || [])) {
      if (!b || typeof b.name !== 'string' || !b.name.trim()) continue;
      if (!/^[0-9a-f]{40}$/i.test(String(b.infoHash || ''))) continue;
      this._handlePeerBoard(b.name, b.infoHash.toLowerCase(), rinfo.address, port, msg.peerId);
    }
  }

  /* ---------------- reporting to the UI ---------------- */

  _emitBoardsChanged() {
    this.emit('boards-changed', this.getBoardsSnapshot());
  }

  /** Boards seen in LAN beacons that this device has NOT joined. Entries
   *  expire when beacons stop arriving; joining one removes it. */
  getDiscoveredSnapshot() {
    const now = Date.now();
    let changed = false;
    for (const [name, ts] of this._discovered) {
      if (now - ts > DISCOVERED_TTL_MS) { this._discovered.delete(name); changed = true; }
    }
    if (changed) this._emitDiscoveredChanged();
    return [...this._discovered.keys()];
  }

  _emitDiscoveredChanged() {
    this.emit('discovered-changed', [...this._discovered.keys()]);
  }

  getBoardsSnapshot() {
    const list = [];
    const now = Date.now();
    for (const [name, b] of this.boards) {
      this._scanFiles(b);
      const files = [...b.files.entries()]
        .map(([rel, meta]) => ({
          name: rel,
          size: meta.length,
          downloadedAt: meta.downloadedAt || Date.now(),
          progress: 1
        }))
        .sort((a, z) => z.downloadedAt - a.downloadedAt); // newest first
      let lan = 0;
      for (const [id, ts] of b.lanPeers) {
        if (now - ts > LAN_PEER_TTL_MS) b.lanPeers.delete(id); else lan++;
      }
      list.push({
        name,
        infoHash: b.infoHash,
        fileCount: files.length,
        totalBytes: files.reduce((s, f) => s + f.size, 0),
        // Peers we are connected to, or LAN devices that announced this board
        // in the last few seconds — whichever is larger.
        peers: Math.max(b.torrent ? b.torrent.numPeers : 0, lan),
        progress: 1,
        syncing: b.merging.size > 0,
        files
      });
    }
    return list;
  }

  destroy() {
    this._destroyed = true;
    if (this.beaconTimer) clearInterval(this.beaconTimer);
    if (this.socket) try { this.socket.close(); } catch (_) { }
    if (this.client) try { this.client.destroy(); } catch (_) { }
    try { fs.rmSync(this.stagingDir, { recursive: true, force: true }); } catch (_) { }
  }
}

module.exports = { BitBoardEngine, boardInfoHash, isImageFile, DATA_DIR, BOARDS_DIR };
