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
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: BoardAdapter
    private var pendingBoardForImage: String? = null

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
            onOpenBoard = { name ->
                startActivity(
                    Intent(this, BoardActivity::class.java)
                        .putExtra(BoardActivity.EXTRA_BOARD_NAME, name)
                )
            }
        )
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.boardsList)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabNewBoard).setOnClickListener {
            showNewBoardDialog()
        }

        requestNotificationPermissionIfNeeded()
        BitBoardService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    App.engine(applicationContext).boards.collect { adapter.submit(it) }
                }
                launch {
                    App.engine(applicationContext).logs.collect { logs ->
                        if (logs.isNotEmpty()) {
                            findViewById<android.widget.TextView>(R.id.logLine).text = logs.last()
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
