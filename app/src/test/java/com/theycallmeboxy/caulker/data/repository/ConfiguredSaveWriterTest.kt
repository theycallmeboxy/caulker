package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.saves.GameSaveIdentity
import com.theycallmeboxy.caulker.data.saves.hashZipContents
import com.theycallmeboxy.caulker.data.saves.LocalSaveUnit
import com.theycallmeboxy.caulker.data.saves.MatchingKeyKind
import com.theycallmeboxy.caulker.data.saves.PresetKey
import com.theycallmeboxy.caulker.data.saves.SavePreset
import com.theycallmeboxy.caulker.data.saves.SaveShape
import com.theycallmeboxy.caulker.data.saves.SaveSyncMode
import com.theycallmeboxy.caulker.data.saves.SaveTargetLayout
import com.theycallmeboxy.caulker.data.saves.resolveSaveLocations
import com.theycallmeboxy.caulker.data.saves.resolvedPathFor
import com.theycallmeboxy.caulker.data.util.RootFileHelper
import com.theycallmeboxy.caulker.data.util.md5Hex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// JVM tests for ConfiguredSaveWriter (save-sync design doc, Part 2 §12 phase
// 3, independent review's phase 3A fixes) against a REAL temp directory --
// RootFileHelper's non-root path is plain java.io.File, so a real temp dir
// works as a "fake FS" here without needing root or Robolectric; this class
// deliberately has no SaveRepository/network dependency so it's testable
// this way at all (see its own doc comment).
class ConfiguredSaveWriterTest {

    private lateinit var root: File
    private val rootHelper = RootFileHelper()
    private val writer = ConfiguredSaveWriter(rootHelper)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("cswtest").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun folderPath() = root.absolutePath

