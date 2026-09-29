package com.theycallmeboxy.caulker.data.saves

// Save shapes a preset can declare (save-sync design doc, Part 2 §2). Pure
// Kotlin, no Android deps -- this whole package is JVM-testable data model +
// matching logic; the real per-emulator preset table (§9/§10) is Phase 4, and
// zip pack/unpack for FILE_SET/FOLDER is Phase 2.
enum class SaveShape {
    // One file per game (e.g. RetroArch's `.srm`).
    SINGLE_FILE,
    // A fixed list of relative path patterns per game (each containing
    // NAME_TOKEN), transferred together as one save unit.
    FILE_SET,
    // A directory per game.
    FOLDER
}

// Mirrors RomM's `SaveTargetLayout` (models/rom.py), used to interpret a
// game's `save_target` field (§3 rule 2, §4). Kept as a Kotlin enum here
// rather than deserialized directly by Moshi on the API model, so an unknown
// future value from a newer server degrades to "no layout" (fromWire returns
// null) instead of failing the whole ROM row's JSON parse.
enum class SaveTargetLayout(val wireValue: String, val isFolder: Boolean, val isPrefix: Boolean) {
    FILE_EXACT("file-exact", isFolder = false, isPrefix = false),
    FILE_PREFIX("file-prefix", isFolder = false, isPrefix = true),
    FOLDER_EXACT("folder-exact", isFolder = true, isPrefix = false),
    FOLDER_PREFIX("folder-prefix", isFolder = true, isPrefix = true),
    // Reserved for a save split across more than one on-disk location for a
    // single game (e.g. a dual-tree layout); not exercised by any v1 system.
    // Matches the same way as FOLDER_PREFIX -- the "split" comes from a
    // preset declaring more than one pattern root, not from different match
    // semantics (see SaveMatcher.matchBySaveTarget). Note isPrefix (like
    // isFolder) only drives matching when the preset declares no patterns --
    // a preset that does declare patterns always matches them exactly,
    // regardless of this flag (see matchBySaveTarget's doc comment).
    FOLDER_SPLIT("folder-split", isFolder = true, isPrefix = true);

    companion object {
        fun fromWire(value: String?): SaveTargetLayout? = value?.let { v -> entries.find { it.wireValue == v } }
    }
}
