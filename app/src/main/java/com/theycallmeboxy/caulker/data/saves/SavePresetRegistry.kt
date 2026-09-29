package com.theycallmeboxy.caulker.data.saves

// The real RetroArch per-core preset table (save-sync design doc, Part 2
// §10.x "Supported" rows only -- design phase 4 data, landed early as part
// of Phase 3A's live-sync wiring so there's something real to wire against
// instead of a placeholder). Standalone-emulator presets (DuckStation,
// PPSSPP standalone, DraStic, Flycast standalone, Dolphin, ...) are
// deliberately NOT included here -- §12 phase 5 gates every preset on
// on-device verification, and per this task's instructions the standalone
// half of §9's table hasn't had that pass yet. Arcade (MAME 2003-Plus/FBNeo)
// is also omitted: §9 calls it a stretch goal and §11 leaves FBNeo's exact
// on-disk layout as an open item, so it isn't "trivially expressible" yet.
// Wii and the PS2/GameCube/Dreamcast-standalone/N64-Exchange systems in §9
// are standalone-emulator or Exchange presets, likewise out of scope here.
//
// RomM platform fs_slug values (the SLUG_TO_SYSTEM map below) were read
// directly from a live RomM 5.3.1 checkout's own vocabulary --
// backend/utils/platform_slugs.py's UniversalPlatformSlug enum, the single
// source of truth for fs_slug strings in that version (no separate alias
// table exists in that module). A platform slug with no entry in
// SLUG_TO_SYSTEM has no v1 preset -- presetsForPlatform returns empty and the
// platform falls back to legacy resolution (§6), exactly like any other
// unconfigured platform.

data class RetroArchPresetInfo(
    val preset: SavePreset,
    // Human display name for the settings UI's preset picker, e.g. "FCEUmm".
    val coreDisplayName: String,
    // §10.x's "Required core-option change" column, one human-readable line
    // per change; empty when that column says "None". A later phase's UI
    // shows these as setup instructions -- Caulker never changes the option
    // itself, only tells the user what to change (design principle #2:
    // Caulker is a sync tool, not a launcher).
    val setupNotes: List<String>
)

object SavePresetRegistry {
    // presetsForPlatform's public entry point: every RetroArch core preset
    // available for a RomM platform fs_slug, or empty if v1 has none (§6
    // fallback). Returns a defensive copy's worth of immutable data --
    // callers never mutate a SavePreset, so sharing the same List instances
    // across calls is safe and avoids rebuilding the table per lookup.
    fun presetsForPlatform(fsSlug: String?): List<RetroArchPresetInfo> {
        val system = fsSlug?.let { SLUG_TO_SYSTEM[it] } ?: return emptyList()
        return PRESETS_BY_SYSTEM[system].orEmpty()
    }

    // Looks up one specific preset by its key within a platform's list --
    // used to resolve a persisted SavePlatformConfig (data/prefs/PrefsStore.kt)
    // back into a real SavePreset. Returns null if the platform has no v1
    // presets at all, or if `key` no longer matches any of them (e.g. a
    // preset removed/renamed after the user configured it) -- either way,
    // the caller's correct response is the same as an unconfigured platform
    // (§6 legacy fallback), not a crash.
    fun presetFor(fsSlug: String?, key: PresetKey): SavePreset? =
        presetsForPlatform(fsSlug).firstOrNull { it.preset.key == key }?.preset

    // Human display name for an emulator id (§7's id table), e.g.
    // "mednafen_saturn" -> "Beetle Saturn" -- used by the download guard's
    // confirmation prompt (item 6: "names both emulators in plain words")
    // to show the INCOMING save's emulator by name, not its raw wire id.
    // Returns null for an id not in this registry (a standalone emulator's
    // id, or a RetroArch core outside v1's table) -- the caller falls back
    // to the raw id, which is still better than nothing for an id Caulker
    // doesn't otherwise know a friendly name for.
    fun displayNameForEmulatorId(emulatorId: String?): String? {
        emulatorId ?: return null
        return DISPLAY_NAME_BY_EMULATOR_ID[emulatorId]
    }

    private val DISPLAY_NAME_BY_EMULATOR_ID: Map<String, String> by lazy {
        PRESETS_BY_SYSTEM.values.flatten().associate { it.preset.emulatorId to it.coreDisplayName }
    }

