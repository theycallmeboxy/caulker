package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.api.SaveConflictException
import com.theycallmeboxy.caulker.data.api.model.ClientSaveState
import com.theycallmeboxy.caulker.data.api.model.SyncOperation
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.ConfiguredPlatform
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveLocationRepository
import com.theycallmeboxy.caulker.data.repository.SaveRepository
import com.theycallmeboxy.caulker.data.saves.LocalSaveUnit
import com.theycallmeboxy.caulker.data.saves.resolvedPathFor
import com.theycallmeboxy.caulker.data.util.msToIso
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SaveSyncOverallState {
    data object Idle : SaveSyncOverallState
    data class Syncing(
        val done: Int,
        val total: Int,
        val currentRomId: Int? = null,
        val currentRomName: String? = null
    ) : SaveSyncOverallState
    data class Done(val uploaded: Int, val downloaded: Int, val skipped: Int, val errors: Int) : SaveSyncOverallState
    data class Error(val message: String) : SaveSyncOverallState
}

// App-scoped driver for "sync all save-enrolled ROMs" — used by the in-app
// SaveSyncAll button, the foreground service, and the QS tile. Runs in an
// app-lifetime scope so it survives ViewModel destruction; observers just
// collect `state`.
//
// RomM 4.9: the per-save conflict decision is made server-side. We send the
// device's local save state to /api/sync/negotiate, then execute the returned
// upload/download operations and close the session. Negotiation is global per
// device, so we filter the returned ops to the user's enrolled ROMs + their
// preferred slot to preserve Caulker's per-ROM enrollment model.
@Singleton
class SaveSyncOrchestrator @Inject constructor(
    private val prefsStore: PrefsStore,
    private val saveRepository: SaveRepository,
    private val romRepository: RomRepository,
    private val saveSyncLock: SaveSyncLock,
    // v1 save-location wiring (save-sync design doc, Part 2 §12 phase 3): a
    // null ConfiguredPlatform for a ROM's platform means it has no v1
    // config, so that ROM's gather/execute steps fall through to the exact
    // legacy behavior below (§6 hard constraint). A *failed* configured
    // lookup/scan is a different thing entirely from "no config" -- see
    // gatherOne's doc comment (independent review, item 6).
    private val saveLocationRepository: SaveLocationRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SaveSyncOverallState>(SaveSyncOverallState.Idle)
    val state: StateFlow<SaveSyncOverallState> = _state.asStateFlow()

    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    // Fire-and-forget. No-op if a sync is already running. Resets to Idle
    // first so observers see a clean transition rather than Done→Syncing.
    fun syncAllEnrolled() {
        if (job?.isActive == true) return
        _state.value = SaveSyncOverallState.Idle
        job = scope.launch { run() }
    }

    fun cancel() {
        job?.cancel()
    }

    // Per-ROM context captured during the build phase and reused while executing
    // the negotiated operations.
    private data class RomCtx(
        val rom: RomEntity,
        val slot: String,
        val localFileName: String?,
        val platformFsSlug: String?,
        val emulator: String?,
        // Local file content hash as captured pre-negotiate (phase 1), used as a
        // defense-in-depth check before overwriting with a "download" op: if the
        // file changed since, something else (a manual upload) wrote it after we
        // reported it to the server, so we must not clobber it.
        val negotiatedContentHash: String? = null,
        // v1 save-location wiring (§12 phase 3): non-null exactly when this
        // ROM's platform has a resolved v1 config -- the execute phase uses
        // this instead of the legacy per-ROM path whenever it's set.
        val configured: ConfiguredPlatform? = null,
        val configuredUnit: LocalSaveUnit? = null
    )

    private suspend fun run() {
        try {
            saveSyncLock.mutex.withLock { runLocked() }
        } catch (e: CancellationException) {
            _state.value = SaveSyncOverallState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = SaveSyncOverallState.Error(e.message ?: "Save sync failed")
        }
    }

    // Runs while saveSyncLock is held for the whole negotiate→execute→close
    // sequence, so no per-ROM manual action can race it.
    private suspend fun runLocked() {
        val enrolled = prefsStore.saveSyncEnrolled.first().toList()
        if (enrolled.isEmpty()) {
            _state.value = SaveSyncOverallState.Done(0, 0, 0, 0)
            return
        }

        val deviceId = saveRepository.getOrRegisterDeviceId()

        // --- Phase 1: gather local save state for each enrolled ROM. ---
        // The real total isn't known until negotiate returns the actionable set
        // (phase 3), so this phase reports total=0 as a "preparing" sentinel
        // rather than enrolled.size, which would otherwise visibly jump down
        // once the real (typically smaller) actionable count is known.
        _state.value = SaveSyncOverallState.Syncing(done = 0, total = 0)
        val ctxByRom = HashMap<Int, RomCtx>()
        val clientSaves = ArrayList<ClientSaveState>()
        // A gather failure for one ROM (e.g. its configured folder can't be
        // read) must count as a real error, never silently fall back to
        // legacy or "no local save" (independent review, item 6) -- but one
        // ROM's failure shouldn't abort the whole batch either, so it's
        // tallied here and folded into the final error count instead of
        // aborting runLocked().
        var gatherErrors = 0

        // Pre-scan each distinct configured platform ONCE for every enrolled
        // ROM on it (item 7), instead of gatherOne calling
        // SaveLocationRepository.unitFor() per ROM and re-reading the same
        // folder once per ROM on that platform. A platform whose scan fails
        // here just isn't in preScannedUnits -- gatherOne re-derives it (and
        // that per-ROM failure is what gets tallied into gatherErrors), so a
        // batch-scan failure for one platform doesn't lose the whole run.
        val enrolledRoms = enrolled.mapNotNull { romRepository.getById(it) }
        val preScannedUnits = try {
            saveLocationRepository.unitsFor(enrolledRoms)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

        enrolled.forEachIndexed { index, romId ->
            val rom = romRepository.getById(romId) ?: return@forEachIndexed
            _state.value = SaveSyncOverallState.Syncing(
                done = 0, total = 0, currentRomId = romId, currentRomName = rom.name
            )
            try {
                gatherOne(rom, romId, ctxByRom, clientSaves, preScannedUnits[romId])
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                gatherErrors++
            }
        }

        // --- Phase 2: ask the server to plan the sync. ---
        // Scope negotiate to the enrolled ROMs (RomM 5.3.1+) so the server doesn't
        // plan downloads for the rest of the user's save library; negotiate()
        // falls back to an unscoped request on older servers or an over-cap list.
        val neg = saveRepository.negotiate(deviceId, clientSaves, romIds = enrolled)
        val enrolledSet = enrolled.toSet()

        fun matchesEnrolledSlot(op: SyncOperation): Boolean {
            if (op.romId !in enrolledSet) return false
            val ctxSlot = ctxByRom[op.romId]?.slot ?: "default"
            return op.slot == ctxSlot
        }

        val actionable = neg.operations.filter {
            matchesEnrolledSlot(it) && (it.action == "upload" || it.action == "download")
        }
        var conflicts = neg.operations.count { matchesEnrolledSlot(it) && it.action == "conflict" }

        // --- Phase 3: execute the planned operations. ---
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var errors = 0

        actionable.forEachIndexed { index, op ->
            val ctx = ctxByRom[op.romId] ?: return@forEachIndexed
            _state.value = SaveSyncOverallState.Syncing(
                done = index, total = actionable.size, currentRomId = op.romId, currentRomName = ctx.rom.name
            )
            try {
                when (op.action) {
                    "upload" -> executeUpload(op, ctx, neg.sessionId, onUploaded = { uploaded++ }, onSkipped = { skipped++ })
                    "download" -> executeDownload(op, ctx, neg.sessionId, onDownloaded = { downloaded++ }, onSkipped = { skipped++ })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SaveConflictException) {
                // The server rejected this upload because the slot has moved since
                // negotiate ran (another device won the race). Report it as a
                // conflict for this ROM rather than a generic failure — the next
                // refresh()'s determineSyncAction() will surface it for resolution
                // rather than silently dropping the change.
                conflicts++
            } catch (_: Exception) {
                errors++
            }
        }

        // --- Phase 4: close the session. ---
        try {
            saveRepository.completeSession(
                neg.sessionId,
                operationsCompleted = uploaded + downloaded,
                operationsFailed = errors + gatherErrors
            )
        } catch (_: Exception) {
            // best-effort; the sync itself already happened
        }

        _state.value = SaveSyncOverallState.Done(uploaded, downloaded, skipped + conflicts, errors + gatherErrors)
    }

    // One ROM's phase-1 gather step. A configured platform's scan failure
    // (AndroidSaveFolderScanner throwing) propagates out of this function --
    // the caller (runLocked's forEachIndexed) counts it as a gather error,
    // never catches it here to silently fall back to the legacy branch below
    // (independent review, item 6: "a config lookup failure must fail that
    // ROM's sync, never fall back to legacy").
    private suspend fun gatherOne(
        rom: RomEntity,
        romId: Int,
        ctxByRom: HashMap<Int, RomCtx>,
        clientSaves: ArrayList<ClientSaveState>,
        preScannedUnit: LocalSaveUnit?
    ) {
        val slot = prefsStore.saveSyncSlotPref(rom.id).first()
        val serverSaves = try { saveRepository.syncSavesForRom(rom.id) } catch (_: Exception) { emptyList() }
        // Strict (rom_id, slot) match, mirroring server negotiate pairing —
        // a null-slot save is archival and never pairs against a named slot.
        val serverSave = serverSaves
            .filter { it.slot == slot }
            .maxByOrNull { it.updatedAt ?: "" }

        // v1 save-location wiring (§12 phase 3): a configured platform
        // resolves its local save via the folder/preset scan; an
        // unconfigured one (configured == null) keeps the exact legacy
        // resolution below (§6 hard constraint). No try/catch around either
        // call -- a real failure here propagates to gatherOne's own caller.
        // preScannedUnit reuses runLocked's one-scan-per-platform batch
        // (item 7) when it succeeded; a null here (batch scan failed, or
        // this ROM genuinely has no local save) falls back to scanning just
        // this one ROM so a batch-scan hiccup doesn't silently skip it.
        val configured = saveLocationRepository.configuredPlatform(rom.platformFsSlug)
        val configuredUnit = if (configured != null) {
            preScannedUnit ?: saveLocationRepository.unitFor(rom)?.second
        } else null

        val localFileName: String?
        val contentHash: String?
        val sizeBytes: Long
        val modifiedMs: Long
        val emulatorId: String?

        if (configured != null) {
            localFileName = configuredUnit?.matchingKeyName
            contentHash = configuredUnit?.contentHash
            sizeBytes = 0L // unknown without re-reading bytes; server only uses this as a hint
            modifiedMs = configuredUnit?.modifiedMs ?: 0L
            emulatorId = configured.preset.emulatorId
        } else {
            localFileName = saveRepository.resolveLocalSaveFileName(
                serverSave?.fileName, rom.fileName, rom.platformFsSlug
            ) ?: serverSave?.fileName
            val stat = localFileName?.let { saveRepository.localSaveStat(it, rom.platformFsSlug) }
            contentHash = stat?.contentHash
            sizeBytes = stat?.sizeBytes ?: 0L
            modifiedMs = stat?.modifiedMs ?: 0L
            emulatorId = serverSave?.emulator
        }

        ctxByRom[romId] = RomCtx(
            rom = rom,
            slot = slot,
            localFileName = localFileName,
            platformFsSlug = rom.platformFsSlug,
            emulator = emulatorId,
            negotiatedContentHash = contentHash,
            configured = configured,
            configuredUnit = configuredUnit
        )

        if (localFileName != null && contentHash != null) {
            clientSaves += ClientSaveState(
                romId = romId,
                fileName = localFileName,
                slot = slot,
                emulator = emulatorId ?: "caulker",
                contentHash = contentHash,
                updatedAt = msToIso(modifiedMs),
                fileSizeBytes = sizeBytes
            )
        }
    }

    private suspend fun executeUpload(
        op: SyncOperation,
        ctx: RomCtx,
        sessionId: Int,
        onUploaded: () -> Unit,
        onSkipped: () -> Unit
    ) {
        val configured = ctx.configured
        val unit = ctx.configuredUnit
        if (configured != null && unit != null) {
            val result = saveLocationRepository.upload(configured, unit, op.romId, ctx.slot, sessionId = sessionId)
            onUploaded()
            // Upload never touches local files, so unit.resolvedPath (already
            // known) is still correct for the baseline -- no rescan needed.
            val baselineHash = result.contentHash ?: ctx.negotiatedContentHash
            if (baselineHash != null) {
                prefsStore.setSyncBaseline(
                    op.romId, ctx.slot,
                    SyncBaseline(baselineHash, result.saveId, msToIso(result.serverMs), unit.resolvedPath)
                )
            }
        } else if (configured != null) {
            onSkipped() // configured platform but no matched local unit -- nothing to upload
        } else {
            val fileName = ctx.localFileName
            if (fileName == null) {
                onSkipped()
            } else {
                val result = saveRepository.uploadSaveFromDisk(
                    op.romId, ctx.slot, fileName, ctx.platformFsSlug, sessionId = sessionId
                )
                onUploaded()
                // Server now holds exactly what we just uploaded — record it
                // as the new common-ancestor baseline for future decisions.
                val baselineHash = result.contentHash ?: ctx.negotiatedContentHash
                if (baselineHash != null) {
                    prefsStore.setSyncBaseline(
                        op.romId, ctx.slot,
                        SyncBaseline(baselineHash, result.saveId, msToIso(result.serverMs))
                    )
                }
            }
        }
    }

    private suspend fun executeDownload(
        op: SyncOperation,
        ctx: RomCtx,
        sessionId: Int,
        onDownloaded: () -> Unit,
        onSkipped: () -> Unit
    ) {
        val saveId = op.saveId
        val configured = ctx.configured
        if (saveId == null) {
            onSkipped()
        } else if (configured != null) {
            // Defense in depth, same spirit as the legacy branch below:
            // re-check the matched local unit's hash right before
            // overwriting it, skipping rather than clobbering if it changed
            // since negotiate. A real scan error here propagates (no
            // swallow-to-null) so it's counted as an error by the caller,
            // not silently treated as "unchanged" (item 6).
            // TODO(phase-3B): this re-scans the platform per download
            // operation even though gatherOne's preScannedUnits already
            // scanned it once this sync pass -- independent review round 2,
            // item 6, left as a TODO rather than fixed here (the freshness
            // check's whole point is to see state AS OF right now, so simply
            // reusing the stale gather-phase unit isn't a free win the way
            // the read-side caching elsewhere in this pass is).
            val currentUnit = saveLocationRepository.unitFor(ctx.rom)?.second
            val changedSinceNegotiate = ctx.negotiatedContentHash != null &&
                currentUnit != null &&
                currentUnit.contentHash != ctx.negotiatedContentHash
            if (changedSinceNegotiate) {
                onSkipped()
                return
            }
            // preset-aware: SAVE_TARGET-matched presets (Dreamcast, PSP) key
            // on RomEntity.saveTarget, not the ROM filename.
            val matchingKeyName = saveLocationRepository.matchingKeyNameFor(configured.preset, ctx.rom)
            if (matchingKeyName == null) {
                onSkipped()
                return
            }
            val outcome = saveLocationRepository.download(
                configured, matchingKeyName, saveId, op.fileName,
                incomingEmulatorId = op.emulator, sessionId = sessionId, previousUnit = currentUnit
            )
            if (!outcome.success) {
                // A refused/failed download writes nothing and must not
                // record a baseline (blocker 1) -- and per the independent
                // review, must be counted as an error (not silently
                // skipped), so it's actually visible instead of blending into
                // ordinary "nothing to do" skips. Thrown so the phase-3
                // catch-all (errors++) handles it the same way as any other
                // failed operation.
                error(outcome.reason ?: "configured download failed")
            }
            onDownloaded()
            // §5 path (item 1) and hash fallback (item 2) both come from
            // what was ACTUALLY just written -- resolvedPathFor is the same
            // function buildUnit uses so a FOLDER save's path always agrees
            // with what the next scan computes, and writtenContentHash is
            // the new content's own hash, never currentUnit's stale
            // pre-download one.
            val resolvedPath = resolvedPathFor(configured.folderPath, configured.preset.shape, outcome.writtenRelativePaths)
            val baselineHash = op.serverContentHash ?: outcome.writtenContentHash
            if (baselineHash != null) {
                prefsStore.setSyncBaseline(
                    op.romId, ctx.slot, SyncBaseline(baselineHash, saveId, op.serverUpdatedAt, resolvedPath)
                )
            }
        } else {
            val fileName = ctx.localFileName
                ?: saveRepository.resolveLocalSaveFileName(op.fileName, ctx.rom.fileName, ctx.platformFsSlug)
                ?: op.fileName
            // Defense in depth: even though the lock should prevent any
            // other path from touching this file during our run, guard
            // against clock/mtime surprises by re-checking the file's
            // state right before overwriting it. If it no longer matches
            // what we reported to negotiate, skip rather than clobber.
            val currentStat = saveRepository.localSaveStat(fileName, ctx.platformFsSlug)
            val changedSinceNegotiate = ctx.negotiatedContentHash != null &&
                currentStat != null &&
                currentStat.contentHash != ctx.negotiatedContentHash
            if (changedSinceNegotiate) {
                onSkipped()
                return
            }
            val remoteMs = parseIsoToMs(op.serverUpdatedAt)
            saveRepository.downloadSave(saveId, fileName, ctx.platformFsSlug, remoteMs, sessionId = sessionId)
            onDownloaded()
            // Local file now matches what the server had — record
            // that as the new common-ancestor baseline.
            val baselineHash = op.serverContentHash
                ?: saveRepository.localSaveStat(fileName, ctx.platformFsSlug)?.contentHash
            if (baselineHash != null) {
                prefsStore.setSyncBaseline(op.romId, ctx.slot, SyncBaseline(baselineHash, saveId, op.serverUpdatedAt))
            }
        }
    }
}
