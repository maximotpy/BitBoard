package com.bitboard.app.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User settings + blacklists, persisted in SharedPreferences (Android) and
 * mirrored to a JSON file so the desktop app's data dir stays portable.
 *
 * Blacklists:
 *  - imageHashes: SHA-1 of image CONTENT — an image is blocked on every
 *    device that has the same bytes, regardless of its file name.
 *  - boards: board names that must never be joined and are hidden from the
 *    "discovered on network" list.
 *  - peers: peerIds (from LAN beacons / relay announcements) whose boards and
 *    merges are ignored entirely.
 */
class SettingsStore(context: Context) {

    data class Settings(
        // QoL toggles
        val showLog: Boolean = true,
        val autoJoinDiscovered: Boolean = false,
        val confirmLeaveBoard: Boolean = true,
        // Blacklists
        val imageHashes: Set<String> = emptySet(),
        val boards: Set<String> = emptySet(),
        val peers: Set<String> = emptySet()
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bitboard_settings", Context.MODE_PRIVATE)

    /** JSON mirror next to state.json (desktop parity / easy inspection). */
    private val jsonFile: File = File(File(context.filesDir, "data"), "settings.json")

    @Volatile private var cached: Settings = load()

    /** Live settings; re-read on every change so all callers see the same view. */
    fun current(): Settings = cached

    fun update(transform: (Settings) -> Settings): Settings {
        val next = transform(cached)
        cached = next
        persist(next)
        return next
    }

    /* ---------------- individual helpers ---------------- */

    fun addImageHash(hash: String) = update { it.copy(imageHashes = it.imageHashes + hash.lowercase()) }
    fun removeImageHash(hash: String) = update { it.copy(imageHashes = it.imageHashes - hash.lowercase()) }
    fun isImageBlocked(hash: String): Boolean = cached.imageHashes.contains(hash.lowercase())

    fun addBoard(name: String) = update { it.copy(boards = it.boards + name.trim()) }
    fun removeBoard(name: String) = update { it.copy(boards = it.boards - name.trim()) }
    fun isBoardBlocked(name: String): Boolean = cached.boards.contains(name.trim())

    fun addPeer(peerId: String) = update { it.copy(peers = it.peers + peerId.trim()) }
    fun removePeer(peerId: String) = update { it.copy(peers = it.peers - peerId.trim()) }
    fun isPeerBlocked(peerId: String): Boolean =
        peerId.isNotEmpty() && cached.peers.contains(peerId.trim())

    fun setFlag(key: String, value: Boolean) = update { s ->
        when (key) {
            KEY_SHOW_LOG -> s.copy(showLog = value)
            KEY_AUTO_JOIN -> s.copy(autoJoinDiscovered = value)
            KEY_CONFIRM_LEAVE -> s.copy(confirmLeaveBoard = value)
            else -> s
        }
    }

    /* ---------------- persistence ---------------- */

    private fun load(): Settings {
        // SharedPreferences is the source of truth; the JSON file is a mirror.
        val p = prefs
        val hashes = p.getStringSet(KEY_IMAGE_HASHES, emptySet()) ?: emptySet()
        val boards = p.getStringSet(KEY_BOARDS, emptySet()) ?: emptySet()
        val peers = p.getStringSet(KEY_PEERS, emptySet()) ?: emptySet()
        return Settings(
            showLog = p.getBoolean(KEY_SHOW_LOG, true),
            autoJoinDiscovered = p.getBoolean(KEY_AUTO_JOIN, false),
            confirmLeaveBoard = p.getBoolean(KEY_CONFIRM_LEAVE, true),
            imageHashes = hashes.map { it.lowercase() }.toSet(),
            boards = boards,
            peers = peers
        )
    }

    private fun persist(s: Settings) {
        prefs.edit()
            .putBoolean(KEY_SHOW_LOG, s.showLog)
            .putBoolean(KEY_AUTO_JOIN, s.autoJoinDiscovered)
            .putBoolean(KEY_CONFIRM_LEAVE, s.confirmLeaveBoard)
            .putStringSet(KEY_IMAGE_HASHES, s.imageHashes)
            .putStringSet(KEY_BOARDS, s.boards)
            .putStringSet(KEY_PEERS, s.peers)
            .apply()
        writeJsonMirror(s)
    }

    private fun writeJsonMirror(s: Settings) {
        try {
            val obj = JSONObject()
                .put("showLog", s.showLog)
                .put("autoJoinDiscovered", s.autoJoinDiscovered)
                .put("confirmLeaveBoard", s.confirmLeaveBoard)
                .put("imageHashes", JSONArray(s.imageHashes.sorted()))
                .put("boards", JSONArray(s.boards.sorted()))
                .put("peers", JSONArray(s.peers.sorted()))
            jsonFile.parentFile?.mkdirs()
            jsonFile.writeText(obj.toString(2))
        } catch (_: Exception) {}
    }

    companion object {
        const val KEY_SHOW_LOG = "showLog"
        const val KEY_AUTO_JOIN = "autoJoinDiscovered"
        const val KEY_CONFIRM_LEAVE = "confirmLeaveBoard"
        const val KEY_IMAGE_HASHES = "imageHashes"
        const val KEY_BOARDS = "boards"
        const val KEY_PEERS = "peers"
    }
}