    // --- Builders -----------------------------------------------------
    // Small helpers so each system's preset list below reads like the
    // §10.x table it's transcribed from, instead of repeating SavePreset's
    // full constructor call six times per core.

    private fun single(
        system: String,
        core: String,
        displayName: String,
        pattern: String,
        emulatorId: String,
        matchingKey: MatchingKeyKind = MatchingKeyKind.ROM_STEM,
        saveTargetLayout: SaveTargetLayout? = null,
        notes: List<String> = emptyList()
    ) = RetroArchPresetInfo(
        preset = SavePreset(
            key = PresetKey.RetroArchCore(system, core),
            mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE,
            patterns = listOf(pattern),
            matchingKey = matchingKey,
            saveTargetLayout = saveTargetLayout,
            emulatorId = emulatorId
        ),
        coreDisplayName = displayName,
        setupNotes = notes
    )

    // FILE_SET with ROM-stem matching, used for §10.x rows whose "Files per
    // game" column lists more than one possible extension for the same
    // core (an RTC-equipped cart's `{name}.rtc`, NES's mutually-exclusive
    // cart-vs-FDS extension, Beetle Saturn's always-written `.smpc`). Only
    // the patterns that actually exist on disk for a given game ever match
    // (SaveMatcher); SaveTransferPlan.kt uploads a single present member raw
    // rather than as a one-entry zip, so this degrades to SINGLE_FILE
    // behavior for the common case without needing a second preset shape.
    private fun fileSet(
        system: String,
        core: String,
        displayName: String,
        patterns: List<String>,
        emulatorId: String,
        notes: List<String> = emptyList()
    ) = RetroArchPresetInfo(
        preset = SavePreset(
            key = PresetKey.RetroArchCore(system, core),
            mode = SaveSyncMode.DIRECT,
            shape = SaveShape.FILE_SET,
            patterns = patterns,
            matchingKey = MatchingKeyKind.ROM_STEM,
            emulatorId = emulatorId
        ),
        coreDisplayName = displayName,
        setupNotes = notes
    )

    // FOLDER with save_target matching and no declared patterns -- per
    // SaveMatcher.matchBySaveTarget's doc comment, this is exactly the case
    // for a save named by the game/emulator itself (a PSP GameID+SaveName
    // folder) rather than by a fixed suffix appended to the matching key.
    private fun folder(
        system: String,
        core: String,
        displayName: String,
        saveTargetLayout: SaveTargetLayout,
        emulatorId: String,
        notes: List<String> = emptyList()
    ) = RetroArchPresetInfo(
        preset = SavePreset(
            key = PresetKey.RetroArchCore(system, core),
            mode = SaveSyncMode.DIRECT,
            shape = SaveShape.FOLDER,
            patterns = emptyList(),
            matchingKey = MatchingKeyKind.SAVE_TARGET,
            saveTargetLayout = saveTargetLayout,
            emulatorId = emulatorId
        ),
        coreDisplayName = displayName,
        setupNotes = notes
    )

    // --- §10.x table, one list per system key ---------------------------
    // System keys are Caulker's own internal labels (PresetKey.system),
    // independent of any single RomM fs_slug -- SLUG_TO_SYSTEM below is
    // what maps one or more real RomM slugs onto each list.

    private val NES = listOf(
        single("nes", "fceumm", "FCEUmm", "{name}.srm", emulatorId = "fceumm"),
        // Cart .srm and FDS's default .sav are mutually exclusive per game
        // (§10.x) -- modeled as FILE_SET so whichever one actually exists
        // for a given ROM is the one SaveMatcher picks up; see fileSet()'s
        // doc comment above for why this doesn't force a needless zip.
        fileSet("nes", "nestopia", "Nestopia UE", listOf("{name}.srm", "{name}.sav"), emulatorId = "nestopia"),
        fileSet("nes", "mesen", "Mesen", listOf("{name}.srm", "{name}.ips"), emulatorId = "mesen")
    )

