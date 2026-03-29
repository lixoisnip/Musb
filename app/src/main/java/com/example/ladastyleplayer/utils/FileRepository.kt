package com.example.ladastyleplayer.utils

import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Repository for SAF-backed folder browsing and audio file collection.
 */
class FileRepository {

    data class BranchContent(
        val subfolders: List<DocumentFile>,
        val audioFiles: List<DocumentFile>
    )

    /**
     * Returns children sorted by directories first and then files, all alphabetically.
     */
    suspend fun listChildren(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        val children = folder.listFiles().toList()
        children.sortedWith(entryComparator)
    }

    /**
     * Collects supported audio files from [folder] without recursion.
     */
    suspend fun listSupportedAudioFiles(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        folder.listFiles()
            .filter { it.isFile && isSupportedAudio(it.name) }
            .sortedWith(entryComparator)
    }

    /**
     * Returns true when [folder] has supported audio files directly or in descendants.
     */
    suspend fun hasMusicInBranch(folder: DocumentFile): Boolean = withContext(Dispatchers.IO) {
        hasMusicInBranchInternal(folder)
    }

    /**
     * Returns folder children that are meaningful for music browsing.
     */
    suspend fun collectMusicRelevantFolders(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        folder.listFiles()
            .filter { it.isDirectory && hasMusicInBranchInternal(it) }
            .sortedWith(entryComparator)
    }

    /**
     * Collects all supported audio files recursively from [folder].
     */
    suspend fun collectSupportedAudioRecursively(folder: DocumentFile): List<DocumentFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DocumentFile>()
        collectSupportedAudioRecursivelyInternal(folder, result)
        result.sortedWith(entryComparator)
    }

    /**
     * Collects right-panel content for a selected branch.
     * - subfolders are only direct children that lead to music
     * - files are all branch tracks recursively
     */
    suspend fun collectBranchContent(folder: DocumentFile): BranchContent = withContext(Dispatchers.IO) {
        val relevantSubfolders = folder.listFiles()
            .filter { it.isDirectory && hasMusicInBranchInternal(it) }
            .sortedWith(entryComparator)

        val files = mutableListOf<DocumentFile>()
        collectSupportedAudioRecursivelyInternal(folder, files)

        BranchContent(
            subfolders = relevantSubfolders,
            audioFiles = files.sortedWith(entryComparator)
        )
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

    private val entryComparator = compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase(Locale.ROOT) ?: "" })
}
