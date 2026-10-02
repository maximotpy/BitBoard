package com.bitboard.app.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * BitBoard P2P engine for Android, a faithful port of the desktop engine
 * (src/engine.js) on top of libtorrent4j.
 *
 * Every "board" is a folder of images published as a BitTorrent. Devices that
 * join the same board seed and leech simultaneously, so images replicate
 * automatically, cloud-like sync with no cloud.
 *
 * Discovery:
 *  - DHT + UDP trackers (cross-network)
 *  - LAN UDP multicast beacon (same-network instant discovery), identical
 *    wire format to the desktop app so the two interoperate.
 */
class BitBoardEngine(val context: Context) {

    /** UI-facing snapshot of a board. */
    data class FileMeta(
        val name: String,
        val size: Long,
        val downloadedAt: Long,
        val progress: Float
    )

    data class BoardSnapshot(
        val name: String,
        val infoHash: String,
        val fileCount: Int,
        val totalBytes: Long,
        val peers: Int,
        val progress: Float,
        val files: List<FileMeta>
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _boards = MutableStateFlow<List<BoardSnapshot>>(emptyList())
    val boards: StateFlow<List<BoardSnapshot>> = _boards

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _discovered = MutableStateFlow<List<String>>(emptyList())
    val discovered: StateFlow<List<String>> = _discovered

    private lateinit var dataDir: File
    private lateinit var boardsDir: File
    private lateinit var torrentsDir: File
    private lateinit var stateFile: File
    private lateinit var peerIdFile: File

    private val session = SessionManager()
    private val boardMap = LinkedHashMap<String, Board>() // name -> board
    private var peerId: String = ""
    private var started = false

    /**
     * Single-threaded dispatcher that serializes ALL access to libtorrent
     * native objects (session + torrent handles). libtorrent4j's Java
     * wrappers are NOT thread-safe: a TorrentHandle freed by session.remove()
     * on one thread segfaults if another thread calls status()/infoHash() on
     * it concurrently. Routing every native touch through this one thread
     * eliminates the use-after-free race that crashed create/join.
     */
    private val ltExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bitboard-lt").apply { isDaemon = true }
    }
    private val ltDispatcher = ltExecutor.asCoroutineDispatcher()

    /** Run [block] on the libtorrent thread and wait for the result. If the
     *  caller is ALREADY the lt thread (e.g. scanFiles/snapshot invoked from
     *  within a postLt block), run inline to avoid self-deadlock. Other
     *  callers are coroutines on Dispatchers.IO (never the UI thread and
     *  never libtorrent's alert thread). */
    private fun <T> onLt(block: () -> T): T =
        if (Thread.currentThread().name == "bitboard-lt") block()
        else runBlocking(ltDispatcher) { block() }

    /** Post [block] to the libtorrent thread WITHOUT waiting. Used from the
     *  alert listener, which runs on libtorrent's own internal thread ,
     *  blocking that thread (onLt) would deadlock against session.* calls
     *  executing on the lt thread. */
    private fun postLt(block: () -> Unit) {
        ltExecutor.execute {
            try { block() } catch (_: Throwable) {}
        }
    }

    /** Internal mutable board record. */
    private class Board(
        val name: String,
        val dir: File,
        var infoHash: String,
        var handle: TorrentHandle? = null,
        val files: LinkedHashMap<String, FileMeta> = LinkedHashMap(),
        var createdAt: Long = System.currentTimeMillis(),
        var migrating: Boolean = false
    )

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    fun start() {
        if (started) return
        started = true

        dataDir = File(context.filesDir, "data").apply { mkdirs() }
        boardsDir = File(dataDir, "boards").apply { mkdirs() }
        torrentsDir = File(dataDir, "torrents").apply { mkdirs() }
        stateFile = File(dataDir, "state.json")
        peerIdFile = File(dataDir, "peer-id")

        peerId = try {
            peerIdFile.readText().trim().ifEmpty { newPeerId() }
        } catch (_: Exception) {
            newPeerId()
        }.also { id ->
            try { peerIdFile.writeText(id) } catch (_: Exception) {}
        }

        scope.launch {
            startSession()
            restoreBoards()
            LanDiscovery(this@BitBoardEngine).start()
        }
    }

    private fun newPeerId(): String =
        UUID.randomUUID().toString().replace("-", "").take(16)

