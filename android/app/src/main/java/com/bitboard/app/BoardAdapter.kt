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
 * file count / peers / progress, an "Add image" button, a "Join board" row,
 * and a "Discovered on network" section listing boards other devices
 * announced that this device has NOT joined (nothing is auto-joined).
 *
 * ALL rows (boards + join + discovered) are submitted to ListAdapter as ONE
 * list so DiffUtil sees every change atomically. The previous design kept the
 * discovered rows OUT of the submitted list and overrode getItemCount() ,
 * when the discovered section changed (a peer created a board, or a join
 * removed a row) the item count changed without a matching notify, and
 * DiffUtil's granular updates, computed in the boards-only coordinate
 * space, landed on the wrong rows: RecyclerView "Inconsistency detected" /
 * IndexOutOfBoundsException → app crash.
 */
class BoardAdapter(
    private val onAddImage: (String) -> Unit,
    private val onJoin: () -> Unit,
    private val onJoinDiscovered: (String) -> Unit = {},
    private val onOpenBoard: (String) -> Unit = {},
    private val onBoardMenu: (String) -> Unit = {}
) : ListAdapter<BoardAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_BOARD = 0
        private const val TYPE_JOIN = 1
        private const val TYPE_EMPTY = 2
        private const val TYPE_DISCOVERED_HEADER = 3
        private const val TYPE_DISCOVERED = 4
    }

    /** One visual row. The whole screen is a single ListAdapter list. */
    sealed class Row {
        data class Board(val snapshot: BoardSnapshot) : Row()
        object Join : Row()
        object Empty : Row()
        object DiscoveredHeader : Row()
        data class Discovered(val name: String) : Row()
    }

    /** Single entry point: boards + discovered names are combined into rows. */
    fun submit(boards: List<BoardSnapshot>, discovered: List<String>) {
        // A discovered name that we already joined must not render twice.
        val pending = discovered.filter { n -> boards.none { it.name == n } }
        val rows = ArrayList<Row>(boards.size + pending.size + 2)
        if (boards.isEmpty() && pending.isEmpty()) {
            rows.add(Row.Empty)
        } else {
            for (b in boards) rows.add(Row.Board(b))
            rows.add(Row.Join)
            if (pending.isNotEmpty()) {
                rows.add(Row.DiscoveredHeader)
                for (name in pending) rows.add(Row.Discovered(name))
            }
        }
        submitList(rows)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Board -> TYPE_BOARD
        is Row.Join -> TYPE_JOIN
        is Row.Empty -> TYPE_EMPTY
        is Row.DiscoveredHeader -> TYPE_DISCOVERED_HEADER
        is Row.Discovered -> TYPE_DISCOVERED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_BOARD -> BoardHolder(inf.inflate(R.layout.item_board, parent, false))
            TYPE_JOIN -> JoinHolder(inf.inflate(R.layout.item_join, parent, false))
            TYPE_DISCOVERED_HEADER -> DiscoveredHeaderHolder(inf.inflate(R.layout.item_discovered_header, parent, false))
            TYPE_DISCOVERED -> DiscoveredHolder(inf.inflate(R.layout.item_discovered, parent, false))
            else -> EmptyHolder(inf.inflate(R.layout.item_empty, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is BoardHolder -> holder.bind(
                (getItem(position) as Row.Board).snapshot,
                onAddImage, onOpenBoard, onBoardMenu
            )
            is JoinHolder -> holder.bind(onJoin)
            is DiscoveredHolder -> holder.bind((getItem(position) as Row.Discovered).name, onJoinDiscovered)
            else -> Unit
        }
    }

    class BoardHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: TextView = v.findViewById(R.id.boardIcon)
        private val name: TextView = v.findViewById(R.id.boardName)
        private val meta: TextView = v.findViewById(R.id.boardMeta)
        private val progress: ProgressBar = v.findViewById(R.id.boardProgress)
        private val addBtn: Button = v.findViewById(R.id.btnAddImage)

        fun bind(
            b: BoardSnapshot,
            onAddImage: (String) -> Unit,
            onOpenBoard: (String) -> Unit,
            onBoardMenu: (String) -> Unit
        ) {
            name.text = b.name
            if (b.icon.isNotBlank()) {
                icon.text = b.icon
                icon.visibility = View.VISIBLE
            } else {
                icon.visibility = View.GONE
            }
            meta.text = "${b.fileCount} image(s) · ${b.peers} peer(s) · " +
                "${formatBytes(b.totalBytes)} · ${(b.progress * 100).toInt()}%"
            progress.visibility = if (b.progress >= 1f) View.GONE else View.VISIBLE
            progress.progress = (b.progress * 100).toInt()
            addBtn.setOnClickListener { onAddImage(b.name) }
            itemView.setOnClickListener { onOpenBoard(b.name) }
            // Long-press: leave / icon / blacklist (QoL menu).
            itemView.setOnLongClickListener { onBoardMenu(b.name); true }
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

    class DiscoveredHeaderHolder(v: View) : RecyclerView.ViewHolder(v)

    class DiscoveredHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.discoveredName)
        private val btn: Button = v.findViewById(R.id.btnJoinDiscovered)
        fun bind(boardName: String, onJoinDiscovered: (String) -> Unit) {
            name.text = boardName
            btn.setOnClickListener { onJoinDiscovered(boardName) }
        }
    }

    class EmptyHolder(v: View) : RecyclerView.ViewHolder(v)

    private object DIFF : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row): Boolean = when {
            a is Row.Board && b is Row.Board -> a.snapshot.name == b.snapshot.name
            a is Row.Discovered && b is Row.Discovered -> a.name == b.name
            else -> a::class == b::class
        }

        override fun areContentsTheSame(a: Row, b: Row): Boolean = a == b
    }
}
