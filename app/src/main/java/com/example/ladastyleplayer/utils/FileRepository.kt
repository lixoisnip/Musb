package com.example.ladastyleplayer.utils

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Repository for SAF-backed folder browsing and recursive audio file collection.
 */
class FileRepository {

    /**
     * Returns children sorted by directories first and then files, all alphabetically.
     */
    suspend fun listChildren(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        val children = folder.listFiles().toList()
        children.sortedWith(entryComparator)
    }

    /**
     * Recursively collects supported audio files from [root] and its sub-folders.
     */
    suspend fun collectAudioRecursive(root: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        val output = mutableListOf<DocumentFile>()
        collectRecursiveInternal(root, output)
        output
    }

    private fun collectRecursiveInternal(folder: DocumentFile, output: MutableList<DocumentFile>) {
        val children = folder.listFiles().sortedWith(entryComparator)
        children.forEach { child ->
            if (child.isDirectory) {
                collectRecursiveInternal(child, output)
            } else if (isSupportedAudio(child.name)) {
                output += child
            }
        }
    }

    internal fun isSupportedAudio(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.endsWith(".mp3") || normalized.endsWith(".flac") || normalized.endsWith(".wav")
    }

    private val entryComparator = compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase(Locale.ROOT) ?: "" })
}
