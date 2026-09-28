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
 *  - Torrent name: "bitboard-<boardName>".
 *  - LAN beacon: JSON {app:"bitboard", peerId, host, boards:[{name, infoHash}]}
 *    on UDP multicast 239.255.66.66:45666 every 3 s.
 */
object Protocol {
    const val MULTICAST_ADDR = "239.255.66.66"
    const val MULTICAST_PORT = 45666
    const val BEACON_INTERVAL_MS = 3000L
    const val MANIFEST_NAME = "bitboard-manifest.json"
    const val LEGACY_MANIFEST_NAME = ".bitboard-manifest.json"
    const val TORRENT_PREFIX = "bitboard-"
    const val HASH_PREFIX = "bitboard-board-v1:"

    val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.tracker.cl:1337/announce",
        "http://tracker.opentrackr.org:1337/announce"
    )

    val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "avif", "ico", "tif", "tiff"
    )

    fun isImageFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    fun sha1Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Deterministic info-hash seed for a board name (desktop parity). */
    fun boardInfoHash(boardName: String): String =
        sha1Hex(HASH_PREFIX + boardName.trim().lowercase())

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
