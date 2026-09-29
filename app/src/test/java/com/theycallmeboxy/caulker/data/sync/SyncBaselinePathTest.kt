package com.theycallmeboxy.caulker.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// Pure-JVM tests for effectiveBaseline() -- the §5 baseline+path safety rule
// applied ahead of determineSyncAction() (save-sync design doc, Part 2 §5 /
// §13 test plan). Kept in its own file so SyncActionTest.kt (determineSyncAction
// itself) is untouched and its existing behavior/tests keep passing unchanged.
class SyncBaselinePathTest {

    @Test
    fun `no baseline at all stays null`() {
        assertNull(effectiveBaseline(null, "/saves/snes/Zelda.srm"))
    }

    @Test
    fun `baseline with no recorded path (pre-v1) is treated as matching`() {
        val baseline = SyncBaseline(contentHash = "hash")
        val result = effectiveBaseline(baseline, "/saves/snes/Zelda.srm")
        assertSame(baseline, result)
    }

    @Test
    fun `baseline whose recorded path matches the current path is kept`() {
        val baseline = SyncBaseline(contentHash = "hash", resolvedPath = "/saves/snes/Zelda.srm")
        val result = effectiveBaseline(baseline, "/saves/snes/Zelda.srm")
        assertSame(baseline, result)
    }

    @Test
    fun `baseline whose recorded path differs from the current path is treated as no baseline`() {
        val baseline = SyncBaseline(contentHash = "hash", resolvedPath = "/saves/snes/Zelda.srm")
        val result = effectiveBaseline(baseline, "/saves/snes-override/Zelda.srm")
        assertNull(result)
    }

    @Test
    fun `baseline with a recorded path but no current path (folder unset) is treated as no baseline`() {
        val baseline = SyncBaseline(contentHash = "hash", resolvedPath = "/saves/snes/Zelda.srm")
        val result = effectiveBaseline(baseline, null)
        assertNull(result)
    }
}
