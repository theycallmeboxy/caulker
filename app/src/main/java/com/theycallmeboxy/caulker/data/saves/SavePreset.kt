package com.theycallmeboxy.caulker.data.saves

// Direct vs. Exchange (save-sync design doc, Part 2 §1).
enum class SaveSyncMode { DIRECT, EXCHANGE }

// Which of §3's two rules a preset matches local files with.
enum class MatchingKeyKind { ROM_STEM, SAVE_TARGET }

// Identifies a preset. RetroArch presets are keyed on (system, core) rather
// than system alone -- the core determines the file set/extensions and
// matching key, not the system (§10) -- so it's a distinct case from a
// standalone emulator's (system, app id) key rather than a shared
// (system, string) pair, to keep a RetroArch core slug from ever being
// confused with a standalone app id that happens to collide with it (both
// "melonds" e.g. per §7's id table).
sealed class PresetKey {
    abstract val system: String

    data class RetroArchCore(override val system: String, val core: String) : PresetKey()
    data class Standalone(override val system: String, val emulatorApp: String) : PresetKey()
}

// A save-shape + matching configuration for one preset. Phase 1 only defines
// this shape and the pure logic that consumes it (matching, emulator id
// selection); the real per-emulator table (§9/§10.x) is Phase 4 data, and the
// settings UI that lets a user pick/edit a preset is Phase 3.
data class SavePreset(
    val key: PresetKey,
    val mode: SaveSyncMode,
    val shape: SaveShape,
    // Relative path patterns, each containing NAME_TOKEN exactly once.
    // SINGLE_FILE declares exactly one; FILE_SET/FOLDER may declare more than
    // one (FILE_SET's optional members, e.g. `{name}.srm` + `{name}.rtc`, or a
    // FOLDER's sibling roots for a multi-root archive per §2).
    val patterns: List<String>,
    val matchingKey: MatchingKeyKind,
    // Required (non-null) when matchingKey == SAVE_TARGET; unused otherwise.
    val saveTargetLayout: SaveTargetLayout? = null,
    // The id this preset uploads in the `emulator` field (§7) -- a libretro
    // core slug for a RetroArch preset, a standalone app id otherwise.
    val emulatorId: String
)
