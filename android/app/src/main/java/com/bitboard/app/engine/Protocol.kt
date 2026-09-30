package com.bitboard.app.engine

import java.io.File
import java.security.MessageDigest

/**
 * BitBoard protocol constants and helpers, byte-for-byte compatible with the
 * desktop (Electron) engine in src/engine.js.
 *
 * Wire protocol:
 *  - Board folder name: sha1(name.trim().toLowerCase()) hex, first 16 chars.
 *  - Manifest: "bitboard-manifest.json" (visible, NOT dot-prefixed —
 *    create-torrent silently drops hidden files) with shape
 *    { board, createdAt, files: [{name, size, mtime}] }.
 *  - Torrent name: the board folder's basename (libtorrent/WebTorrent both
 *    derive it from the folder).
 *  - LAN beacon: JSON {app:"bitboard", v:2, peerId, host, port, reply,
 *    boards:[{name, infoHash}]} on UDP multicast + subnet broadcast
 *    239.255.66.66:45666 every 3 s. `port` is the sender's BitTorrent TCP
 *    port so receivers can connect directly without trackers/DHT.
 *  - Infohashes are NOT shared between devices (they cover mtimes, creation
 *    date, …). Devices instead MERGE: fetch the peer's torrent, copy the
 *    images they lack, republish their own folder.
 */
object Protocol {
    const val MULTICAST_ADDR = "239.255.66.66"
    const val MULTICAST_PORT = 45666
    const val BEACON_INTERVAL_MS = 3000L
    const val MANIFEST_NAME = "bitboard-manifest.json"
    const val LEGACY_MANIFEST_NAME = ".bitboard-manifest.json"
    const val TORRENT_PREFIX = "bitboard-"
    const val HASH_PREFIX = "bitboard-board-v1:"

    const val METADATA_TIMEOUT_MS = 90_000L   // give up fetching a peer's torrent metadata (DHT lookups over the internet are slower than LAN)
    const val STALL_TIMEOUT_MS = 90_000L      // give up when a transfer makes no progress
    const val RETRY_COOLDOWN_MS = 20_000L     // don't hammer a peer whose fetch just failed
    const val LAN_PEER_TTL_MS = 10_000L       // a LAN peer counts as present for this long
    const val DISCOVERED_TTL_MS = 30_000L     // a discovered (not joined) board stays offered this long

    // Internet rendezvous (see SignalChannel.kt / src/signal.js)
    const val SIGNAL_URL = "https://ntfy.sh"
    const val SIGNAL_HEARTBEAT_MS = 10 * 60 * 1000L   // re-announce our infohash this often
    const val SIGNAL_TTL_MS = 35 * 60 * 1000L         // a peer's announcement stays actionable this long
    const val SIGNAL_TICK_MS = 30 * 1000L             // retry unfinished merges this often
    const val SIGNAL_BATCH_MS = 1500L                 // collapse the relay backlog to the newest per peer
    const val SIGNAL_ANNOUNCE_DEBOUNCE_MS = 2000L     // coalesce bursts of re-publishes
    const val MAX_RETRY_COOLDOWN_MS = 10 * 60 * 1000L // back-off ceiling for a peer that keeps failing

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    fun isInfoHash(s: String): Boolean = HEX40.matches(s)

    val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "http://tracker.opentrackr.org:1337/announce",
        // Extra public UDP trackers (same list as the desktop app).
        "udp://open.stealth.si:80/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce"
    )

    val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "ico", "tif", "tiff"
    )

    fun isImageFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    fun sha1Hex(s: String): String = sha1Hex(s.toByteArray(Charsets.UTF_8))

    fun sha1Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-1").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Deterministic info-hash seed for a board name (desktop parity). */
    fun boardInfoHash(boardName: String): String =
        sha1Hex(HASH_PREFIX + boardName.trim().lowercase())

    /**
     * Relay topic for a board: "bitboard-" + first 32 hex chars of
     * sha256("bitboard-signal-v1:" + lowercased name). MUST match signalTopic()
     * in src/signal.js.
     */
    fun signalTopic(boardName: String): String {
        val d = MessageDigest.getInstance("SHA-256")
            .digest(("bitboard-signal-v1:" + boardName.trim().lowercase()).toByteArray(Charsets.UTF_8))
        return "bitboard-" + d.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Board folder name: first 16 hex chars of sha1(lowercased name). */
    fun boardDirName(boardName: String): String =
        sha1Hex(boardName.trim().lowercase()).take(16)

    /**
     * Magnet URI for a board swarm. Includes tracker hints (`tr=`) so a
     * joining peer can rendezvous via the trackers even when DHT is slow or
     * blocked (desktop parity).
     */
    fun magnetUri(boardName: String, infoHash: String): String {
        val tr = TRACKERS.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
        return "magnet:?xt=urn:btih:$infoHash&dn=${TORRENT_PREFIX}" +
            java.net.URLEncoder.encode(boardName, "UTF-8") + tr
    }
}
