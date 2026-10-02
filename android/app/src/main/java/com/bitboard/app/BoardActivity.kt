package com.bitboard.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Board detail screen, the Android counterpart of the desktop "Gallery" tab.
 * Shows the board's images in a grid; tapping an image opens it full-screen.
 */
class BoardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOARD_NAME = "board_name"
    }

    private lateinit var boardName: String
    private lateinit var adapter: ImageAdapter

    /** Emoji palette for the board-icon picker. */
    private val iconPalette = listOf(
        "📌", "🎨", "📷", "🐱", "🐶", "🍕", "�,", "✈️", "🏖", "⛰", "🎮", "🎵",
        "💻", "📚", "💼", "🔥", "⭐", "🌙", "☀️", "🌈", "🍀", "🌊", "🎂", "🎁"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_board)

        boardName = intent.getStringExtra(EXTRA_BOARD_NAME) ?: run { finish(); return }
        findViewById<TextView>(R.id.boardTitle).text = boardName

        adapter = ImageAdapter(
            onClick = { file -> showFullImage(file) },
            onLongClick = { file -> showImageMenu(file) }
        )
        val rv = findViewById<RecyclerView>(R.id.imageGrid)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter

        // Board menu (leave / icon / blacklist) via the title.
        findViewById<View>(R.id.boardTitle).setOnLongClickListener {
            showBoardMenu(); true
        }

        // Refresh whenever the engine publishes a new snapshot (sync progress,
        // newly arrived files, …).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.engine(applicationContext).boards.collect { refresh() }
            }
        }
    }

    /** Long-press on an image: blacklist by content hash / delete. */
    private fun showImageMenu(file: File) {
        val options = arrayOf(
            getString(R.string.blacklist_image),
            getString(R.string.remove)
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> blacklistImage(file)
                    1 -> deleteImage(file)
                }
            }
            .show()
    }

    private fun blacklistImage(file: File) {
        lifecycleScope.launch {
            val hash = withContext(Dispatchers.IO) {
                App.engine(applicationContext).imageHash(file)
            }
            if (hash == null) {
                Toast.makeText(this@BoardActivity, "Could not hash image", Toast.LENGTH_SHORT).show()
                return@launch
            }
            App.engine(applicationContext).settings.addImageHash(hash)
            refresh()
            Toast.makeText(this@BoardActivity, R.string.blocked_image_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteImage(file: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove))
            .setMessage("Delete \"${file.name}\" from this board?")
            .setPositiveButton(getString(R.string.remove)) { _, _ ->
                lifecycleScope.launch {
                    App.engine(applicationContext).removeImage(boardName, file.name)
                    refresh()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Board menu: leave / icon / blacklist. */
    private fun showBoardMenu() {
        val options = arrayOf(
            getString(R.string.set_icon),
            getString(R.string.leave_board),
            getString(R.string.blacklist_board)
        )
        AlertDialog.Builder(this)
            .setTitle(boardName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showIconPicker()
                    1 -> confirmLeave()
                    2 -> blacklistBoard()
                }
            }
            .show()
    }

    private fun showIconPicker() {
        val current = App.engine(applicationContext).boards.value
            .find { it.name == boardName }?.icon ?: ""
        val palette = (iconPalette + (if (current.isNotBlank()) listOf(current) else emptyList()))
            .distinct().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_pick_title) + ", " + boardName)
            .setItems(palette) { _, which ->
                lifecycleScope.launch {
                    App.engine(applicationContext).setBoardIcon(boardName, palette[which])
                }
            }
            .setNeutralButton("Clear") { _, _ ->
                lifecycleScope.launch { App.engine(applicationContext).setBoardIcon(boardName, "") }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmLeave() {
        val engine = App.engine(applicationContext)
        if (engine.settings.current().confirmLeaveBoard) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.leave_board))
                .setMessage(getString(R.string.leave_confirm, boardName))
                .setPositiveButton(getString(R.string.leave_keep_files)) { _, _ -> doLeave(false) }
                .setNeutralButton(getString(R.string.leave_delete_files)) { _, _ -> doLeave(true) }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } else {
            doLeave(false)
        }
    }

    private fun doLeave(deleteFiles: Boolean) {
        lifecycleScope.launch {
            App.engine(applicationContext).leaveBoard(boardName, deleteFiles)
            finish()
        }
    }

    private fun blacklistBoard() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.blacklist_board))
            .setMessage("\"$boardName\" will be hidden from discovery and cannot be joined until unblacklisted in Settings.")
            .setPositiveButton("Blacklist") { _, _ ->
                App.engine(applicationContext).settings.addBoard(boardName)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                App.engine(applicationContext).boardFiles(boardName)
            }
            findViewById<TextView>(R.id.emptyHint).visibility =
                if (files.isEmpty()) View.VISIBLE else View.GONE
            adapter.submit(files)
        }
    }

    private fun showFullImage(file: File) {
        val overlay = findViewById<View>(R.id.fullImageOverlay)
        val img = findViewById<ImageView>(R.id.fullImage)
        img.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
        overlay.visibility = View.VISIBLE
        overlay.setOnClickListener { overlay.visibility = View.GONE }
    }

    private class ImageAdapter(
        val onClick: (File) -> Unit,
        val onLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<ImageAdapter.Holder>() {

        private var items: List<File> = emptyList()

        fun submit(list: List<File>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_image, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val f = items[position]
            holder.img.setImageDrawable(null)
            // Decode a downscaled thumbnail off the main thread.
            holder.img.tag = f.absolutePath
            holder.itemView.post {
                Thread {
                    val bmp = decodeThumb(f, 512)
                    holder.itemView.post {
                        if (holder.img.tag == f.absolutePath) holder.img.setImageBitmap(bmp)
                    }
                }.start()
            }
            holder.itemView.setOnClickListener { onClick(f) }
            holder.itemView.setOnLongClickListener { onLongClick(f); true }
        }

        private fun decodeThumb(f: File, maxDim: Int): android.graphics.Bitmap? {
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                var sample = 1
                while (opts.outWidth / sample > maxDim * 2 || opts.outHeight / sample > maxDim * 2) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    f.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } catch (_: Exception) {
                null
            }
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.imageThumb)
        }
    }
}
