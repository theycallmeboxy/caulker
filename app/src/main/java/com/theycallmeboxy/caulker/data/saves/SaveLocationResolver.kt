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
    val modifiedMs: Long,
    // True when this unit came from a manual Unassigned-files assignment
    // (§3 rule 3) rather than a rule-based match (§3 rules 1/2). Ground
    // truth for "is this ROM's save actively backed by an assignment right
    // now" -- Phase 3B round-2 fixes, should-fix (blocker 1 follow-on): a
    // raw PrefsStore assignment record can go stale (its target entry gets
    // rule-matched by a DIFFERENT, newly-added ROM, or disappears) without
    // being deleted, so UI/logic that needs to know "is this assignment
    // still doing anything" must check THIS field on the current unit, not
    // just whether a record exists in PrefsStore.
    val isAssigned: Boolean = false
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
    // the Unassigned-files UI. Only ever contains entries ELIGIBLE for this
    // preset's shape (see eligibleUnassignedEntries) -- Phase 3B fixes,
    // should-fix item 5/6: a FILE_SET's `nvram/` housing directory, or a
    // stray file under a FOLDER preset, can never actually be matched or
    // assigned, so it no longer inflates this list/count forever.
    val unassigned: List<LocalSaveEntry>
)

// Phase 3B fixes, should-fix item 5/6: narrows a raw "claimed by nothing"
// list down to entries that could ever actually become a unit for this
// preset's shape -- SINGLE_FILE/FILE_SET only ever match/assign a FILE
// (never the directory merely housing a FILE_SET pattern, e.g. arcade's
// `nvram/`); FOLDER only ever matches/assigns a TOP-LEVEL directory (never a
// nested subdirectory or a stray file inside/alongside one). Anything else
// left in `unassigned` is inert clutter, not a real candidate -- showing it
// in the Unassigned-files UI or counting it in setup warnings would be
// permanently misleading (independent review item 6: "no more permanent '1
// file isn't matched' for an nvram/ dir").
fun eligibleUnassignedEntries(preset: SavePreset, unassigned: List<LocalSaveEntry>): List<LocalSaveEntry> =
    if (preset.shape == SaveShape.FOLDER) {
        unassigned.filter { it.isDirectory && !it.relativePath.contains('/') }
    } else {
        unassigned.filterNot { it.isDirectory }
    }

// Phase 3B fixes, blocker 1: recovers the §3 matching-key name that, fed
// back through this preset's own patterns, reproduces `assignedRelativePath`
// EXACTLY -- the inverse of resolveSafeRelativePath (SavePathSafety.kt).
// This is what makes an assigned unit's uploads/downloads land back at the
// exact file/folder the user pointed at, by reusing the SAME
// pattern-substitution code every rule-matched unit's upload/download
// already goes through (SaveTransferPlan.kt), instead of inventing a
// parallel "assigned path" concept that every transfer function would need
// to know about separately. Before this fix, an assigned unit's
// matchingKeyName was just the ROM's own stem -- resolving `{name}.srm` with
// that stem almost never reproduced the actual assigned file, so a download
// silently wrote to a DIFFERENT path than the one the user assigned, and
// then removeStaleMembers (ConfiguredSaveWriter.kt) backed up and deleted
// the real assigned file as "stale."
//
// FOLDER has no patterns to invert: its name is simply the assigned
// directory's own basename (a FOLDER assignment always claims a top-level
// directory -- see eligibleUnassignedEntries), which trivially round-trips
// since FOLDER's Unpack placement writes directly to `<name>/`, no pattern
// substitution involved.
//
// Returns null if no single pattern's prefix/suffix shape matches the
// assigned path at all -- callers (SaveLocationRepository.assignUnassignedFile)
// reject the assignment outright in that case rather than ever storing one
// that can't be inverted; resolveSaveLocations treats a null here (a stale
// assignment surviving from before that validation existed) the same as any
// other stale assignment -- skipped, not guessed at.
//
// Phase 3B round-2 fixes, nit: more than one pattern can successfully
// round-trip the same path (e.g. melonDS DS-style patterns `{name}.sav` and
// `{name}.public.sav` both round-trip "Foo.public.sav" -- as "Foo.public"
// and "Foo" respectively), so this can't just return the FIRST match; that
// would make the derived name depend on the preset's pattern ORDER, an
// implementation detail. Instead every round-tripping pattern is considered
// and the one with the longest literal prefix+suffix (the most SPECIFIC
// match -- ".public.sav" beats ".sav") wins, since a longer literal match is
// never a coincidental substring hit the way a short generic suffix can be.
fun deriveEffectiveName(preset: SavePreset, assignedRelativePath: String): String? {
    if (preset.shape == SaveShape.FOLDER) {
        val baseName = assignedRelativePath.substringAfterLast('/')
        return baseName.takeIf { isSafeNameSegment(it) }
    }
    var bestCandidate: String? = null
    var bestSpecificity = -1
    for (pattern in preset.patterns) {
        val tokenIndex = pattern.indexOf(NAME_TOKEN)
        if (tokenIndex < 0) continue
        val prefix = pattern.substring(0, tokenIndex)
        val suffix = pattern.substring(tokenIndex + NAME_TOKEN.length)
        if (!assignedRelativePath.startsWith(prefix) || !assignedRelativePath.endsWith(suffix)) continue
        val candidate = assignedRelativePath.substring(prefix.length, assignedRelativePath.length - suffix.length)
        if (candidate.isBlank()) continue
        // Round-trip check, not just a substring slice: the candidate must
        // reconstruct assignedRelativePath EXACTLY through this same
        // pattern (and pass the same safety check any other substitution
        // does) before it's even considered.
        if (resolveSafeRelativePath(pattern, candidate) != assignedRelativePath) continue
        val specificity = prefix.length + suffix.length
        if (specificity > bestSpecificity) {
            bestCandidate = candidate
            bestSpecificity = specificity
        }
    }
    return bestCandidate
}

