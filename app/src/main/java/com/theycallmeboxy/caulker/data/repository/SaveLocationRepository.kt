package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.saves.GameSaveIdentity
import com.theycallmeboxy.caulker.data.saves.LocalSaveEntry
import com.theycallmeboxy.caulker.data.saves.LocalSaveUnit
import com.theycallmeboxy.caulker.data.saves.MatchingKeyKind
import com.theycallmeboxy.caulker.data.saves.SaveLocationScanResult
import com.theycallmeboxy.caulker.data.saves.SavePlatformConfig
import com.theycallmeboxy.caulker.data.saves.SavePreset
import com.theycallmeboxy.caulker.data.saves.SavePresetRegistry
import com.theycallmeboxy.caulker.data.saves.SaveShape
import com.theycallmeboxy.caulker.data.saves.SaveSyncMode
import com.theycallmeboxy.caulker.data.saves.SaveTargetLayout
import com.theycallmeboxy.caulker.data.saves.SaveUploadPlan
import com.theycallmeboxy.caulker.data.saves.decideDownloadTarget
import com.theycallmeboxy.caulker.data.saves.DownloadTargetDecision
import com.theycallmeboxy.caulker.data.saves.deriveEffectiveName
import com.theycallmeboxy.caulker.data.saves.downloadNeedsGuardConfirmation
import com.theycallmeboxy.caulker.data.saves.eligibleUnassignedEntries
import com.theycallmeboxy.caulker.data.saves.isBackupPath
import com.theycallmeboxy.caulker.data.saves.matchSaves
import com.theycallmeboxy.caulker.data.saves.planSaveUpload
import com.theycallmeboxy.caulker.data.saves.resolveSaveLocations
import com.theycallmeboxy.caulker.data.util.RootFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// A platform's resolved v1 save-location config: the real SavePreset a
// stored SavePlatformConfig's PresetKey points at, plus the folder the user
// configured (save-sync design doc, Part 2 §12 phase 3). Every function in
// this class returns null for an unconfigured platform (§6) or a config
// whose PresetKey no longer resolves to anything in SavePresetRegistry (a
// registry entry removed/renamed after the user configured it) -- both
// degrade identically to "legacy," never a crash or a partial/best-effort
// configured path. configuredPlatform() itself is prefs/registry-only (no
// I/O that can meaningfully fail) -- it's scan()/upload()/download() that
// can throw on a real folder-access error, and callers must NOT catch those
// and fall back to legacy (independent review, phase 3A fixes item 6: "A
// config lookup failure must fail that ROM's sync, never fall back to
// legacy" / "A scan failure must be an error, never 'no local save'").
data class ConfiguredPlatform(val preset: SavePreset, val folderPath: String)

