package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Pure-JVM tests for unpackSaveZip -- round-trips against packSaveZip, the
// root-verification tiers and refusal, FILE_SET pattern-based placement, the
// path-traversal guard, the zip safety caps, and a fixture built to mirror
// Argosy's own archive layout byte-for-byte (save-sync design doc, Part 2 §2
// / §13 test plan). See SaveZipUnpacker.kt's file-level comment for the
// §2-vs-Argosy findings this implementation follows. buildRawZip is shared
// from SaveZipTestFakes.kt.
class SaveZipUnpackerTest {

    private fun dir(path: String) = LocalSaveEntry(path, isDirectory = true)
    private fun file(path: String) = LocalSaveEntry(path, isDirectory = false)

    // --- Round-trips through packSaveZip ---

    @Test
    fun `round-trip FOLDER single-root through pack then unpack`() {
        val original = mapOf(
            "ULUS10064/DATA.BIN" to byteArrayOf(1, 2, 3),
            "ULUS10064/sub/PARAM.SFO" to byteArrayOf(4, 5)
        )
        val zip = packSaveZip(SaveShape.FOLDER, listOf(dir("ULUS10064")), FakeSaveFileSource(original))

        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", sink)

        assertTrue(outcome is UnpackOutcome.Success)
        outcome as UnpackOutcome.Success
        assertEquals(ArchiveRootMatchTier.EXACT, outcome.rootMatch)
        assertEquals(original.keys, sink.written.keys)
        for ((path, bytes) in original) assertEquals(bytes.toList(), sink.written.getValue(path).toList())
    }

    @Test
    fun `round-trip FOLDER multi-root through pack then unpack`() {
        val original = mapOf(
            "ULUS10064DATA00/DATA.BIN" to byteArrayOf(1),
            "ULUS10064SETTINGS/OPTIONS.BIN" to byteArrayOf(2)
        )
        val entries = listOf(dir("ULUS10064DATA00"), dir("ULUS10064SETTINGS"))
        val zip = packSaveZip(SaveShape.FOLDER, entries, FakeSaveFileSource(original))

        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", sink)

        assertTrue(outcome is UnpackOutcome.Success)
        outcome as UnpackOutcome.Success
        assertEquals(ArchiveRootMatchTier.PREFIX, outcome.rootMatch)
        assertEquals(original.keys, sink.written.keys)
    }

    @Test
    fun `round-trip FILE_SET through pack then unpack`() {
        val original = mapOf(
            "Star Ocean.srm" to byteArrayOf(1),
            "Star Ocean.rtc" to byteArrayOf(2)
        )
        val entries = listOf(file("Star Ocean.srm"), file("Star Ocean.rtc"))
        val patterns = listOf("{name}.srm", "{name}.rtc")
        val zip = packSaveZip(SaveShape.FILE_SET, entries, FakeSaveFileSource(original))

        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "Star Ocean", sink,
            fileSetPatterns = patterns
        )

