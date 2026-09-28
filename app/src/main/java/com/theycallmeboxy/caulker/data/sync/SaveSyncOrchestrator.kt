package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.api.SaveConflictException
import com.theycallmeboxy.caulker.data.api.model.ClientSaveState
import com.theycallmeboxy.caulker.data.api.model.SyncOperation
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveRepository
import com.theycallmeboxy.caulker.data.util.msToIso
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
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
    private val saveSyncLock: SaveSyncLock
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
        val negotiatedContentHash: String? = null
    )

    private suspend fun run() {
        try {
            saveSyncLock.mutex.withLock { runLocked() }
        } catch (e: kotlinx.coroutines.CancellationException) {
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

        enrolled.forEachIndexed { index, romId ->
            val rom = romRepository.getById(romId) ?: return@forEachIndexed
            _state.value = SaveSyncOverallState.Syncing(
                done = 0, total = 0, currentRomId = romId, currentRomName = rom.name
            )
            val slot = prefsStore.saveSyncSlotPref(rom.id).first()
            val serverSaves = try { saveRepository.syncSavesForRom(rom.id) } catch (_: Exception) { emptyList() }
            // Strict (rom_id, slot) match, mirroring server negotiate pairing —
            // a null-slot save is archival and never pairs against a named slot.
            val serverSave = serverSaves
                .filter { it.slot == slot }
                .maxByOrNull { it.updatedAt ?: "" }
            val localFileName = saveRepository.resolveLocalSaveFileName(
                serverSave?.fileName, rom.fileName, rom.platformFsSlug
            ) ?: serverSave?.fileName

            val stat = localFileName?.let { saveRepository.localSaveStat(it, rom.platformFsSlug) }

            ctxByRom[romId] = RomCtx(
                rom = rom,
                slot = slot,
                localFileName = localFileName,
                platformFsSlug = rom.platformFsSlug,
                emulator = serverSave?.emulator,
                negotiatedContentHash = stat?.contentHash
            )

            if (localFileName != null && stat != null) {
                clientSaves += ClientSaveState(
                    romId = romId,
                    fileName = localFileName,
                    slot = slot,
                    emulator = serverSave?.emulator ?: "caulker",
                    contentHash = stat.contentHash,
                    updatedAt = msToIso(stat.modifiedMs),
                    fileSizeBytes = stat.sizeBytes
                )
            }
        }

        // --- Phase 2: ask the server to plan the sync. ---
        val neg = saveRepository.negotiate(deviceId, clientSaves)
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
                    "upload" -> {
                        val fileName = ctx.localFileName
                        if (fileName == null) {
                            skipped++
                        } else {
                            val result = saveRepository.uploadSaveFromDisk(
                                op.romId, ctx.slot, fileName, ctx.platformFsSlug, sessionId = neg.sessionId
                            )
                            uploaded++
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
                    "download" -> {
                        val saveId = op.saveId
                        if (saveId == null) {
                            skipped++
                        } else {
                            val fileName = ctx.localFileName
                                ?: saveRepository.resolveLocalSaveFileName(
                                    op.fileName, ctx.rom.fileName, ctx.platformFsSlug
                                )
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
                                skipped++
                            } else {
                                val remoteMs = parseIsoToMs(op.serverUpdatedAt)
                                saveRepository.downloadSave(
                                    saveId, fileName, ctx.platformFsSlug, remoteMs, sessionId = neg.sessionId
                                )
                                downloaded++
                                // Local file now matches what the server had — record
                                // that as the new common-ancestor baseline.
                                val baselineHash = op.serverContentHash
                                    ?: saveRepository.localSaveStat(fileName, ctx.platformFsSlug)?.contentHash
                                if (baselineHash != null) {
                                    prefsStore.setSyncBaseline(
                                        op.romId, ctx.slot,
                                        SyncBaseline(baselineHash, saveId, op.serverUpdatedAt)
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
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
                operationsFailed = errors
            )
        } catch (_: Exception) {
            // best-effort; the sync itself already happened
        }

        _state.value = SaveSyncOverallState.Done(uploaded, downloaded, skipped + conflicts, errors)
    }
}