    private val fileSetPreset = SavePreset(
        key = PresetKey.RetroArchCore("snes", "snes9x"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FILE_SET,
        patterns = listOf("{name}.srm", "{name}.rtc"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "snes9x"
    )

    private val folderPreset = SavePreset(
        key = PresetKey.Standalone("psp", "ppsspp"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FOLDER,
        patterns = emptyList(),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FOLDER_PREFIX,
        emulatorId = "ppsspp"
    )

    private fun folderZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zos ->
            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    // --- Blocker 1: a refused unpack must fail, not succeed ---

    @Test
    fun `unpack refused by root mismatch fails and writes nothing`() = runBlocking {
        // Root name "WrongId" doesn't match the expected save id "ULUS10041".
        val zip = folderZip("WrongId/DATA.BIN" to byteArrayOf(1, 2, 3))
        val configured = ConfiguredPlatform(folderPreset, folderPath())

        val outcome = writer.apply(configured, "ULUS10041", zip, serverFileName = "save.zip", previousUnit = null)

        assertFalse(outcome.success)
        assertTrue(outcome.reason!!.contains("does not match"))
        assertTrue(outcome.writtenRelativePaths.isEmpty())
        assertTrue(File(root, "ULUS10041").listFiles().orEmpty().isEmpty() || !File(root, "ULUS10041").exists())
        assertTrue(!File(root, "WrongId").exists())
    }

    // --- Item 3: stale members are removed after a successful write ---

    @Test
    fun `a member the new content no longer includes is removed after backup`() = runBlocking {
        File(root, "Mario.srm").writeBytes("old-srm".toByteArray())
        File(root, "Mario.rtc").writeBytes("old-rtc".toByteArray())
        val previousUnit = LocalSaveUnit(
            romId = 1, shape = SaveShape.FILE_SET, matchingKeyName = "Mario",
            memberPaths = listOf("Mario.rtc", "Mario.srm"), resolvedPath = "x", contentHash = "x", modifiedMs = 0
        )
        val zip = folderZip("Mario.srm" to "new-srm".toByteArray()) // no .rtc member anymore
        val configured = ConfiguredPlatform(fileSetPreset, folderPath())

        val outcome = writer.apply(configured, "Mario", zip, serverFileName = null, previousUnit = previousUnit)

        assertTrue(outcome.success)
        assertEquals("new-srm", File(root, "Mario.srm").readText())
        assertFalse("stale .rtc member should be removed", File(root, "Mario.rtc").exists())
        // ...but backed up first, not just deleted.
        val backupDir = File(root, ".caulker_backup")
        assertTrue(backupDir.exists())
        val backedUp = backupDir.listFiles()?.any { it.name.startsWith("Mario_") && it.name.endsWith(".rtc") } ?: false
        assertTrue("removed member should have been backed up", backedUp)
    }

    // --- Item 8: a partial multi-member write is rolled back ---

    @Test
    fun `a failed write partway through a multi-member unpack restores every member from backup`() = runBlocking {
        // Pre-existing content for both members.
        File(root, "SAVE1").mkdirs()
        File(root, "SAVE1/a.bin").writeBytes("old-a".toByteArray())
        // "b.bin" is pre-created as a DIRECTORY so writing bytes to it throws.
        File(root, "SAVE1/b.bin").mkdirs()

        val zip = folderZip("SAVE1/a.bin" to "new-a".toByteArray(), "SAVE1/b.bin" to "new-b".toByteArray())
        val configured = ConfiguredPlatform(folderPreset, folderPath())

        val outcome = writer.apply(configured, "SAVE1", zip, serverFileName = null, previousUnit = null)

        assertFalse(outcome.success)
        assertTrue(outcome.reason!!.contains("restored"))
        // a.bin (successfully written, then rolled back) must be restored to its ORIGINAL content.
        assertEquals("old-a", File(root, "SAVE1/a.bin").readText())
        // b.bin's write itself threw, so it must still be the directory it was before.
        assertTrue(File(root, "SAVE1/b.bin").isDirectory)
    }

    @Test
    fun `a failed write with no prior content leaves no trace after rollback`() = runBlocking {
        // "SAVE1" doesn't exist at all yet; a.bin would succeed, b.bin is
        // blocked by a pre-existing directory at that exact path.
        File(root, "SAVE1").mkdirs()
        File(root, "SAVE1/b.bin").mkdirs()
        val zip = folderZip("SAVE1/a.bin" to "new-a".toByteArray(), "SAVE1/b.bin" to "new-b".toByteArray())
        val configured = ConfiguredPlatform(folderPreset, folderPath())

        val outcome = writer.apply(configured, "SAVE1", zip, serverFileName = null, previousUnit = null)

        assertFalse(outcome.success)
        // a.bin had nothing before this call -- rollback must delete it, not
        // leave the new (uncommitted) content behind.
        assertFalse(File(root, "SAVE1/a.bin").exists())
    }

    // --- Successful raw + zip writes actually land where expected ---

    @Test
    fun `a successful raw write reports the written path`() = runBlocking {
        val singlePreset = SavePreset(
            key = PresetKey.RetroArchCore("gba", "mgba"), mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE, patterns = listOf("{name}.srm"),
            matchingKey = MatchingKeyKind.ROM_STEM, emulatorId = "mgba"
        )
        val configured = ConfiguredPlatform(singlePreset, folderPath())
        val outcome = writer.apply(configured, "Chrono Trigger", "raw bytes".toByteArray(), serverFileName = null, previousUnit = null)

        assertTrue(outcome.success)
        assertEquals(listOf("Chrono Trigger.srm"), outcome.writtenRelativePaths)
        assertEquals("raw bytes", File(root, "Chrono Trigger.srm").readText())
    }

    @Test
    fun `backups never land inside a FOLDER save's own directory`() = runBlocking {
        File(root, "SAVE1").mkdirs()
        File(root, "SAVE1/a.bin").writeBytes("old".toByteArray())
        val previousUnit = LocalSaveUnit(
            romId = 1, shape = SaveShape.FOLDER, matchingKeyName = "SAVE1",
            memberPaths = listOf("SAVE1"), resolvedPath = "x", contentHash = "x", modifiedMs = 0
        )
        // New content drops a.bin entirely -- it should be backed up + removed.
        val zip = folderZip("SAVE1/c.bin" to "c".toByteArray())
        val configured = ConfiguredPlatform(folderPreset, folderPath())

        writer.apply(configured, "SAVE1", zip, serverFileName = null, previousUnit = previousUnit)

        // The backup must be at <root>/.caulker_backup/..., never inside SAVE1/.
        assertFalse(File(root, "SAVE1/.caulker_backup").exists())
        assertTrue(File(root, ".caulker_backup").exists())
    }

    // --- Item 1 (round 2): the post-download resolvedPath must be built the
    // SAME way resolveSaveLocations/buildUnit build it for a scanned unit,
    // for every shape -- otherwise effectiveBaseline() finds "no history"
    // after every download and the next sync shows CONFLICT instead of
    // UPLOAD. Each test writes via the writer, then does a completely
    // independent fresh scan (AndroidSaveFolderScanner + resolveSaveLocations)
    // and asserts the two paths are byte-for-byte identical.

    private suspend fun scannedResolvedPath(preset: SavePreset, folderPath: String, identity: GameSaveIdentity): String {
        val scanner = AndroidSaveFolderScanner.snapshot(rootHelper, folderPath)
        val result = resolveSaveLocations(preset, folderPath, listOf(identity), scanner)
        return result.units.single { it.romId == identity.romId }.resolvedPath
    }

    @Test
    fun `resolvedPath after a SINGLE_FILE write matches a fresh scan`() = runBlocking {
        val singlePreset = SavePreset(
            key = PresetKey.RetroArchCore("gba", "mgba"), mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE, patterns = listOf("{name}.srm"),
            matchingKey = MatchingKeyKind.ROM_STEM, emulatorId = "mgba"
        )
        val configured = ConfiguredPlatform(singlePreset, folderPath())
        val outcome = writer.apply(configured, "Chrono Trigger", "bytes".toByteArray(), serverFileName = null, previousUnit = null)

        val writtenPath = resolvedPathFor(folderPath(), singlePreset.shape, outcome.writtenRelativePaths)
        val scannedPath = scannedResolvedPath(singlePreset, folderPath(), GameSaveIdentity(romId = 1, romStem = "Chrono Trigger"))
        assertEquals(scannedPath, writtenPath)
    }

    @Test
    fun `resolvedPath after a FILE_SET write with one member matches a fresh scan`() = runBlocking {
        val zip = folderZip("Mario.srm" to "srm".toByteArray()) // single member -> written raw
        val configured = ConfiguredPlatform(fileSetPreset, folderPath())
        val outcome = writer.apply(configured, "Mario", zip, serverFileName = null, previousUnit = null)

        val writtenPath = resolvedPathFor(folderPath(), fileSetPreset.shape, outcome.writtenRelativePaths)
        val scannedPath = scannedResolvedPath(fileSetPreset, folderPath(), GameSaveIdentity(romId = 1, romStem = "Mario"))
        assertEquals(scannedPath, writtenPath)
    }

    @Test
    fun `resolvedPath after a FILE_SET write with two members matches a fresh scan`() = runBlocking {
        val zip = folderZip("Mario.srm" to "srm".toByteArray(), "Mario.rtc" to "rtc".toByteArray())
        val configured = ConfiguredPlatform(fileSetPreset, folderPath())
        val outcome = writer.apply(configured, "Mario", zip, serverFileName = null, previousUnit = null)

        val writtenPath = resolvedPathFor(folderPath(), fileSetPreset.shape, outcome.writtenRelativePaths)
        val scannedPath = scannedResolvedPath(fileSetPreset, folderPath(), GameSaveIdentity(romId = 1, romStem = "Mario"))
        assertEquals(scannedPath, writtenPath)
    }

    @Test
    fun `resolvedPath after a single-root FOLDER write matches a fresh scan`() = runBlocking {
        val zip = folderZip("ULUS10041/DATA.BIN" to "a".toByteArray(), "ULUS10041/PARAM.SFO" to "b".toByteArray())
        val configured = ConfiguredPlatform(folderPreset, folderPath())
        val outcome = writer.apply(configured, "ULUS10041", zip, serverFileName = null, previousUnit = null)
        assertTrue(outcome.success)

        val writtenPath = resolvedPathFor(folderPath(), folderPreset.shape, outcome.writtenRelativePaths)
        val scannedPath = scannedResolvedPath(
            folderPreset, folderPath(), GameSaveIdentity(romId = 1, romStem = "irrelevant", saveTarget = "ULUS10041")
        )
        assertEquals(scannedPath, writtenPath)
        // Sanity: this must be the root path, NOT the flat per-file paths.
        assertEquals("${folderPath()}/ULUS10041", writtenPath)
    }

    @Test
    fun `resolvedPath after a multi-root FOLDER write matches a fresh scan`() = runBlocking {
        // Both roots must match "ULUS10041" at some tier (exact / prefix).
        val zip = folderZip(
            "ULUS10041/DATA.BIN" to "a".toByteArray(),
            "ULUS10041_SETTINGS/OPTIONS.BIN" to "b".toByteArray()
        )
        val configured = ConfiguredPlatform(folderPreset, folderPath())
        val outcome = writer.apply(configured, "ULUS10041", zip, serverFileName = null, previousUnit = null)
        assertTrue(outcome.success)

        val writtenPath = resolvedPathFor(folderPath(), folderPreset.shape, outcome.writtenRelativePaths)
        val scannedPath = scannedResolvedPath(
            folderPreset, folderPath(), GameSaveIdentity(romId = 1, romStem = "irrelevant", saveTarget = "ULUS10041")
        )
        assertEquals(scannedPath, writtenPath)
    }

    // --- Item 2 (round 2): the baseline hash fallback must be the newly
    // written content's own hash, never a stale pre-download hash.

    @Test
    fun `writtenContentHash for a raw write is the new bytes' own hash`() = runBlocking {
        val singlePreset = SavePreset(
            key = PresetKey.RetroArchCore("gba", "mgba"), mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE, patterns = listOf("{name}.srm"),
            matchingKey = MatchingKeyKind.ROM_STEM, emulatorId = "mgba"
        )
        val configured = ConfiguredPlatform(singlePreset, folderPath())
        val newBytes = "brand new content".toByteArray()
        val outcome = writer.apply(configured, "Chrono Trigger", newBytes, serverFileName = null, previousUnit = null)

        assertEquals(md5Hex(newBytes), outcome.writtenContentHash)
    }

    @Test
    fun `writtenContentHash for a zip write is hashZipContents of the downloaded zip, not previousUnit's stale hash`() = runBlocking {
        val zip = folderZip("Mario.srm" to "new-srm".toByteArray(), "Mario.rtc" to "new-rtc".toByteArray())
        val staleUnit = LocalSaveUnit(
            romId = 1, shape = SaveShape.FILE_SET, matchingKeyName = "Mario",
            memberPaths = listOf("Mario.rtc", "Mario.srm"), resolvedPath = "x",
            contentHash = "stale-pre-download-hash", modifiedMs = 0
        )
        val configured = ConfiguredPlatform(fileSetPreset, folderPath())
        val outcome = writer.apply(configured, "Mario", zip, serverFileName = null, previousUnit = staleUnit)

        assertEquals(hashZipContents(zip), outcome.writtenContentHash)
        assertTrue(outcome.writtenContentHash != staleUnit.contentHash)
    }

    // --- Item 4 (round 2): rollback keeps going if one restore throws ---

    @Test
    fun `restoreAll keeps restoring the rest even if one restore fails, and reports it`() = runBlocking {
        // Two pre-existing members; corrupt "b"'s backup after it's taken so
        // its own restore throws, while "a" must still be restored fine.
        File(root, "SAVE1").mkdirs()
        File(root, "SAVE1/a.bin").writeBytes("old-a".toByteArray())
        File(root, "SAVE1/b.bin").writeBytes("old-b".toByteArray())

        val backupA = writer.backupMember(folderPath(), "SAVE1/a.bin")
        val backupB = writer.backupMember(folderPath(), "SAVE1/b.bin")
        // Delete b's backup out from under it so restoring it throws.
        File(root, backupB.backupRelativePath!!).delete()

        // Mutate both "live" files so we can tell whether a restore actually ran.
        File(root, "SAVE1/a.bin").writeBytes("mutated-a".toByteArray())
        File(root, "SAVE1/b.bin").writeBytes("mutated-b".toByteArray())

        val failureReport = writer.restoreAll(folderPath(), listOf(backupA, backupB))

        assertEquals("old-a", File(root, "SAVE1/a.bin").readText()) // restored despite b's failure
        assertEquals("mutated-b", File(root, "SAVE1/b.bin").readText()) // b's restore failed, left as-is
        assertTrue(failureReport != null && failureReport.contains("b.bin"))
    }

    // --- Item 4 (round 2): backup pruning + millisecond-safe naming ---

    @Test
    fun `old backups beyond the retention count are pruned`() = runBlocking {
        File(root, "Mario.srm").writeBytes("v0".toByteArray())
        // 7 backups of the same member -- only the newest 5 should remain.
        // A tiny delay keeps each backup's millisecond timestamp distinct.
        repeat(7) { i ->
            writer.backupMember(folderPath(), "Mario.srm")
            Thread.sleep(2)
            File(root, "Mario.srm").writeBytes("v${i + 1}".toByteArray())
        }
        val backups = File(root, ".caulker_backup").listFiles()
            ?.filter { it.name.startsWith("Mario_") && it.name.endsWith(".srm") }
            .orEmpty()
        assertEquals(5, backups.size)
    }
}
