package com.ghostmikz.ghostly.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonMusicFolderTest {
    private fun filtered(folder: String) = MusicRepository.isNonMusicFolder(folder)

    @Test fun soundFoldersAreFiltered() {
        assertTrue(filtered("Recordings"))
        assertTrue(filtered("Music/Call recordings"))
        assertTrue(filtered("Voice Recorder"))
        assertTrue(filtered("Notifications"))
        assertTrue(filtered("Podcasts/Show"))
        assertTrue(filtered("VoiceNotes"))
    }

    @Test fun musicFoldersAreKept() {
        assertFalse(filtered("Music/Callum"))
        assertFalse(filtered("Music/Voices"))
        assertFalse(filtered("Music/Recorded live"))
        assertFalse(filtered("Download"))
        assertFalse(filtered(""))
    }
}
