package com.theycallmeboxy.caulker.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pure-JVM tests for the multi-file (multi-disc) ROM download helpers:
// the .m3u playlist rule (mirrors RomM's utils/m3u.py) and the per-file
// relative-path/safety computation (mirrors RomFile.file_name_for_download).
class MultiFileRomTest {

    // --- playlistLines / buildM3uContent ---

    @Test
    fun `chd plus m3u drops the m3u and keeps the discs sorted`() {
        val candidates = listOf(
            PlaylistCandidate("D (USA) (Disc 2).chd", "D (USA) (Disc 2).chd"),
            PlaylistCandidate("D (USA).m3u", "D (USA).m3u"),
            PlaylistCandidate("D (USA) (Disc 1).chd", "D (USA) (Disc 1).chd")
        )
        assertEquals(
            listOf("D (USA) (Disc 1).chd", "D (USA) (Disc 2).chd"),
            playlistLines(candidates)
        )
        assertEquals(
            "D (USA) (Disc 1).chd\nD (USA) (Disc 2).chd\n",
            buildM3uContent(candidates)
        )
    }

    @Test
    fun `cue bin set drops the companion bin, keeping only the cue sheet`() {
        val candidates = listOf(
            PlaylistCandidate("Game (Disc 1).bin", "Game (Disc 1).bin"),
            PlaylistCandidate("Game (Disc 1).cue", "Game (Disc 1).cue"),
            PlaylistCandidate("Game (Disc 2).bin", "Game (Disc 2).bin"),
            PlaylistCandidate("Game (Disc 2).cue", "Game (Disc 2).cue")
        )
        assertEquals(
            listOf("Game (Disc 1).cue", "Game (Disc 2).cue"),
            playlistLines(candidates)
        )
    }

    @Test
    fun `gdi and standalone chd mix keeps both -- a disc that stands on its own is not a companion`() {
        val candidates = listOf(
            PlaylistCandidate("Track.raw", "Track.raw"),
            PlaylistCandidate("Game.gdi", "Game.gdi"),
            PlaylistCandidate("Extra.chd", "Extra.chd")
        )
        // .raw is a companion of the .gdi descriptor and is dropped; the
        // standalone .chd isn't referenced by any descriptor and is kept.
        assertEquals(
            listOf("Extra.chd", "Game.gdi"),
            playlistLines(candidates)
        )
    }

    @Test
    fun `all-companion set with no descriptor present keeps every file`() {
        // No .cue/.gdi/.ccd/.mds among the discs -- playlist_files() returns
        // the discs unfiltered rather than treating everything as an orphaned
        // companion.
        val candidates = listOf(
            PlaylistCandidate("Track02.bin", "Track02.bin"),
            PlaylistCandidate("Track01.bin", "Track01.bin")
        )
        assertEquals(
            listOf("Track01.bin", "Track02.bin"),
            playlistLines(candidates)
        )
    }

    @Test
    fun `lines use the download-relative path, not the bare file name`() {
        val candidates = listOf(
            PlaylistCandidate("Disc 2.chd", "nested/Disc 2.chd"),
            PlaylistCandidate("Disc 1.chd", "nested/Disc 1.chd")
        )
        assertEquals(
            listOf("nested/Disc 1.chd", "nested/Disc 2.chd"),
            playlistLines(candidates)
        )
    }

    @Test
    fun `empty candidate list produces empty content`() {
        assertEquals("", buildM3uContent(emptyList()))
    }

    // --- resolveDownloadRelativePath ---

    @Test
    fun `nested file below the rom folder keeps its subpath`() {
        assertEquals(
            "Disc 1.chd",
            resolveDownloadRelativePath(
                romFullPath = "saturn/D (USA)",
                fileFullPath = "saturn/D (USA)/Disc 1.chd",
                fileName = "Disc 1.chd"
            )
        )
    }

    @Test
    fun `deeper nested file keeps its full subpath`() {
        assertEquals(
            "sub/Disc 1.chd",
            resolveDownloadRelativePath(
                romFullPath = "saturn/D (USA)",
                fileFullPath = "saturn/D (USA)/sub/Disc 1.chd",
                fileName = "Disc 1.chd"
            )
        )
    }

    @Test
    fun `flat sibling file that is not nested under the rom folder falls back to its own name`() {
        assertEquals(
            "D (USA) (Disc 1).chd",
            resolveDownloadRelativePath(
                romFullPath = "saturn/D (USA).m3u",
                fileFullPath = "saturn/D (USA) (Disc 1).chd",
                fileName = "D (USA) (Disc 1).chd"
            )
        )
    }

    @Test
    fun `missing server paths fall back to the file name`() {
        assertEquals(
            "Disc 1.chd",
            resolveDownloadRelativePath(romFullPath = null, fileFullPath = null, fileName = "Disc 1.chd")
        )
    }

    @Test
    fun `traversal segment in the resolved subpath is rejected`() {
        assertNull(
            resolveDownloadRelativePath(
                romFullPath = "saturn/D (USA)",
                fileFullPath = "saturn/D (USA)/../../etc/passwd",
                fileName = "passwd"
            )
        )
    }

    @Test
    fun `absolute resolved subpath is rejected`() {
        assertNull(
            resolveDownloadRelativePath(
                romFullPath = "saturn/D (USA)",
                fileFullPath = "saturn/D (USA)//etc/passwd",
                fileName = "passwd"
            )
        )
    }

    @Test
    fun `file name fallback that is itself a traversal segment is rejected`() {
        assertNull(resolveDownloadRelativePath(romFullPath = null, fileFullPath = null, fileName = ".."))
    }

    @Test
    fun `isM3uFileName is case-insensitive`() {
        assertEquals(true, isM3uFileName("Game.M3U"))
        assertEquals(false, isM3uFileName("Game.chd"))
    }
}
