package com.bitboard.app.engine

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * BitBoard P2P engine for Android — port of the desktop engine (src/engine.js)
 * on top of libtorrent4j.
 *
 * Every "board" is a folder of images that this device seeds as its OWN
 * torrent. Devices never share an infohash for "the same" board (the hash
 * covers mtimes, the creation date, the creator string, …), so replication is
 * a MERGE, not a swarm switch:
 *
 *   1. a LAN beacon says "peer P has board B as torrent H at ip:port";
 *   2. if we haven't merged H yet, fetch H into a staging folder — connecting
 *      straight to ip:port (no tracker/DHT needed) and downloading ONLY the
 *      images we don't already have;
 *   3. copy them into our board folder and re-publish our own torrent.
 *
 * Each distinct peer hash is merged at most once, so two devices converge
 * instead of endlessly abandoning each other's swarm.
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
        val files: List<FileMeta>,
        val syncing: Boolean = false,
        /** Emoji chosen by the user for this board (empty = default glyph). */
        val icon: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _boards = MutableStateFlow<List<BoardSnapshot>>(emptyList())
    val boards: StateFlow<List<BoardSnapshot>> = _boards

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _discovered = MutableStateFlow<List<String>>(emptyList())
    val discovered: StateFlow<List<String>> = _discovered

    /** name -> last beacon time for boards seen on the LAN but NOT joined. */
    private val discoveredMap = ConcurrentHashMap<String, Long>()

    /** Internet rendezvous: exchanges infohashes with devices on OTHER networks. */
    private val signal = SignalChannel(log = { log(it) })
    private var signalLoops: Job? = null

    /** Latest infohash announced by an internet peer. */
    private class SignalPeer(val hash: String, val at: Long)

    private lateinit var dataDir: File
    private lateinit var boardsDir: File
    private lateinit var torrentsDir: File
    private lateinit var stagingDir: File
    private lateinit var stateFile: File
    private lateinit var peerIdFile: File

    private val session = SessionManager()
    private val boardMap = ConcurrentHashMap<String, Board>() // name -> board
    private val ensureMutex = Mutex()
    private var discovery: LanDiscovery? = null
    private var peerId: String = ""
    @Volatile private var started = false

    /** User settings + blacklists (images by hash, boards, peers). */
    val settings = SettingsStore(context)

    /** name -> emoji icon, persisted in state.json. */
    private val boardIcons = ConcurrentHashMap<String, String>()

    /**
     * Single-threaded dispatcher that serializes ALL access to libtorrent
     * native objects (session + torrent handles). libtorrent4j's Java
     * wrappers are NOT thread-safe: a TorrentHandle freed by session.remove()
     * on one thread segfaults if another thread calls status()/infoHash() on
     * it concurrently.
     */
    private val ltExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bitboard-lt").apply { isDaemon = true }
    }
    private val ltDispatcher = ltExecutor.asCoroutineDispatcher()

    /** Run [block] on the libtorrent thread and wait for the result. Runs
     *  inline if the caller already IS the lt thread. Callers are coroutines
     *  on Dispatchers.IO (never the UI thread, never libtorrent's alert
     *  thread). */
    private fun <T> onLt(block: () -> T): T =
        if (Thread.currentThread().name == "bitboard-lt") block()
        else runBlocking(ltDispatcher) { block() }

    /** Post [block] to the libtorrent thread WITHOUT waiting. */
    private fun postLt(block: () -> Unit) {
        ltExecutor.execute {
            try { block() } catch (_: Throwable) {}
        }
    }

    /** Internal mutable board record. */
    private class Board(
        val name: String,
        val dir: File,
        val createdAt: Long
    ) {
        /** Real infohash of OUR torrent (valid once [handle] != null). */
        @Volatile var infoHash: String = Protocol.boardInfoHash(name)
        /** Handle of OUR seeding torrent. Only touched on the lt thread. */
        @Volatile var handle: TorrentHandle? = null
        val files = ConcurrentHashMap<String, FileMeta>()
        val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()      // peer hashes merged
        val merging: MutableSet<String> = ConcurrentHashMap.newKeySet()   // peer hashes in flight
        val retryAt = ConcurrentHashMap<String, Long>()
        val lanPeers = ConcurrentHashMap<String, Long>()                  // peerId -> last beacon
        val signalPeers = ConcurrentHashMap<String, SignalPeer>()         // relay peerId -> announcement
        val signalInbox = ConcurrentHashMap<String, JSONObject>()         // relay peerId -> newest raw msg
        val failCount = ConcurrentHashMap<String, Int>()                  // peer hash -> failed merges
        @Volatile var sub: SignalChannel.Subscription? = null
        @Volatile var announceJob: Job? = null
        @Volatile var flushJob: Job? = null
        val publishLock = Mutex()
        @Volatile var mergeProgress = 1f
    }

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    fun start() {
        if (started) return
        started = true

        dataDir = File(context.filesDir, "data").apply { mkdirs() }
        boardsDir = File(dataDir, "boards").apply { mkdirs() }
        torrentsDir = File(dataDir, "torrents").apply { mkdirs() }
        stagingDir = File(dataDir, "staging").apply { deleteRecursively(); mkdirs() }
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
            // Start discovery BEFORE restoring boards: restoring re-seeds every
            // board (slow), and beacons must not wait for that.
            discovery = LanDiscovery(this@BitBoardEngine).also { it.start() }
            startSignalLoops()
            restoreBoards()
            // Idempotent: also covers a stop()/start() cycle within one process.
            boardMap.values.forEach { startSignal(it) }
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
        // POSIX disk I/O, NOT the default mmap one: mmap is unstable on Android
        // (SIGSEGV / SIGBUS on the disk thread while seeding).
        params.setPosixDiskIO()
        session.addListener(alertListener)
        session.start(params)
        log("session started (DHT + LSD), port ${listenPort()}")
    }

    fun stop() {
        if (!started) return
        started = false
        try { discovery?.stop() } catch (_: Exception) {}
        discovery = null
        signalLoops?.cancel()
        signalLoops = null
        boardMap.values.forEach { stopSignal(it) }
        try { session.stop() } catch (_: Exception) {}
    }

    private fun dhtNodes(): Long = try { session.dhtNodes() } catch (_: Throwable) { -1L }

    /** The TCP/uTP port libtorrent really listens on (announced in beacons). */
    fun listenPort(): Int = try {
        val p = session.swig().listen_port()
        if (p > 0) p else 6881
    } catch (_: Throwable) {
        6881
    }

    /* ------------------------------------------------------------------ */
    /* alerts                                                              */
    /* ------------------------------------------------------------------ */

    /** Only used for diagnostics. Torrent handles are looked up by infohash
     *  (session.find) — never wired from alerts — so a torrent can never be
     *  attached to the wrong board. */
    private val alertListener = object : AlertListener {
        override fun types(): IntArray? = null // all alerts

        override fun alert(a: Alert<*>) {
            // Runs on libtorrent's internal thread; nothing may escape.
            try {
                if (a is AddTorrentAlert) {
                    val err = a.error()
                    if (err != null && err.isError) log("add torrent failed: ${err.message}")
                }
            } catch (_: Throwable) {}
        }
    }

    /* ------------------------------------------------------------------ */
    /* persistence                                                         */
    /* ------------------------------------------------------------------ */

    /** name -> createdAt from state.json (same format as the desktop app). */
    private fun loadState(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        try {
            val root = JSONObject(stateFile.readText())
            // Board icons (optional; older state files don't have them).
            val icons = root.optJSONObject("icons")
            if (icons != null) {
                val ik = icons.keys()
                while (ik.hasNext()) {
                    val k = ik.next()
                    boardIcons[k] = icons.optString(k, "")
                }
            }
            val boardsObj = root.optJSONObject("boards") ?: return out
            val keys = boardsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val b = boardsObj.optJSONObject(key) ?: continue
                out[b.optString("name", key)] = b.optLong("createdAt", System.currentTimeMillis())
            }
        } catch (_: Exception) {}
        return out
    }

    private fun saveState() {
        try {
            val boardsObj = JSONObject()
            for (b in boardMap.values.sortedBy { it.createdAt }) {
                boardsObj.put(
                    b.name,
                    JSONObject()
                        .put("name", b.name)
                        .put("infoHash", b.infoHash)
                        .put("createdAt", b.createdAt)
                )
            }
            val iconsObj = JSONObject()
            for ((k, v) in boardIcons) iconsObj.put(k, v)
            stateFile.writeText(
                JSONObject().put("boards", boardsObj).put("icons", iconsObj).toString(2)
            )
        } catch (_: Exception) {}
    }

    private suspend fun restoreBoards() = withContext(Dispatchers.IO) {
        val saved = loadState()
        if (saved.isEmpty()) return@withContext
        log("restoring ${saved.size} saved board(s)…")
        for ((name, createdAt) in saved) {
            try {
                ensureBoard(name, "restored", createdAt)
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

    /**
     * Create OR join a board — the same operation: make the folder, seed it,
     * and let LAN beacons bring in whatever peers already have.
     */
    private suspend fun ensureBoard(name: String, verb: String, createdAt: Long? = null): Board {
        val n = name.trim()
        require(n.isNotEmpty()) { "Board name required" }

        var created = false
        val board = ensureMutex.withLock {
            val existing = boardMap[n]
            if (existing != null) {
                existing
            } else {
                val dir = boardDir(n).apply { mkdirs() }
                val b = Board(n, dir, createdAt ?: System.currentTimeMillis())
                boardMap[n] = b
                created = true
                b
            }
        }
        if (!created) return board

        startSignal(board)
        scanFiles(board)
        publishBoards() // show in UI right away
        try {
            publishLocked(board)
        } catch (e: Exception) {
            stopSignal(board)
            boardMap.remove(n)
            publishBoards()
            log("failed to $verb board \"$n\": ${e.message}")
            throw e
        }
        saveState()
        publishBoards()
        discoveredMap.remove(n)   // no longer just "discovered" — we have it
        _discovered.value = discoveredMap.keys.toList()
        log("board \"$n\" $verb (${board.infoHash.take(12)}…)")
        return board
    }

    suspend fun createBoard(name: String): BoardSnapshot = withContext(Dispatchers.IO) {
        snapshot(ensureBoard(name, "created"))
    }

    suspend fun joinBoard(name: String): BoardSnapshot = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (settings.isBoardBlocked(n)) {
            throw IllegalArgumentException("Board \"$n\" is blacklisted (unblock it in Settings)")
        }
        snapshot(ensureBoard(n, "joined"))
    }

    /**
     * Leave a board: stop seeding/announcing it and forget it. The local
     * folder is kept unless [deleteFiles] is set, so "leave + rejoin" keeps
     * the images the device already had.
     */
    suspend fun leaveBoard(name: String, deleteFiles: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val board = boardMap.remove(name.trim()) ?: return@withContext false
            stopSignal(board)
            onLt {
                val h = board.handle
                board.handle = null
                if (h != null) {
                    try { session.remove(h) } catch (_: Throwable) {}
                }
            }
            if (deleteFiles) {
                try { board.dir.deleteRecursively() } catch (_: Exception) {}
            }
            saveState()
            publishBoards()
            discoveredMap.remove(board.name)
            _discovered.value = discoveredMap.keys.toList()
            log("left board \"${board.name}\"" + if (deleteFiles) " (files deleted)" else "")
            true
        }

    /** Set (or clear with "") the emoji icon shown next to a board. */
    suspend fun setBoardIcon(name: String, icon: String) = withContext(Dispatchers.IO) {
        val n = name.trim()
        if (icon.isBlank()) boardIcons.remove(n) else boardIcons[n] = icon.take(8)
        saveState()
        publishBoards()
    }

    /** Serialise (re)publishing per board. */
    private suspend fun publishLocked(board: Board) =
        board.publishLock.withLock { publishBoard(board) }

    /**
     * Publish (or re-publish) OUR torrent from the local folder. Rebuilt
     * whenever files change so peers can see the update.
     */
    private suspend fun publishBoard(board: Board) = withContext(Dispatchers.IO) {
        val entries = board.dir.listFiles()
            ?.filter { it.isFile && Protocol.isImageFile(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        // Drop the legacy hidden manifest from earlier versions.
        File(board.dir, Protocol.LEGACY_MANIFEST_NAME).delete()

        // Visible manifest guarantees the torrent always has >= 1 file.
        val filesJson = JSONArray()
        for (f in entries) {
            filesJson.put(
                JSONObject().put("name", f.name).put("size", f.length()).put("mtime", f.lastModified())
            )
        }
        val manifest = JSONObject()
            .put("board", board.name)
            .put("createdAt", board.createdAt)
            .put("files", filesJson)
            .toString(2)
        File(board.dir, Protocol.MANIFEST_NAME).writeText(manifest)

        // v1-ONLY torrent. libtorrent defaults to a v1+v2 hybrid, which adds
        // pad files and v2 keys; WebTorrent (desktop) only speaks v1 and can
        // choke on them.
        val builder = TorrentBuilder()
            .path(board.dir)
            .creator("BitBoard Android 1.0")
            .flags(TorrentBuilder.V1_ONLY)
        Protocol.TRACKERS.forEach { builder.addTracker(it) }
        val result = builder.generate()
        val bytes = result.entry().bencode()

        val ti = TorrentInfo.bdecode(bytes)
        val newHash = ti.infoHash().toHex()

        val added = onLt {
            try {
                val old = board.handle
                val oldHash = board.infoHash
                if (old != null && oldHash.equals(newHash, true)) {
                    true // identical torrent already seeding
                } else {
                    // Null before remove: session.remove() frees the native
                    // object; nothing may touch the old handle afterwards.
                    board.handle = null
                    if (old != null) {
                        try { session.remove(old) } catch (_: Throwable) {}
                    }
                    // Save path = PARENT of the board folder: libtorrent
                    // lays files out as <save_path>/<torrent name>/<files>
                    // and the torrent name is the folder's basename.
                    session.download(ti, boardsDir)
                    true
                }
            } catch (e: Throwable) {
                log("failed to seed \"${board.name}\": ${e.message}")
                false
            }
        }
        if (!added) {
            publishBoards()
            return@withContext
        }
        board.infoHash = newHash
        board.seen.add(newHash)

        try { File(torrentsDir, "$newHash.torrent").writeBytes(bytes) } catch (_: Exception) {}

        // session.download() is asynchronous: look the handle up by infohash.
        if (board.handle == null) {
            val sha = ti.infoHash()
            val deadline = System.currentTimeMillis() + 10_000
            while (board.handle == null && System.currentTimeMillis() < deadline) {
                val h = onLt { try { session.find(sha) } catch (_: Throwable) { null } }
                if (h != null) board.handle = h else delay(100)
            }
        }
        scanFiles(board)
        if (board.handle != null) {
            log("published \"${board.name}\" (${newHash.take(12)}…)")
            scheduleAnnounce(board) // tell internet peers our new infohash
        } else {
            log("\"${board.name}\" was added but is not seeding yet")
        }
        publishBoards()
    }

    /** Add an image (copied from a content URI-backed temp file) to a board. */
    suspend fun addImage(boardName: String, src: File): Boolean = withContext(Dispatchers.IO) {
        val board = boardMap[boardName] ?: throw IllegalArgumentException("Unknown board: $boardName")
        val base = src.name
        require(Protocol.isImageFile(base)) { "Not an image file: $base" }
        // Blacklist check by CONTENT hash: the same image is rejected under
        // any file name.
        val digest = try { Protocol.sha1Hex(src.readBytes()) } catch (_: Exception) { null }
        if (digest != null && settings.isImageBlocked(digest)) {
            log("blocked $base — image is blacklisted")
            return@withContext false
        }
        val dest = File(board.dir, base)
        src.copyTo(dest, overwrite = true)
        scanFiles(board)
        publishLocked(board)
        saveState()
        publishBoards()
        log("added $base to \"$boardName\" — replicating to peers")
        true
    }

    /** SHA-1 of a board image's content (for the blacklist UI). */
    fun imageHash(file: File): String? = try {
        Protocol.sha1Hex(file.readBytes())
    } catch (_: Exception) {
        null
    }

    /** Delete a local image from a board and re-publish. */
    suspend fun removeImage(boardName: String, fileName: String): Boolean =
        withContext(Dispatchers.IO) {
            val board = boardMap[boardName] ?: return@withContext false
            val f = File(board.dir, fileName)
            if (!f.isFile || f.canonicalFile != File(board.dir, f.name).canonicalFile) {
                return@withContext false
            }
            val ok = f.delete()
            if (ok) {
                scanFiles(board)
                publishLocked(board)
                saveState()
                publishBoards()
                log("removed $fileName from \"$boardName\"")
            }
            ok
        }

    private fun scanFiles(board: Board) {
        try {
            val entries = board.dir.listFiles()
                ?.filter { it.isFile && Protocol.isImageFile(it.name) }
                ?: return
            val seen = HashSet<String>()
            for (f in entries) {
                seen.add(f.name)
                val existing = board.files[f.name]
                if (existing == null || existing.size != f.length()) {
                    board.files[f.name] = FileMeta(
                        name = f.name,
                        size = f.length(),
                        downloadedAt = f.lastModified(),
                        progress = 1f
                    )
                }
            }
            board.files.keys.retainAll(seen)
        } catch (_: Exception) {}
    }

    /* ------------------------------------------------------------------ */
    /* merging a peer's board                                              */
    /* ------------------------------------------------------------------ */

    /** Boards announced by a LAN peer (beacon handler; runs on the UDP thread). */
    fun onLanBoards(peerBoards: List<Pair<String, String>>, addr: String, port: Int, fromPeerId: String) {
        // Blacklisted peers are ignored entirely: their boards never show up
        // as discovered and never merge.
        if (settings.isPeerBlocked(fromPeerId)) return
        val now = System.currentTimeMillis()
        // Expire discovered boards whose beacons stopped arriving.
        discoveredMap.entries.removeIf { now - it.value > Protocol.DISCOVERED_TTL_MS }
        for ((name, hash) in peerBoards) {
            // Blacklisted boards are hidden from discovery and never merged.
            if (settings.isBoardBlocked(name)) {
                discoveredMap.remove(name)
                continue
            }
            if (!boardMap.containsKey(name) && discoveredMap.put(name, now) == null) {
                _discovered.value = discoveredMap.keys.toList()
            }
            handlePeerBoard(name, hash.lowercase(), addr, port, fromPeerId)
        }
    }

    /** Boards seen on the LAN that this device has NOT joined. */
    fun discoveredBoards(): List<String> = discoveredMap.keys.toList()

    private fun handlePeerBoard(name: String, hash: String, addr: String, port: Int, fromPeerId: String) {
        val board = boardMap[name]
        if (board == null) {
            // Board we don't have: it is already in the discovered list (the
            // beacon handler adds it). NEVER auto-join — a board only lands
            // on this device when the user asks for it.
            return
        }
        if (fromPeerId.isNotEmpty()) board.lanPeers[fromPeerId] = System.currentTimeMillis()

        // Same torrent already: just make sure we are directly connected.
        if (hash.equals(board.infoHash, true)) {
            connectDirect(board, addr, port)
            return
        }
        tryMerge(board, hash, addr, port)
    }

    /**
     * Merge a peer's torrent [hash] into [board] unless we already did, are
     * doing it, or failed recently. [addr]/[port] are optional: LAN beacons
     * know where the peer is; internet announcements don't ("" / 0) and rely
     * on trackers / DHT to connect.
     */
    private fun tryMerge(board: Board, hash: String, addr: String, port: Int) {
        if (hash.equals(board.infoHash, true)) return
        if (board.seen.contains(hash)) return
        if ((board.retryAt[hash] ?: 0L) > System.currentTimeMillis()) return
        if (!board.merging.add(hash)) return // already being fetched

        scope.launch {
            try {
                mergeFromPeer(board, hash, addr, port)
                board.failCount.remove(hash)
            } catch (e: Exception) {
                // Exponential back-off so an offline peer isn't hammered.
                val n = (board.failCount[hash] ?: 0) + 1
                board.failCount[hash] = n
                val wait = minOf(Protocol.RETRY_COOLDOWN_MS * (1L shl (n - 1).coerceAtMost(10)), Protocol.MAX_RETRY_COOLDOWN_MS)
                board.retryAt[hash] = System.currentTimeMillis() + wait
                log("sync of \"${board.name}\" from ${addr.ifEmpty { "internet peer" }} failed: ${e.message}")
            } finally {
                board.merging.remove(hash)
                board.mergeProgress = 1f
                publishBoards()
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* internet rendezvous (relay)                                         */
    /* ------------------------------------------------------------------ */

    private fun startSignal(board: Board) {
        if (board.sub != null) return
        board.sub = signal.subscribe(Protocol.signalTopic(board.name)) { msg -> onSignal(board, msg) }
    }

    private fun stopSignal(board: Board) {
        board.sub?.close()
        board.sub = null
        board.announceJob?.cancel()
        board.flushJob?.cancel()
    }

    /** Heartbeat announcements + retry of unfinished merges. */
    private fun startSignalLoops() {
        signalLoops?.cancel()
        signalLoops = scope.launch {
            launch {
                while (true) {
                    delay(Protocol.SIGNAL_HEARTBEAT_MS)
                    boardMap.values.forEach { announceNow(it) }
                }
            }
            launch {
                while (true) {
                    delay(Protocol.SIGNAL_TICK_MS)
                    signalTick()
                }
            }
        }
    }

    private fun scheduleAnnounce(board: Board) {
        if (!started) return
        board.announceJob?.cancel()
        board.announceJob = scope.launch {
            delay(Protocol.SIGNAL_ANNOUNCE_DEBOUNCE_MS)
            announceNow(board)
        }
    }

    private fun announceNow(board: Board) {
        if (board.handle == null) return
        val payload = JSONObject()
            .put("app", "bitboard")
            .put("v", 1)
            .put("peerId", peerId)
            .put("board", board.name)
            .put("infoHash", board.infoHash)
            .put("ts", System.currentTimeMillis())
        scope.launch {
            try {
                signal.publish(Protocol.signalTopic(board.name), payload)
                log("announced \"${board.name}\" ${board.infoHash.take(8)}… to relay")
            } catch (e: Exception) {
                log("could not announce \"${board.name}\": ${e.message}")
            }
        }
    }

    /** A relay message for [board] (runs on the signal thread). Batched so a backlog collapses to the newest per peer. */
    private fun onSignal(board: Board, msg: JSONObject) {
        if (msg.optString("app") != "bitboard") return
        val from = msg.optString("peerId")
        if (from.isEmpty() || from == peerId) return // ours, or malformed
        if (settings.isPeerBlocked(from)) return     // blacklisted peer
        if (!Protocol.isInfoHash(msg.optString("infoHash"))) return
        log("relay: \"${board.name}\" peer ${from.take(4)} announced ${msg.optString("infoHash").take(8)}…")
        val prev = board.signalInbox[from]
        if (prev == null || msg.optLong("ts", 0L) >= prev.optLong("ts", 0L)) board.signalInbox[from] = msg
        if (board.flushJob?.isActive == true) return
        board.flushJob = scope.launch {
            delay(Protocol.SIGNAL_BATCH_MS)
            for (id in board.signalInbox.keys.toList()) {
                val m = board.signalInbox.remove(id) ?: continue
                val hash = m.optString("infoHash").lowercase()
                board.signalPeers[id] = SignalPeer(hash, System.currentTimeMillis())
                tryMerge(board, hash, "", 0)
            }
        }
    }

    /** Retry merges from internet peers that announced recently but haven't synced yet. */
    private fun signalTick() {
        val now = System.currentTimeMillis()
        for (b in boardMap.values) {
            for ((id, p) in b.signalPeers.entries.toList()) {
                if (now - p.at > Protocol.SIGNAL_TTL_MS) { b.signalPeers.remove(id); continue }
                tryMerge(b, p.hash, "", 0)
            }
        }
    }

    /** Connect our seeding torrent straight to a LAN peer (no tracker/DHT). */
    private fun connectDirect(board: Board, addr: String, port: Int) {
        if (port <= 0) return
        postLt {
            val h = board.handle ?: return@postLt
            h.swig().connect_peer(TcpEndpoint(addr, port).swig())
        }
    }

    private suspend fun mergeFromPeer(board: Board, hash: String, addr: String, port: Int) =
        withContext(Dispatchers.IO) {
            val stageRoot = File(stagingDir, hash)
            var h: TorrentHandle? = null
            try {
                stageRoot.deleteRecursively()
                stageRoot.mkdirs()
                board.mergeProgress = 0f
                publishBoards()
                log("syncing \"${board.name}\" from ${addr.ifEmpty { "internet peers (tracker/DHT)" }}…")

                onLt { session.download(Protocol.magnetUri(board.name, hash), stageRoot, torrent_flags_t()) }

                // 1. find the handle of the staging torrent
                val sha = Sha1Hash.parseHex(hash)
                val t0 = System.currentTimeMillis()
                var found: TorrentHandle? = null
                while (found == null) {
                    found = onLt { try { session.find(sha) } catch (_: Throwable) { null } }
                    if (found == null) {
                        if (System.currentTimeMillis() - t0 > 10_000) throw IOException("could not start the transfer")
                        delay(100)
                    }
                }
                val handle: TorrentHandle = found ?: throw IOException("could not start the transfer")
                h = handle

                // 2. connect directly, wait for metadata, choose the missing files
                var wanted: List<String>? = null
                var lastConnect = 0L
                var lastStatus = 0L
                var lastDone = -1L
                var lastMove = System.currentTimeMillis()
                while (true) {
                    val now = System.currentTimeMillis()
                    val st = onLt { handle.status() }

                    if (wanted == null) {
                        if (st.hasMetadata()) {
                            wanted = onLt { selectMissing(board, handle) }
                            lastMove = now
                            if (wanted.isEmpty()) break // we already have everything
                        } else if (now - t0 > Protocol.METADATA_TIMEOUT_MS) {
                            throw IOException("timed out fetching torrent metadata " +
                                "(peers=${st.numPeers()}, dht nodes=${dhtNodes()}) — " +
                                "the other device is unreachable (NAT/firewall) or offline")
                        } else if (now - lastStatus > 8_000) {
                            lastStatus = now
                            log("waiting for peer: peers=${st.numPeers()}, dht nodes=${dhtNodes()}")
                        }
                    } else {
                        val total = st.totalWanted()
                        val done = st.totalWantedDone()
                        board.mergeProgress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        if (st.isFinished()) break
                        if (done > lastDone) { lastDone = done; lastMove = now }
                        else if (now - lastMove > Protocol.STALL_TIMEOUT_MS) throw IOException("transfer stalled")
                    }

                    // (Re)connect to the LAN peer; harmless if already connected.
                    if (port > 0 && st.numPeers() == 0 && now - lastConnect > 4000) {
                        lastConnect = now
                        onLt {
                            try { handle.swig().connect_peer(TcpEndpoint(addr, port).swig()) }
                            catch (_: Throwable) {}
                        }
                    }
                    delay(500)
                }

                // 3. merge into our folder and republish
                val staged = (wanted ?: emptyList()).map { rel -> File(stageRoot, rel) }
                onLt { try { session.remove(handle) } catch (_: Throwable) {} }
                h = null
                val added = importStaged(board, staged)
                board.seen.add(hash)
                if (added > 0) {
                    scanFiles(board)
                    publishLocked(board) // new content => new hash of our own
                    saveState()
                    log("board \"${board.name}\": received $added new image(s)")
                }
            } finally {
                val leftover = h
                if (leftover != null) {
                    onLt { try { session.remove(leftover) } catch (_: Throwable) {} }
                }
                stageRoot.deleteRecursively()
            }
        }

    /**
     * Runs on the lt thread. Ignores every file we don't need (already have it
     * with the same size, not a flat image file, manifest, pad files, …) and
     * returns the torrent-relative paths of the ones we do want.
     */
    private fun selectMissing(board: Board, h: TorrentHandle): List<String> {
        val ti = h.torrentFile() ?: return emptyList()
        val fs = ti.files()
        val wanted = ArrayList<String>()
        for (i in 0 until fs.numFiles()) {
            val rel = fs.filePath(i)
            val parts = rel.split('/')                     // [<torrent name>, <file>]
            var need = parts.size == 2 && Protocol.isImageFile(parts[1])
            if (need) {
                val local = File(board.dir, parts[1])
                if (local.isFile && local.length() == fs.fileSize(i)) need = false
            }
            if (need) wanted.add(rel) else h.filePriority(i, Priority.IGNORE)
        }
        return wanted
    }

    /** Copy staged images into the board folder; returns how many were new. */
    private fun importStaged(board: Board, staged: List<File>): Int {
        var added = 0
        for (f in staged) {
            if (!f.isFile) continue
            val data = try { f.readBytes() } catch (_: Exception) { continue }
            val digest = Protocol.sha1Hex(data)

            // Already have these exact bytes (under any name)? Nothing to do.
            val local = board.dir.listFiles()?.filter { it.isFile && Protocol.isImageFile(it.name) } ?: emptyList()
            val dup = local.any { l ->
                l.length() == data.size.toLong() &&
                    try { Protocol.sha1Hex(l.readBytes()) == digest } catch (_: Exception) { false }
            }
            if (dup) continue

            var name = f.name
            var dest = File(board.dir, name)
            if (dest.exists()) {
                // Same name, different content: keep BOTH. The suffix comes from
                // the content hash so every device names the copy identically.
                val dot = name.lastIndexOf('.')
                val stem = if (dot > 0) name.substring(0, dot) else name
                val ext = if (dot > 0) name.substring(dot) else ""
                name = "$stem-${digest.take(6)}$ext"
                dest = File(board.dir, name)
                if (dest.exists()) continue
            }
            try {
                dest.writeBytes(data)
                added++
            } catch (e: Exception) {
                log("could not save $name: ${e.message}")
            }
        }
        return added
    }

    /* ------------------------------------------------------------------ */
    /* reporting                                                           */
    /* ------------------------------------------------------------------ */

    private fun snapshot(b: Board): BoardSnapshot {
        scanFiles(b)
        val files = b.files.values.sortedByDescending { it.downloadedAt }
        val now = System.currentTimeMillis()
        b.lanPeers.entries.removeIf { now - it.value > Protocol.LAN_PEER_TTL_MS }
        val lan = b.lanPeers.size
        // Read peers on the libtorrent thread (see onLt).
        val connected = onLt {
            try { b.handle?.status()?.numPeers() ?: 0 } catch (_: Throwable) { 0 }
        }
        val syncing = b.merging.isNotEmpty()
        return BoardSnapshot(
            name = b.name,
            infoHash = b.infoHash,
            fileCount = files.size,
            totalBytes = files.sumOf { it.size },
            // Peers we are connected to, or LAN devices that announced this
            // board in the last few seconds — whichever is larger.
            peers = maxOf(connected, lan),
            progress = if (syncing) b.mergeProgress else 1f,
            files = files,
            syncing = syncing,
            icon = boardIcons[b.name] ?: ""
        )
    }

    fun publishBoards() {
        _boards.value = boardMap.values.sortedBy { it.createdAt }.map { snapshot(it) }
    }

    fun log(line: String) {
        _logs.update { (it + line).takeLast(200) }
        android.util.Log.d("BitBoard", line)
    }

    /** Our beacon payload: only boards that are actually seeding (announcing a
     *  hash nobody can fetch just makes peers time out). */
    fun beaconBoards(): List<Pair<String, String>> =
        boardMap.values
            .filter { it.handle != null }
            .sortedBy { it.createdAt }
            .map { it.name to it.infoHash }

    /** Image files currently present in a board's folder (for the gallery).
     *  Blacklisted images (by content hash) are hidden. */
    fun boardFiles(name: String): List<File> {
        val b = boardMap[name] ?: return emptyList()
        scanFiles(b)
        val blocked = settings.current().imageHashes
        return b.dir.listFiles()
            ?.filter { it.isFile && Protocol.isImageFile(it.name) }
            ?.filter { f ->
                if (blocked.isEmpty()) true
                else try { !blocked.contains(Protocol.sha1Hex(f.readBytes())) } catch (_: Exception) { true }
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun hostName(): String = Build.MODEL ?: "android"

    fun peerIdValue(): String = peerId
}
