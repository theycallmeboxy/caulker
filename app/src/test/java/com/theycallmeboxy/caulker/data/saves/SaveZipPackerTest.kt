package com.theycallmeboxy.caulker.data.saves

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Pure-JVM tests for packSaveZip -- archive shape (single-root vs
// multi-root vs flat), no-manifest, empty-result refusal, and determinism
// (save-sync design doc, Part 2 §2 / §13 test plan). See SaveZipPacker.kt's
// file-level comment for how this was verified against Argosy's actual
// archiver rather than just §2's prose.
class SaveZipPackerTest {

    private fun dir(path: String) = LocalSaveEntry(path, isDirectory = true)
    private fun file(path: String) = LocalSaveEntry(path, isDirectory = false)

    private fun readZipEntries(bytes: ByteArray): List<ZipEntry> {
        val entries = mutableListOf<ZipEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries += entry
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    // --- FOLDER shape: single-root ---

    @Test
    fun `single-root FOLDER archive has no explicit entry for the root itself`() {
        val source = FakeSaveFileSource(
            mapOf(
                "SAVE1/DATA.BIN" to byteArrayOf(1, 2, 3),
                "SAVE1/PARAM.SFO" to byteArrayOf(4, 5)
            )
        )
        val bytes = packSaveZip(SaveShape.FOLDER, listOf(dir("SAVE1")), source)
        val names = readZipEntries(bytes).map { it.name }

        assertFalse("root entry must not appear", names.contains("SAVE1/"))
        assertTrue(names.contains("SAVE1/DATA.BIN"))
        assertTrue(names.contains("SAVE1/PARAM.SFO"))
    }

    @Test
    fun `single-root FOLDER archive still writes explicit entries for nested subdirectories`() {
        // Mirrors Argosy's zipFolderRecursive: only the single top-level
        // root is special-cased to have no entry -- everything below it,
        // including a nested subdirectory, gets one.
        val source = FakeSaveFileSource(
            mapOf(
                "SAVE1/sub/DATA.BIN" to byteArrayOf(9)
            )
        )
        val bytes = packSaveZip(SaveShape.FOLDER, listOf(dir("SAVE1")), source)
        val names = readZipEntries(bytes).map { it.name }

        assertFalse(names.contains("SAVE1/"))
        assertTrue(names.contains("SAVE1/sub/"))
        assertTrue(names.contains("SAVE1/sub/DATA.BIN"))
    }

    // --- FOLDER shape: multi-root ---

    @Test
    fun `multi-root FOLDER archive writes an explicit directory entry per root`() {
        val source = FakeSaveFileSource(
            mapOf(
                "ULUS10064DATA00/DATA.BIN" to byteArrayOf(1),
                "ULUS10064SETTINGS/OPTIONS.BIN" to byteArrayOf(2)
            )
        )
        val bytes = packSaveZip(
            SaveShape.FOLDER,
            listOf(dir("ULUS10064DATA00"), dir("ULUS10064SETTINGS")),
            source
        )
        val names = readZipEntries(bytes).map { it.name }

        assertTrue(names.contains("ULUS10064DATA00/"))
        assertTrue(names.contains("ULUS10064SETTINGS/"))
        assertTrue(names.contains("ULUS10064DATA00/DATA.BIN"))
        assertTrue(names.contains("ULUS10064SETTINGS/OPTIONS.BIN"))
    }

    // --- FILE_SET shape: flat, no root at all ---

    @Test
    fun `FILE_SET archive is flat with no directory prefix, even across source directories`() {
        val source = FakeSaveFileSource(
            mapOf(
                "Star Ocean.srm" to byteArrayOf(1),
                "Star Ocean.rtc" to byteArrayOf(2)
            )
        )
        val bytes = packSaveZip(
            SaveShape.FILE_SET,
            listOf(file("Star Ocean.srm"), file("Star Ocean.rtc")),
            source
        )
        val names = readZipEntries(bytes).map { it.name }.toSet()

        assertEquals(setOf("Star Ocean.srm", "Star Ocean.rtc"), names)
    }

    @Test
    fun `FILE_SET archive flattens members from different source directories to bare filenames`() {
        val source = FakeSaveFileSource(
            mapOf(
                "nvram/sf2.nv" to byteArrayOf(1),
                "hi/sf2.hi" to byteArrayOf(2)
            )
        )
        val bytes = packSaveZip(
            SaveShape.FILE_SET,
            listOf(file("nvram/sf2.nv"), file("hi/sf2.hi")),
            source
        )
        val names = readZipEntries(bytes).map { it.name }.toSet()

        // No "nvram/" or "hi/" prefix survives -- mirrors Argosy's zipFiles,
        // which names entries by File.name alone (SaveZipPacker.kt point 1).
        assertEquals(setOf("sf2.nv", "sf2.hi"), names)
    }

    @Test
    fun `FILE_SET archive refuses when two members collide once flattened`() {
        val source = FakeSaveFileSource(
            mapOf(
                "nvram/x.nv" to byteArrayOf(1),
                "other/x.nv" to byteArrayOf(2)
            )
        )
        try {
            packSaveZip(SaveShape.FILE_SET, listOf(file("nvram/x.nv"), file("other/x.nv")), source)
            fail("expected DuplicateArchiveEntryException")
        } catch (e: DuplicateArchiveEntryException) {
            // expected
        }
    }

    // --- FILE_SET pattern-collision validation (lead review, fix 2) ---

    @Test
    fun `packSaveZip refuses a FILE_SET preset whose patterns collide once resolved`() {
        // Only one member actually exists on disk, so the organic
        // duplicate-basename check above wouldn't trigger -- this is the
        // preset-level check (SaveZipFileSetPatterns.kt), independent of
        // which member files happen to be present.
        val source = FakeSaveFileSource(mapOf("nvram/x.nv" to byteArrayOf(1)))
        try {
            packSaveZip(
                SaveShape.FILE_SET, listOf(file("nvram/x.nv")), source,
                fileSetPatterns = listOf("nvram/{name}.nv", "other/{name}.nv"),
                fileSetResolvedName = "x"
            )
            fail("expected DuplicateArchiveEntryException")
        } catch (e: DuplicateArchiveEntryException) {
            // expected
        }
    }

    @Test
    fun `packSaveZip accepts non-colliding FILE_SET patterns without complaint`() {
        val source = FakeSaveFileSource(mapOf("nvram/x.nv" to byteArrayOf(1)))
        val bytes = packSaveZip(
            SaveShape.FILE_SET, listOf(file("nvram/x.nv")), source,
            fileSetPatterns = listOf("nvram/{name}.nv", "hi/{name}.hi"),
            fileSetResolvedName = "x"
        )
        assertEquals(setOf("x.nv"), readZipEntries(bytes).map { it.name }.toSet())
    }

    // --- No manifest, ever ---

    @Test
    fun `packed archives never contain a manifest entry`() {
        val source = FakeSaveFileSource(mapOf("SAVE1/a.bin" to byteArrayOf(1)))
        val bytes = packSaveZip(SaveShape.FOLDER, listOf(dir("SAVE1")), source)
        val names = readZipEntries(bytes).map { it.name.lowercase() }
        assertTrue(names.none { it.contains("manifest") || it.endsWith(".json") || it.endsWith(".txt") })
    }

    // --- Empty result refuses rather than uploading an empty/root-only zip ---

    @Test
    fun `empty FOLDER entry list refuses instead of producing an empty archive`() {
        val source = FakeSaveFileSource(emptyMap())
        try {
            packSaveZip(SaveShape.FOLDER, emptyList(), source)
            fail("expected EmptySaveArchiveException")
        } catch (e: EmptySaveArchiveException) {
            // expected
        }
    }

    @Test
    fun `a FOLDER root with zero files inside refuses rather than an empty archive`() {
        val source = FakeSaveFileSource(emptyMap()) // "SAVE1" has no children at all
        try {
            packSaveZip(SaveShape.FOLDER, listOf(dir("SAVE1")), source)
            fail("expected EmptySaveArchiveException")
        } catch (e: EmptySaveArchiveException) {
            // expected
        }
    }

    @Test
    fun `empty FILE_SET entry list refuses instead of producing an empty archive`() {
        val source = FakeSaveFileSource(emptyMap())
        try {
            packSaveZip(SaveShape.FILE_SET, emptyList(), source)
            fail("expected EmptySaveArchiveException")
        } catch (e: EmptySaveArchiveException) {
            // expected
        }
    }

    // --- SINGLE_FILE is never zipped ---

    @Test
    fun `packSaveZip rejects SINGLE_FILE shape outright`() {
        val source = FakeSaveFileSource(mapOf("Zelda.srm" to byteArrayOf(1)))
        try {
            packSaveZip(SaveShape.SINGLE_FILE, listOf(file("Zelda.srm")), source)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- Determinism: identical input packs to identical bytes ---

    @Test
    fun `identical content packs to byte-identical zips across repeated calls`() {
        val source = FakeSaveFileSource(
            mapOf(
                "ULUS10064DATA00/DATA.BIN" to byteArrayOf(1, 2, 3),
                "ULUS10064DATA00/PARAM.SFO" to byteArrayOf(4, 5),
                "ULUS10064SETTINGS/OPTIONS.BIN" to byteArrayOf(6)
            )
        )
        val entries = listOf(dir("ULUS10064DATA00"), dir("ULUS10064SETTINGS"))
        val first = packSaveZip(SaveShape.FOLDER, entries, source)
        val second = packSaveZip(SaveShape.FOLDER, entries, source)
        assertArrayEquals(first, second)
    }

    @Test
    fun `entry order does not depend on the order entries are passed in`() {
        val source = FakeSaveFileSource(
            mapOf(
                "A/a.bin" to byteArrayOf(1),
                "B/b.bin" to byteArrayOf(2)
            )
        )
        val forward = packSaveZip(SaveShape.FOLDER, listOf(dir("A"), dir("B")), source)
        val reversed = packSaveZip(SaveShape.FOLDER, listOf(dir("B"), dir("A")), source)
        assertArrayEquals(forward, reversed)
    }
}