@Singleton
class SaveLocationRepository @Inject constructor(
    private val prefsStore: PrefsStore,
    private val rootHelper: RootFileHelper,
    private val saveRepository: SaveRepository,
    private val writer: ConfiguredSaveWriter,
    // Phase 3B fixes, should-fix item 3: assignment validation and the
    // matching-against-every-ROM fix both need the platform's WHOLE ROM
    // list, not just whichever subset a caller happened to pass in.
    private val romRepository: RomRepository
) {
    suspend fun configuredPlatform(platformFsSlug: String?): ConfiguredPlatform? {
        val slug = platformFsSlug?.takeIf { it.isNotBlank() } ?: return null
        val config = prefsStore.getSavePlatformConfig(slug) ?: return null
        val preset = SavePresetRegistry.presetFor(slug, config.presetKey) ?: return null
        return ConfiguredPlatform(preset, config.folderPath)
    }

    // Scans a configured platform's folder and matches it against `games`
    // (§3). Returns null when the platform isn't configured -- the caller
    // falls back to the legacy per-ROM resolution path unchanged. THROWS on
    // a genuine scan failure (an unreadable folder in
    // AndroidSaveFolderScanner.snapshot, or an unreadable member of a matched
    // unit when resolveSaveLocations hashes it) rather than degrading to an empty result;
    // callers must treat that as a real sync error, not "no local save"
    // (item 6). `games` must be the platform's WHOLE ROM list for matching
    // to be correct (should-fix item 3); `romIdsToBuild` (null = build every
    // matched ROM) then narrows which of THOSE matches actually get hashed,
    // so a caller that only wants one or a few ROMs' units doesn't pay to
    // hash every other save on the platform just to match correctly against
    // all of them. unitFor/unitsFor below are the only callers that use
    // this narrowing; every other caller (setup warnings, the
    // Unassigned-files screen) already wants every ROM's unit anyway.
    suspend fun scan(
        platformFsSlug: String?,
        games: List<GameSaveIdentity>,
        romIdsToBuild: Set<Int>? = null
    ): ConfiguredScan? {
        val configured = configuredPlatform(platformFsSlug) ?: return null
        // Non-null here: configuredPlatform() only returns non-null for a
        // non-blank slug (see its own body).
        val slug = platformFsSlug!!
        // Independent review, phase 3A round 2 item 3: every caller here is a
        // ViewModel running on viewModelScope (Main), and neither the folder
        // listing nor the per-unit hashing this triggers switched dispatcher
        // on its own -- file reads, md5, and zip packing were all running on
        // Main before this.
        return withContext(Dispatchers.IO) {
            val scanner = AndroidSaveFolderScanner.snapshot(rootHelper, configured.folderPath)
            // §3 rule 3: manual Unassigned-file assignments are applied on
            // top of rule-based matching (see resolveSaveLocations' own doc
            // comment) -- read once per scan, same as the rest of this
            // function's I/O.
            val assignments = prefsStore.getSaveAssignments(slug)
            val result = resolveSaveLocations(
                configured.preset, configured.folderPath, games, scanner, assignments, romIdsToBuild
            )
            ConfiguredScan(configured.preset, configured.folderPath, result)
        }
    }

    // Single-ROM convenience for SaveSyncViewModel's per-ROM screen: the
    // matched unit for just this ROM against its platform's configured
    // folder, or null when the platform isn't configured or this ROM has no
    // local save yet. Propagates scan()'s exception on a real read failure
    // (item 6) -- callers must not swallow it into "null" (that's
    // indistinguishable from "no local save" to everything downstream).
    // Matches against the platform's WHOLE ROM list (should-fix item 3) so
    // an assignment for this ROM can never steal a file another ROM
    // rule-matches, but only hashes this one ROM's unit.
    // TODO(phase-3B round 3+): this re-reads the platform's ENTIRE ROM list
    // from Room on every single call (observeByPlatform().first()), even
    // when called many times in a row for the same platform (e.g.
    // resolveDownloadTarget -> unitFor, right after a caller already did
    // its own scan) -- not fixed here, left as a known perf cost (round-2
    // fixes nit).
    suspend fun unitFor(rom: RomEntity): Pair<SavePreset, LocalSaveUnit>? = withContext(Dispatchers.IO) {
        val identity = gameSaveIdentityFor(rom) ?: return@withContext null
        val allIdentities = romRepository.observeByPlatform(rom.platformId).first().mapNotNull { gameSaveIdentityFor(it) }
        // allIdentities should already include `identity` (rom is on its own
        // platform's list); fall back defensively if the DB read raced the
        // ROM's own upsert and somehow missed it.
        val games = if (allIdentities.any { it.romId == rom.id }) allIdentities else allIdentities + identity
        val configured = scan(rom.platformFsSlug, games, romIdsToBuild = setOf(rom.id)) ?: return@withContext null
        val unit = configured.result.units.firstOrNull { it.romId == rom.id } ?: return@withContext null
        configured.preset to unit
    }

    // Batch form of unitFor: scans each DISTINCT configured platform among
    // `roms` exactly ONCE, covering every one of that platform's ROMs in the
    // same pass, instead of a caller looping unitFor() per ROM and re-reading
    // the same folder once per ROM on it (independent review, item 7 --
    // "scan each configured platform once per sync pass ... reuse the
    // result"). A ROM on an unconfigured platform, or with no local save,
    // simply has no entry in the result -- same "missing means legacy/no
    // save" contract as unitFor(). Still propagates a real scan failure
    // (item 6); a caller processing many ROMs per platform should wrap its
    // own per-platform iteration if it wants one bad platform not to abort
    // the others. Matches against each platform's WHOLE ROM list
    // (should-fix item 3, same reasoning as unitFor), hashing only the
    // requested `roms`.
    suspend fun unitsFor(roms: List<RomEntity>): Map<Int, LocalSaveUnit> = withContext(Dispatchers.IO) {
        val result = HashMap<Int, LocalSaveUnit>()
        for ((platformFsSlug, romsForPlatform) in roms.groupBy { it.platformFsSlug }) {
            val platformId = romsForPlatform.first().platformId
            val allRoms = romRepository.observeByPlatform(platformId).first()
            val allIdentities = allRoms.mapNotNull { gameSaveIdentityFor(it) }
            if (allIdentities.isEmpty()) continue
            val romIdsToBuild = romsForPlatform.mapTo(HashSet()) { it.id }
            val scanned = scan(platformFsSlug, allIdentities, romIdsToBuild) ?: continue
            for (unit in scanned.result.units) {
                result[unit.romId] = unit
            }
        }
        result
    }

    // The bare §3 matching-key guess for a ROM with no local unit and no
    // assignment record at all -- preset-aware (SAVE_TARGET-matched presets
    // use RomEntity.saveTarget, not the ROM filename), but otherwise no
    // awareness of assignments. Phase 3B round-2 fixes, blocker 1: this
    // function ALONE must never drive a real download -- resolveDownloadTarget
    // below is the actual entry point every download path uses; this is
    // just its last-resort fallback (and kept available on its own for
    // tests/completeness).
    fun matchingKeyNameFor(preset: SavePreset, rom: RomEntity): String? = when (preset.matchingKey) {
        MatchingKeyKind.SAVE_TARGET -> rom.saveTarget
        MatchingKeyKind.ROM_STEM ->
            rom.fileNameNoExt?.takeIf { it.isNotBlank() }
                ?: rom.fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    }

    // The message to show when resolveDownloadTarget's NoLocalUnit fallback
    // still can't produce a name -- a SAVE_TARGET preset with no save_target
    // has a specific, actionable message rather than a generic "can't
    // resolve" one (Phase 3B fixes, blocker 1). Owner decision (2026-09-29):
    // manual assignment is EXCHANGE-only, so the message differs by mode --
    // an EXCHANGE preset can still be rescued by assigning a save file;
    // a DIRECT preset can't be rescued at all (matching is automatic-only),
    // so the message instead explains WHY (no game id from the server) and,
    // for Dreamcast specifically, how to get one (a CHD/ISO/data-track BIN
    // image, not .gdi/.cdi -- see docs/supported-emulators.md).
    fun matchingKeyNameErrorFor(preset: SavePreset): String = when (preset.matchingKey) {
        MatchingKeyKind.SAVE_TARGET -> if (preset.mode == SaveSyncMode.EXCHANGE) {
            "This game has no save id from the server and hasn't been assigned a save file yet. " +
                "Assign a save file first, from this game's save screen."
        } else {
            val base = "RomM has no game ID for this ROM, so its save can't be matched automatically."
            if (preset.key.system == "dreamcast") {
                "$base RomM reads Dreamcast game IDs from CHD images, not .gdi/.cdi."
            } else {
                base
            }
        }
        MatchingKeyKind.ROM_STEM -> "Cannot resolve a matching-key name for this ROM"
    }

    sealed class DownloadTarget {
        // matchingKeyName to pass to download(); previousUnit (if any) to
        // pass alongside it for stale-member cleanup.
        data class Resolved(val matchingKeyName: String, val previousUnit: LocalSaveUnit?) : DownloadTarget()
        data class Refused(val reason: String) : DownloadTarget()
    }

    // Phase 3B round-2 fixes, blocker 1: the single source of truth for
    // "what should a download for this ROM write to" -- replaces the old
    // pattern (round 1) of calling matchingKeyNameFor() and unitFor()
    // separately, which could DISAGREE the moment an assignment went stale:
    // a newly-added ROM rule-matching the SAME file an older ROM was
    // manually assigned steals that match away (correctly -- rules always
    // win, see resolveSaveLocations), so unitFor() correctly reports "no
    // unit" for the older ROM, but the old matchingKeyNameFor kept blindly
    // trusting the raw PrefsStore assignment record and returning its
    // derived name anyway -- resolving a download target that either
    // overwrote the OTHER ROM's save, or got itself deleted as a "stale
    // member" of the wrong ROM's previousUnit.
    //
    // Decision (decideDownloadTarget, SaveLocationResolver.kt -- kept pure
    // and separately tested there):
    //  - A unit exists (rule-matched, or a still-valid assignment -- the
    //    resolver's own overlay already decided which one wins) -> use its
    //    own matchingKeyName, guaranteed consistent with the next scan.
    //  - No unit, but a raw assignment record still exists in PrefsStore ->
    //    stale (overridden by another ROM's rule match, or its target entry
    //    is gone/changed kind) -> refused with a specific, actionable
    //    reason, and the stale record is cleared here so it stops being
    //    treated as active (e.g. SaveSyncScreen's "Unassign" affordance,
    //    which reads LocalSaveUnit.isAssigned off the scan, not the raw
    //    record, so it already stops showing once there's no unit -- this
    //    clear just stops the record from lingering forever in PrefsStore).
    //  - No unit and no assignment record -> the ordinary "first-ever
    //    download" case, falls back to the bare preset-derived guess.
    //
    // Owner decision (2026-09-29): assignment records are only ever
    // consulted for an EXCHANGE preset -- a DIRECT preset never reads
    // PrefsStore's assignment map here at all, so a leftover/stale record
    // (from before this restriction, or from a preset since switched away
    // from EXCHANGE) can never produce a StaleAssignment refusal for it;
    // DIRECT always goes straight to NoLocalUnit's bare guess.
    suspend fun resolveDownloadTarget(configured: ConfiguredPlatform, rom: RomEntity): DownloadTarget {
        val unit = unitFor(rom)?.second
        val slug = rom.platformFsSlug
        val hasRawAssignment = configured.preset.mode == SaveSyncMode.EXCHANGE &&
            !slug.isNullOrBlank() && prefsStore.getSaveAssignments(slug)[rom.id] != null
        return when (val decision = decideDownloadTarget(unit, hasRawAssignment)) {
            is DownloadTargetDecision.UseUnit ->
                DownloadTarget.Resolved(decision.unit.matchingKeyName, decision.unit)
            is DownloadTargetDecision.StaleAssignment -> {
                if (!slug.isNullOrBlank()) prefsStore.clearSaveAssignment(slug, rom.id)
                DownloadTarget.Refused(
                    "The save file assigned to this game now belongs to another game -- reassign it."
                )
            }
            is DownloadTargetDecision.NoLocalUnit -> {
                val guess = matchingKeyNameFor(configured.preset, rom)
                if (guess != null) DownloadTarget.Resolved(guess, null)
                else DownloadTarget.Refused(matchingKeyNameErrorFor(configured.preset))
            }
        }
    }

    // §5's "resolved local path" for a save whose member paths are known
    // without a second scan (e.g. right after download() writes them) --
    // callers should use data.saves.resolvedPathFor(folderPath, shape,
    // writtenRelativePaths) directly (same function buildUnit uses, so a
    // baseline written here can never disagree with what the next scan
    // computes -- independent review round 2, item 1).

    // Every entry currently assigned to a ROM on this platform (§3 rule 3),
    // or empty for a platform with none / not configured. Read-only
    // convenience for the Unassigned-files UI (which needs to know what's
    // already assigned, not just what's still unassigned) and for
    // SaveSyncScreen's "was this ROM's save manually pointed at, and can it
    // be un-pointed" affordance -- both call this rather than reaching into
    // PrefsStore directly, same as every other configured-platform read
    // going through this repository.
    suspend fun getAssignments(fsSlug: String): Map<Int, String> = prefsStore.getSaveAssignments(fsSlug)

    sealed class AssignmentResult {
        data object Success : AssignmentResult()
        data class Rejected(val reason: String) : AssignmentResult()
    }

    // Manually assigns `relativePath` (an entry the last scan reported as
    // unassigned) to `romId` (§3 rule 3), after validating it (Phase 3B
    // fixes, blocker 1 + should-fix item 3) rather than trusting it blindly:
    //  - the platform must actually be configured;
    //  - owner decision (2026-09-29): the preset's mode must be EXCHANGE --
    //    Direct mode is automatic-only, so manual assignment is refused
    //    outright for a DIRECT preset (defense in depth: the UI already
    //    doesn't offer this action for one, per UnassignedFilesScreen);
    //  - the path must be derivable through deriveEffectiveName -- an
    //    assignment that can't round-trip through the preset's own patterns
    //    would silently write a download to the WRONG place (the bug this
    //    whole validation exists to prevent);
    //  - no OTHER ROM on the platform may already rule-match this same
    //    entry (§3 rules 1/2) -- an assignment must never steal a file a
    //    rule already resolves correctly.
    // `platformId` is needed (alongside `fsSlug`) to fetch the platform's
    // whole ROM list for that last check.
    suspend fun assignUnassignedFile(
        platformId: Int,
        fsSlug: String,
        romId: Int,
        relativePath: String
    ): AssignmentResult = withContext(Dispatchers.IO) {
        val configured = configuredPlatform(fsSlug)
            ?: return@withContext AssignmentResult.Rejected("This platform isn't configured for save sync.")
        if (configured.preset.mode != SaveSyncMode.EXCHANGE) {
            return@withContext AssignmentResult.Rejected(
                "Manual assignment is only available for Exchange-mode presets."
            )
        }
        if (deriveEffectiveName(configured.preset, relativePath) == null) {
            return@withContext AssignmentResult.Rejected(
                "This file's name doesn't fit what this preset expects, so it can't be assigned."
            )
        }
        val allRoms = romRepository.observeByPlatform(platformId).first()
        val allIdentities = allRoms.mapNotNull { gameSaveIdentityFor(it) }
        val entries = AndroidSaveFolderScanner.snapshot(rootHelper, configured.folderPath)
            .listAllEntries().filterNot { isBackupPath(it.relativePath) }
        val ruleOutcome = matchSaves(configured.preset, allIdentities, entries)
        val claimedByOther = ruleOutcome.matchedByGame.any { (otherRomId, claimedEntries) ->
            otherRomId != romId && claimedEntries.any { it.relativePath == relativePath }
        }
        if (claimedByOther) {
            return@withContext AssignmentResult.Rejected(
                "Another game already matches this file automatically -- it can't be reassigned."
            )
        }
        prefsStore.setSaveAssignment(fsSlug, romId, relativePath)
        AssignmentResult.Success
    }

    // Reverses assignUnassignedFile -- the entry goes back to being
    // unassigned on the next scan. Never touches the file itself.
    suspend fun clearAssignment(fsSlug: String, romId: Int) = prefsStore.clearSaveAssignment(fsSlug, romId)

    // Saves (or replaces) a platform's v1 save-location config, clearing any
    // Unassigned-file assignments recorded for it first (nit: "clear a
    // platform's assignments when its folder or preset changes") -- an
    // assignment's relativePath and derived name are only meaningful
    // relative to the preset/folder they were made under; carrying them
    // over to a different preset or folder risks silently misapplying them
    // to unrelated files. The platform-settings confirm dialog states this.
    // Unconditional (not just "when switching TO DIRECT"): this also
    // satisfies the owner's DIRECT-clears-assignments decision (2026-09-29)
    // on its own -- every config save clears the slate, so a platform
    // landing on DIRECT never keeps old assignment records around, and
    // nothing stale can survive into a LATER switch to EXCHANGE either.
    // resolveSaveLocations/resolveDownloadTarget also independently ignore
    // any assignment record for a DIRECT preset regardless (see their own
    // doc comments) -- this clear is belt-and-suspenders cleanup, not the
    // only thing keeping DIRECT assignment-free.
    suspend fun setSaveLocationConfig(fsSlug: String, config: SavePlatformConfig) {
        prefsStore.clearAllSaveAssignments(fsSlug)
        prefsStore.setSavePlatformConfig(fsSlug, config)
    }

    // Reset to Default (§6) -- also clears assignments, same reasoning as
    // setSaveLocationConfig above.
    suspend fun clearSaveLocationConfig(fsSlug: String) {
        prefsStore.clearAllSaveAssignments(fsSlug)
        prefsStore.clearSavePlatformConfig(fsSlug)
    }

    // Pure ALLOW/WARN check for the download guard (§7), without fetching
    // anything -- lets a ViewModel decide whether to show the "Download
    // anyway?" confirmation BEFORE calling download() at all, rather than
    // calling it, getting refused, and calling it again with confirmedSaveId.
    // download() itself still re-checks (defense in depth, and it's the
    // only check the non-interactive orchestrator path ever gets).
    fun downloadGuardWarningFor(configured: ConfiguredPlatform, incomingEmulatorId: String?): Boolean =
        downloadNeedsGuardConfirmation(incomingEmulatorId, configured.preset.emulatorId)

    // Uploads a configured-platform save unit (raw or zipped, per
    // SaveTransferPlan.kt's planSaveUpload -- a lone-present FILE_SET member
    // travels raw, matching Argosy's own behavior per that file's citations)
    // and returns the same UploadResult shape the legacy path uses. Upload
    // never touches local files, so callers can reuse `unit.resolvedPath`
    // for the baseline afterward without a rescan.
    suspend fun upload(
        configured: ConfiguredPlatform,
        unit: LocalSaveUnit,
        romId: Int,
        slotKey: String,
        sessionId: Int? = null,
        overwrite: Boolean = false
    ): UploadResult {
        // Reading the local member(s) + packing a zip happen here on IO; the
        // network call inside uploadSaveBytes does its own dispatching, so
        // this is only wrapped as far as the local file work.
        val (fileName, bytes) = withContext(Dispatchers.IO) {
            val scanner = AndroidSaveFolderScanner.snapshot(rootHelper, configured.folderPath)
            val entries = unit.memberPaths.map { LocalSaveEntry(it, configured.preset.shape == SaveShape.FOLDER) }
            val plan = planSaveUpload(configured.preset.shape, entries, scanner, unit.matchingKeyName, configured.preset.patterns)
                ?: error("Nothing to upload for \"${unit.matchingKeyName}\" -- local save is no longer readable")
            when (plan) {
                is SaveUploadPlan.Raw -> plan.relativePath.substringAfterLast('/') to plan.bytes
                is SaveUploadPlan.Zip -> plan.fileName to plan.bytes
            }
        }
        return saveRepository.uploadSaveBytes(
            romId, slotKey, fileName, bytes, sessionId,
            overwrite = overwrite, emulatorId = configured.preset.emulatorId
        )
    }

    // Downloads and writes a configured-platform save. The download guard
    // (§7, DOWNLOAD_GUARD_ENABLED) is checked here, before any network call,
    // since it's a pure decision over the incoming/configured emulator ids;
    // everything after fetching the bytes (backup, write, stale-member
    // cleanup, partial-write rollback) is ConfiguredSaveWriter's job --
    // see its doc comment for why that split exists (item 6/8 testability).
    //
    // confirmedSaveId (Phase 3B fixes nit: "the guard bypass must be tied to
    // the specific save id the user confirmed") skips the guard check only
    // when it equals THIS call's `saveId` -- set by an interactive caller to
    // the exact save id the user saw and confirmed "Download anyway" for
    // (SaveSyncViewModel, SaveSyncAllViewModel's Revert). If a refetch
    // between the confirmation and this call returns a DIFFERENT save
    // (someone else uploaded in the meantime), the stale confirmation no
    // longer applies and the guard fires again rather than silently
    // trusting a decision made about a different save. SaveSyncOrchestrator
    // never passes this, so the guard always has the final word on the
    // non-interactive path.
    suspend fun download(
        configured: ConfiguredPlatform,
        matchingKeyName: String,
        saveId: Int,
        serverFileName: String?,
        incomingEmulatorId: String? = null,
        sessionId: Int? = null,
        previousUnit: LocalSaveUnit? = null,
        confirmedSaveId: Int? = null
    ): ConfiguredSaveWriter.DownloadOutcome {
        if (confirmedSaveId != saveId && downloadGuardWarningFor(configured, incomingEmulatorId)) {
            // Nit: never show this raw string as a user-facing message --
            // callers must check `guardWarning` and show their own
            // confirmation copy (GuardConfirmDialog et al.), not `reason`.
            return ConfiguredSaveWriter.DownloadOutcome(
                success = false,
                reason = "download guard: incoming emulator id \"$incomingEmulatorId\" is a known-incompatible " +
                    "format for the configured \"${configured.preset.emulatorId}\"",
                guardWarning = true
            )
        }
        val bytes = saveRepository.downloadSaveBytes(saveId, sessionId)
        // The actual disk work (backup, write, stale-member cleanup, hashing
        // what was written) belongs on IO -- see scan()'s doc comment for the
        // same reasoning (item 3, independent review round 2).
        return withContext(Dispatchers.IO) {
            writer.apply(configured, matchingKeyName, bytes, serverFileName, previousUnit)
        }
    }
}

data class ConfiguredScan(val preset: SavePreset, val folderPath: String, val result: SaveLocationScanResult)

// Builds a GameSaveIdentity from a RomEntity for §3 matching -- the shared
// definition SaveLocationRepository uses internally (unitFor/unitsFor, which
// only ever build identities for ROMs already known to be save-sync
// relevant) and that UI code building a whole platform's candidate list also
// needs (the setup-warnings scan and the Unassigned-files ROM picker want
// EVERY ROM on the platform, not just enrolled ones) -- one definition
// instead of two copies of the same stem-fallback logic. Returns null for a
// ROM with no filename at all (nothing to match saves against).
fun gameSaveIdentityFor(rom: RomEntity): GameSaveIdentity? {
    val stem = rom.fileNameNoExt?.takeIf { it.isNotBlank() }
        ?: rom.fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        ?: return null
    return GameSaveIdentity(
        romId = rom.id,
        romStem = stem,
        saveTarget = rom.saveTarget,
        saveTargetLayout = SaveTargetLayout.fromWire(rom.saveTargetLayout)
    )
}
