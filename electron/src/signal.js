'use strict';

/**
 * Internet rendezvous for BitBoard.
 *
 * WHY THIS EXISTS
 * Every device seeds its OWN torrent for a board, and that torrent's infohash
 * is different on every device (it covers mtimes, creation date, ...). Trackers
 * and the DHT can only match peers that ask for the SAME infohash, so two
 * devices on different networks could never find each other's torrent. The
 * only place infohashes were exchanged was the LAN UDP beacon - which is why
 * sync worked on one Wi-Fi and never across the internet.
 *
 * This module gives devices a way to tell each other "for board X, my current
 * torrent is <infohash>" over the internet. Once a device knows a peer's
 * infohash, the normal BitTorrent machinery (trackers, DHT, PEX, UPnP, ...)
 * finds and connects to that peer, and the existing merge code does the rest.
 *
 * HOW
 * A tiny pub/sub relay speaking the ntfy protocol (https://ntfy.sh by
 * default, override with BITBOARD_SIGNAL_URL to use your own server). One topic
 * per board; the topic is derived from a hash of the board name, so only
 * people who know the board name can find it. Only infohashes are sent - never
 * images, IPs or file names.
 *
 * The wire format is mirrored in android/.../engine/SignalChannel.kt.
 */

const crypto = require('crypto');

const DEFAULT_RELAY = 'https://ntfy.sh';

/** Relay topic for a board. MUST match Protocol.signalTopic() on Android. */
function signalTopic(boardName) {
  const h = crypto.createHash('sha256')
    .update('bitboard-signal-v1:' + String(boardName).trim().toLowerCase())
    .digest('hex');
  return 'bitboard-' + h.slice(0, 32);
}

class SignalChannel {
  /**
   * @param {{baseUrl?: string, log?: (s: string) => void}} [opts]
   */
  constructor(opts = {}) {
    this.baseUrl = String(opts.baseUrl || process.env.BITBOARD_SIGNAL_URL || DEFAULT_RELAY).replace(/\/+$/, '');
    this.log = opts.log || (() => { });
    this._subs = new Set();
    this._closed = false;
  }

  /** Publish one JSON-serialisable object to a topic. */
  async publish(topic, obj) {
    const res = await fetch(`${this.baseUrl}/${topic}`, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: JSON.stringify(obj),
      signal: AbortSignal.timeout(15000)
    });
    if (!res.ok) throw new Error(`signal relay answered HTTP ${res.status}`);
  }

  /**
   * Subscribe to a topic. Reconnects forever with back-off. `onMessage`
   * receives each parsed message object (the JSON we published).
   * Returns { close() }.
   */
  subscribe(topic, onMessage, opts = {}) {
    const sub = { closed: false, ctrl: null, lastId: null, wake: null };
    this._subs.add(sub);
    let backoff = 2000;

    const run = async () => {
      while (!sub.closed && !this._closed) {
        const ctrl = new AbortController();
        sub.ctrl = ctrl;
        let watchdog = null;
        try {
          const since = sub.lastId || opts.since || '30m';
          const res = await fetch(
            `${this.baseUrl}/${topic}/json?since=${encodeURIComponent(since)}`,
            { signal: ctrl.signal }
          );
          if (!res.ok) {
            if (res.status === 400) sub.lastId = null; // unknown/expired id
            throw new Error(`signal relay answered HTTP ${res.status}`);
          }
          backoff = 2000;

          // The relay sends a keepalive every ~45 s. Silence for 2 minutes means
          // a dead connection (very common when a phone switches networks).
          let lastData = Date.now();
          watchdog = setInterval(() => {
            if (Date.now() - lastData > 120000) ctrl.abort();
          }, 30000);

          const reader = res.body.getReader();
          const dec = new TextDecoder();
          let buf = '';
          for (; ;) {
            const { done, value } = await reader.read();
            if (done) break;
            lastData = Date.now();
            buf += dec.decode(value, { stream: true });
            let nl;
            while ((nl = buf.indexOf('\n')) >= 0) {
              const line = buf.slice(0, nl).trim();
              buf = buf.slice(nl + 1);
              if (line) this._onLine(sub, line, onMessage);
            }
          }
        } catch (err) {
          if (sub.closed || this._closed) return;
          this.log(`signal relay: ${err.message} - retrying`);
        } finally {
          clearInterval(watchdog);
        }
        if (sub.closed || this._closed) return;
        await new Promise((resolve) => {
          const t = setTimeout(resolve, backoff);
          sub.wake = () => { clearTimeout(t); resolve(); };
        });
        sub.wake = null;
        backoff = Math.min(backoff * 2, 60000);
      }
    };
    run();

    return {
      close: () => {
        sub.closed = true;
        this._subs.delete(sub);
        try { if (sub.ctrl) sub.ctrl.abort(); } catch (_) { }
        if (sub.wake) sub.wake();
      }
    };
  }

  _onLine(sub, line, onMessage) {
    let ev;
    try { ev = JSON.parse(line); } catch (_) { return; }
    if (!ev || ev.event !== 'message') return;
    if (ev.id) sub.lastId = ev.id;
    let payload;
    try { payload = JSON.parse(ev.message); } catch (_) { return; }
    if (payload && typeof payload === 'object') {
      try { onMessage(payload); } catch (_) { /* a bad handler must not kill the stream */ }
    }
  }

  close() {
    this._closed = true;
    for (const s of [...this._subs]) {
      s.closed = true;
      try { if (s.ctrl) s.ctrl.abort(); } catch (_) { }
      if (s.wake) s.wake();
    }
    this._subs.clear();
  }
}

module.exports = { SignalChannel, signalTopic, DEFAULT_RELAY };
