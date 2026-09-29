package com.theycallmeboxy.caulker.data.saves

import com.theycallmeboxy.caulker.data.util.md5Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for resolveSaveLocations() (save-sync design doc, Part 2
// §12 phase 3 / §13 test plan): SINGLE_FILE/FILE_SET/FOLDER unit building,
// Unassigned passthrough, and that the FILE_SET/FOLDER content hash matches
// hashLocalContentAsZip exactly (they must never disagree — that's what the
// sync decision compares against RomM's server-side content_hash).
class SaveLocationResolverTest {

    private val folder = "/saves/snes"

    private val single = SavePreset(
        key = PresetKey.RetroArchCore("snes", "mgba"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.SINGLE_FILE,
        patterns = listOf("{name}.srm"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "mgba"
    )

    @Test
    fun `SINGLE_FILE unit hashes the raw file like localSaveStat does today`() {
        val bytes = "save-bytes".toByteArray()
        val scanner = FakeSaveFolderScanner(
            files = mapOf("Chrono Trigger.srm" to bytes),
            mtimes = mapOf("Chrono Trigger.srm" to 12345L)
        )
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "Chrono Trigger"))

        val result = resolveSaveLocations(single, folder, games, scanner)

        assertEquals(1, result.units.size)
        val unit = result.units.single()
        assertEquals(1, unit.romId)
        assertEquals(SaveShape.SINGLE_FILE, unit.shape)
        assertEquals("Chrono Trigger", unit.matchingKeyName)
        assertEquals(listOf("Chrono Trigger.srm"), unit.memberPaths)
        assertEquals("$folder/Chrono Trigger.srm", unit.resolvedPath)
        assertEquals(md5Hex(bytes), unit.contentHash)
        assertEquals(12345L, unit.modifiedMs)
        assertTrue(result.unassigned.isEmpty())
    }

    private val fileSet = SavePreset(
        key = PresetKey.RetroArchCore("snes", "snes9x"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FILE_SET,
        patterns = listOf("{name}.srm", "{name}.rtc"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "snes9x"
    )

    @Test
    fun `FILE_SET unit content hash matches hashLocalContentAsZip directly`() {
        val srm = "srm-bytes".toByteArray()
        val rtc = "rtc-bytes".toByteArray()
        val files = mapOf("Mario.srm" to srm, "Mario.rtc" to rtc)
        val scanner = FakeSaveFolderScanner(files, mtimes = mapOf("Mario.srm" to 100L, "Mario.rtc" to 200L))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "Mario"))

        val result = resolveSaveLocations(fileSet, folder, games, scanner)

        val unit = result.units.single()
        assertEquals(listOf("Mario.rtc", "Mario.srm"), unit.memberPaths) // sorted
        assertEquals(200L, unit.modifiedMs) // latest of the two members
        val expectedHash = hashLocalContentAsZip(
            SaveShape.FILE_SET,
            entries = listOf(LocalSaveEntry("Mario.srm", false), LocalSaveEntry("Mario.rtc", false)),
            source = scanner,
            fileSetPatterns = fileSet.patterns,
            fileSetResolvedName = "Mario"
        )
        assertEquals(expectedHash, unit.contentHash)
    }

    @Test
    fun `FILE_SET unit with only the required member present still builds a unit`() {
        val bytes = "x".toByteArray()
        val scanner = FakeSaveFolderScanner(mapOf("Mario.srm" to bytes))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "Mario"))

        val result = resolveSaveLocations(fileSet, folder, games, scanner)

        val unit = result.units.single()
        assertEquals(listOf("Mario.srm"), unit.memberPaths)
        // A single present FILE_SET member uploads raw (planSaveUpload,
        // SaveTransferPlan.kt), so RomM hashes it as a plain file -- this
        // unit's contentHash must be the plain md5, not hashLocalContentAsZip,
        // or a baseline/negotiate comparison against the server would never
        // match after such an upload.
        assertEquals(md5Hex(bytes), unit.contentHash)
    }

    private val folderShape = SavePreset(
        key = PresetKey.Standalone("psp", "ppsspp"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FOLDER,
        patterns = emptyList(),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FOLDER_PREFIX,
        emulatorId = "ppsspp"
    )

    @Test
    fun `FOLDER unit hashes recursively and takes the latest member mtime`() {
        val files = mapOf(
            "ULUS10041SAVE1/DATA.BIN" to "a".toByteArray(),
            "ULUS10041SAVE1/PARAM.SFO" to "b".toByteArray()
        )
        val scanner = FakeSaveFolderScanner(
            files,
            mtimes = mapOf("ULUS10041SAVE1/DATA.BIN" to 500L, "ULUS10041SAVE1/PARAM.SFO" to 900L)
        )
        val games = listOf(GameSaveIdentity(romId = 3, romStem = "irrelevant", saveTarget = "ULUS10041"))

        val result = resolveSaveLocations(folderShape, folder, games, scanner)

        val unit = result.units.single()
        assertEquals(SaveShape.FOLDER, unit.shape)
        assertEquals("ULUS10041", unit.matchingKeyName)
        assertEquals(listOf("ULUS10041SAVE1"), unit.memberPaths)
        assertEquals(900L, unit.modifiedMs)
        val expectedHash = hashLocalContentAsZip(
            SaveShape.FOLDER,
            entries = listOf(LocalSaveEntry("ULUS10041SAVE1", true)),
            source = scanner
        )
        assertEquals(expectedHash, unit.contentHash)
    }

    @Test
    fun `unmatched local files are surfaced as unassigned, not a unit`() {
        val scanner = FakeSaveFolderScanner(
            mapOf("Mario.srm" to "x".toByteArray(), "Stray File.srm" to "y".toByteArray())
        )
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "Mario"))

        val result = resolveSaveLocations(single, folder, games, scanner)

        assertEquals(1, result.units.size)
        assertEquals(listOf(LocalSaveEntry("Stray File.srm", false)), result.unassigned)
    }

    @Test
    fun `no games and no matching local files yields no units and everything unassigned`() {
        val scanner = FakeSaveFolderScanner(mapOf("Orphan.srm" to "x".toByteArray()))
        val result = resolveSaveLocations(single, folder, emptyList(), scanner)

        assertTrue(result.units.isEmpty())
        assertEquals(listOf(LocalSaveEntry("Orphan.srm", false)), result.unassigned)
    }

    @Test
    fun `SAVE_TARGET unit with no saveTarget on the game is skipped rather than guessed`() {
        val scanner = FakeSaveFolderScanner(mapOf("ULUS10041SAVE1/DATA.BIN" to "a".toByteArray()))
        val games = listOf(GameSaveIdentity(romId = 3, romStem = "irrelevant", saveTarget = null))

        val result = resolveSaveLocations(folderShape, folder, games, scanner)

        assertTrue(result.units.isEmpty())
        assertNull(result.units.firstOrNull())
    }
}
