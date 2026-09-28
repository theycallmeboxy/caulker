package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.api.model.DeviceSaveSync
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.util.parseIsoToMs

// Per-slot sync decision used across the in-app save sync UI, the
// SaveSyncOrchestrator (QS tile path), and the foreground service. Lives in the
// data layer so the orchestrator doesn't have to import upward into UI code.
enum class SyncAction { NONE, UPLOAD, DOWNLOAD, UP_TO_DATE, CONFLICT }

// Mirrors RomM's server-side compare_save_state (backend/handler/sync/comparison.py)
// exactly, aside from one deliberate deviation: a 2s tolerance on every ">"
// comparison, to absorb FAT/SAF mtime rounding on Android storage (the server
// compares with zero tolerance since its timestamps are its own database
// values). Algorithm:
//   1. Identical content-hash -> no-op regardless of timestamps.
//   2. With last-synced history: which side changed since then decides the
//      verdict (both -> conflict, one -> that direction, neither -> up to date).
//   3. No history: timestamp comparison, with a same-timestamp-but-different-hash
//      tie broken as a conflict (matching the server) rather than up to date.
fun determineSyncAction(
    slot: SaveSlotResponse,
    hasLocalFile: Boolean,
    localModifiedMs: Long,
    deviceSync: DeviceSaveSync? = null,
    localHash: String? = null,
    remoteHash: String? = null
): SyncAction {
    if (!slot.hasRemote && !hasLocalFile) return SyncAction.NONE
    if (!slot.hasRemote) return SyncAction.UPLOAD
    if (!hasLocalFile) return SyncAction.DOWNLOAD

    // Identical content -> already in sync, whatever the timestamps say.
    if (localHash != null && remoteHash != null && localHash == remoteHash) {
        return SyncAction.UP_TO_DATE
    }

    val remoteMs = parseIsoToMs(slot.remoteUpdatedAt) ?: return SyncAction.UP_TO_DATE

    val lastSyncedMs = deviceSync?.let { parseIsoToMs(it.lastSyncedAt) }
    if (lastSyncedMs != null) {
        val clientChanged = localModifiedMs > lastSyncedMs + 2_000L
        val serverChanged = remoteMs > lastSyncedMs + 2_000L
        return when {
            clientChanged && serverChanged -> SyncAction.CONFLICT
            clientChanged -> SyncAction.UPLOAD
            serverChanged -> SyncAction.DOWNLOAD
            else -> SyncAction.UP_TO_DATE
        }
    }

    // No sync history: fall back to timestamp comparison.
    val diff = localModifiedMs - remoteMs
    return when {
        diff > 2_000L -> SyncAction.UPLOAD
        diff < -2_000L -> SyncAction.DOWNLOAD
        // Same timestamp (within tolerance) but hashes are known to differ ->
        // conflict, matching the server's tie-break.
        localHash != remoteHash -> SyncAction.CONFLICT
        else -> SyncAction.UP_TO_DATE
    }
}
