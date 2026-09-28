package com.bitboard.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bitboard.app.engine.BitBoardEngine.BoardSnapshot

/**
 * RecyclerView adapter mirroring the desktop UI: a list of boards, each with
 * file count / peers / progress, an "Add image" button, and a "Join board"
 * row at the bottom.
 */
class BoardAdapter(
    private val onAddImage: (String) -> Unit,
    private val onJoin: () -> Unit,
    private val onOpenBoard: (String) -> Unit = {}
) : ListAdapter<BoardSnapshot, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_BOARD = 0
        private const val TYPE_JOIN = 1
        private const val TYPE_EMPTY = 2
    }

    private var items: List<BoardSnapshot> = emptyList()

    fun submit(list: List<BoardSnapshot>) {
        items = list
        submitList(list)
    }

    override fun getItemCount(): Int =
        if (items.isEmpty()) 1 else items.size + 1

    override fun getItemViewType(position: Int): Int = when {
        items.isEmpty() -> TYPE_EMPTY
        position < items.size -> TYPE_BOARD
        else -> TYPE_JOIN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BOARD -> BoardHolder(inf.inflate(R.layout.item_board, parent, false))
            TYPE_JOIN -> JoinHolder(inf.inflate(R.layout.item_join, parent, false))
            else -> EmptyHolder(inf.inflate(R.layout.item_empty, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is BoardHolder -> holder.bind(items[position], onAddImage, onOpenBoard)
            is JoinHolder -> holder.bind(onJoin)
            is EmptyHolder -> Unit
        }
    }

    class BoardHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.boardName)
        private val meta: TextView = v.findViewById(R.id.boardMeta)
        private val progress: ProgressBar = v.findViewById(R.id.boardProgress)
        private val addBtn: Button = v.findViewById(R.id.btnAddImage)

        fun bind(b: BoardSnapshot, onAddImage: (String) -> Unit, onOpenBoard: (String) -> Unit) {
            name.text = b.name
            meta.text = "${b.fileCount} image(s) · ${b.peers} peer(s) · " +
                "${formatBytes(b.totalBytes)} · ${(b.progress * 100).toInt()}%"
            progress.visibility = if (b.progress >= 1f) View.GONE else View.VISIBLE
            progress.progress = (b.progress * 100).toInt()
            addBtn.setOnClickListener { onAddImage(b.name) }
            itemView.setOnClickListener { onOpenBoard(b.name) }
        }

        private fun formatBytes(n: Long): String = when {
            n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0)
            n >= 1 shl 10 -> "%.1f KB".format(n / 1024.0)
            else -> "$n B"
        }
    }

    class JoinHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val btn: Button = v.findViewById(R.id.btnJoinBoard)
        fun bind(onJoin: () -> Unit) {
            btn.setOnClickListener { onJoin() }
        }
    }

    class EmptyHolder(v: View) : RecyclerView.ViewHolder(v)

    private object DIFF : DiffUtil.ItemCallback<BoardSnapshot>() {
        override fun areItemsTheSame(a: BoardSnapshot, b: BoardSnapshot) = a.name == b.name
        override fun areContentsTheSame(a: BoardSnapshot, b: BoardSnapshot) = a == b
    }
}
