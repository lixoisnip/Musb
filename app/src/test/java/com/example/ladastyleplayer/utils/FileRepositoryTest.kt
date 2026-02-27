package com.example.ladastyleplayer.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRepositoryTest {

    private val repo = FileRepository()

    @Test
    fun `supported audio extensions are detected case-insensitively`() {
        assertTrue(repo.isSupportedAudio("track.MP3"))
        assertTrue(repo.isSupportedAudio("track.flac"))
        assertTrue(repo.isSupportedAudio("track.Wav"))
    }

    @Test
    fun `unsupported files are rejected`() {
        assertFalse(repo.isSupportedAudio("track.txt"))
        assertFalse(repo.isSupportedAudio(""))
        assertFalse(repo.isSupportedAudio(null))
    }
}
