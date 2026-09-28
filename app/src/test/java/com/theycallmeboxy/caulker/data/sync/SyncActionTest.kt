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
}
