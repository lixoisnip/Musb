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
    private val onCustomClick: ((String) -> Unit)? = null,
    private val onUpClick: (() -> Unit)? = null,
    private val hierarchicalIndent: Boolean = true
) : RecyclerView.Adapter<FileEntryAdapter.EntryViewHolder>() {

    data class EntryItem(
        val documentFile: DocumentFile? = null,
        val isUpItem: Boolean = false,
        val customId: String? = null,
        val customName: String? = null,
        val customIcon: String? = null,
        val isCustomFolder: Boolean = false,
        val isEnabled: Boolean = true,
        val depth: Int = 0
    )

    private val items = mutableListOf<EntryItem>()
    private var selectedKey: String? = null
    private var highlightedUri: String? = null

    fun submitList(newItems: List<EntryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelectedKey(key: String?) {
        selectedKey = key
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
            holder.name.isActivated = false
            holder.icon.isActivated = false
            holder.duration.isActivated = false
            holder.itemView.setPadding(dpPadding(BASE_PADDING_DP, holder.itemView), holder.itemView.paddingTop, holder.itemView.paddingRight, holder.itemView.paddingBottom)
            holder.itemView.setOnClickListener { onUpClick?.invoke() }
            return
        }

        if (!item.customId.isNullOrBlank()) {
            holder.name.text = item.customName ?: "-"
            holder.icon.text = item.customIcon ?: if (item.isCustomFolder) "📁" else "•"
            holder.duration.visibility = View.GONE
            holder.playFolder.visibility = View.GONE
            val isActive = item.customId == selectedKey
            val isEnabled = item.isEnabled
            holder.itemView.isActivated = isActive
            holder.name.isActivated = isActive
            holder.icon.isActivated = isActive
            holder.duration.isActivated = false
            holder.itemView.alpha = if (isEnabled) 1f else 0.55f
            holder.itemView.setPadding(dpPadding(BASE_PADDING_DP, holder.itemView), holder.itemView.paddingTop, holder.itemView.paddingRight, holder.itemView.paddingBottom)
            holder.itemView.setOnClickListener {
                if (isEnabled) onCustomClick?.invoke(item.customId)
            }
            return
        }

        val documentFile = item.documentFile ?: return
        holder.name.text = documentFile.name ?: "-"

        val uri = documentFile.uri.toString()
        val isActive = uri == selectedKey || uri == highlightedUri
        holder.itemView.isActivated = isActive
        holder.name.isActivated = isActive
        holder.icon.isActivated = isActive
        holder.duration.isActivated = isActive
        holder.itemView.setPadding(resolveStartPadding(documentFile, holder.itemView), holder.itemView.paddingTop, holder.itemView.paddingRight, holder.itemView.paddingBottom)

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

    private fun resolveStartPadding(documentFile: DocumentFile, view: View): Int {
        if (!hierarchicalIndent) return dpPadding(BASE_PADDING_DP, view)
        val nestedLevel = items.firstOrNull { it.documentFile?.uri == documentFile.uri }?.depth?.coerceAtLeast(0) ?: 0
        return dpPadding(BASE_PADDING_DP + (nestedLevel * INDENT_STEP_DP), view)
    }

    private fun dpPadding(value: Int, view: View? = null): Int {
        val baseView = view ?: return value
        return (value * baseView.resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val TAG = "FileEntryAdapter"
        private const val BASE_PADDING_DP = 8
        private const val INDENT_STEP_DP = 16
    }
}