// Scans `scanner`'s folder, matches it against `games` per `preset` (§3), and
// builds one LocalSaveUnit per matched game. A game with matched entries that
// can't be hashed (e.g. a file that disappeared between the scan and the
// read) is silently dropped from `units` rather than throwing -- the caller
// sees one fewer unit and, on its next scan, either sees it again or sees the
// removal reflected, rather than the whole platform's scan failing over one
// game.
//
// `assignments` (romId -> the relative path a user manually assigned to that
// ROM via the Unassigned-files UI, §3 rule 3, persisted per platform in
// PrefsStore) is applied AFTER rule-based matching, and only ever claims an
// entry rule-based matching left in `unassigned` -- an assignment never
// overrides a rule 1/2 match, so it can't create the ambiguity those rules'
// own tie-breaking exists to avoid. A stale assignment (the entry no longer
// exists, exists as the wrong kind -- e.g. a FOLDER preset's assignment
// pointing at a file -- or can no longer be inverted through this preset's
// patterns, see deriveEffectiveName) is silently skipped rather than guessed
// at; the next scan simply shows that ROM with no unit from it, same as if
// the assignment had never been made. Respects the preset's shape exactly
// like a rule-based match: a FOLDER preset's assignment claims the whole
// directory (itself plus everything nested under it, same "nested entries
// are covered" treatment matchSaves gives a rule-matched FOLDER); a
// FILE_SET's assignment also picks up any OTHER pattern's sibling member
// that happens to exist alongside it (e.g. assigning `Stray.srm` under a
// `{name}.srm`+`{name}.rtc` preset also picks up `Stray.rtc` if present) --
// SINGLE_FILE has exactly one pattern, so this is a no-op for it.
//
// Owner decision (2026-09-29): manual assignment is restricted to EXCHANGE
// presets -- Direct mode is automatic-only. `assignments` is therefore only
// EVER consulted when `preset.mode == SaveSyncMode.EXCHANGE`; for a DIRECT
// preset it's ignored entirely, whatever it contains (including leftovers
// from a build that predates this restriction, or from a since-changed
// preset/folder) -- no unit is ever built from it, deriveEffectiveName is
// never called, and there's no stale-assignment refusal, since there's
// nothing to go stale in the first place. Matching for a DIRECT preset is
// therefore purely §3 rules 1/2 (ROM stem or save_target).
//
// `games` must be every ROM on the platform, not just the ones the caller
// ultimately wants units for -- rule-based matching (§3 rules 1/2) and the
// assignment overlay both need the FULL set to correctly decide what's
// already claimed (Phase 3B fixes, should-fix item 3: an assignment must
// never be able to steal a file another, unrequested ROM would have
// rule-matched). `romIdsToBuild` (null = every matched ROM, the original
// behavior) then narrows which matched ROMs actually get their unit BUILT --
// buildUnit is the only I/O-doing step (hashing), so a caller that only
// wants one ROM's unit (e.g. SaveLocationRepository.unitFor) still matches
// against everyone but only pays the hashing cost for the one it asked for.
fun resolveSaveLocations(
    preset: SavePreset,
    folderPath: String,
    games: List<GameSaveIdentity>,
    scanner: SaveFolderScanner,
    assignments: Map<Int, String> = emptyMap(),
    romIdsToBuild: Set<Int>? = null
): SaveLocationScanResult {
    val allEntries = scanner.listAllEntries().filterNot { isBackupPath(it.relativePath) }
    val outcome = matchSaves(preset, games, allEntries)
    val gameById = games.associateBy { it.romId }

    val matchedByGame = outcome.matchedByGame.toMutableMap()
    // Effective (derived, round-trippable) name for a romId claimed by an
    // assignment rather than a rule match -- see deriveEffectiveName's doc
    // comment for why this can't just be the ROM's own stem.
    val nameOverrides = HashMap<Int, String>()
    var unassigned = eligibleUnassignedEntries(preset, outcome.unassigned)
    if (assignments.isNotEmpty() && preset.mode == SaveSyncMode.EXCHANGE) {
        val wantDirectory = preset.shape == SaveShape.FOLDER
        for ((romId, relativePath) in assignments) {
            if (romId in matchedByGame) continue // rule-based match (§3 rules 1/2) always wins
            val entry = unassigned.firstOrNull { it.relativePath == relativePath && it.isDirectory == wantDirectory }
                ?: continue // stale assignment -- entry gone, or no longer the shape's expected/eligible kind
            val name = deriveEffectiveName(preset, relativePath)
                ?: continue // stale assignment -- can no longer be inverted through this preset's patterns

            val claimedEntries: List<LocalSaveEntry>
            val toRemoveFromUnassigned: List<LocalSaveEntry>
            if (wantDirectory) {
                // A FOLDER unit's `entries` is its directory entry ALONE --
                // buildUnit/hashLocalContentAsZip walk its children
                // themselves, matching a rule-matched FOLDER's own shape.
                // Everything nested under it is still removed from
                // `unassigned` (those files were never their own separate
                // claim -- matchSaves' "claimedDirs" nested-entry handling).
                claimedEntries = listOf(entry)
                toRemoveFromUnassigned = unassigned.filter {
                    it.relativePath == entry.relativePath || it.relativePath.startsWith("${entry.relativePath}/")
                }
            } else {
                // FILE_SET siblings: any OTHER pattern resolved with the
                // derived name that also exists among the still-unassigned
                // entries (SINGLE_FILE's one pattern makes this just `entry`
                // itself).
                val siblingPaths = preset.patterns.mapNotNull { resolveSafeRelativePath(it, name) }.toSet()
                claimedEntries = (listOf(entry) + unassigned.filter { it.relativePath in siblingPaths && it.relativePath != entry.relativePath })
                    .distinct()
                toRemoveFromUnassigned = claimedEntries
            }
            matchedByGame[romId] = claimedEntries
            nameOverrides[romId] = name
            unassigned = unassigned - toRemoveFromUnassigned.toSet()
        }
    }

    val units = matchedByGame.mapNotNull { (romId, entries) ->
        if (romIdsToBuild != null && romId !in romIdsToBuild) return@mapNotNull null
        buildUnit(romId, preset, folderPath, gameById[romId], entries, scanner, nameOverrides[romId], isAssigned = romId in nameOverrides)
    }
    return SaveLocationScanResult(units, unassigned)
}