    private val SNES = listOf(
        fileSet("snes", "snes9x", "Snes9x", listOf("{name}.srm", "{name}.rtc"), emulatorId = "snes9x"),
        fileSet("snes", "snes9x2010", "Snes9x 2010", listOf("{name}.srm", "{name}.rtc"), emulatorId = "snes9x2010"),
        fileSet("snes", "bsnes", "bsnes", listOf("{name}.srm", "{name}.rtc"), emulatorId = "bsnes")
    )

    private val GB_GBC = listOf(
        fileSet("gb-gbc", "gambatte", "Gambatte", listOf("{name}.srm", "{name}.rtc"), emulatorId = "gambatte"),
        fileSet("gb-gbc", "sameboy", "SameBoy", listOf("{name}.srm", "{name}.rtc"), emulatorId = "sameboy"),
        fileSet("gb-gbc", "gearboy", "Gearboy", listOf("{name}.srm", "{name}.rtc"), emulatorId = "gearboy")
    )

    private val GBA = listOf(
        single("gba", "mgba", "mGBA", "{name}.srm", emulatorId = "mgba"),
        single("gba", "gpsp", "gpSP", "{name}.srm", emulatorId = "gpsp"),
        single("gba", "vbam", "VBA-M", "{name}.srm", emulatorId = "vbam")
    )

    // Genesis/Mega Drive, Master System, Game Gear, SG-1000, 32X -- one
    // save-file convention shared across the whole Sega 8/16-bit family
    // (§9/§10.x), so one system key covers every RomM slug in that family.
    private val GENESIS_FAMILY = listOf(
        single("genesis-family", "genesis_plus_gx", "Genesis Plus GX", "{name}.srm", emulatorId = "genesis_plus_gx"),
        single("genesis-family", "picodrive", "PicoDrive", "{name}.srm", emulatorId = "picodrive")
    )

    private val SEGA_CD = listOf(
        // Cart-BRAM's optional `<size>Kbit_cart.brm` file isn't {name}-based
        // (its filename is parameterized by cart size, not the matching
        // key), so it can't be expressed as a pattern here -- omitted;
        // tracked as a known simplification (see final report).
        single(
            "segacd", "genesis_plus_gx", "Genesis Plus GX", "{name}.brm", emulatorId = "genesis_plus_gx",
            notes = listOf("Set CD System BRAM to Per-Game (RetroArch core option: genesis_plus_gx_system_bram)")
        ),
        single("segacd", "picodrive", "PicoDrive", "{name}.srm", emulatorId = "picodrive")
    )

    // Sega CD 32X: a distinct system from plain Sega CD -- Genesis Plus GX
    // has no 32X support at all (independent review, phase 3A fixes nits),
    // so this list is PicoDrive only, not SEGA_CD's full pair.
    private val SEGA_CD_32X = listOf(
        single("segacd32", "picodrive", "PicoDrive", "{name}.srm", emulatorId = "picodrive")
    )

    private val PCE = listOf(
        single("pce", "mednafen_pce", "Beetle PCE", "{name}.srm", emulatorId = "mednafen_pce"),
        single("pce", "mednafen_pce_fast", "Beetle PCE Fast", "{name}.srm", emulatorId = "mednafen_pce_fast")
    )

    private val N64 = listOf(
        single("n64", "mupen64plus_next", "Mupen64Plus-Next", "{name}.srm", emulatorId = "mupen64plus_next"),
        single(
            "n64", "parallel_n64", "ParaLLEl N64", "{name}.srm", emulatorId = "parallel_n64",
            notes = listOf(
                "Set Player 1 Pak to Memory (RetroArch core option: parallel-n64-pak1) for games " +
                    "that use the Controller Pak"
            )
        )
    )

    private val PS1 = listOf(
        single("ps1", "mednafen_psx", "Beetle PSX", "{name}.srm", emulatorId = "mednafen_psx"),
        single("ps1", "mednafen_psx_hw", "Beetle PSX HW", "{name}.srm", emulatorId = "mednafen_psx_hw"),
        single("ps1", "swanstation", "SwanStation", "{name}.srm", emulatorId = "swanstation"),
        single(
            "ps1", "pcsx_rearmed", "PCSX ReARMed", "{name}.srm", emulatorId = "pcsx_rearmed",
            notes = listOf(
                "Set Memory Card 2 Type to No Memory Card (RetroArch core option: " +
                    "pcsx_rearmed_memcard2) -- otherwise a shared card is created for every game"
            )
        )
    )

