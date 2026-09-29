package com.theycallmeboxy.caulker.data.saves

import com.theycallmeboxy.caulker.data.util.md5Hex

// Scans a configured platform's folder and turns SaveMatcher's per-game
// matches into save units the rest of the app can sync (save-sync design doc,
// Part 2 §12 phase 3). Pure Kotlin, JVM-testable behind SaveFolderScanner --
// same rationale as SaveZipIo.kt's SaveFileSource/SaveFileSink, whose
// LocalSaveEntry/SaveFileSource shape this reuses rather than introducing a
// parallel one. A real Android-backed SaveFolderScanner (root-aware via
// RootFileHelper) lives in data/repository (SaveLocationRepository.kt),
// outside this package, exactly like SaveZipIo.kt's own real implementation.

// Recursive read access to a configured save folder. Extends SaveFileSource
// (readFile/listChildren) with what matchSaves + this file's hashing need on
// top: the whole recursive entry list (matchSaves itself only takes a flat
// list; producing it is the scanner's job) and per-entry mtimes.
interface SaveFolderScanner : SaveFileSource {
    // Every entry (file or directory) anywhere under the configured folder,
    // recursively, relative to it -- FILE_SET patterns can live in
    // subdirectories (e.g. `nvram/{name}.nv`) and a FOLDER preset's root can
    // itself hold nested subdirectories, so a shallow listing isn't enough
    // for SaveMatcher.matchSaves to do its job.
    fun listAllEntries(): List<LocalSaveEntry>

    // Last-modified time (epoch ms) of the file at relativePath. Only ever
    // called here for entries known to be files (resolveSaveLocations walks a
    // directory's children itself to find its latest file mtime -- see
    // latestModifiedMs below) -- an implementation doesn't need to handle a
    // directory argument meaningfully.
    fun lastModifiedMs(relativePath: String): Long
}

// One game's resolved local save, ready to compare against the server (§5)
// or transfer (SaveTransferPlan.kt). memberPaths are relative to folderPath,
// sorted for determinism; SINGLE_FILE always has exactly one.
data class LocalSaveUnit(
    val romId: Int,
    val shape: SaveShape,
    // The §3 matching-key string (ROM stem or save_target) that produced
    // this unit's match -- needed again by upload/download (SaveTransferPlan.kt):
    // FILE_SET/FOLDER zip naming and pattern resolution both take it as an
    // input, and SaveMatcher itself doesn't return it as an output (see
    // matchingKeyName's own doc comment below).
    val matchingKeyName: String,
    val memberPaths: List<String>,
    // The §5 baseline "resolved local path" -- every member's full path
    // (folderPath + relativePath), sorted and joined with "|". A single-file
    // save's resolvedPath is just that one full path; a multi-member save's
    // captures every member so a preset/folder change that only affects some
    // of a FILE_SET's members (e.g. gaining an .rtc pattern) still changes
    // the recorded path and forces the §5 safety check rather than looking
    // unchanged. Folder root membership (not folderPath alone) is what makes
    // this differ from one platform to another for the same ROM, which is
    // the property effectiveBaseline() (data/sync/SyncAction.kt) needs.
    val resolvedPath: String,
    // SINGLE_FILE: md5Hex of the raw file (matches RomM's plain content_hash,
    // same as SaveRepository.localSaveStat does today). FILE_SET/FOLDER:
    // hashLocalContentAsZip (SaveZipContentHash.kt), matching RomM's
    // hash_zip_contents server-side algorithm for a zip-shaped save.
    val contentHash: String,
    // Latest mtime among this unit's members (recursively, for a FOLDER's
    // contents) -- see latestModifiedMs.
    val modifiedMs: Long
)

// True for any path under a `.caulker_backup` directory, at any depth --
// resolveSaveLocations excludes these from matching/hashing/Unassigned
// entirely (independent review, phase 3A fixes blocker 2): a backup must
// never itself be matched as a save member, folded into a FOLDER save's zip,
// hashed, or reported as an unclaimed file. Checked against every path
// segment, not just a leading one, since a backup can appear at any level
// under the configured folder (see SaveLocationRepository's backup
// placement, which mirrors a matched member's own subdirectory).
fun isBackupPath(relativePath: String): Boolean =
    relativePath == BACKUP_DIR_NAME ||
        relativePath.startsWith("$BACKUP_DIR_NAME/") ||
        relativePath.contains("/$BACKUP_DIR_NAME/") ||
        relativePath.endsWith("/$BACKUP_DIR_NAME")

const val BACKUP_DIR_NAME = ".caulker_backup"

// Canonical §5 "resolved local path" builder -- used both by buildUnit
// (scanning) and by callers recording a baseline right after a write
// (SaveLocationRepository/ConfiguredSaveWriter), so the two can never
// disagree (independent review, phase 3A round 2 item 1: a FOLDER unit's
// resolvedPath is built from its root directory path(s) -- entries.map{it.
// relativePath} where entries are the matched DIRECTORY entries -- never the
// individual files inside them. A caller that instead fed the flat list of
// files an unpack just wrote produced a path that never matched what the
// next scan's buildUnit computed, permanently forcing effectiveBaseline()
// into "no history" -> CONFLICT after every FOLDER download). For FOLDER,
// `memberPaths` may be either the root path(s) directly (as a scan produces)
// or the flat per-file paths an unpack just wrote -- both reduce to the same
// root set here, since packSaveZip always flattens a FOLDER root to its own
// basename (SaveZipPacker.kt's planFolderArchive), so a root is always that
// path's first segment; v1's only FOLDER preset (PPSSPP) never nests a root
// under another directory, so this holds for every shipped case.
fun resolvedPathFor(folderPath: String, shape: SaveShape, memberPaths: List<String>): String {
    val roots = if (shape == SaveShape.FOLDER) memberPaths.map { it.substringBefore('/') }.distinct() else memberPaths
    return roots.sorted().joinToString("|") { "$folderPath/$it" }
}