    private suspend fun startSession() = withContext(Dispatchers.IO) {
        val sp = SettingsPack().apply {
            setEnableDht(true)
            setEnableLsd(true)
            listenInterfaces("0.0.0.0:6881")
            connectionsLimit(200)
        }
        val params = SessionParams(sp)
        // Use the POSIX disk-I/O backend, NOT the default mmap one. The
        // default (memory-mapped) disk I/O is unstable on Android: the kernel
        // can reclaim or fail to fault in mmap'd pages on app-private storage,
        // producing SIGSEGV / SIGBUS (BUS_ADRALN) on the disk thread ~10s into
        // seeding, exactly the crash we saw. POSIX plain file I/O avoids it.
        params.setPosixDiskIO()
        session.addListener(alertListener)
        session.start(params)
        log("session started (DHT + LSD)")
    }

    fun stop() {
        if (!started) return
        started = false
        try { session.stop() } catch (_: Exception) {}
    }

    /* ------------------------------------------------------------------ */
    /* alerts                                                              */
    /* ------------------------------------------------------------------ */

    private val alertListener = object : AlertListener {
        override fun types(): IntArray? = null // all alerts

        override fun alert(a: Alert<*>) {
            // This callback runs on libtorrent's internal thread for EVERY
            // alert type. Any exception escaping it crashes the process, so
            // everything here must be guarded.
            try {
                when (a) {
                    is AddTorrentAlert -> onTorrentAdded(a)
                    is MetadataReceivedAlert -> log("metadata received")
                    is TorrentFinishedAlert -> {
                        val h = safeHandle(a) ?: return
                        postLt {
                            val b = boardByHandle(h)
                            if (b != null) {
                                log("board \"${b.name}\" fully synced")
                                scanFiles(b)
                                publishBoards()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log("alert error: ${e.message}")
            }
        }
    }

    /** Returns the alert's torrent handle, or null if it cannot be read.
     *  NOTE: a handle obtained from an alert is always freshly valid here
     *  (libtorrent hands it to us); we must NOT call isValid() on handles we
     *  cached ourselves, because after session.remove() the native object is
     *  freed and isValid() dereferences it -> SIGSEGV. */
    private fun safeHandle(a: Alert<*>): TorrentHandle? =
        try {
            (a as? org.libtorrent4j.alerts.TorrentAlert<*>)?.handle()
        } catch (_: Exception) {
            null
        }

    private fun boardByHandle(h: TorrentHandle): Board? =
        boardMap.values.firstOrNull {
            try { it.handle != null && it.handle!!.infoHash() == h.infoHash() }
            catch (_: Exception) { false }
        }

    private fun onTorrentAdded(a: AddTorrentAlert) {
        // A failed add (invalid magnet, duplicate, …) carries an error and an
        // INVALID handle, touching it throws on libtorrent's thread.
        val err = a.error()
        if (err != null && err.isError) {
            log("add torrent failed: ${err.message}")
            return
        }
        val h = safeHandle(a) ?: return
        // Post (don't block) to the lt thread: this listener runs on
        // libtorrent's internal thread, and blocking it deadlocks against
        // session.* calls executing on the lt thread.
        postLt {
            val ih = try { h.infoHash().toHex() } catch (_: Throwable) { return@postLt }
            // Match by infohash, with concurrent adds (restore + join +
            // publish) "first board with a null handle" can wire a torrent to
            // the WRONG board, breaking peer counts, progress and removal.
            val b = boardMap.values.firstOrNull { it.handle == null && it.infoHash.equals(ih, true) }
                ?: boardMap.values.firstOrNull { it.handle == null }
                ?: return@postLt
            b.handle = h
            b.infoHash = ih
            scanFiles(b)
            publishBoards()
        }
    }

    /* ------------------------------------------------------------------ */
    /* persistence                                                         */
    /* ------------------------------------------------------------------ */

    private fun loadState(): MutableMap<String, Pair<String, Long>> {
        val out = LinkedHashMap<String, Pair<String, Long>>()
        try {
            val json = stateFile.readText()
            // Minimal JSON parse of {boards:{Name:{name,infoHash,createdAt}}}
            val boardsObj = json.substringAfter("\"boards\"", "").substringAfter("{", "")
            if (boardsObj.isNotEmpty()) {
                val re = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"infoHash\"\\s*:\\s*\"([^\"]+)\"[^}]*?\"createdAt\"\\s*:\\s*(\\d+)")
                for (m in re.findAll(boardsObj)) {
                    out[m.groupValues[1]] = Pair(m.groupValues[2], m.groupValues[3].toLongOrNull() ?: 0L)
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveState() {
        try {
            val sb = StringBuilder("{\n  \"boards\": {\n")
            val entries = boardMap.entries.toList()
            entries.forEachIndexed { i, entry ->
                val name = entry.key
                val b = entry.value
                sb.append("    \"").append(name.replace("\"", "\\\"")).append("\": {\n")
                sb.append("      \"name\": \"").append(name.replace("\"", "\\\"")).append("\",\n")
                sb.append("      \"infoHash\": \"").append(b.infoHash).append("\",\n")
                sb.append("      \"createdAt\": ").append(b.createdAt).append("\n")
                sb.append("    }").append(if (i < entries.size - 1) "," else "").append("\n")
            }
            sb.append("  }\n}")
            stateFile.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    private suspend fun restoreBoards() = withContext(Dispatchers.IO) {
        val saved = loadState()
        if (saved.isEmpty()) return@withContext
        log("restoring ${saved.size} saved board(s)…")
        for ((name, pair) in saved) {
            if (boardMap.containsKey(name)) continue
            val dir = boardDir(name)
            dir.mkdirs()
            val board = Board(name, dir, pair.first, createdAt = pair.second)
            boardMap[name] = board
            scanFiles(board)
            publishBoards()
            try {
                if (dir.listFiles()?.isNotEmpty() == true) {
                    publishBoard(board)
                    log("restored board \"$name\", seeding")
                } else {
                    // Fresh join from the swarm; keep the entry listed.
                    joinSwarm(board)
                }
            } catch (e: Exception) {
                log("could not fully restore \"$name\": ${e.message}")
            }
        }
        saveState()
    }

    /* ------------------------------------------------------------------ */
    /* board lifecycle                                                     */
    /* ------------------------------------------------------------------ */

    fun boardDir(name: String): File =
        File(boardsDir, Protocol.boardDirName(name))

    suspend fun createBoard(name: String): BoardSnapshot = withContext(Dispatchers.IO) {
        val n = name.trim()
        require(n.isNotEmpty()) { "Board name required" }
        boardMap[n]?.let { return@withContext snapshot(it) }

        val dir = boardDir(n)
        dir.mkdirs()
        val board = Board(n, dir, Protocol.boardInfoHash(n))
        boardMap[n] = board
        publishBoards() // show in UI right away

        try {
            publishBoard(board)
        } catch (e: Exception) {
            boardMap.remove(n)
            publishBoards()
            log("failed to create board \"$n\": ${e.message}")
            throw e
        }
        saveState()
        publishBoards()
        log("board \"$n\" created (${board.infoHash.take(12)}…)")
        snapshot(board)
    }

    /**
     * Publish (or re-publish) the board torrent from the local folder.
     * Rebuilt whenever files change so peers receive updates.
     */
    private suspend fun publishBoard(board: Board) = withContext(Dispatchers.IO) {
        val entries = board.dir.listFiles()
            ?.filter { it.isFile && Protocol.isImageFile(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        // Drop the legacy hidden manifest from earlier versions.
        File(board.dir, Protocol.LEGACY_MANIFEST_NAME).delete()

        // Visible manifest guarantees the torrent always has >= 1 file.
        val manifest = buildString {
            append("{\n  \"board\": \"").append(board.name.replace("\"", "\\\"")).append("\",\n")
            append("  \"createdAt\": ").append(board.createdAt).append(",\n")
            append("  \"files\": [\n")
            entries.forEachIndexed { i, f ->
                append("    { \"name\": \"").append(f.name.replace("\"", "\\\""))
                append("\", \"size\": ").append(f.length())
                append(", \"mtime\": ").append(f.lastModified()).append(" }")
                append(if (i < entries.size - 1) "," else "").append("\n")
            }
            append("  ]\n}")
        }
        File(board.dir, Protocol.MANIFEST_NAME).writeText(manifest)

        // All native session/handle access is serialized on the libtorrent
        // thread (see onLt) to avoid use-after-free races.
        val builder = TorrentBuilder()
            .path(board.dir)
            .creator("BitBoard Android 1.0")
        Protocol.TRACKERS.forEach { builder.addTracker(it) }
        val result = builder.generate()

        val ti = TorrentInfo.bdecode(result.entry().bencode())
        board.infoHash = ti.infoHash().toHex()

        val magnet = Protocol.magnetUri(board.name, board.infoHash)
        val added = onLt {
            try {
                // Null before remove: session.remove() frees the native
                // object; nothing may touch the old handle afterwards.
                val old = board.handle
                board.handle = null
                if (old != null) {
                    try { session.remove(old) } catch (_: Throwable) {}
                }
                // CRITICAL: save path must be the PARENT of the board folder.
                // libtorrent derives the torrent name from the folder basename
                // and lays files out as <save_path>/<name>/<files>; pointing
                // save_path at boardsDir makes them land exactly in board.dir
                // (and matches the desktop, which now seeds with the same
                // name scheme, identical infohashes on both platforms).
                session.download(ti, boardsDir)
                true
            } catch (e: Throwable) {
                log("failed to seed \"${board.name}\": ${e.message}")
                false
            }
        }
        if (!added) {
            publishBoards()
            return@withContext
        }

        // Save the .torrent for debugging / manual sharing.
        try {
            File(torrentsDir, "${board.infoHash}.torrent").writeBytes(result.entry().bencode())
        } catch (_: Exception) {}

        // The AddTorrentAlert listener wires the handle; wait briefly for it.
        val deadline = System.currentTimeMillis() + 10_000
        while (board.handle == null && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        board.handle?.let { _ ->
            scanFiles(board)
            log("published \"${board.name}\", magnet: $magnet")
        }
        publishBoards()
    }

    /**
     * Join a board by name. The desktop beacon carries the real content-derived
     * infohash; when we have it we join that swarm directly. Otherwise we fall
     * back to the deterministic hash (works when the creator's torrent was
     * built with the same deterministic scheme).
     */
    suspend fun joinBoard(name: String, knownInfoHash: String? = null): BoardSnapshot =
        withContext(Dispatchers.IO) {
            val n = name.trim()
            require(n.isNotEmpty()) { "Board name required" }
            boardMap[n]?.let { return@withContext snapshot(it) }

            val dir = boardDir(n)
            dir.mkdirs()
            val board = Board(n, dir, knownInfoHash ?: Protocol.boardInfoHash(n))
            boardMap[n] = board
            publishBoards()

            joinSwarm(board)
            saveState()
            publishBoards()
            log("joined board \"$n\"")
            snapshot(board)
        }

    private suspend fun joinSwarm(board: Board) = withContext(Dispatchers.IO) {
        val magnet = Protocol.magnetUri(board.name, board.infoHash)
        onLt {
            try {
                // Save path = boardsDir (parent), NOT board.dir: libtorrent
                // nests files under <save_path>/<torrent name>/ and the
                // torrent name equals the board folder's basename, so files
                // must land in boardsDir/<hash>/ = board.dir. Downloading
                // into board.dir would bury them one level deeper where
                // scanFiles() can never see them.
                session.download(magnet, boardsDir, null)
                log("joining \"${board.name}\", searching swarm…")
            } catch (e: Throwable) {
                // Keep the board listed as "searching for peers" rather than
                // crashing; the next beacon / restart will retry.
                log("could not join \"${board.name}\" yet: ${e.message}")
            }
        }
        publishBoards()
    }

    /** Add an image (copied from a content URI-backed temp file) to a board. */
    suspend fun addImage(boardName: String, src: File): Boolean = withContext(Dispatchers.IO) {
        val board = boardMap[boardName] ?: throw IllegalArgumentException("Unknown board: $boardName")
        val base = src.name
        require(Protocol.isImageFile(base)) { "Not an image file: $base" }
        val dest = File(board.dir, base)
        src.copyTo(dest, overwrite = true)
        scanFiles(board)
        publishBoard(board)
        saveState()
        publishBoards()
        log("added $base to \"$boardName\", replicating to peers")
        true
    }

    private fun scanFiles(board: Board) {
        try {
            val entries = board.dir.listFiles()
                ?.filter { it.isFile && Protocol.isImageFile(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?: return
            val seen = HashSet<String>()
            // Read status() on the libtorrent thread so it can never race a
            // concurrent session.remove() that frees the native handle.
            val progress = onLt {
                try { board.handle?.status()?.progress() ?: 1f }
                catch (_: Throwable) { 1f }
            }
            for (f in entries) {
                seen.add(f.name)
                val existing = board.files[f.name]
                if (existing == null || existing.size != f.length()) {
                    board.files[f.name] = FileMeta(
                        name = f.name,
                        size = f.length(),
                        downloadedAt = f.lastModified(),
                        progress = progress
                    )
                }
            }
            board.files.keys.retainAll(seen)
        } catch (_: Exception) {}
    }

    /* ------------------------------------------------------------------ */
    /* reporting                                                           */
    /* ------------------------------------------------------------------ */

    private fun snapshot(b: Board): BoardSnapshot {
        scanFiles(b)
        val files = b.files.values
            .sortedByDescending { it.downloadedAt }
            .toList()
        // Read peers/progress on the libtorrent thread (see scanFiles).
        val (peers, progress) = onLt {
            try {
                val st = b.handle?.status()
                (st?.numPeers() ?: 0) to (st?.progress() ?: 1f)
            } catch (_: Throwable) {
                0 to 1f
            }
        }
        return BoardSnapshot(
            name = b.name,
            infoHash = b.infoHash,
            fileCount = files.size,
            totalBytes = files.sumOf { it.size },
            peers = peers,
            progress = progress,
            files = files
        )
    }

    fun publishBoards() {
        _boards.value = boardMap.values.map { snapshot(it) }
    }

    fun log(line: String) {
        val stamped = line
        _logs.value = (_logs.value + stamped).takeLast(200)
        android.util.Log.d("BitBoard", line)
    }

    /** Boards announced by a LAN peer that we don't have yet. */
    fun onLanBoards(peerBoards: List<Pair<String, String>>) {
        val missing = peerBoards.filter { (name, _) -> !boardMap.containsKey(name) }
        if (missing.isNotEmpty()) {
            _discovered.value = (_discovered.value + missing.map { it.first }).distinct()
            for ((name, infoHash) in missing) {
                log("discovered board \"$name\" on LAN, joining")
                scope.launch {
                    try { joinBoard(name, infoHash) } catch (_: Exception) {}
                }
            }
        }
        // Boards we already have may have been re-published under a NEW
        // content-derived infohash, follow the peer to the new swarm.
        for ((name, infoHash) in peerBoards) {
            if (boardMap.containsKey(name)) {
                scope.launch { followInfoHashUpdate(name, infoHash) }
            }
        }
    }

    /**
     * A LAN peer announced a board we already have, but with a DIFFERENT
     * infohash, the board was re-published (files added/removed) and the
     * swarm moved. Leave our stale swarm and join the new one so the two
     * devices converge again instead of seeding disjoint swarms forever.
     */
    private suspend fun followInfoHashUpdate(name: String, newInfoHash: String) =
        withContext(Dispatchers.IO) {
            if (newInfoHash.isEmpty()) return@withContext
            val board = boardMap[name] ?: return@withContext
            if (board.infoHash.equals(newInfoHash, true)) return@withContext
            if (board.migrating) return@withContext
            board.migrating = true
            log("board \"$name\" updated on a peer, switching to the new swarm")
            try {
                onLt {
                    // Null before remove (see publishBoard): never leave a
                    // freed native handle reachable from the board.
                    val old = board.handle
                    board.handle = null
                    if (old != null) {
                        try { session.remove(old) } catch (_: Throwable) {}
                    }
                    board.infoHash = newInfoHash
                }
                saveState()
                publishBoards()
                joinSwarm(board)
            } finally {
                board.migrating = false
            }
        }

    /** Our beacon payload board list: name -> infoHash. */
    fun beaconBoards(): List<Pair<String, String>> =
        boardMap.values.map { it.name to it.infoHash }

    /** Image files currently present in a board's folder (for the gallery). */
    fun boardFiles(name: String): List<File> {
        val b = boardMap[name] ?: return emptyList()
        scanFiles(b)
        return b.dir.listFiles()
            ?.filter { it.isFile && Protocol.isImageFile(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun hostName(): String = Build.MODEL ?: "android"

    fun peerIdValue(): String = peerId
}