    private val SATURN = listOf(
        // Optional cart-NV file is a `.bcr`-style name only present when an
        // NV-memory cart is active -- like Sega CD's cart-BRAM file above,
        // not {name}-based, so left untracked by this preset.
        fileSet("saturn", "mednafen_saturn", "Beetle Saturn", listOf("{name}.srm", "{name}.smpc"), emulatorId = "mednafen_saturn"),
        single("saturn", "yabause", "Yabause", "{name}.srm", emulatorId = "yabause")
    )

    // §11's "Dreamcast game-ID matching" open item: both Flycast builds
    // name the per-game VMU after the disc's IP.BIN product number, which
    // RomM 5.3.1's argosy-sigil extractor reproduces as the ROM's
    // save_target under save_target_layout = file-prefix. The preset's own
    // pattern (not the bare save_target) supplies the RetroArch core's
    // `.A1.bin` suffix, per matchBySaveTarget's doc comment.
    private val DREAMCAST = listOf(
        single(
            "dreamcast", "flycast", "Flycast (RetroArch core)", "{name}.A1.bin", emulatorId = "flycast",
            matchingKey = MatchingKeyKind.SAVE_TARGET, saveTargetLayout = SaveTargetLayout.FILE_PREFIX,
            notes = listOf("Set Per-Game VMUs to VMU A1 (RetroArch core option: flycast_per_content_vmus)")
        )
    )

    private val NDS = listOf(
        // Retail carts only ever produce `.srm`; DSiWare's extra
        // `.public.sav`/`.private.sav`/`.banner.sav` are optional members --
        // same "FILE_SET with only what's present" treatment as NES/SNES/GB.
        fileSet(
            "nds", "melondsds", "melonDS DS",
            listOf("{name}.srm", "{name}.public.sav", "{name}.private.sav", "{name}.banner.sav"),
            emulatorId = "melondsds"
        ),
        single("nds", "melonds", "melonDS (legacy)", "{name}.sav", emulatorId = "melonds"),
        single("nds", "desmume", "DeSmuME", "{name}.dsv", emulatorId = "desmume"),
        single("nds", "desmume2015", "DeSmuME 2015", "{name}.dsv", emulatorId = "desmume2015")
    )

    private val PSP = listOf(
        folder("psp", "ppsspp", "PPSSPP (core)", SaveTargetLayout.FOLDER_PREFIX, emulatorId = "ppsspp")
    )

    private val PRESETS_BY_SYSTEM: Map<String, List<RetroArchPresetInfo>> = mapOf(
        "nes" to NES,
        "snes" to SNES,
        "gb-gbc" to GB_GBC,
        "gba" to GBA,
        "genesis-family" to GENESIS_FAMILY,
        "segacd" to SEGA_CD,
        "segacd32" to SEGA_CD_32X,
        "pce" to PCE,
        "n64" to N64,
        "ps1" to PS1,
        "saturn" to SATURN,
        "dreamcast" to DREAMCAST,
        "nds" to NDS,
        "psp" to PSP
    )

    // RomM fs_slug (UniversalPlatformSlug enum value, backend/utils/
    // platform_slugs.py) -> Caulker system key. Famicom/Super Famicom alias
    // to the NES/SNES lists: same RetroArch cores, same save format, only
    // the ROM library's platform label differs.
    private val SLUG_TO_SYSTEM: Map<String, String> = mapOf(
        "nes" to "nes",
        "famicom" to "nes",
        "snes" to "snes",
        "sfam" to "snes",
        "gb" to "gb-gbc",
        "gbc" to "gb-gbc",
        "gba" to "gba",
        "genesis" to "genesis-family",
        "sms" to "genesis-family",
        "gamegear" to "genesis-family",
        "sg1000" to "genesis-family",
        "sega32" to "genesis-family",
        "segacd" to "segacd",
        "segacd32" to "segacd32",
        "tg16" to "pce",
        "turbografx-cd" to "pce",
        "n64" to "n64",
        "psx" to "ps1",
        "saturn" to "saturn",
        "dc" to "dreamcast",
        "nds" to "nds",
        "psp" to "psp"
    )
}
