package com.bitboard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bitboard.app.engine.BitBoardEngine
import com.bitboard.app.engine.BitBoardService
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: BoardAdapter
    private var pendingBoardForImage: String? = null

    /** Emoji palette for the board-icon picker. */
    private val iconPalette = listOf(
        "📌", "🎨", "📷", "🐱", "🐶", "🍕", "�,", "✈️", "🏖", "⛰", "🎮", "🎵",
        "💻", "📚", "💼", "🔥", "⭐", "🌙", "☀️", "🌈", "🍀", "🌊", "🎂", "🎁"
    )

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            val board = pendingBoardForImage
            if (uri != null && board != null) {
                copyAndAdd(board, uri)
            }
            pendingBoardForImage = null
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = BoardAdapter(
            onAddImage = { name -> pickImage(name) },
            onJoin = { showJoinDialog() },
            onJoinDiscovered = { name -> joinDiscovered(name) },
            onOpenBoard = { name ->
                startActivity(
                    Intent(this, BoardActivity::class.java)
                        .putExtra(BoardActivity.EXTRA_BOARD_NAME, name)
                )
            },
            onBoardMenu = { name -> showBoardMenu(name) }
        )
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.boardsList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabNewBoard).setOnClickListener {
            showNewBoardDialog()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
        BitBoardService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // ONE combined flow → ONE submit() per change. Submitting the
                // boards and discovered lists separately let the adapter's
                // item count and the diffed list get out of sync (crash when a
                // peer created a board or when joining one).
                launch {
                    combine(
                        App.engine(applicationContext).boards,
                        App.engine(applicationContext).discovered
                    ) { boards, discovered -> boards to discovered }
                        .collect { (boards, discovered) -> adapter.submit(boards, discovered) }
                }
                launch {
                    App.engine(applicationContext).logs.collect { logs ->
                        if (logs.isNotEmpty()) {
                            val logView = findViewById<android.widget.TextView>(R.id.logLine)
                            logView.text = logs.takeLast(6).joinToString("\n")
                            logView.visibility =
                                if (App.engine(applicationContext).settings.current().showLog)
                                    View.VISIBLE else View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun showNewBoardDialog() {
        val input = EditText(this).apply { hint = "Board name (e.g. Vacation)" }
        AlertDialog.Builder(this)
            .setTitle("New board")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            App.engine(applicationContext).createBoard(name)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Long-press menu on a board row: leave / icon / blacklist. */
    private fun showBoardMenu(name: String) {
        val options = arrayOf(
            getString(R.string.set_icon),
            getString(R.string.leave_board),
            getString(R.string.blacklist_board)
        )
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showIconPicker(name)
                    1 -> confirmLeave(name)
                    2 -> blacklistBoard(name)
                }
            }
            .show()
    }

    private fun showIconPicker(name: String) {
        val current = App.engine(applicationContext).boards.value
            .find { it.name == name }?.icon ?: ""
        val palette = (iconPalette + (if (current.isNotBlank()) listOf(current) else emptyList()))
            .distinct().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_pick_title) + ", " + name)
            .setItems(palette) { _, which ->
                lifecycleScope.launch {
                    App.engine(applicationContext).setBoardIcon(name, palette[which])
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                lifecycleScope.launch { App.engine(applicationContext).setBoardIcon(name, "") }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmLeave(name: String) {
        val engine = App.engine(applicationContext)
        if (engine.settings.current().confirmLeaveBoard) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.leave_board))
                .setMessage(getString(R.string.leave_confirm, name))
                .setPositiveButton(getString(R.string.leave_keep_files)) { _, _ -> doLeave(name, false) }
                .setNeutralButton(getString(R.string.leave_delete_files)) { _, _ -> doLeave(name, true) }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else {
            doLeave(name, false)
        }
    }

    private fun doLeave(name: String, deleteFiles: Boolean) {
        lifecycleScope.launch {
            try {
                App.engine(applicationContext).leaveBoard(name, deleteFiles)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun blacklistBoard(name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.blacklist_board))
            .setMessage("\"$name\" will be hidden from discovery and cannot be joined until unblacklisted in Settings.")
            .setPositiveButton("Blacklist") { _, _ ->
                App.engine(applicationContext).settings.addBoard(name)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun joinDiscovered(name: String) {
        lifecycleScope.launch {
            try {
                App.engine(applicationContext).joinBoard(name)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showJoinDialog() {
        val input = EditText(this).apply {
            hint = "Board name (must match exactly)"
        }
        AlertDialog.Builder(this)
            .setTitle("Join board")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        try {
                            App.engine(applicationContext).joinBoard(name)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickImage(boardName: String) {
        pendingBoardForImage = boardName
        imagePicker.launch("image/*")
    }

    private fun copyAndAdd(boardName: String, uri: Uri) {
        lifecycleScope.launch {
            try {
                val tmp = File(cacheDir, "import-" + System.currentTimeMillis() +
                    "-" + queryFileName(uri))
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot read image")
                App.engine(applicationContext).addImage(boardName, tmp)
                tmp.delete()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryFileName(uri: Uri): String {
        var name = "image.png"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
        }
        return name
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
