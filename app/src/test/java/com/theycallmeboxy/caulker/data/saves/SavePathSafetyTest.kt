package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Pure-JVM tests for the path-traversal guard every matching path goes
// through before it's used to read/write a file (save-sync design doc, Part 2
// §2 "Path-traversal guard" / §13 test plan).
class SavePathSafetyTest {

    @Test
    fun `plain name substitutes normally`() {
        assertEquals("Zelda.srm", resolveSafeRelativePath("{name}.srm", "Zelda"))
    }

    @Test
    fun `name with a slash is rejected`() {
        assertNull(resolveSafeRelativePath("{name}.srm", "evil/Zelda"))
    }

    @Test
    fun `name with a backslash is rejected`() {
        assertNull(resolveSafeRelativePath("{name}.srm", "evil\\Zelda"))
    }

    @Test
    fun `name that is a traversal segment is rejected`() {
        assertNull(resolveSafeRelativePath("{name}.srm", ".."))
        assertNull(resolveSafeRelativePath("{name}.srm", "."))
    }

    @Test
    fun `name embedding a traversal sequence is rejected`() {
        assertNull(resolveSafeRelativePath("{name}.srm", "../../etc/passwd"))
    }

    @Test
    fun `blank name is rejected`() {
        assertNull(resolveSafeRelativePath("{name}.srm", ""))
        assertNull(resolveSafeRelativePath("{name}.srm", "   "))
    }

    @Test
    fun `pattern with a traversal segment is rejected even with a safe name`() {
        assertNull(resolveSafeRelativePath("../{name}.srm", "Zelda"))
        assertNull(resolveSafeRelativePath("saves/../../{name}.srm", "Zelda"))
    }

    @Test
    fun `pattern that resolves to an absolute path is rejected`() {
        assertNull(resolveSafeRelativePath("/etc/{name}.srm", "Zelda"))
    }

    @Test
    fun `pattern that resolves to a windows drive path is rejected`() {
        assertNull(resolveSafeRelativePath("C:\\{name}.srm", "Zelda"))
    }

    @Test
    fun `subdirectory pattern resolves fine`() {
        assertEquals("nvram/Zelda.nv", resolveSafeRelativePath("nvram/{name}.nv", "Zelda"))
    }

    @Test
    fun `isSafeNameSegment rejects null and blank`() {
        assertEquals(false, isSafeNameSegment(null))
        assertEquals(false, isSafeNameSegment(""))
    }
}
