package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.api.model.DeviceSaveSync
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.util.parseIsoToMs

// Per-slot sync decision used across the in-app save sync UI, the
// SaveSyncOrchestrator (QS tile path), and the foreground service. Lives in the
// data layer so the orchestrator doesn't have to import upward into UI code.
enum class SyncAction { NONE, UPLOAD, DOWNLOAD, UP_TO_DATE, CONFLICT }

// A per-(romId, slotKey) fingerprint of the version Caulker last successfully
// synced (uploaded, downloaded, or resolved a conflict for). Persisted in
// PrefsStore and used as the common ancestor for the 3-way merge below.
// saveId/updatedAt are diagnostic only — the decision only ever looks at
// contentHash. resolvedPath is the local file path this baseline was recorded
// against (save-sync design doc, Part 2 §5) — see effectiveBaseline() below;
// null for a baseline persisted before this field existed (pre-v1) or by a
// caller that hasn't started recording it yet.
data class SyncBaseline(
    val contentHash: String,
    val saveId: Int? = null,
    val updatedAt: String? = null,
    val resolvedPath: String? = null
)

// Applies the §5 baseline-path safety rule ahead of determineSyncAction(): a
// baseline recorded against a different resolved local path than the one in
// use now is treated as if no baseline existed at all, forcing
// determineSyncAction() into its no-history branch (CONFLICT on a hash
// mismatch) instead of silently comparing against an unrelated file that
// happens to already sit at the newly-resolved path — e.g. after a
// preset/folder change or a first-time Unassigned-file assignment. A baseline
// with no recorded path at all (persisted before this field existed) is
// treated as still matching — the one exception, scoped to the pre-v1 -> v1
// migration, so upgrading Caulker doesn't force a re-prompt on every
// already-synced save.
fun effectiveBaseline(baseline: SyncBaseline?, currentResolvedPath: String?): SyncBaseline? {
    baseline ?: return null
    val recordedPath = baseline.resolvedPath ?: return baseline
    return if (recordedPath == currentResolvedPath) baseline else null
}

// Client-side 3-way merge by content hash. RomM's own device_syncs endpoint
// (GET /api/saves?device_id=...) synthesizes a *placeholder* device_sync entry
// (last_synced_at = save.updated_at, is_current = false) whenever this device
// has never actually synced the row — so a naive "server changed since last
// sync" check driven purely by device_syncs sees that placeholder and
// concludes nothing changed, silently masking real divergence. To make CONFLICT
// reachable, the decision is instead anchored on a client-persisted baseline
// hash (the content hash of the version this device last successfully synced),
// not on the server's per-device bookkeeping.
//
// Algorithm:
//   1. Neither side has the save -> NONE. Only one side -> UPLOAD/DOWNLOAD.
//   2. Local and remote content hashes are equal -> UP_TO_DATE, regardless of
//      timestamps or history.
//   3. Hashes differ and a baseline hash is known: whichever side(s) moved off
//      the baseline decide the verdict (both -> CONFLICT, one -> that
//      direction).
//   4. No baseline, but the server sent a real (non-placeholder) device_sync
//      for this device:
//        - is_current == true: the server hasn't changed since our last real
//          sync, so a hash mismatch means only the local file changed ->
//          UPLOAD.
//        - is_current == false and last_synced_at != save.updated_at (a real,
//          stale sync, not the placeholder): the server changed since we last
//          synced; local also changed (by mtime, with the usual 2s tolerance)
//          -> CONFLICT, else -> DOWNLOAD.
//        - is_current == false and last_synced_at == save.updated_at: this is
//          the synthesized placeholder, i.e. no real history -> treated as (5).
//   5. No history at all (no device_sync, or only the placeholder) and hashes
//      differ -> CONFLICT. We have no common ancestor to reason from, so we
//      refuse to guess a direction and silently overwrite either side.
//   6. If a hash is unavailable on either side, fall back to comparing
//      timestamps with a 2s tolerance (FAT/SAF mtime rounding on Android
//      storage); a same-timestamp-but-different-hash tie is broken as a
//      conflict rather than up to date.
fun determineSyncAction(
    slot: SaveSlotResponse,
    hasLocalFile: Boolean,
    localModifiedMs: Long,
    deviceSync: DeviceSaveSync? = null,
    localHash: String? = null,
    remoteHash: String? = null,
    baseline: SyncBaseline? = null
): SyncAction {
    if (!slot.hasRemote && !hasLocalFile) return SyncAction.NONE
    if (!slot.hasRemote) return SyncAction.UPLOAD
    if (!hasLocalFile) return SyncAction.DOWNLOAD

    if (localHash != null && remoteHash != null) {
        // Identical content -> already in sync, whatever the timestamps say.
        if (localHash == remoteHash) return SyncAction.UP_TO_DATE

        val baselineHash = baseline?.contentHash
        if (baselineHash != null) {
            val localChanged = localHash != baselineHash
            val serverChanged = remoteHash != baselineHash
            return when {
                localChanged && serverChanged -> SyncAction.CONFLICT
                localChanged -> SyncAction.UPLOAD
                serverChanged -> SyncAction.DOWNLOAD
                // Hashes differ from each other but neither differs from the
                // baseline -- algebraically impossible, but don't guess.
                else -> SyncAction.CONFLICT
            }
        }

        // No client-persisted baseline: fall back to the server's device_syncs,
        // distinguishing a real sync history from the synthesized placeholder.
        val remoteMs = parseIsoToMs(slot.remoteUpdatedAt)
        val lastSyncedMs = deviceSync?.lastSyncedAt?.let { parseIsoToMs(it) }
        val isPlaceholder = deviceSync != null && !deviceSync.isCurrent &&
            remoteMs != null && lastSyncedMs != null && lastSyncedMs == remoteMs

        if (deviceSync != null && !isPlaceholder) {
            return if (deviceSync.isCurrent) {
                // Server unchanged since our last real sync; hashes still
                // differ, so only the local file moved.
                SyncAction.UPLOAD
            } else {
                // Real, stale sync: the server changed since then.
                val localChanged = lastSyncedMs != null && localModifiedMs > lastSyncedMs + 2_000L
                if (localChanged) SyncAction.CONFLICT else SyncAction.DOWNLOAD
            }
        }

        // No usable history (no device_sync at all, or only the placeholder) --
        // we can't tell which side is newer relative to a common ancestor, so
        // treat it as a conflict rather than silently pick a direction.
        return SyncAction.CONFLICT
    }

    // One or both hashes unavailable: fall back to timestamp comparison.
    val remoteMs = parseIsoToMs(slot.remoteUpdatedAt) ?: return SyncAction.UP_TO_DATE
    val diff = localModifiedMs - remoteMs
    return when {
        diff > 2_000L -> SyncAction.UPLOAD
        diff < -2_000L -> SyncAction.DOWNLOAD
        // Same timestamp (within tolerance): only call it a conflict if we
        // positively know the hashes differ; if either is unknown, assume up
        // to date rather than manufacture a conflict from missing data.
        localHash != null && remoteHash != null && localHash != remoteHash -> SyncAction.CONFLICT
        else -> SyncAction.UP_TO_DATE
    }
}
