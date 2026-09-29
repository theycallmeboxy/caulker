package com.theycallmeboxy.caulker.data.sync

import com.theycallmeboxy.caulker.data.api.model.DeviceSaveSync
import com.theycallmeboxy.caulker.data.api.model.SaveSlotResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

// Pure-JVM tests for the client-side 3-way merge in determineSyncAction().
// No Android deps are exercised here (SyncAction.kt only touches
// java.time.Instant via parseIsoToMs), so these run under the plain JVM unit
// test task without Robolectric.
class SyncActionTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val t1 = t0.plusSeconds(3600) // an hour later

    private fun iso(instant: Instant) = instant.toString()
    private fun ms(instant: Instant) = instant.toEpochMilli()

    // --- Basic presence/absence, no hashes involved ---

    @Test
    fun `neither side exists yields NONE`() {
        val slot = SaveSlotResponse(hasRemote = false)
        val result = determineSyncAction(slot, hasLocalFile = false, localModifiedMs = 0)
        assertEquals(SyncAction.NONE, result)
    }

    @Test
    fun `only remote exists yields DOWNLOAD`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(slot, hasLocalFile = false, localModifiedMs = 0)
        assertEquals(SyncAction.DOWNLOAD, result)
    }

    @Test
    fun `only local exists yields UPLOAD`() {
        val slot = SaveSlotResponse(hasRemote = false)
        val result = determineSyncAction(slot, hasLocalFile = true, localModifiedMs = ms(t0))
        assertEquals(SyncAction.UPLOAD, result)
    }

    // --- Equal hashes always win, regardless of timestamps ---

    @Test
    fun `equal hashes yields UP_TO_DATE even with very different timestamps`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1),
            localHash = "same-hash", remoteHash = "same-hash"
        )
        assertEquals(SyncAction.UP_TO_DATE, result)
    }

    // --- Baseline known: whichever side(s) moved off it decide the verdict ---

    @Test
    fun `baseline known, both sides changed yields CONFLICT`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t1))
        val baseline = SyncBaseline(contentHash = "base")
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1),
            localHash = "local-changed", remoteHash = "remote-changed",
            baseline = baseline
        )
        assertEquals(SyncAction.CONFLICT, result)
    }

    @Test
    fun `baseline known, local-only changed yields UPLOAD`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val baseline = SyncBaseline(contentHash = "base")
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1),
            localHash = "local-changed", remoteHash = "base",
            baseline = baseline
        )
        assertEquals(SyncAction.UPLOAD, result)
    }

    @Test
    fun `baseline known, server-only changed yields DOWNLOAD`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t1))
        val baseline = SyncBaseline(contentHash = "base")
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0),
            localHash = "base", remoteHash = "remote-changed",
            baseline = baseline
        )
        assertEquals(SyncAction.DOWNLOAD, result)
    }

    // --- No baseline: fall back to the server's device_syncs entry ---

    @Test
    fun `no baseline, real is_current entry yields UPLOAD (server unchanged, local moved)`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val deviceSync = DeviceSaveSync(
            deviceId = "this-device",
            lastSyncedAt = iso(t1), // this device synced *after* the save's updated_at
            isCurrent = true
        )
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1),
            deviceSync = deviceSync,
            localHash = "local-changed", remoteHash = "server-hash"
        )
        assertEquals(SyncAction.UPLOAD, result)
    }

    @Test
    fun `no baseline, real not-current entry, local unchanged since sync yields DOWNLOAD`() {
        // last_synced_at is real (not equal to remote_updated_at) and in the past;
        // the local file hasn't been touched since then, so only the server moved.
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t1))
        val deviceSync = DeviceSaveSync(
            deviceId = "this-device",
            lastSyncedAt = iso(t0),
            isCurrent = false
        )
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0),
            deviceSync = deviceSync,
            localHash = "local-hash", remoteHash = "server-hash"
        )
        assertEquals(SyncAction.DOWNLOAD, result)
    }

    @Test
    fun `no baseline, real not-current entry, local changed since sync yields CONFLICT`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t1))
        val deviceSync = DeviceSaveSync(
            deviceId = "this-device",
            lastSyncedAt = iso(t0),
            isCurrent = false
        )
        // Local file modified well after the last real sync.
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1.plusSeconds(60)),
            deviceSync = deviceSync,
            localHash = "local-hash", remoteHash = "server-hash"
        )
        assertEquals(SyncAction.CONFLICT, result)
    }

    // --- The RomM placeholder device_sync (last_synced_at == save.updated_at,
    // is_current == false) must be treated as no history at all, not as
    // "server unchanged" -- this is the core bug this rework fixes. ---

    @Test
    fun `placeholder device_sync with differing hashes yields CONFLICT, never UP_TO_DATE`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val placeholder = DeviceSaveSync(
            deviceId = "this-device",
            lastSyncedAt = iso(t0), // == save.updated_at: the synthesized placeholder
            isCurrent = false
        )
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0),
            deviceSync = placeholder,
            localHash = "local-hash", remoteHash = "server-hash"
        )
        assertEquals(SyncAction.CONFLICT, result)
    }

    @Test
    fun `no device_sync at all and hashes differ yields CONFLICT`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0),
            deviceSync = null,
            localHash = "local-hash", remoteHash = "server-hash"
        )
        assertEquals(SyncAction.CONFLICT, result)
    }

    // --- Hashes unavailable: fall back to timestamp comparison with 2s tolerance ---

    @Test
    fun `missing hashes, local clearly newer yields UPLOAD`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0) + 10_000L,
            localHash = null, remoteHash = null
        )
        assertEquals(SyncAction.UPLOAD, result)
    }

    @Test
    fun `missing hashes, remote clearly newer yields DOWNLOAD`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0) - 10_000L,
            localHash = null, remoteHash = null
        )
        assertEquals(SyncAction.DOWNLOAD, result)
    }

    @Test
    fun `missing hashes, timestamps tie yields UP_TO_DATE`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0) + 500L,
            localHash = null, remoteHash = null
        )
        assertEquals(SyncAction.UP_TO_DATE, result)
    }

    @Test
    fun `one hash missing, timestamps tie yields UP_TO_DATE (can't prove a conflict)`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0),
            localHash = "local-hash", remoteHash = null
        )
        assertEquals(SyncAction.UP_TO_DATE, result)
    }

    @Test
    fun `unparseable remote timestamp yields UP_TO_DATE fallback`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = "not-a-date")
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0),
            localHash = null, remoteHash = null
        )
        assertEquals(SyncAction.UP_TO_DATE, result)
    }

    // --- Phase 3B fixes, blocker 2: "will ask before overwriting" is false
    // -- a CONFIGURED platform with no baseline must never fall through to
    // the device_sync branch, even when the server sent a real,
    // non-placeholder, is_current entry. This is the exact device repro:
    // NES was synced on the legacy path (a real device_sync exists), the
    // platform was then configured with a preset on the same folder and the
    // local file edited -- the baseline has no path (or the wrong one), so
    // effectiveBaseline() already returns null, but determineSyncAction used
    // to still consult device_sync and return UPLOAD. ---

    @Test
    fun `configured platform, no baseline, real is_current device_sync still yields CONFLICT`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val deviceSync = DeviceSaveSync(deviceId = "this-device", lastSyncedAt = iso(t1), isCurrent = true)
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1),
            deviceSync = deviceSync,
            localHash = "local-changed", remoteHash = "server-hash",
            baseline = null, isConfiguredPlatform = true
        )
        assertEquals(SyncAction.CONFLICT, result)
    }

    @Test
    fun `legacy platform, same inputs, still yields UPLOAD (unchanged behavior)`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val deviceSync = DeviceSaveSync(deviceId = "this-device", lastSyncedAt = iso(t1), isCurrent = true)
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1),
            deviceSync = deviceSync,
            localHash = "local-changed", remoteHash = "server-hash",
            baseline = null, isConfiguredPlatform = false
        )
        assertEquals(SyncAction.UPLOAD, result)
    }

    @Test
    fun `configured platform WITH a path-matching baseline still uses the 3-way merge, not a forced CONFLICT`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t1))
        val baseline = SyncBaseline(contentHash = "base", resolvedPath = "/saves/nes/Mario.srm")
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t1),
            localHash = "local-changed", remoteHash = "base",
            baseline = baseline, isConfiguredPlatform = true
        )
        assertEquals(SyncAction.UPLOAD, result)
    }

    @Test
    fun `configured platform, equal hashes still yields UP_TO_DATE even with no baseline`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0),
            localHash = "same", remoteHash = "same",
            baseline = null, isConfiguredPlatform = true
        )
        assertEquals(SyncAction.UP_TO_DATE, result)
    }

    // Phase 3B round-2 fixes, should-fix 3: a configured platform with no
    // baseline and a MISSING hash (remoteHash unknown here) must still ask
    // rather than let the timestamp fallback silently pick a direction --
    // local is clearly "newer" by mtime, which used to yield UPLOAD.
    @Test
    fun `configured platform, no baseline, missing remote hash still yields CONFLICT, not a timestamp-driven UPLOAD`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0) + 10_000L,
            localHash = "local-hash", remoteHash = null,
            baseline = null, isConfiguredPlatform = true
        )
        assertEquals(SyncAction.CONFLICT, result)
    }

    @Test
    fun `legacy platform, same missing-hash inputs, still uses the timestamp fallback (unchanged)`() {
        val slot = SaveSlotResponse(hasRemote = true, remoteUpdatedAt = iso(t0))
        val result = determineSyncAction(
            slot, hasLocalFile = true, localModifiedMs = ms(t0) + 10_000L,
            localHash = "local-hash", remoteHash = null,
            baseline = null, isConfiguredPlatform = false
        )
        assertEquals(SyncAction.UPLOAD, result)
    }

    // --- needsAttentionInsteadOfSync (blocker 2 / review item 3): the
    // orchestrator's non-SyncAction equivalent of the same rule, used
    // directly against a SyncOperation's server_content_hash. ---

    @Test
    fun `needsAttentionInsteadOfSync is true for differing hashes with no path-matching baseline`() {
        assertEquals(
            true,
            needsAttentionInsteadOfSync(
                localExists = true, remoteExists = true, localHash = "local", remoteHash = "remote", baseline = null, currentResolvedPath = "/saves/nes/Mario.srm"
            )
        )
    }

    @Test
    fun `needsAttentionInsteadOfSync is false when hashes match`() {
        assertEquals(
            false,
            needsAttentionInsteadOfSync(
                localExists = true, remoteExists = true, localHash = "same", remoteHash = "same", baseline = null, currentResolvedPath = "/saves/nes/Mario.srm"
            )
        )
    }

    @Test
    fun `needsAttentionInsteadOfSync is false with a path-matching baseline`() {
        val baseline = SyncBaseline(contentHash = "base", resolvedPath = "/saves/nes/Mario.srm")
        assertEquals(
            false,
            needsAttentionInsteadOfSync(
                localExists = true, remoteExists = true, localHash = "local", remoteHash = "remote", baseline = baseline, currentResolvedPath = "/saves/nes/Mario.srm"
            )
        )
    }

    @Test
    fun `needsAttentionInsteadOfSync is true when the baseline's recorded path doesn't match`() {
        val baseline = SyncBaseline(contentHash = "base", resolvedPath = "/saves/nes/Old.srm")
        assertEquals(
            true,
            needsAttentionInsteadOfSync(
                localExists = true, remoteExists = true, localHash = "local", remoteHash = "remote", baseline = baseline, currentResolvedPath = "/saves/nes/Mario.srm"
            )
        )
    }

    // Phase 3B round-2 fixes, should-fix 3: "unknown counts as ask" -- a
    // missing hash (e.g. op.serverContentHash absent from a real negotiate
    // response) with no usable baseline must fail CLOSED (needs attention),
    // not silently proceed. Round 1 of this function bailed out to false
    // whenever EITHER hash was null -- that was the fail-open bug.
    @Test
    fun `needsAttentionInsteadOfSync is true when a hash is missing and there's no baseline`() {
        assertEquals(true, needsAttentionInsteadOfSync(true, true, null, "remote", null, "/x"))
        assertEquals(true, needsAttentionInsteadOfSync(true, true, "local", null, null, "/x"))
    }

    @Test
    fun `needsAttentionInsteadOfSync is false when a hash is unknown but a path-matching baseline exists`() {
        val baseline = SyncBaseline(contentHash = "base", resolvedPath = "/saves/nes/Mario.srm")
        assertEquals(false, needsAttentionInsteadOfSync(true, true, "local", null, baseline, "/saves/nes/Mario.srm"))
    }

    // Phase 3B final review blocker: a first upload (no server save) or a
    // first download (no local save) has nothing to overwrite, so it must
    // never be held back for attention, baseline or not.
    @Test
    fun `needsAttentionInsteadOfSync is false when only one side has a save`() {
        assertEquals(false, needsAttentionInsteadOfSync(true, false, "local", null, null, "/x"))
        assertEquals(false, needsAttentionInsteadOfSync(false, true, null, "remote", null, "/x"))
    }
}
