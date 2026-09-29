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
// already-synced save. This exception is legacy-only: pass
// requirePathRecorded = true for a configured (v1) platform, where a
// pathless baseline means the save was last synced via the legacy path and
// has never been resolved against this platform's configured folder at all
// -- configuring a platform IS the location change §5 exists to guard
// against, so it gets no free pass (independent review, phase 3A fixes §5).
fun effectiveBaseline(
    baseline: SyncBaseline?,
    currentResolvedPath: String?,
    requirePathRecorded: Boolean = false
): SyncBaseline? {
    baseline ?: return null
    val recordedPath = baseline.resolvedPath
        ?: return if (requirePathRecorded) null else baseline
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
    baseline: SyncBaseline? = null,
    // Phase 3B fixes, blocker 2: true for a v1 CONFIGURED platform (the same
    // flag callers already pass to effectiveBaseline's requirePathRecorded).
    // When true and there's no usable baseline, this function refuses to
    // fall back to the server's device_syncs bookkeeping below -- that
    // bookkeeping describes history for whatever path/preset was in effect
    // when it was recorded, which the baseline-path check (§5) has ALREADY
    // decided is untrustworthy for the CURRENTLY resolved path. Without
    // this, a legacy sync's real, non-placeholder device_sync
    // (is_current == true) would silently justify an UPLOAD here even
    // though Caulker has never actually compared these two particular
    // files -- exactly the "will ask before overwriting" promise this flag
    // exists to keep. Legacy (unconfigured) platforms pass false and are
    // completely unaffected -- they keep reaching the device_sync branch
    // below exactly as before this fix.
    isConfiguredPlatform: Boolean = false
): SyncAction {
    if (!slot.hasRemote && !hasLocalFile) return SyncAction.NONE
    if (!slot.hasRemote) return SyncAction.UPLOAD
    if (!hasLocalFile) return SyncAction.DOWNLOAD

    // Both sides exist from here on. Identical content -> already in sync,
    // whatever the timestamps say, configured or not.
    if (localHash != null && remoteHash != null && localHash == remoteHash) return SyncAction.UP_TO_DATE

    val baselineHash = baseline?.contentHash
    if (baselineHash != null && localHash != null && remoteHash != null) {
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

    // Phase 3B round-2 fixes, should-fix 3: a configured platform with no
    // USABLE baseline (either none was recorded, or a hash needed for the
    // 3-way merge above is unknown) never falls through to device_syncs or
    // the timestamp fallback below -- both sides exist and Caulker has no
    // trustworthy history for the CURRENTLY resolved path, so this is
    // always a conflict, regardless of what the server's bookkeeping or
    // mtimes suggest. "Unknown counts as ask." Legacy (unconfigured)
    // platforms are unaffected -- they keep reaching the branches below
    // exactly as before this fix.
    if (isConfiguredPlatform) {
        return SyncAction.CONFLICT
    }

    if (localHash != null && remoteHash != null) {
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

    // One or both hashes unavailable (legacy platform only -- a configured
    // one already returned CONFLICT above): fall back to timestamp comparison.
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

// Phase 3B fixes, blocker 2 / review item 3: SaveSyncOrchestrator's
// equivalent of determineSyncAction's isConfiguredPlatform short-circuit
// above, factored out as its own pure, JVM-testable decision -- the
// orchestrator acts on negotiate's own SyncOperations directly rather than
// computing a SyncAction, so it can't call determineSyncAction itself, but
// the underlying rule is identical, per round-2 fixes should-fix 3: no
// baseline recorded against the CURRENTLY resolved local path, with both
// sides present, needs the user's attention UNLESS both hashes are known
// AND provably equal -- "unknown counts as ask." Round 1 of this fix
// bailed out early ("return false") whenever EITHER hash was null, which
// made a missing op.serverContentHash (a real, observed case -- the server
// doesn't always send it) fail OPEN, silently letting the orchestrator
// execute a download/upload negotiate proposed with no baseline behind it
// at all. Now only a POSITIVE equality check skips the attention flag;
// anything else (differing hashes, or either one unknown) falls through to
// the same baseline check as before.
//
// Only applies when BOTH sides actually have a save: a first upload of a
// brand-new local save (no server save yet) or a first download onto a
// device with no local save can't overwrite anything, so they proceed
// normally (phase 3B final review blocker -- without this, background sync
// skipped every first sync on a configured platform forever).
fun needsAttentionInsteadOfSync(
    localExists: Boolean,
    remoteExists: Boolean,
    localHash: String?,
    remoteHash: String?,
    baseline: SyncBaseline?,
    currentResolvedPath: String?
): Boolean {
    if (!localExists || !remoteExists) return false
    if (localHash != null && remoteHash != null && localHash == remoteHash) return false
    return effectiveBaseline(baseline, currentResolvedPath, requirePathRecorded = true) == null
}
