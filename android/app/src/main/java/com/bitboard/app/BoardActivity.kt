package com.bitboard.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
 * Board detail screen — the Android counterpart of the desktop "Gallery" tab.
 * Shows the board's images in a grid; tapping an image opens it full-screen.
 */
class BoardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOARD_NAME = "board_name"
    }

    private lateinit var boardName: String
    private lateinit var adapter: ImageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_board)

        boardName = intent.getStringExtra(EXTRA_BOARD_NAME) ?: run { finish(); return }
        findViewById<TextView>(R.id.boardTitle).text = boardName

        adapter = ImageAdapter { file -> showFullImage(file) }
        val rv = findViewById<RecyclerView>(R.id.imageGrid)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter

        // Refresh whenever the engine publishes a new snapshot (sync progress,
        // newly arrived files, …).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.engine(applicationContext).boards.collect { refresh() }
            }
        }
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

    private class ImageAdapter(val onClick: (File) -> Unit) :
        RecyclerView.Adapter<ImageAdapter.Holder>() {

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