data class SaveLocationScanResult(
    val units: List<LocalSaveUnit>,
    // Local entries claimed by no enrolled game (§3 rule 3) -- surfaced by
    // the Unassigned-files UI a later phase builds; Phase 3A just carries it
    // through.
    val unassigned: List<LocalSaveEntry>
)

// Scans `scanner`'s folder, matches it against `games` per `preset` (§3), and
// builds one LocalSaveUnit per matched game. A game with matched entries that
// can't be hashed (e.g. a file that disappeared between the scan and the
// read) is silently dropped from `units` rather than throwing -- the caller
// sees one fewer unit and, on its next scan, either sees it again or sees the
// removal reflected, rather than the whole platform's scan failing over one
// game.
fun resolveSaveLocations(
    preset: SavePreset,
    folderPath: String,
    games: List<GameSaveIdentity>,
    scanner: SaveFolderScanner
): SaveLocationScanResult {
    val allEntries = scanner.listAllEntries().filterNot { isBackupPath(it.relativePath) }
    val outcome = matchSaves(preset, games, allEntries)
    val gameById = games.associateBy { it.romId }

    val units = outcome.matchedByGame.mapNotNull { (romId, entries) ->
        buildUnit(romId, preset, folderPath, gameById[romId], entries, scanner)
    }
    return SaveLocationScanResult(units, outcome.unassigned)
}

private fun buildUnit(
    romId: Int,
    preset: SavePreset,
    folderPath: String,
    game: GameSaveIdentity?,
    entries: List<LocalSaveEntry>,
    scanner: SaveFolderScanner
): LocalSaveUnit? {
    if (entries.isEmpty()) return null // defensive; matchSaves never emits an empty match list

    // Needed for every shape now (SINGLE_FILE included, for
    // SaveTransferPlan.kt's upload/download naming) -- previously only
    // computed on the FILE_SET/FOLDER hash branch.
    val name = matchingKeyName(preset, game) ?: return null

    // Must track SaveTransferPlan.kt's planSaveUpload exactly: RomM only
    // computes content_hash via hash_zip_contents for an asset that's
    // actually a zip on the server. A FILE_SET with just one member present
    // uploads RAW (Argosy finding (b), SaveTransferPlan.kt), so the server
    // hashes it as a plain file -- the same plain md5Hex used for
    // SINGLE_FILE, not hashLocalContentAsZip. Baselines/negotiate compare
    // this value against the server's, so it has to match whatever shape
    // the upload actually took, not just the preset's declared shape.
    val fileEntries = entries.filterNot { it.isDirectory }
    val contentHash = when {
        // SaveMatcher guarantees a SINGLE_FILE preset (exactly one pattern,
        // per SavePreset's doc comment) matches at most one entry per game.
        preset.shape == SaveShape.SINGLE_FILE ->
            scanner.readFile(entries.single().relativePath)?.let(::md5Hex) ?: return null
        preset.shape == SaveShape.FILE_SET && fileEntries.size == 1 ->
            scanner.readFile(fileEntries.single().relativePath)?.let(::md5Hex) ?: return null
        else -> hashLocalContentAsZip(preset.shape, entries, scanner, preset.patterns, name)
    }

    val sortedRelativePaths = entries.map { it.relativePath }.sorted()
    val resolvedPath = resolvedPathFor(folderPath, preset.shape, sortedRelativePaths)
    val modifiedMs = entries.maxOf { latestModifiedMs(it, scanner) }

    return LocalSaveUnit(romId, preset.shape, name, sortedRelativePaths, resolvedPath, contentHash, modifiedMs)
}

// The matching-key name (§3) that produced this game's match -- needed again
// here only for FILE_SET/FOLDER's hashLocalContentAsZip (its optional
// fileSetPatterns/fileSetResolvedName args), which SaveMatcher itself doesn't
// return since it only needs the name internally to resolve patterns, not as
// an output.
private fun matchingKeyName(preset: SavePreset, game: GameSaveIdentity?): String? =
    when (preset.matchingKey) {
        MatchingKeyKind.ROM_STEM -> game?.romStem
        MatchingKeyKind.SAVE_TARGET -> game?.saveTarget
    }

// A file's own mtime, or -- for a directory (a FOLDER save's root) -- the
// latest mtime found anywhere in its subtree. A FOLDER save with no files at
// all under it (shouldn't happen; matchSaves only matches an existing
// directory) reports 0L rather than throwing.
private fun latestModifiedMs(entry: LocalSaveEntry, scanner: SaveFolderScanner): Long {
    if (!entry.isDirectory) return scanner.lastModifiedMs(entry.relativePath)
    return scanner.listChildren(entry.relativePath).maxOfOrNull { latestModifiedMs(it, scanner) } ?: 0L
}
