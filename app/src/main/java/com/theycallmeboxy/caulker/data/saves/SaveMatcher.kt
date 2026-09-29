package com.theycallmeboxy.caulker.data.saves

// One file or directory found in a platform's configured save folder,
// relative to that folder ("/" separators, no leading "/"). Produced by
// scanning the folder (Phase 3); pure input here so the matcher stays
// Android-free and unit-testable.
data class LocalSaveEntry(val relativePath: String, val isDirectory: Boolean)

// A game's matching identity, sourced from RomEntity. romStem is the existing
// convention (SaveRepository.resolveLocalSaveFileName's romBase, i.e. the ROM
// filename without its extension); saveTarget/saveTargetLayout are RomM
// 5.3+'s server-computed, per-ROM fields (§4), null on an older server or an
// unscanned ROM. saveTargetLayout here is the ROM's own layout and takes
// precedence over the preset's (see matchSaves) -- RomM computes it per ROM,
// not per preset.
data class GameSaveIdentity(
    val romId: Int,
    val romStem: String,
    val saveTarget: String? = null,
    val saveTargetLayout: SaveTargetLayout? = null
)

// Result of matching a folder's contents against a preset's games: which
// local entries belong to which ROM, and which matched no ROM at all (surfaced
// by Phase 3's Unassigned-files UI, §3 rule 3). matchedByGame only lists an
// entry directly claimed by that game's pattern/save_target match -- an entry
// nested under one of those (e.g. a file inside a matched FOLDER save) is
// covered by it (excluded from unassigned) without being listed again here.
data class SaveMatchOutcome(
    val matchedByGame: Map<Int, List<LocalSaveEntry>>,
    val unassigned: List<LocalSaveEntry>
)

// One game's raw candidate match, together with the matching-key string that
// produced it -- used to arbitrate an entry more than one game's claim
// touches (see matchSaves).
private data class Claim(val romId: Int, val matchKeyLength: Int, val entries: List<LocalSaveEntry>)

// Matches a scanned folder's entries to the given games, per the preset's
// declared matching key (§3):
//   - ROM_STEM: each pattern is resolved with the ROM's stem and matched
//     exactly against an entry of the shape's expected kind (file for
//     SINGLE_FILE/FILE_SET, directory for FOLDER).
//   - SAVE_TARGET: entries are matched by the game's save_target, per
//     save_target_layout -- see matchBySaveTarget. The game's own
//     saveTargetLayout wins over the preset's (RomM computes it per ROM); if
//     neither is set, the game is skipped and its files, if any, end up
//     unassigned rather than guessed at.
//
// Ambiguous claims: an entry more than one game's rules match (e.g. Dreamcast
// product ids where one is a prefix of another, "T-811" vs "T-8111N") is
// awarded to whichever claim used the longer matching-key string (the more
// specific one); an exact-length tie goes to *no* game rather than guess, and
// the entry is left unassigned.
//
// Nested entries: once a directory is matched to a game, every entry whose
// path sits under it is covered by that same game (excluded from
// unassigned) without needing its own separate claim -- Phase 2's zip pack
// reads the whole directory anyway. This does not affect FILE_SET patterns
// that merely live in a subdirectory (e.g. `nvram/{name}.nv`): those are
// matched directly by their own resolved path, same as any other pattern.
fun matchSaves(
    preset: SavePreset,
    games: List<GameSaveIdentity>,
    localEntries: List<LocalSaveEntry>
): SaveMatchOutcome {
    val claims = games.mapNotNull { game -> claimFor(preset, game, localEntries) }

    val claimantsByPath = HashMap<String, MutableList<Claim>>()
    for (claim in claims) {
        for (entry in claim.entries) {
            claimantsByPath.getOrPut(entry.relativePath) { mutableListOf() }.add(claim)
        }
    }

    // Walk localEntries (not the claim map) so a game's matched list comes
    // out in a stable, scan order rather than HashMap iteration order.
    val matched = LinkedHashMap<Int, MutableList<LocalSaveEntry>>()
    val wonPaths = HashSet<String>()
    for (entry in localEntries) {
        val contenders = claimantsByPath[entry.relativePath] ?: continue
        val maxKeyLength = contenders.maxOf { it.matchKeyLength }
        val winners = contenders.filter { it.matchKeyLength == maxKeyLength }.map { it.romId }.distinct()
        if (winners.size != 1) continue // tie (or, defensively, no contenders) -- left unassigned
        matched.getOrPut(winners[0]) { mutableListOf() }.add(entry)
        wonPaths += entry.relativePath
    }

    val claimedDirs = matched.values.flatten().filter { it.isDirectory }.map { it.relativePath }
    val unassigned = localEntries.filterNot { entry ->
        entry.relativePath in wonPaths || claimedDirs.any { entry.relativePath.startsWith("$it/") }
    }

    return SaveMatchOutcome(matched, unassigned)
}

