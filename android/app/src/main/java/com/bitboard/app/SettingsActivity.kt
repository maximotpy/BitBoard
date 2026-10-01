package com.bitboard.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.bitboard.app.engine.SettingsStore

/**
 * Settings screen: QoL toggles + the three blacklists (images by content
 * hash, boards, peers). Every blacklist entry can be removed here.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    private lateinit var swShowLog: SwitchCompat
    private lateinit var swConfirmLeave: SwitchCompat
    private lateinit var imageList: LinearLayout
    private lateinit var boardList: LinearLayout
    private lateinit var peerList: LinearLayout
    private lateinit var emptyImages: TextView
    private lateinit var emptyBoards: TextView
    private lateinit var emptyPeers: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = App.engine(applicationContext).settings

        swShowLog = findViewById(R.id.swShowLog)
        swConfirmLeave = findViewById(R.id.swConfirmLeave)
        imageList = findViewById(R.id.imageBlacklistList)
        boardList = findViewById(R.id.boardBlacklistList)
        peerList = findViewById(R.id.peerBlacklistList)
        emptyImages = findViewById(R.id.emptyImages)
        emptyBoards = findViewById(R.id.emptyBoards)
        emptyPeers = findViewById(R.id.emptyPeers)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val s = settings.current()
        swShowLog.isChecked = s.showLog
        swConfirmLeave.isChecked = s.confirmLeaveBoard
        swShowLog.setOnCheckedChangeListener { _, checked ->
            settings.setFlag(SettingsStore.KEY_SHOW_LOG, checked)
        }
        swConfirmLeave.setOnCheckedChangeListener { _, checked ->
            settings.setFlag(SettingsStore.KEY_CONFIRM_LEAVE, checked)
        }

        findViewById<TextView>(R.id.peerIdLabel).text =
            getString(R.string.peer_id_label, App.engine(applicationContext).peerIdValue())

        renderBlacklists()
    }

    private fun renderBlacklists() {
        val s = settings.current()

        imageList.removeAllViews()
        emptyImages.visibility = if (s.imageHashes.isEmpty()) View.VISIBLE else View.GONE
        for (hash in s.imageHashes.sorted()) {
            addEntry(imageList, hash.take(16) + "…") { settings.removeImageHash(hash); renderBlacklists() }
        }

        boardList.removeAllViews()
        emptyBoards.visibility = if (s.boards.isEmpty()) View.VISIBLE else View.GONE
        for (name in s.boards.sorted()) {
            addEntry(boardList, name) { settings.removeBoard(name); renderBlacklists() }
        }

        peerList.removeAllViews()
        emptyPeers.visibility = if (s.peers.isEmpty()) View.VISIBLE else View.GONE
        for (id in s.peers.sorted()) {
            addEntry(peerList, id.take(12) + "…") { settings.removePeer(id); renderBlacklists() }
        }
    }

    private fun addEntry(container: LinearLayout, label: String, onRemove: () -> Unit) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_blacklist_entry, container, false)
        v.findViewById<TextView>(R.id.entryLabel).text = label
        v.findViewById<Button>(R.id.btnRemove).setOnClickListener {
            onRemove()
            Toast.makeText(this, R.string.removed_toast, Toast.LENGTH_SHORT).show()
        }
        container.addView(v)
    }
}