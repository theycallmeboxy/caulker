package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for matchSaves() — pattern expansion with {name}, each
// save_target_layout's matching behavior, unassigned-file detection,
// ambiguous-claim arbitration, and nested-entry coverage (save-sync design
// doc, Part 2 §3 / §13 test plan).
class SaveMatcherTest {

    private fun file(path: String) = LocalSaveEntry(path, isDirectory = false)
    private fun dir(path: String) = LocalSaveEntry(path, isDirectory = true)

    // --- ROM_STEM matching (§3 rule 1) ---

    private val snes9x = SavePreset(
        key = PresetKey.RetroArchCore("snes", "snes9x"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.SINGLE_FILE,
        patterns = listOf("{name}.srm"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "snes9x"
    )

    @Test
    fun `SINGLE_FILE ROM_STEM matches the exact expanded pattern`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "Chrono Trigger (USA)"))
        val entries = listOf(file("Chrono Trigger (USA).srm"), file("Other Game.srm"))
        val result = matchSaves(snes9x, games, entries)
        assertEquals(listOf(file("Chrono Trigger (USA).srm")), result.matchedByGame[1])
        assertEquals(listOf(file("Other Game.srm")), result.unassigned)
    }

    // FILE_SET with an optional RTC member — present for some games, absent
    // for others; both are matched together as one unit when present.
    private val snes9xRtc = SavePreset(
        key = PresetKey.RetroArchCore("snes", "snes9x"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FILE_SET,
        patterns = listOf("{name}.srm", "{name}.rtc"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "snes9x"
    )

    @Test
    fun `FILE_SET matches every present member and tolerates a missing optional one`() {
        val games = listOf(
            GameSaveIdentity(romId = 1, romStem = "Super Mario RPG"), // no RTC cart
            GameSaveIdentity(romId = 2, romStem = "Star Ocean")       // SRTC cart
        )
        val entries = listOf(
            file("Super Mario RPG.srm"),
            file("Star Ocean.srm"),
            file("Star Ocean.rtc")
        )
        val result = matchSaves(snes9xRtc, games, entries)
        assertEquals(listOf(file("Super Mario RPG.srm")), result.matchedByGame[1])
        assertEquals(
            setOf(file("Star Ocean.srm"), file("Star Ocean.rtc")),
            result.matchedByGame[2]?.toSet()
        )
        assertTrue(result.unassigned.isEmpty())
    }

    // A subdirectory pattern (`nvram/{name}.nv`-style) must keep matching by
    // its own exact resolved path -- unaffected by the nested-entry coverage
    // rule, which only applies to entries sitting *under* a matched
    // directory, not to a file whose own pattern happens to include a path.
    private val arcadeNested = SavePreset(
        key = PresetKey.Standalone("arcade", "app"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FILE_SET,
        patterns = listOf("nvram/{name}.nv", "hi/{name}.hi"),
        matchingKey = MatchingKeyKind.ROM_STEM,
        emulatorId = "app"
    )

    @Test
    fun `nested FILE_SET patterns still match by their own exact resolved path`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "sf2"))
        val entries = listOf(file("nvram/sf2.nv"), file("hi/sf2.hi"), file("nvram/other.nv"))
        val result = matchSaves(arcadeNested, games, entries)
        assertEquals(
            setOf(file("nvram/sf2.nv"), file("hi/sf2.hi")),
            result.matchedByGame[1]?.toSet()
        )
        assertEquals(listOf(file("nvram/other.nv")), result.unassigned)
    }

    @Test
    fun `a file matching no game is unassigned`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "Chrono Trigger (USA)"))
        val entries = listOf(file("Chrono Trigger (USA).srm"), file("stray_file.srm"))
        val result = matchSaves(snes9x, games, entries)
        assertEquals(listOf(file("stray_file.srm")), result.unassigned)
    }

    @Test
    fun `a directory never matches a SINGLE_FILE preset even with the right name`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "Chrono Trigger (USA)"))
        val entries = listOf(dir("Chrono Trigger (USA).srm"))
        val result = matchSaves(snes9x, games, entries)
        assertTrue(result.matchedByGame.isEmpty())
        assertEquals(entries, result.unassigned)
    }

    // --- SAVE_TARGET matching (§3 rule 2): pattern-less, layout-driven ---

    // Real PSP folder names are `<GameID><SaveName>`, not `{name}` alone, so
    // this preset declares no patterns and relies on FOLDER_PREFIX.
    private val ppsspp = SavePreset(
        key = PresetKey.Standalone("psp", "ppsspp"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FOLDER,
        patterns = emptyList(),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FOLDER_PREFIX,
        emulatorId = "ppsspp"
    )

    @Test
    fun `pattern-less FOLDER_PREFIX matches every directory whose name starts with save_target`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "ULUS10064"))
        val entries = listOf(
            dir("ULUS10064DATA00"),
            dir("ULUS10064SETTINGS"),
            dir("ULUS99999OTHER"),
            file("ULUS10064DATA00_loose_file.bin")
        )
        val result = matchSaves(ppsspp, games, entries)
        assertEquals(
            setOf(dir("ULUS10064DATA00"), dir("ULUS10064SETTINGS")),
            result.matchedByGame[1]?.toSet()
        )
        assertTrue(result.unassigned.any { it.relativePath == "ULUS99999OTHER" })
        assertTrue(result.unassigned.any { it.relativePath == "ULUS10064DATA00_loose_file.bin" })
    }

    private val ps2FolderCard = SavePreset(
        key = PresetKey.Standalone("ps2", "nethersx2"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FOLDER,
        patterns = emptyList(),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FOLDER_EXACT,
        emulatorId = "nethersx2"
    )

    @Test
    fun `pattern-less FOLDER_EXACT requires the directory name to equal save_target exactly`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "BASLUS-01234"))
        val entries = listOf(dir("BASLUS-01234"), dir("BASLUS-01234-extra"))
        val result = matchSaves(ps2FolderCard, games, entries)
        assertEquals(listOf(dir("BASLUS-01234")), result.matchedByGame[1])
        assertEquals(listOf(dir("BASLUS-01234-extra")), result.unassigned)
    }

    // --- SAVE_TARGET matching: patterns present beat layout-driven prefix ---

    private val flycastCore = SavePreset(
        key = PresetKey.RetroArchCore("dreamcast", "flycast"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.SINGLE_FILE,
        patterns = listOf("{name}.A1.bin"),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FILE_PREFIX,
        emulatorId = "flycast"
    )

    @Test
    fun `a preset with patterns matches exactly, not merely by prefix`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "T-8111N"))
        val entries = listOf(file("T-8111N.A1.bin"), file("T-8111N.txt"))
        val result = matchSaves(flycastCore, games, entries)
        assertEquals(listOf(file("T-8111N.A1.bin")), result.matchedByGame[1])
        assertEquals(listOf(file("T-8111N.txt")), result.unassigned)
    }

    private val fileExactPreset = SavePreset(
        key = PresetKey.Standalone("system", "app"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.SINGLE_FILE,
        patterns = listOf("{name}.sav"),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FILE_EXACT,
        emulatorId = "app"
    )

    @Test
    fun `FILE_EXACT with a pattern requires the resolved pattern path to match exactly`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "ABC123"))
        val entries = listOf(file("ABC123.sav"), file("ABC123-extra.sav"))
        val result = matchSaves(fileExactPreset, games, entries)
        assertEquals(listOf(file("ABC123.sav")), result.matchedByGame[1])
        assertEquals(listOf(file("ABC123-extra.sav")), result.unassigned)
    }

    // FOLDER_SPLIT with multi-root patterns declared -- patterns win, so this
    // resolves each root exactly rather than by prefix; matches identically
    // here since the test data has no extra suffix beyond save_target.
    private val splitPreset = SavePreset(
        key = PresetKey.Standalone("arcade", "app"),
        mode = SaveSyncMode.DIRECT,
        shape = SaveShape.FOLDER,
        patterns = listOf("nvram/{name}", "hi/{name}"),
        matchingKey = MatchingKeyKind.SAVE_TARGET,
        saveTargetLayout = SaveTargetLayout.FOLDER_SPLIT,
        emulatorId = "app"
    )

    @Test
    fun `FOLDER_SPLIT collects matches across every declared root`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "sf2"))
        val entries = listOf(dir("nvram/sf2"), dir("hi/sf2"), dir("nvram/other"))
        val result = matchSaves(splitPreset, games, entries)
        assertEquals(setOf(dir("nvram/sf2"), dir("hi/sf2")), result.matchedByGame[1]?.toSet())
        assertEquals(listOf(dir("nvram/other")), result.unassigned)
    }

    // --- SAVE_TARGET matching: per-ROM layout overrides the preset's ---

    @Test
    fun `a game's own saveTargetLayout overrides the preset's default`() {
        // Preset default is FOLDER_EXACT, which would reject a suffixed
        // folder name -- the game's own layout says FOLDER_PREFIX instead.
        val strictPreset = ps2FolderCard.copy(saveTargetLayout = SaveTargetLayout.FOLDER_EXACT)
        val games = listOf(
            GameSaveIdentity(
                romId = 1, romStem = "ignored", saveTarget = "ULUS10064",
                saveTargetLayout = SaveTargetLayout.FOLDER_PREFIX
            )
        )
        val entries = listOf(dir("ULUS10064DATA00"))
        val result = matchSaves(strictPreset, games, entries)
        assertEquals(listOf(dir("ULUS10064DATA00")), result.matchedByGame[1])
    }

    // --- SAVE_TARGET matching with save_target/layout unavailable ---

    @Test
    fun `null save_target leaves the game unmatched and its files unassigned`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = null))
        val entries = listOf(dir("ULUS10064DATA00"))
        val result = matchSaves(ppsspp, games, entries)
        assertTrue(result.matchedByGame.isEmpty())
        assertEquals(entries, result.unassigned)
    }

    @Test
    fun `no layout at all (game and preset both null) leaves the game unmatched`() {
        val noLayoutPreset = ppsspp.copy(saveTargetLayout = null)
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "ULUS10064"))
        val entries = listOf(dir("ULUS10064DATA00"))
        val result = matchSaves(noLayoutPreset, games, entries)
        assertTrue(result.matchedByGame.isEmpty())
        assertEquals(entries, result.unassigned)
    }

    // --- Ambiguous claims: longest matching key wins; a tie is unassigned ---

    @Test
    fun `an overlapping-prefix id is awarded to the game with the longer key`() {
        // Dreamcast product numbers vary in length; "T-811" is a literal
        // prefix of "T-8111N". Pattern-less FILE_PREFIX so both are eligible
        // prefix candidates for the same file.
        val filePrefixPreset = flycastCore.copy(patterns = emptyList())
        val games = listOf(
            GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "T-811"),
            GameSaveIdentity(romId = 2, romStem = "ignored", saveTarget = "T-8111N")
        )
        val entries = listOf(file("T-8111N.A1.bin"))
        val result = matchSaves(filePrefixPreset, games, entries)
        assertEquals(listOf(file("T-8111N.A1.bin")), result.matchedByGame[2])
        assertTrue(result.matchedByGame[1] == null || result.matchedByGame[1]!!.isEmpty())
        assertTrue(result.unassigned.isEmpty())
    }

    @Test
    fun `an exact-length tie between two games' keys is left unassigned`() {
        val games = listOf(
            GameSaveIdentity(romId = 1, romStem = "Zelda"),
            GameSaveIdentity(romId = 2, romStem = "Zelda") // duplicate stem, same length
        )
        val entries = listOf(file("Zelda.srm"))
        val result = matchSaves(snes9x, games, entries)
        assertTrue(result.matchedByGame[1] == null || result.matchedByGame[1]!!.isEmpty())
        assertTrue(result.matchedByGame[2] == null || result.matchedByGame[2]!!.isEmpty())
        assertEquals(listOf(file("Zelda.srm")), result.unassigned)
    }

    // --- Nested entries: a matched directory covers everything under it ---

    @Test
    fun `children of a matched directory are covered, not unassigned, and not re-listed`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "ULUS10064"))
        val entries = listOf(
            dir("ULUS10064DATA00"),
            file("ULUS10064DATA00/DATA.BIN"),
            file("ULUS10064DATA00/PARAM.SFO"),
            dir("ULUS99999OTHER")
        )
        val result = matchSaves(ppsspp, games, entries)
        // Only the directory itself is listed -- its children aren't repeated.
        assertEquals(listOf(dir("ULUS10064DATA00")), result.matchedByGame[1])
        // The children are covered, so they're absent from unassigned too.
        assertEquals(listOf(dir("ULUS99999OTHER")), result.unassigned)
    }

    // --- Path-traversal safety, exercised through the matcher ---

    @Test
    fun `a save_target attempting traversal never matches anything`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "ignored", saveTarget = "../../etc"))
        val entries = listOf(dir("SAVEDATA/../../etc"))
        val result = matchSaves(ppsspp, games, entries)
        assertTrue(result.matchedByGame.isEmpty())
    }

    @Test
    fun `a rom stem attempting traversal never matches anything`() {
        val games = listOf(GameSaveIdentity(romId = 1, romStem = "../../etc/passwd"))
        val entries = listOf(file("../../etc/passwd.srm"))
        val result = matchSaves(snes9x, games, entries)
        assertTrue(result.matchedByGame.isEmpty())
    }
}
