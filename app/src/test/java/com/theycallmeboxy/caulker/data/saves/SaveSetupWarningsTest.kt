package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for the setup-warnings decision (save-sync design doc, Part
// 2 §12 phase 3B item 2) -- PlatformSettingsViewModel does the actual scan
// (off the main thread) and hands its outcome to these functions; this
// covers the decision, not the I/O.
class SaveSetupWarningsTest {

    private val unit = LocalSaveUnit(
        romId = 1, shape = SaveShape.SINGLE_FILE, matchingKeyName = "Mario",
        memberPaths = listOf("Mario.srm"), resolvedPath = "/saves/snes/Mario.srm",
        contentHash = "deadbeef", modifiedMs = 1000L
    )
    private val unassignedEntry = LocalSaveEntry("Stray.srm", false)

    @Test
    fun `a folder with units and no unassigned files has no warnings`() {
        val result = SaveLocationScanResult(units = listOf(unit), unassigned = emptyList())
        val warnings = computeSaveSetupWarnings(result)

        assertTrue(warnings.isEmpty)
        assertFalse(warnings.folderEmptyOrNoSaves)
        assertEquals(0, warnings.unassignedCount)
        assertNull(warnings.folderUnreadable)
    }

    @Test
    fun `an empty folder (no units, no unassigned) warns folderEmptyOrNoSaves`() {
        val result = SaveLocationScanResult(units = emptyList(), unassigned = emptyList())
        val warnings = computeSaveSetupWarnings(result)

        assertTrue(warnings.folderEmptyOrNoSaves)
        assertFalse(warnings.isEmpty)
    }

    @Test
    fun `no units but some unassigned files does not also claim the folder is empty`() {
        val result = SaveLocationScanResult(units = emptyList(), unassigned = listOf(unassignedEntry))
        val warnings = computeSaveSetupWarnings(result)

        assertFalse(warnings.folderEmptyOrNoSaves)
        assertEquals(1, warnings.unassignedCount)
    }

    @Test
    fun `units present and some unassigned reports only the unassigned count`() {
        val result = SaveLocationScanResult(units = listOf(unit), unassigned = listOf(unassignedEntry, unassignedEntry.copy(relativePath = "Other.srm")))
        val warnings = computeSaveSetupWarnings(result)

        assertFalse(warnings.folderEmptyOrNoSaves)
        assertEquals(2, warnings.unassignedCount)
        assertFalse(warnings.isEmpty)
    }

    @Test
    fun `a scan failure reports the exception's own message`() {
        val warnings = computeSaveSetupWarningsForFailure("Permission denied")

        assertEquals("Permission denied", warnings.folderUnreadable)
        assertFalse(warnings.isEmpty)
    }

    @Test
    fun `a scan failure with no message falls back to a generic reason`() {
        val warnings = computeSaveSetupWarningsForFailure(null)

        assertEquals("Folder isn't readable", warnings.folderUnreadable)
    }

    @Test
    fun `a scan failure with a blank message falls back to a generic reason`() {
        val warnings = computeSaveSetupWarningsForFailure("   ")

        assertEquals("Folder isn't readable", warnings.folderUnreadable)
    }
}
