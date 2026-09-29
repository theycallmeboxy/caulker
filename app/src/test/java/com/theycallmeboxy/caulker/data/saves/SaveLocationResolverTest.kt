package com.theycallmeboxy.caulker.data.saves

import com.theycallmeboxy.caulker.data.util.md5Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- §3 rule 3: manual Unassigned-file assignments -----------------------
    // Owner decision (2026-09-29): manual assignment is EXCHANGE-only --
    // every test in this section that needs an assignment to actually be
    // HONORED uses an EXCHANGE copy of the relevant fixture preset; `single`/
    // `fileSet`/`folderShape` themselves stay DIRECT (as most real v1
    // presets are) and get their own "ignores assignments" coverage below.

    private val singleExchange = single.copy(mode = SaveSyncMode.EXCHANGE)
    private val fileSetExchange = fileSet.copy(mode = SaveSyncMode.EXCHANGE)
    private val folderExchange = folderShape.copy(mode = SaveSyncMode.EXCHANGE)

    @Test
    fun `EXCHANGE - a manual assignment claims a SINGLE_FILE entry for a ROM rule matching never would`() {
        val bytes = "x".toByteArray()
        // "Stray File.srm" doesn't match ROM 7's stem ("Mario") by rule 1.
        val scanner = FakeSaveFolderScanner(mapOf("Stray File.srm" to bytes), mtimes = mapOf("Stray File.srm" to 42L))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "Mario"))

        val result = resolveSaveLocations(singleExchange, folder, games, scanner, assignments = mapOf(7 to "Stray File.srm"))

        assertEquals(1, result.units.size)
        val unit = result.units.single()
        assertEquals(7, unit.romId)
        assertEquals(listOf("Stray File.srm"), unit.memberPaths)
        // Phase 3B fixes, blocker 1: the assigned unit's matchingKeyName is
        // derived from the ASSIGNED FILE's own name (stripping the
        // pattern's ".srm" suffix), never the ROM's stem -- resolving
        // "{name}.srm" with this name must reproduce the assigned path
        // exactly, or a download would silently land somewhere else and the
        // real assigned file would get deleted as "stale" (the bug this
        // fixes).
        assertEquals("Stray File", unit.matchingKeyName)
        assertEquals("Stray File.srm", resolveSafeRelativePath(singleExchange.patterns.single(), unit.matchingKeyName))
        assertTrue(unit.isAssigned)
        assertEquals(md5Hex(bytes), unit.contentHash)
        assertTrue(result.unassigned.isEmpty())
    }

    @Test
    fun `EXCHANGE - a FILE_SET assignment picks up a sibling member present alongside the assigned file`() {
        val srm = "srm".toByteArray()
        val rtc = "rtc".toByteArray()
        val scanner = FakeSaveFolderScanner(mapOf("Stray.srm" to srm, "Stray.rtc" to rtc))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "NotStray"))

        val result = resolveSaveLocations(fileSetExchange, folder, games, scanner, assignments = mapOf(7 to "Stray.srm"))

        val unit = result.units.single()
        assertEquals("Stray", unit.matchingKeyName)
        assertEquals(listOf("Stray.rtc", "Stray.srm"), unit.memberPaths) // sorted, sibling picked up
        assertTrue(result.unassigned.isEmpty())
    }

    @Test
    fun `deriveEffectiveName round-trips through resolveSafeRelativePath for SINGLE_FILE, FILE_SET and FOLDER`() {
        assertEquals("Stray File", deriveEffectiveName(single, "Stray File.srm"))
        assertEquals("Stray", deriveEffectiveName(fileSet, "Stray.rtc"))
        assertEquals("SomeSave", deriveEffectiveName(folderShape, "SomeSave"))
        // No pattern's shape fits -- e.g. a totally different extension.
        assertNull(deriveEffectiveName(single, "notes.txt"))
    }

    @Test
    fun `EXCHANGE - an assignment never overrides a rule-based match`() {
        val marioBytes = "mario".toByteArray()
        val strayBytes = "stray".toByteArray()
        val scanner = FakeSaveFolderScanner(mapOf("Mario.srm" to marioBytes, "Stray File.srm" to strayBytes))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "Mario"))

        // ROM 7 already has a real rule-1 match ("Mario.srm") -- an
        // assignment pointing it at a different file must not steal that
        // match away or duplicate it, even under EXCHANGE.
        val result = resolveSaveLocations(singleExchange, folder, games, scanner, assignments = mapOf(7 to "Stray File.srm"))

        assertEquals(1, result.units.size)
        assertEquals(listOf("Mario.srm"), result.units.single().memberPaths)
        assertFalse(result.units.single().isAssigned) // rule-matched, not assignment-matched
        assertEquals(listOf(LocalSaveEntry("Stray File.srm", false)), result.unassigned)
    }

    @Test
    fun `EXCHANGE - a stale assignment pointing at a file that no longer exists is skipped, not guessed at`() {
        val scanner = FakeSaveFolderScanner(mapOf("Mario.srm" to "x".toByteArray()))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "NotMario"))

        val result = resolveSaveLocations(singleExchange, folder, games, scanner, assignments = mapOf(7 to "Gone.srm"))

        assertTrue(result.units.isEmpty())
        assertEquals(listOf(LocalSaveEntry("Mario.srm", false)), result.unassigned)
    }

    @Test
    fun `EXCHANGE - a FOLDER assignment claims the whole directory and everything nested under it`() {
        val files = mapOf(
            "SomeSave/DATA.BIN" to "a".toByteArray(),
            "SomeSave/PARAM.SFO" to "b".toByteArray()
        )
        val scanner = FakeSaveFolderScanner(files)
        // No saveTarget at all -- rule 2 could never have matched this ROM,
        // which is exactly the scenario the Unassigned-files UI exists for.
        val games = listOf(GameSaveIdentity(romId = 3, romStem = "irrelevant", saveTarget = null))

        val result = resolveSaveLocations(folderExchange, folder, games, scanner, assignments = mapOf(3 to "SomeSave"))

        assertEquals(1, result.units.size)
        val unit = result.units.single()
        assertEquals(SaveShape.FOLDER, unit.shape)
        assertEquals(listOf("SomeSave"), unit.memberPaths)
        assertTrue(result.unassigned.isEmpty())
    }

    @Test
    fun `EXCHANGE - an assignment pointing at a file when the preset wants a FOLDER is skipped`() {
        val scanner = FakeSaveFolderScanner(mapOf("SomeSave.txt" to "x".toByteArray()))
        val games = listOf(GameSaveIdentity(romId = 3, romStem = "irrelevant", saveTarget = null))

        val result = resolveSaveLocations(folderExchange, folder, games, scanner, assignments = mapOf(3 to "SomeSave.txt"))

        assertTrue(result.units.isEmpty())
        // A plain file is never an eligible Unassigned candidate for a
        // FOLDER preset (should-fix item 5) -- it doesn't linger in the
        // list either.
        assertTrue(result.unassigned.isEmpty())
    }

    // --- Owner decision (2026-09-29): DIRECT ignores assignments entirely ---

    @Test
    fun `DIRECT ignores a well-formed assignment entirely -- no unit, entry stays unassigned`() {
        val bytes = "x".toByteArray()
        val scanner = FakeSaveFolderScanner(mapOf("Stray File.srm" to bytes))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "Mario"))

        // `single` is DIRECT -- the exact same assignment claims this file
        // under EXCHANGE (see the EXCHANGE test above); under DIRECT it
        // must be completely ignored.
        val result = resolveSaveLocations(single, folder, games, scanner, assignments = mapOf(7 to "Stray File.srm"))

        assertTrue(result.units.isEmpty())
        assertEquals(listOf(LocalSaveEntry("Stray File.srm", false)), result.unassigned)
    }

    @Test
    fun `DIRECT ignores an assignment even for a game rule 2 could never have matched on its own`() {
        // No saveTarget on the game -- rule 2 could never match it, which is
        // exactly the case EXCHANGE's assignment exists to rescue (see the
        // FOLDER EXCHANGE test above). Under DIRECT there's no rescue.
        val files = mapOf("SomeSave/DATA.BIN" to "a".toByteArray())
        val scanner = FakeSaveFolderScanner(files)
        val games = listOf(GameSaveIdentity(romId = 3, romStem = "irrelevant", saveTarget = null))

        val result = resolveSaveLocations(folderShape, folder, games, scanner, assignments = mapOf(3 to "SomeSave"))

        assertTrue(result.units.isEmpty())
        assertEquals(listOf(LocalSaveEntry("SomeSave", true)), result.unassigned)
    }

    @Test
    fun `DIRECT never refuses because of a stale assignment -- it's simply never consulted`() {
        // A "stale" assignment (pointing at a path that no longer exists)
        // isn't a special case for DIRECT at all -- it's just data that's
        // never read. The result with a stale assignment given must be
        // IDENTICAL to the result with no assignments at all.
        val scanner = FakeSaveFolderScanner(mapOf("Mario.srm" to "x".toByteArray()))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "Mario"))

        val withStaleAssignment = resolveSaveLocations(single, folder, games, scanner, assignments = mapOf(7 to "Gone.srm"))
        val withNoAssignment = resolveSaveLocations(single, folder, games, scanner)

        assertEquals(withNoAssignment, withStaleAssignment)
    }

    // --- should-fix item 3: matching against ALL of a platform's ROMs -------

    @Test
    fun `romIdsToBuild limits which matched ROMs get hashed, not which ROMs can claim a file`() {
        val marioBytes = "mario".toByteArray()
        val luigiBytes = "luigi".toByteArray()
        val scanner = FakeSaveFolderScanner(mapOf("Mario.srm" to marioBytes, "Luigi.srm" to luigiBytes))
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "Mario"), GameSaveIdentity(romId = 2, romStem = "Luigi"))

        val result = resolveSaveLocations(single, folder, games, scanner, romIdsToBuild = setOf(1))

        // Only ROM 1's unit was built...
        assertEquals(1, result.units.size)
        assertEquals(1, result.units.single().romId)
        // ...but ROM 2's file is still correctly recognized as MATCHED (by
        // rule 1), not left in `unassigned` just because it wasn't built.
        assertTrue(result.unassigned.isEmpty())
    }

    @Test
    fun `EXCHANGE - an assignment cannot steal a file a ROM outside romIdsToBuild would rule-match`() {
        // Luigi.srm matches ROM 2 by rule 1 -- an assignment pointing ROM 1
        // at the same file must lose, even though only ROM 1's unit is
        // being built this call (mirrors SaveLocationRepository.unitFor
        // scanning against the WHOLE platform, not just the requested ROM).
        val scanner = FakeSaveFolderScanner(mapOf("Luigi.srm" to "luigi".toByteArray()))
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "Mario"), GameSaveIdentity(romId = 2, romStem = "Luigi"))

        val result = resolveSaveLocations(
            singleExchange, folder, games, scanner,
            assignments = mapOf(1 to "Luigi.srm"), romIdsToBuild = setOf(1)
        )

        assertTrue(result.units.none { it.romId == 1 })
    }

    // --- Phase 3B round-2 fixes, blocker 1: decideDownloadTarget's decision
    // tree, exercised against both of the review's named scenarios (EXCHANGE,
    // where assignment applies at all). ---

    @Test
    fun `scenario - a newly rule-matching ROM overrides an older assignment, which decides as StaleAssignment`() {
        // X (romId 7) was assigned "Other.srm". A new ROM "Other" (romId 8)
        // gets added and rule-matches "Other.srm" directly -- the rule match
        // always wins (see "an assignment never overrides a rule-based
        // match" above), so X ends up with NO unit at all.
        val scanner = FakeSaveFolderScanner(mapOf("Other.srm" to "bytes".toByteArray()))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "X"), GameSaveIdentity(romId = 8, romStem = "Other"))

        val result = resolveSaveLocations(singleExchange, folder, games, scanner, assignments = mapOf(7 to "Other.srm"))

        val xUnit = result.units.firstOrNull { it.romId == 7 }
        assertNull(xUnit)
        // The raw assignment record still exists (nothing about a scan
        // clears PrefsStore by itself) -- decideDownloadTarget must treat
        // this as stale, not fall back to guessing.
        val decision = decideDownloadTarget(xUnit, hasRawAssignmentRecord = true)
        assertEquals(DownloadTargetDecision.StaleAssignment, decision)
    }

    @Test
    fun `scenario - X later rule-matches X_srm directly, which decides as UseUnit`() {
        // Same ROM (X, romId 7), now with its OWN file "X.srm" present --
        // rule 1 matches it directly, independent of any assignment.
        val scanner = FakeSaveFolderScanner(mapOf("X.srm" to "bytes".toByteArray()))
        val games = listOf(GameSaveIdentity(romId = 7, romStem = "X"))

        val result = resolveSaveLocations(singleExchange, folder, games, scanner, assignments = mapOf(7 to "Other.srm"))

        val xUnit = result.units.single { it.romId == 7 }
        assertEquals("X", xUnit.matchingKeyName)
        assertFalse(xUnit.isAssigned) // rule-matched, not assignment-matched
        val decision = decideDownloadTarget(xUnit, hasRawAssignmentRecord = true)
        assertEquals(DownloadTargetDecision.UseUnit(xUnit), decision)
    }

    @Test
    fun `decideDownloadTarget is NoLocalUnit when there's no unit and no assignment record`() {
        assertEquals(DownloadTargetDecision.NoLocalUnit, decideDownloadTarget(null, hasRawAssignmentRecord = false))
    }

    // --- Phase 3B round-2 fixes, nit: deriveEffectiveName must prefer the
    // most SPECIFIC round-tripping pattern, not just the first one in
    // pattern order. ---

    @Test
    fun `deriveEffectiveName prefers the longer, more specific suffix regardless of pattern order`() {
        // Both "{name}.sav" and "{name}.public.sav" round-trip
        // "Foo.public.sav" (candidates "Foo.public" and "Foo" respectively)
        // -- deliberately listed with the SHORTER suffix first to prove the
        // result doesn't depend on iteration order.
        val preset = SavePreset(
            key = PresetKey.RetroArchCore("nds", "melondsds"), mode = SaveSyncMode.DIRECT,
            shape = SaveShape.FILE_SET, patterns = listOf("{name}.sav", "{name}.public.sav"),
            matchingKey = MatchingKeyKind.ROM_STEM, emulatorId = "melondsds"
        )

        assertEquals("Foo", deriveEffectiveName(preset, "Foo.public.sav"))

        // Same preset, patterns in the OPPOSITE order -- same result.
        val reordered = preset.copy(patterns = listOf("{name}.public.sav", "{name}.sav"))
        assertEquals("Foo", deriveEffectiveName(reordered, "Foo.public.sav"))
    }
}
