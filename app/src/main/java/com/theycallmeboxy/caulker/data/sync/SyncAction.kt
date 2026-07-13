package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.api.model.DeviceSaveSync
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import com.theycallmeboxy.caulker.data.util.parseIsoToMs

// Per-slot sync decision used across the in-app save sync UI, the
// SaveSyncOrchestrator (QS tile path), and the foreground service. Lives in the
// data layer so the orchestrator doesn't have to import upward into UI code.
enum class SyncAction { NONE, UPLOAD, DOWNLOAD, UP_TO_DATE, CONFLICT }

// Mirrors RomM 4.9's server-side compare_save_state (hash-first): identical
// content is a no-op regardless of timestamps, then last-synced tracking decides
// direction / conflict, then a timestamp fallback. Passing the local and server
// `content_hash` keeps this in-app verdict aligned with the server's
// /sync/negotiate result, so a byte-identical save is never flagged for
// upload/download the way a timestamp-only comparison would.
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

    if (deviceSync != null) {
        val lastSyncedMs = parseIsoToMs(deviceSync.lastSyncedAt)
        if (lastSyncedMs != null) {
            val localChanged = localModifiedMs > lastSyncedMs + 2_000L
            val remoteChanged = remoteMs > lastSyncedMs + 2_000L
            if (localChanged && remoteChanged) return SyncAction.CONFLICT
        }
        if (deviceSync.isCurrent) {
            return if (localModifiedMs > remoteMs + 2_000L) SyncAction.UPLOAD else SyncAction.UP_TO_DATE
        }
    }

    // No deviceSync, or isCurrent=false (another source uploaded since last sync).
    // Apply a 2s tolerance only in the DOWNLOAD direction — clock skew from
    // another device uploading is the real risk there. For UPLOAD, trust local
    // being any amount newer: a file the emulator just saved should always win.
    val diff = localModifiedMs - remoteMs
    return when {
        diff > 0L -> SyncAction.UPLOAD
        diff < -2_000L -> SyncAction.DOWNLOAD
        else -> SyncAction.UP_TO_DATE
    }
}