private fun claimFor(preset: SavePreset, game: GameSaveIdentity, entries: List<LocalSaveEntry>): Claim? =
    when (preset.matchingKey) {
        MatchingKeyKind.ROM_STEM -> {
            val matches = matchByExactPatterns(preset, game.romStem, entries)
            if (matches.isEmpty()) null else Claim(game.romId, game.romStem.length, matches)
        }
        MatchingKeyKind.SAVE_TARGET -> {
            val target = game.saveTarget
            val layout = game.saveTargetLayout ?: preset.saveTargetLayout
            if (target == null || layout == null) {
                null
            } else {
                val matches = matchBySaveTarget(preset, target, layout, entries)
                if (matches.isEmpty()) null else Claim(game.romId, target.length, matches)
            }
        }
    }

// ROM-stem matching (§3 rule 1), and the patterns branch of save_target
// matching (see matchBySaveTarget): every pattern is resolved with `name` and
// compared for an exact relative-path match. Unsafe substitutions (see
// resolveSafeRelativePath) are simply dropped, never matched.
private fun matchByExactPatterns(
    preset: SavePreset,
    name: String,
    entries: List<LocalSaveEntry>
): List<LocalSaveEntry> {
    val wantDirectory = preset.shape == SaveShape.FOLDER
    val resolvedPaths = preset.patterns.mapNotNull { resolveSafeRelativePath(it, name) }.toSet()
    if (resolvedPaths.isEmpty()) return emptyList()
    return entries.filter { it.relativePath in resolvedPaths && it.isDirectory == wantDirectory }
}

// save_target matching (§3 rule 2):
//   - If the preset declares patterns, they win outright: each is resolved
//     with save_target and matched exactly (matchByExactPatterns), whatever
//     the layout says. A preset's patterns encode the emulator's own suffix
//     convention (e.g. Flycast core `{name}.A1.bin` vs. standalone Flycast
//     `{name}_vmu_save_A1.bin`) -- a plain prefix match here would also catch
//     an unrelated file that merely starts with the same id, like
//     `T-8111N.txt` next to `T-8111N.A1.bin`.
//   - Only when the preset declares NO patterns does save_target_layout's
//     exact/prefix flag drive matching directly, against the entry's own
//     last path segment. This covers save units named by the game/emulator
//     itself rather than by a fixed suffix (PSP GameID+SaveName folders,
//     PS2's region-prefixed serial folders).
private fun matchBySaveTarget(
    preset: SavePreset,
    saveTarget: String,
    layout: SaveTargetLayout,
    entries: List<LocalSaveEntry>
): List<LocalSaveEntry> {
    // Guard the server-supplied save_target itself before it's used in any
    // comparison -- never let it reach outside the configured folder.
    if (!isSafeNameSegment(saveTarget)) return emptyList()

    if (preset.patterns.isNotEmpty()) {
        return matchByExactPatterns(preset, saveTarget, entries)
    }

    val wantDirectory = layout.isFolder
    val candidates = entries.filter { it.isDirectory == wantDirectory }
    return if (layout.isPrefix) {
        candidates.filter { lastSegment(it.relativePath).startsWith(saveTarget) }
    } else {
        candidates.filter { lastSegment(it.relativePath) == saveTarget }
    }
}

private fun lastSegment(path: String) = path.substringAfterLast('/')
