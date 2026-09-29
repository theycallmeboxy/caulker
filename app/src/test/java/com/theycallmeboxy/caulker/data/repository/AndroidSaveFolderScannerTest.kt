package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.util.RootFileHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

// JVM tests for AndroidSaveFolderScanner against a real temp directory
// (independent review, phase 3A fixes blocker 2 / item 6): `.caulker_backup`
// entries are excluded from the snapshot entirely (never read into memory
// for hashing), and an unreadable file makes the whole scan throw rather
// than silently omitting it.
class AndroidSaveFolderScannerTest {

    private lateinit var root: File
    private val rootHelper = RootFileHelper()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scannertest").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `caulker_backup entries are excluded from the snapshot and never read`() = runBlocking {
        File(root, "Mario.srm").writeBytes("real save".toByteArray())
        File(root, ".caulker_backup").mkdirs()
        File(root, ".caulker_backup/Mario_20260101_000000.srm").writeBytes("backup content".toByteArray())
        // Nested backup dir (e.g. under a FOLDER save's own subtree, if one
        // ever ended up there) is excluded too.
        File(root, "SAVE1").mkdirs()
        File(root, "SAVE1/.caulker_backup").mkdirs()
        File(root, "SAVE1/.caulker_backup/x.bin").writeBytes("nested backup".toByteArray())
        File(root, "SAVE1/data.bin").writeBytes("real folder save".toByteArray())

        val scanner = AndroidSaveFolderScanner.snapshot(rootHelper, root.absolutePath)

        val paths = scanner.listAllEntries().map { it.relativePath }
        assertTrue(paths.contains("Mario.srm"))
        assertTrue(paths.contains("SAVE1"))
        assertTrue(paths.contains("SAVE1/data.bin"))
        assertFalse(paths.any { it.contains(".caulker_backup") })
        // Never even read into fileBytes -- readFile must return null for it.
        assertTrue(scanner.readFile(".caulker_backup/Mario_20260101_000000.srm") == null)
    }

    @Test
    fun `an unreadable file fails only when it is read, never silently omitted`() = runBlocking {
        File(root, "Mario.srm").writeBytes("ok".toByteArray())
        // A broken symlink: listed as a file entry (not a directory), but
        // reading it fails -- exactly the "found but unreadable" case a real
        // permission error or a file that disappeared mid-scan would produce.
        val brokenLink = File(root, "Broken.srm").toPath()
        Files.createSymbolicLink(brokenLink, File(root, "does-not-exist.srm").toPath())

        // Listing never reads contents, so an unreadable file that belongs to
        // no save (Unassigned) can't fail the whole platform's scan...
        val scanner = AndroidSaveFolderScanner.snapshot(rootHelper, root.absolutePath)
        assertTrue(scanner.listAllEntries().any { it.relativePath == "Broken.srm" })
        assertEquals("ok", String(scanner.readFile("Mario.srm")!!))

        // ...but reading it (because a matched unit needs it) throws with its
        // path rather than returning null, which would read as "no local save".
        try {
            scanner.readFile("Broken.srm")
            fail("expected readFile() to throw for an unreadable entry")
        } catch (e: SaveFolderScanException) {
            assertTrue(e.message!!.contains("Broken.srm"))
        }
    }
}
