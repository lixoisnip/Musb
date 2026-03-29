package com.example.ladastyleplayer

import android.util.Log
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
    private val onPlayFolder: (DocumentFile) -> Unit,
    private val onUpClick: (() -> Unit)? = null
) : RecyclerView.Adapter<FileEntryAdapter.EntryViewHolder>() {

    data class EntryItem(
        val documentFile: DocumentFile? = null,
        val isUpItem: Boolean = false
    )

    private val items = mutableListOf<EntryItem>()
    private var selectedUri: String? = null
    private var highlightedUri: String? = null

    fun submitList(newItems: List<EntryItem>) {
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

    fun findPositionByUri(uri: String?): Int {
        if (uri.isNullOrBlank()) return RecyclerView.NO_POSITION
        return items.indexOfFirst { it.documentFile?.uri?.toString() == uri }
    }

    fun getItemByUri(uri: String?): DocumentFile? {
        if (uri.isNullOrBlank()) return null
        return items.firstOrNull { it.documentFile?.uri?.toString() == uri }?.documentFile
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false)
        return EntryViewHolder(view)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        val item = items[position]
        if (item.isUpItem) {
            holder.name.text = ".."
            holder.icon.text = "↩"
            holder.duration.visibility = View.GONE
            holder.playFolder.visibility = View.GONE
            holder.itemView.isActivated = false
            holder.itemView.setOnClickListener { onUpClick?.invoke() }
            return
        }

        val documentFile = item.documentFile ?: return
        holder.name.text = documentFile.name ?: "-"

        val uri = documentFile.uri.toString()
        holder.itemView.isActivated = uri == selectedUri || uri == highlightedUri

        if (documentFile.isDirectory) {
            holder.icon.text = "📁"
            holder.duration.visibility = View.GONE
            holder.playFolder.visibility = if (showPlayFolderButton) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener { onFolderClick(documentFile) }
            holder.playFolder.setOnClickListener { onPlayFolder(documentFile) }
        } else {
            holder.icon.text = "♪"
            holder.duration.visibility = View.VISIBLE
            holder.duration.text = "--:--"
            holder.playFolder.visibility = View.GONE
            holder.itemView.setOnClickListener {
                Log.d(TAG, "File row tapped: uri=${documentFile.uri}")
                onFileClick(documentFile)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: TextView = view.findViewById(R.id.entryIcon)
        val name: TextView = view.findViewById(R.id.entryName)
        val duration: TextView = view.findViewById(R.id.entryDuration)
        val playFolder: Button = view.findViewById(R.id.playFolderButton)
    }

    companion object {
        private const val TAG = "FileEntryAdapter"
    }
}
