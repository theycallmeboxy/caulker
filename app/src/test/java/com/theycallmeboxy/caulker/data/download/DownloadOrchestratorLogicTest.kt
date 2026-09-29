package com.theycallmeboxy.caulker.data.download

import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pure-JVM tests for the two bits of DownloadOrchestrator logic that don't
// need coroutines or a RomRepository: the force/already-installed filtering
// rule (planDownload) and the pending-request FIFO (PendingDownloadQueue).
class DownloadOrchestratorLogicTest {

    private fun rom(id: Int) = RomEntity(id = id, name = "Rom $id", platformId = 1)

    // --- planDownload ---

    @Test
    fun `already-installed roms are skipped when not forced`() {
        val roms = listOf(rom(1), rom(2), rom(3))
        val plan = planDownload(
            requestedIdCount = 3, resolvedRoms = roms, installedIds = setOf(2), force = false
        )
        assertEquals(listOf(1, 3), plan.toDownload.map { it.id })
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `force re-downloads everything, ignoring installed status`() {
        val roms = listOf(rom(1), rom(2), rom(3))
        val plan = planDownload(
            requestedIdCount = 3, resolvedRoms = roms, installedIds = setOf(1, 2, 3), force = true
        )
        assertEquals(listOf(1, 2, 3), plan.toDownload.map { it.id })
        assertEquals(0, plan.skipped)
    }

    @Test
    fun `ids that never resolved to a cached rom are skipped even when forced`() {
        // Requested 3 ids, only 2 resolved (library not synced for the third).
        val roms = listOf(rom(1), rom(2))
        val plan = planDownload(
            requestedIdCount = 3, resolvedRoms = roms, installedIds = emptySet(), force = true
        )
        assertEquals(listOf(1, 2), plan.toDownload.map { it.id })
        assertEquals(1, plan.skipped)
    }

    @Test
    fun `nothing to download when every resolved rom is already installed`() {
        val roms = listOf(rom(1), rom(2))
        val plan = planDownload(
            requestedIdCount = 2, resolvedRoms = roms, installedIds = setOf(1, 2), force = false
        )
        assertEquals(emptyList<RomEntity>(), plan.toDownload)
        assertEquals(2, plan.skipped)
    }

    // --- PendingDownloadQueue ---

    @Test
    fun `dequeue returns requests in FIFO order`() {
        val queue = PendingDownloadQueue()
        queue.enqueue(PendingDownload("A", listOf(1), force = false))
        queue.enqueue(PendingDownload("B", listOf(2), force = true))

        assertEquals("A", queue.dequeue()?.label)
        assertEquals("B", queue.dequeue()?.label)
        assertNull(queue.dequeue())
    }

    @Test
    fun `clear drops everything queued`() {
        val queue = PendingDownloadQueue()
        queue.enqueue(PendingDownload("A", listOf(1), force = false))
        queue.enqueue(PendingDownload("B", listOf(2), force = false))

        queue.clear()

        assertNull(queue.dequeue())
    }
}
