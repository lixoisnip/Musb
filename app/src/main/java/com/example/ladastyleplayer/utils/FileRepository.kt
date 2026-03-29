package com.example.ladastyleplayer.utils

import androidx.documentfile.provider.DocumentFile
import android.util.Log
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
     * Returns only direct child folders sorted stably.
     */
    suspend fun listChildFoldersOnly(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        folder.listFiles()
            .filter { it.isDirectory }
            .let(::sortFoldersStable)
    }

    /**
     * Collects supported audio files from [folder] without recursion.
     */
    suspend fun listSupportedAudioFiles(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        folder.listFiles()
            .filter { it.isFile && isSupportedAudio(it.name) }
            .let(::sortTracksStable)
    }

    /**
     * Returns true when [folder] has supported audio files directly or in descendants.
     */
    suspend fun hasMusicInBranch(folder: DocumentFile): Boolean = withContext(Dispatchers.IO) {
        val hasMusic = hasMusicInBranchInternal(folder)
        Log.d(TAG, "hasMusicInBranch folder=${folder.uri} result=$hasMusic")
        hasMusic
    }

    /**
     * Returns folder children that are meaningful for music browsing.
     */
    suspend fun collectMusicRelevantFolders(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        val folders = folder.listFiles()
            .filter { it.isDirectory && hasMusicInBranchInternal(it) }
            .let(::sortFoldersStable)
        Log.d(TAG, "collectMusicRelevantFolders root=${folder.uri} size=${folders.size}")
        folders
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

    /**
     * Collects all folders recursively under [root], including [root].
     */
    suspend fun collectFolderTree(root: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DocumentFile>()
        collectFoldersRecursivelyInternal(root, result)
        Log.d(TAG, "collectFolderTree root=${root.uri} folders=${result.size}")
        result
    }

    fun suggestSourceLabel(folderName: String?, treeUri: String): String {
        val normalized = (folderName ?: "").trim()
        if (normalized.isNotBlank()) return normalized
        val lowerUri = treeUri.lowercase(Locale.ROOT)
        return when {
            "download" in lowerUri -> "Download"
            "music" in lowerUri -> "Music"
            "usb" in lowerUri -> "USB"
            "sd" in lowerUri || "card" in lowerUri -> "SD card"
            "primary" in lowerUri -> "Internal storage"
            else -> "Source"
        }
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

    private fun hasMusicInBranchInternal(folder: DocumentFile): Boolean {
        folder.listFiles().forEach { child ->
            if (child.isFile && isSupportedAudio(child.name)) return true
            if (child.isDirectory && hasMusicInBranchInternal(child)) return true
        }
        return false
    }

    private fun collectSupportedAudioRecursivelyInternal(folder: DocumentFile, acc: MutableList<DocumentFile>) {
        folder.listFiles().sortedWith(entryComparator).forEach { child ->
            when {
                child.isFile && isSupportedAudio(child.name) -> acc.add(child)
                child.isDirectory -> collectSupportedAudioRecursivelyInternal(child, acc)
            }
        }
    }

    private fun collectFoldersRecursivelyInternal(folder: DocumentFile, acc: MutableList<DocumentFile>) {
        acc.add(folder)
        folder.listFiles()
            .filter { it.isDirectory }
            .let(::sortFoldersStable)
            .forEach { child ->
                collectFoldersRecursivelyInternal(child, acc)
            }
    }

    private val entryComparator = compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase(Locale.ROOT) ?: "" })

    companion object {
        private const val TAG = "FileRepository"
    }
}
