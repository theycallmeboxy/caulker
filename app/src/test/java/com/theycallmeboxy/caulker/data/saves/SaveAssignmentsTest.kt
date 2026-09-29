package com.theycallmeboxy.caulker.data.saves

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for the Unassigned-file assignment wire format (save-sync
// design doc, Part 2 §3 rule 3 / §12 phase 3B item 3) -- PrefsStore wraps
// these in the actual DataStore read/write, which needs an Android Context
// and isn't unit-testable here; this covers the part that is.
class SaveAssignmentsTest {

    @Test
    fun `round-trips a map of romId to relative path`() {
        val map = mapOf(1 to "Mario.srm", 2 to "SomeSave", 42 to "nvram/foo.nv")

        val json = serializeSaveAssignments(map)
        val parsed = parseSaveAssignments(json)

        assertEquals(map, parsed)
    }

    @Test
    fun `null input yields an empty map`() {
        assertTrue(parseSaveAssignments(null).isEmpty())
    }

    @Test
    fun `a non-integer key is dropped, not the whole map`() {
        val obj = JSONObject().put("7", "Mario.srm").put("not-a-number", "Other.srm")
        val parsed = parseSaveAssignments(obj)
        assertEquals(mapOf(7 to "Mario.srm"), parsed)
    }

    @Test
    fun `a blank path is dropped`() {
        val obj = JSONObject().put("7", "")
        assertTrue(parseSaveAssignments(obj).isEmpty())
    }

    @Test
    fun `an unsafe path (traversal) is dropped -- defense in depth`() {
        val obj = JSONObject().put("7", "../../etc/passwd")
        assertTrue(parseSaveAssignments(obj).isEmpty())
    }

    @Test
    fun `one bad entry does not drop the rest of the map`() {
        val obj = JSONObject()
            .put("7", "Mario.srm")
            .put("8", "../escape")
            .put("9", "SomeSave/Nested")
        val parsed = parseSaveAssignments(obj)
        assertEquals(mapOf(7 to "Mario.srm", 9 to "SomeSave/Nested"), parsed)
    }
}
