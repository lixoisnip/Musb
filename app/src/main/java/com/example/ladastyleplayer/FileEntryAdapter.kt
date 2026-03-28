package com.example.ladastyleplayer

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.RecyclerView

/**
 * Recycler adapter for folder/file entries shown in left and right lists.
 */
class FileEntryAdapter(
    private val showPlayFolderButton: Boolean,
    private val onFolderClick: (DocumentFile) -> Unit,
    private val onFileClick: (DocumentFile) -> Unit,
    private val onPlayFolder: (DocumentFile) -> Unit
) : RecyclerView.Adapter<FileEntryAdapter.EntryViewHolder>() {

    private val items = mutableListOf<DocumentFile>()
    private var selectedUri: String? = null
    private var highlightedUri: String? = null

    fun submitList(newItems: List<DocumentFile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelectedUri(uri: String?) {
        selectedUri = uri
        notifyDataSetChanged()
    }

    fun setHighlightedUri(uri: String?) {
        highlightedUri = uri
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        return EntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name ?: "-"

        val uri = item.uri.toString()
        holder.itemView.isActivated = uri == selectedUri || uri == highlightedUri

        val depth = documentDepth(item.uri)
        holder.name.setPaddingRelative(8 * depth, holder.name.paddingTop, holder.name.paddingEnd, holder.name.paddingBottom)

        if (item.isDirectory) {
            holder.icon.text = "📁"
            holder.duration.visibility = View.GONE
            holder.playFolder.visibility = if (showPlayFolderButton) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onFolderClick(item) }
            holder.playFolder.setOnClickListener { onPlayFolder(item) }
        } else {
            holder.icon.text = "♪"
            holder.duration.visibility = View.VISIBLE
            holder.duration.text = "--:--"
            holder.playFolder.visibility = View.GONE
            holder.itemView.setOnClickListener { onFileClick(item) }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun documentDepth(uri: Uri): Int {
        val docId = Uri.decode(uri.toString()).substringAfterLast(":", "")
        return docId.count { it == '/' }.coerceIn(0, 4)
    }

    class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.entryIcon)
        val name: TextView = view.findViewById(R.id.entryName)
        val duration: TextView = view.findViewById(R.id.entryDuration)
        val playFolder: Button = view.findViewById(R.id.playFolderButton)
    }
}
