package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for planSaveUpload/planSaveDownload (save-sync design doc,
// Part 2 §12 phase 3, §13 test plan): upload/download shape decisions,
// including the Argosy-verified edge cases cited in SaveTransferPlan.kt's
// header comment -- (a) zip upload filename, (b) a FILE_SET unit with only
// one member present travels raw, and (c) the two download mismatch cases
// (raw arriving for FILE_SET, zip arriving for SINGLE_FILE).
class SaveTransferPlanTest {

    private val single = SavePreset(
        key = PresetKey.RetroArchCore("gba", "mgba"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.SINGLE_FILE,
        patterns = listOf("{name}.srm"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "mgba"
    )

    private val fileSet = SavePreset(
        key = PresetKey.RetroArchCore("snes", "snes9x"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FILE_SET,
        patterns = listOf("{name}.srm", "{name}.rtc"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "snes9x"
    )

    private val folder = SavePreset(
        key = PresetKey.Standalone("psp", "ppsspp"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FOLDER,
        patterns = emptyList(),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FOLDER_PREFIX,
        emulatorId = "ppsspp"
    )

    // --- Upload ---

    @Test
    fun `SINGLE_FILE always uploads raw`() {
        val source = FakeSaveFileSource(mapOf("Mario.srm" to "bytes".toByteArray()))
        val entries = listOf(LocalSaveEntry("Mario.srm", false))

        val plan = planSaveUpload(SaveShape.SINGLE_FILE, entries, source, "Mario")

        assertTrue(plan is SaveUploadPlan.Raw)
        assertEquals("Mario.srm", (plan as SaveUploadPlan.Raw).relativePath)
    }

    @Test
    fun `FILE_SET with only one member present travels raw, not zipped (Argosy finding b)`() {
        val source = FakeSaveFileSource(mapOf("Mario.srm" to "srm".toByteArray()))
        val entries = listOf(LocalSaveEntry("Mario.srm", false))

        val plan = planSaveUpload(SaveShape.FILE_SET, entries, source, "Mario", fileSet.patterns)

        assertTrue(plan is SaveUploadPlan.Raw)
        assertEquals("Mario.srm", (plan as SaveUploadPlan.Raw).relativePath)
    }

    @Test
    fun `FILE_SET with two or more members present travels as a zip`() {
        val source = FakeSaveFileSource(mapOf("Mario.srm" to "srm".toByteArray(), "Mario.rtc" to "rtc".toByteArray()))
        val entries = listOf(LocalSaveEntry("Mario.srm", false), LocalSaveEntry("Mario.rtc", false))

        val plan = planSaveUpload(SaveShape.FILE_SET, entries, source, "Mario", fileSet.patterns)

        assertTrue(plan is SaveUploadPlan.Zip)
        // Argosy finding (a): the zip upload's server file_name is
        // "<matching-key name>.zip".
        assertEquals("Mario.zip", (plan as SaveUploadPlan.Zip).fileName)
        assertTrue(isZipBytes(plan.bytes))
    }

    @Test
    fun `FOLDER always uploads as a zip, named after the matching key`() {
        val source = FakeSaveFileSource(mapOf("ULUS10041SAVE1/DATA.BIN" to "x".toByteArray()))
        val entries = listOf(LocalSaveEntry("ULUS10041SAVE1", true))

        val plan = planSaveUpload(SaveShape.FOLDER, entries, source, "ULUS10041")

        assertTrue(plan is SaveUploadPlan.Zip)
        assertEquals("ULUS10041.zip", (plan as SaveUploadPlan.Zip).fileName)
    }

    @Test
    fun `no entries yields no upload plan`() {
        val source = FakeSaveFileSource(emptyMap())
        assertEquals(null, planSaveUpload(SaveShape.SINGLE_FILE, emptyList(), source, "Mario"))
    }

    // --- Download ---

    @Test
    fun `SINGLE_FILE writes raw content to the preset's one pattern`() {
        val placement = planSaveDownload(single, "raw bytes".toByteArray(), "Chrono Trigger")
        assertEquals(SaveDownloadPlacement.WriteRaw("Chrono Trigger.srm"), placement)
    }

    @Test
    fun `a zip arriving for a SINGLE_FILE save is refused, not guessed (Argosy finding c-ii)`() {
        val zipBytes = packSaveZip(
            SaveShape.FOLDER,
            listOf(LocalSaveEntry("Mario", true)),
            FakeSaveFileSource(mapOf("Mario/x.bin" to "x".toByteArray()))
        )
        val placement = planSaveDownload(single, zipBytes, "Chrono Trigger")
        assertTrue(placement is SaveDownloadPlacement.Refused)
    }

    @Test
    fun `a zip arriving for a FILE_SET save is unpacked`() {
        val zipBytes = packSaveZip(
            SaveShape.FILE_SET,
            listOf(LocalSaveEntry("Mario.srm", false), LocalSaveEntry("Mario.rtc", false)),
            FakeSaveFileSource(mapOf("Mario.srm" to "a".toByteArray(), "Mario.rtc" to "b".toByteArray())),
            fileSetPatterns = fileSet.patterns,
            fileSetResolvedName = "Mario"
        )
        val placement = planSaveDownload(fileSet, zipBytes, "Mario")
        assertEquals(SaveDownloadPlacement.Unpack("Mario", fileSet.patterns), placement)
    }

    @Test
    fun `raw content arriving for a FILE_SET save is placed by matching extension (Argosy finding c-i)`() {
        val placement = planSaveDownload(fileSet, "raw rtc bytes".toByteArray(), "Mario", serverFileName = "[ts].rtc")
        assertEquals(SaveDownloadPlacement.WriteRaw("Mario.rtc"), placement)
    }

    @Test
    fun `raw content for a FILE_SET save with no server filename is refused rather than guessed`() {
        val placement = planSaveDownload(fileSet, "raw bytes".toByteArray(), "Mario", serverFileName = null)
        assertTrue(placement is SaveDownloadPlacement.Refused)
    }

    @Test
    fun `raw content for a FILE_SET save whose extension matches no pattern is refused`() {
        val placement = planSaveDownload(fileSet, "raw bytes".toByteArray(), "Mario", serverFileName = "[ts].bin")
        assertTrue(placement is SaveDownloadPlacement.Refused)
    }

    // Independent review, phase 3A fixes item 4: melonDS DS's
    // .public.sav/.private.sav/.banner.sav all share the extension `.sav`,
    // and RomM renames uploads with a datetime tag, so the filename's own
    // base is never a usable tiebreaker either -- more than one pattern
    // resolving to the same extension must refuse, not pick one.
    private val melonDsDsFileSet = SavePreset(
        key = PresetKey.RetroArchCore("nds", "melondsds"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FILE_SET,
        patterns = listOf("{name}.public.sav", "{name}.private.sav", "{name}.banner.sav"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "melondsds"
    )

    @Test
    fun `raw content matching more than one FILE_SET pattern's extension is refused, not guessed`() {
        val placement = planSaveDownload(
            melonDsDsFileSet, "raw bytes".toByteArray(), "SomeGame", serverFileName = "[2026-01-01_00-00-00].sav"
        )
        assertTrue(placement is SaveDownloadPlacement.Refused)
        assertTrue((placement as SaveDownloadPlacement.Refused).reason.contains("ambiguous"))
    }

    @Test
    fun `a raw file arriving for a FOLDER save is refused`() {
        val placement = planSaveDownload(folder, "raw bytes".toByteArray(), "ULUS10041")
        assertTrue(placement is SaveDownloadPlacement.Refused)
    }

    @Test
    fun `a zip arriving for a FOLDER save is unpacked`() {
        val zipBytes = packSaveZip(
            SaveShape.FOLDER,
            listOf(LocalSaveEntry("ULUS10041SAVE1", true)),
            FakeSaveFileSource(mapOf("ULUS10041SAVE1/DATA.BIN" to "x".toByteArray()))
        )
        val placement = planSaveDownload(folder, zipBytes, "ULUS10041")
        assertEquals(SaveDownloadPlacement.Unpack("ULUS10041", emptyList()), placement)
    }

    // --- Zip magic (SaveZipMagic.kt) ---

    @Test
    fun `isZipBytes recognizes real zip content and rejects plain bytes`() {
        val zipBytes = packSaveZip(
            SaveShape.FOLDER,
            listOf(LocalSaveEntry("X", true)),
            FakeSaveFileSource(mapOf("X/a.bin" to "a".toByteArray()))
        )
        assertTrue(isZipBytes(zipBytes))
        assertTrue(!isZipBytes("not a zip".toByteArray()))
        assertTrue(!isZipBytes(ByteArray(2)))
    }
}
