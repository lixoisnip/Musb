package com.example.ladastyleplayer.utils

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Repository for SAF-backed folder browsing and audio file collection.
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
     * Returns true when the folder can be safely enumerated for navigation context.
     */
    suspend fun canEnumerateFolderContext(folder: DocumentFile): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!folder.exists() || !folder.isDirectory) return@runCatching false
            folder.listFiles()
            true
        }.getOrDefault(false)
    }

    /**
     * Returns only direct child folders sorted stably.
     */
    suspend fun listChildFoldersOnly(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        folder.listFiles()
            .filter { it.isDirectory }
            .let(::sortFoldersStable)
    }

    /**
     * Collects all supported audio files recursively from [folder].
     */
    suspend fun collectSupportedAudioRecursively(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DocumentFile>()
        collectSupportedAudioRecursivelyInternal(folder, result)
        val sorted = sortTracksStable(result)
        Log.d(TAG, "collectSupportedAudioRecursively folder=${folder.uri} tracks=${sorted.size}")
        sorted
    }

    fun sortFoldersStable(folders: List<DocumentFile>): List<DocumentFile> {
        return folders.sortedWith(compareBy<DocumentFile>({ it.name?.lowercase(Locale.ROOT) ?: "" }, { it.uri.toString() }))
    }

    fun sortTracksStable(tracks: List<DocumentFile>): List<DocumentFile> {
        return tracks.sortedWith(compareBy<DocumentFile>({ it.name?.lowercase(Locale.ROOT) ?: "" }, { it.uri.toString() }))
    }

    internal fun isSupportedAudio(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = name.lowercase(Locale.ROOT)
        return normalized.endsWith(".mp3") || normalized.endsWith(".flac") || normalized.endsWith(".wav")
    }

    private fun collectSupportedAudioRecursivelyInternal(folder: DocumentFile, acc: MutableList<DocumentFile>) {
        folder.listFiles().sortedWith(entryComparator).forEach { child ->
            when {
                child.isFile && isSupportedAudio(child.name) -> acc.add(child)
                child.isDirectory -> collectSupportedAudioRecursivelyInternal(child, acc)
            }
        }
    }

    private val entryComparator = compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase(Locale.ROOT) ?: "" })

    companion object {
        private const val TAG = "FileRepository"
    }
}