private fun buildUnit(
    romId: Int,
    preset: SavePreset,
    folderPath: String,
    game: GameSaveIdentity?,
    entries: List<LocalSaveEntry>,
    scanner: SaveFolderScanner,
    nameOverride: String? = null,
    isAssigned: Boolean = false
): LocalSaveUnit? {
    if (entries.isEmpty()) return null // defensive; matchSaves never emits an empty match list

    // Needed for every shape now (SINGLE_FILE included, for
    // SaveTransferPlan.kt's upload/download naming) -- previously only
    // computed on the FILE_SET/FOLDER hash branch. nameOverride (a manual
    // assignment, see resolveSaveLocations) takes precedence over deriving
    // it from the preset's own matching-key convention.
    val name = nameOverride ?: matchingKeyName(preset, game) ?: return null

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

    return LocalSaveUnit(romId, preset.shape, name, sortedRelativePaths, resolvedPath, contentHash, modifiedMs, isAssigned)
}

// Phase 3B round-2 fixes, blocker 1: the pure decision tree behind
// SaveLocationRepository.resolveDownloadTarget -- kept separate and
// JVM-testable rather than embedded in that suspend function, since the
// underlying bug (a stale raw PrefsStore assignment record silently
// driving a download after its target entry got rule-matched by a
// DIFFERENT, newly-added ROM) is really a matching-logic problem: `unit` is
// whatever the CURRENT scan resolved for this ROM (null if nothing
// matches/no-longer-matches it right now), and `hasRawAssignmentRecord` is
// whether PrefsStore still has an assignment entry for it (independent of
// whether the scan actually honored it).
sealed class DownloadTargetDecision {
    // Use this unit's own matchingKeyName -- guaranteed consistent with
    // what the next scan will also see, whether the unit came from a rule
    // match or a still-valid assignment.
    data class UseUnit(val unit: LocalSaveUnit) : DownloadTargetDecision()
    // No unit, but PrefsStore still has a raw assignment record for this
    // ROM -- it's gone stale (overridden by another ROM's rule match, or
    // its target entry disappeared/changed kind). The caller must refuse
    // the download AND clear the stale record so it stops being treated as
    // active, rather than ever falling back to a bare guess.
    data object StaleAssignment : DownloadTargetDecision()
    // No unit and no assignment record -- the ordinary "nothing local yet"
    // case; the caller falls back to the bare preset-derived guess.
    data object NoLocalUnit : DownloadTargetDecision()
}

fun decideDownloadTarget(unit: LocalSaveUnit?, hasRawAssignmentRecord: Boolean): DownloadTargetDecision = when {
    unit != null -> DownloadTargetDecision.UseUnit(unit)
    hasRawAssignmentRecord -> DownloadTargetDecision.StaleAssignment
    else -> DownloadTargetDecision.NoLocalUnit
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