        assertTrue(outcome is UnpackOutcome.Success)
        outcome as UnpackOutcome.Success
        assertNull("FILE_SET has no root to verify", outcome.rootMatch)
        assertTrue(outcome.skippedEntries.isEmpty())
        assertEquals(original.keys, sink.written.keys)
    }

    // --- Root-verification tiers ---

    @Test
    fun `exact root match unpacks with EXACT tier`() {
        val zip = buildRawZip(listOf("ULUS10064/data.bin" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", FakeSaveFileSink())
        assertEquals(ArchiveRootMatchTier.EXACT, (outcome as UnpackOutcome.Success).rootMatch)
    }

    @Test
    fun `prefix root match unpacks with PREFIX tier`() {
        val zip = buildRawZip(listOf("ULUS10064DATA00/data.bin" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", FakeSaveFileSink())
        assertEquals(ArchiveRootMatchTier.PREFIX, (outcome as UnpackOutcome.Success).rootMatch)
    }

    @Test
    fun `contains root match unpacks with CONTAINS tier`() {
        val zip = buildRawZip(listOf("SAVEDATA_ULUS10064_1/data.bin" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", FakeSaveFileSink())
        assertEquals(ArchiveRootMatchTier.CONTAINS, (outcome as UnpackOutcome.Success).rootMatch)
    }

    @Test
    fun `a root matching at no tier refuses the unpack`() {
        val zip = buildRawZip(listOf("SOMEOTHERGAME/data.bin" to byteArrayOf(1)))
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", sink)
        assertTrue(outcome is UnpackOutcome.Refused)
        assertTrue(sink.written.isEmpty())
    }

    @Test
    fun `a multi-root archive is refused entirely when any one root fails to match`() {
        val zip = buildRawZip(
            listOf(
                "ULUS10064DATA00/data.bin" to byteArrayOf(1),
                "UNRELATED/other.bin" to byteArrayOf(2)
            )
        )
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", sink)
        assertTrue(outcome is UnpackOutcome.Refused)
        assertTrue("must not partially extract", sink.written.isEmpty())
    }

    @Test
    fun `a multi-root archive reports the weakest tier among its roots`() {
        val zip = buildRawZip(
            listOf(
                "ULUS10064/exact.bin" to byteArrayOf(1),
                "ULUS10064DATA00/prefix.bin" to byteArrayOf(2)
            )
        )
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", FakeSaveFileSink())
        assertEquals(ArchiveRootMatchTier.PREFIX, (outcome as UnpackOutcome.Success).rootMatch)
    }

    @Test
    fun `FILE_SET archives skip root verification entirely`() {
        // The name used here isn't a root, and needn't relate to the
        // archive's bytes at all beyond resolving a pattern that matches --
        // there's no root-name tier check applied for this shape at all.
        val zip = buildRawZip(listOf("save.bin" to byteArrayOf(1)))
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "save", FakeSaveFileSink(),
            fileSetPatterns = listOf("{name}.bin")
        )
        assertTrue(outcome is UnpackOutcome.Success)
        assertNull((outcome as UnpackOutcome.Success).rootMatch)
    }

    @Test
    fun `a FOLDER archive with a stray flat entry alongside a root is refused`() {
        val zip = buildRawZip(
            listOf(
                "ULUS10064/data.bin" to byteArrayOf(1),
                "stray.bin" to byteArrayOf(2)
            )
        )
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `a FILE_SET archive containing a directory-shaped entry is refused`() {
        val zip = buildRawZip(listOf("sub/data.bin" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FILE_SET, "id", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    // --- FILE_SET pattern-based placement (lead review, fix 2) ---

    @Test
    fun `FILE_SET unpack places a flat entry at its pattern's subdirectory path`() {
        // MAME 2003-Plus-style: nvram/{name}.nv + hi/{name}.hi, both zipped
        // flat as bare "sf2.nv"/"sf2.hi" (SaveZipPacker.kt point 1).
        val zip = buildRawZip(listOf("sf2.nv" to byteArrayOf(1), "sf2.hi" to byteArrayOf(2)))
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "sf2", sink,
            fileSetPatterns = listOf("nvram/{name}.nv", "hi/{name}.hi")
        )
        assertTrue(outcome is UnpackOutcome.Success)
        outcome as UnpackOutcome.Success
        assertEquals(2, outcome.filesWritten)
        assertTrue(outcome.skippedEntries.isEmpty())
        assertEquals(setOf("nvram/sf2.nv", "hi/sf2.hi"), sink.written.keys)
    }

    @Test
    fun `FILE_SET unpack skips and reports an entry matching no pattern`() {
        val zip = buildRawZip(
            listOf(
                "Star Ocean.srm" to byteArrayOf(1),
                "Star Ocean.extra" to byteArrayOf(2) // e.g. a member this preset doesn't use
            )
        )
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "Star Ocean", sink,
            fileSetPatterns = listOf("{name}.srm")
        )
        assertTrue(outcome is UnpackOutcome.Success)
        outcome as UnpackOutcome.Success
        assertEquals(1, outcome.filesWritten)
        assertEquals(listOf("Star Ocean.extra"), outcome.skippedEntries)
        assertEquals(setOf("Star Ocean.srm"), sink.written.keys)
    }

    @Test
    fun `FILE_SET unpack refuses when nothing in the archive matches any pattern`() {
        val zip = buildRawZip(listOf("unrelated.bin" to byteArrayOf(1)))
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "Star Ocean", sink,
            fileSetPatterns = listOf("{name}.srm", "{name}.rtc")
        )
        assertTrue(outcome is UnpackOutcome.Refused)
        assertTrue(sink.written.isEmpty())
    }

    @Test
    fun `unpackSaveZip refuses a FILE_SET preset whose patterns collide once resolved`() {
        val zip = buildRawZip(listOf("x.nv" to byteArrayOf(1)))
        try {
            unpackSaveZip(
                zip, SaveShape.FILE_SET, "x", FakeSaveFileSink(),
                fileSetPatterns = listOf("nvram/{name}.nv", "other/{name}.nv")
            )
            fail("expected DuplicateArchiveEntryException")
        } catch (e: DuplicateArchiveEntryException) {
            // expected
        }
    }

    // --- Path traversal / zip-slip guard ---

    @Test
    fun `dot-dot segment at the start of an entry is refused`() {
        val zip = buildRawZip(listOf("../x" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FILE_SET, "id", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `absolute path entry is refused`() {
        val zip = buildRawZip(listOf("/abs" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FILE_SET, "id", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `dot-dot segment buried inside an otherwise normal path is refused`() {
        val zip = buildRawZip(listOf("a/../../x" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "a", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `backslash traversal trick is refused`() {
        val zip = buildRawZip(listOf("a\\..\\x" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FILE_SET, "id", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `traversal in a root directory entry itself is refused`() {
        val zip = buildRawZip(listOf("../evil" to null, "../evil/x" to byteArrayOf(1)))
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "evil", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `a bad entry refuses the whole archive rather than extracting the safe entries around it`() {
        val zip = buildRawZip(
            listOf(
                "ULUS10064/good.bin" to byteArrayOf(1),
                "../evil.bin" to byteArrayOf(2)
            )
        )
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", sink)
        assertTrue(outcome is UnpackOutcome.Refused)
        assertTrue("nothing should have been written", sink.written.isEmpty())
    }

    // --- Zip safety caps ---

    @Test
    fun `an archive exceeding the entry-count cap is refused`() {
        val entries = (1..5).map { "file$it.bin" to byteArrayOf(1) }
        val zip = buildRawZip(entries)
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "id", FakeSaveFileSink(),
            limits = ZipSafetyLimits(maxEntryCount = 3)
        )
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `an archive exceeding the total uncompressed size cap is refused`() {
        val zip = buildRawZip(listOf("big.bin" to ByteArray(10_000)))
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "id", FakeSaveFileSink(),
            limits = ZipSafetyLimits(maxTotalUncompressedBytes = 1_000)
        )
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `an archive within both caps unpacks normally`() {
        val zip = buildRawZip(listOf("small.bin" to byteArrayOf(1, 2, 3)))
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "small", FakeSaveFileSink(),
            fileSetPatterns = listOf("{name}.bin"),
            limits = ZipSafetyLimits(maxEntryCount = 10, maxTotalUncompressedBytes = 1_000)
        )
        assertTrue(outcome is UnpackOutcome.Success)
    }

    // --- Misc refusals ---

    @Test
    fun `an empty archive is refused`() {
        val zip = buildRawZip(emptyList())
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "id", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `an archive holding only directory entries is refused`() {
        val zip = buildRawZip(listOf("ULUS10064" to null))
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `garbage bytes that are not a zip at all are refused, not thrown`() {
        val outcome = unpackSaveZip(byteArrayOf(1, 2, 3, 4), SaveShape.FILE_SET, "id", FakeSaveFileSink())
        assertTrue(outcome is UnpackOutcome.Refused)
    }

    @Test
    fun `unpackSaveZip rejects SINGLE_FILE shape outright`() {
        val zip = buildRawZip(listOf("x.srm" to byteArrayOf(1)))
        try {
            unpackSaveZip(zip, SaveShape.SINGLE_FILE, "id", FakeSaveFileSink())
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- Fixture mirroring Argosy's exact archive layout ---
    //
    // Built by hand to match what argosy-launcher's SaveArchiver actually
    // writes (verified by reading its source, not assumed from §2):
    //   - zipFolder (single root): no entry for the root itself, children
    //     prefixed by its name, nested subdirectories DO get their own
    //     entry (zipFolderRecursive).
    //   - zipNamedFolders (multi-root): each root gets an explicit "name/"
    //     entry before its contents.
    //   - zipFiles (flat FILE_SET bundle): entries named by base filename
    //     alone, no directory prefix, regardless of source layout.

    @Test
    fun `unpacks a fixture matching Argosy's single-root zipFolder layout`() {
        // No "PSPSAVE001/" entry, matching zipFolder's behavior exactly.
        val zip = buildRawZip(
            listOf(
                "PSPSAVE001/DATA.BIN" to byteArrayOf(1, 2),
                "PSPSAVE001/PARAM.SFO" to byteArrayOf(3)
            )
        )
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "PSPSAVE001", sink)
        assertTrue(outcome is UnpackOutcome.Success)
        assertEquals(ArchiveRootMatchTier.EXACT, (outcome as UnpackOutcome.Success).rootMatch)
        assertEquals(setOf("PSPSAVE001/DATA.BIN", "PSPSAVE001/PARAM.SFO"), sink.written.keys)
    }

    @Test
    fun `unpacks a fixture matching Argosy's multi-root zipNamedFolders layout`() {
        val zip = buildRawZip(
            listOf(
                "ULUS10064DATA00" to null,
                "ULUS10064DATA00/DATA.BIN" to byteArrayOf(1),
                "ULUS10064SETTINGS" to null,
                "ULUS10064SETTINGS/OPTIONS.BIN" to byteArrayOf(2)
            )
        )
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(zip, SaveShape.FOLDER, "ULUS10064", sink)
        assertTrue(outcome is UnpackOutcome.Success)
        assertEquals(ArchiveRootMatchTier.PREFIX, (outcome as UnpackOutcome.Success).rootMatch)
        assertEquals(
            setOf("ULUS10064DATA00/DATA.BIN", "ULUS10064SETTINGS/OPTIONS.BIN"),
            sink.written.keys
        )
    }

    @Test
    fun `unpacks a fixture matching Argosy's flat zipFiles FILE_SET layout`() {
        val zip = buildRawZip(
            listOf(
                "Star Ocean.srm" to byteArrayOf(1),
                "Star Ocean.rtc" to byteArrayOf(2)
            )
        )
        val sink = FakeSaveFileSink()
        val outcome = unpackSaveZip(
            zip, SaveShape.FILE_SET, "Star Ocean", sink,
            fileSetPatterns = listOf("{name}.srm", "{name}.rtc")
        )
        assertTrue(outcome is UnpackOutcome.Success)
        assertNull((outcome as UnpackOutcome.Success).rootMatch)
        assertEquals(setOf("Star Ocean.srm", "Star Ocean.rtc"), sink.written.keys)
    }
}
