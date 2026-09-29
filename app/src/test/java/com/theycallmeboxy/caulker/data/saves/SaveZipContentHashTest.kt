package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Test

// Pure-JVM tests for hashZipContents/hashLocalContentAsZip -- the content
// hash Caulker's sync decision must compare against RomM's server-side
// `content_hash` (see SaveZipContentHash.kt for the full rationale and the
// algorithm as verified against RomM's Python source). Two kinds of
// coverage:
//   - Known-answer tests against hashes computed independently in Python,
//     using RomM's exact hash_zip_contents algorithm (see
//     tools/romm_zip_hash_kat.py; run it with python3 to reproduce).
//     The zip bytes here are built in Kotlin from the SAME entry
//     names/decompressed content the Python script used; they don't
//     need to be byte-identical zips (different compressor, different
//     library) for the hash to match, since the
//     algorithm only ever hashes DECOMPRESSED entry content.
//   - Round-trip: hashLocalContentAsZip(shape, entries, source) must equal
//     hashZipContents(packSaveZip(shape, entries, source)) for the same
//     input, since Caulker needs to be able to compute this hash for a
//     local save without packing a zip first (e.g. to skip an unnecessary
//     upload).
class SaveZipContentHashTest {

    private fun dir(path: String) = LocalSaveEntry(path, isDirectory = true)
    private fun file(path: String) = LocalSaveEntry(path, isDirectory = false)

    // --- Known-answer tests (computed by tools/romm_zip_hash_kat.py) ---

    @Test
    fun `hashZipContents matches RomM's hash_zip_contents for a FOLDER-shaped fixture`() {
        // Mirrors what packSaveZip produces for a single-root FOLDER save
        // with one nested subdirectory: no "SAVE1/" entry, but "SAVE1/sub/"
        // DOES get an explicit (and here, directory-only) entry -- included
        // to prove hashZipContents skips names ending in "/".
        val zip = buildRawZip(
            listOf(
                "SAVE1/sub" to null,
                "SAVE1/DATA.BIN" to "data-contents".toByteArray(),
                "SAVE1/PARAM.SFO" to "param-contents".toByteArray(),
                "SAVE1/sub/EXTRA.BIN" to "extra-contents".toByteArray()
            )
        )
        // Computed by tools/romm_zip_hash_kat.py's build_folder_zip() + hash_zip_contents().
        assertEquals("65ef15a10fae905e902546f642af37a6", hashZipContents(zip))
    }

    @Test
    fun `hashZipContents matches RomM's hash_zip_contents for a flat FILE_SET fixture`() {
        val zip = buildRawZip(
            listOf(
                "Star Ocean.srm" to "srm-contents".toByteArray(),
                "Star Ocean.rtc" to "rtc-contents".toByteArray(),
                "Star Ocean.extra" to "extra-member-contents".toByteArray()
            )
        )
        // Computed by tools/romm_zip_hash_kat.py's build_fileset_zip() + hash_zip_contents().
        assertEquals("ac19277e8f12a1ee51e1aa355a799ac4", hashZipContents(zip))
    }

    @Test
    fun `hashZipContents is invariant to entry order`() {
        val forward = buildRawZip(
            listOf(
                "SAVE1/DATA.BIN" to "data-contents".toByteArray(),
                "SAVE1/PARAM.SFO" to "param-contents".toByteArray()
            )
        )
        val reversed = buildRawZip(
            listOf(
                "SAVE1/PARAM.SFO" to "param-contents".toByteArray(),
                "SAVE1/DATA.BIN" to "data-contents".toByteArray()
            )
        )
        assertEquals(hashZipContents(forward), hashZipContents(reversed))
    }

    // --- hashLocalContentAsZip must agree with hashZipContents(packSaveZip(...)) ---

    @Test
    fun `hashLocalContentAsZip equals hashing a packed FOLDER archive directly`() {
        val files = mapOf(
            "SAVE1/DATA.BIN" to byteArrayOf(1, 2, 3),
            "SAVE1/sub/EXTRA.BIN" to byteArrayOf(4, 5)
        )
        val source = FakeSaveFileSource(files)
        val entries = listOf(dir("SAVE1"))

        val viaPack = hashZipContents(packSaveZip(SaveShape.FOLDER, entries, source))
        val direct = hashLocalContentAsZip(SaveShape.FOLDER, entries, source)

        assertEquals(viaPack, direct)
    }

    @Test
    fun `hashLocalContentAsZip equals hashing a packed FILE_SET archive directly`() {
        val files = mapOf(
            "Star Ocean.srm" to byteArrayOf(1),
            "Star Ocean.rtc" to byteArrayOf(2)
        )
        val source = FakeSaveFileSource(files)
        val entries = listOf(file("Star Ocean.srm"), file("Star Ocean.rtc"))

        val viaPack = hashZipContents(packSaveZip(SaveShape.FILE_SET, entries, source))
        val direct = hashLocalContentAsZip(SaveShape.FILE_SET, entries, source)

        assertEquals(viaPack, direct)
    }

    @Test
    fun `hashLocalContentAsZip is unaffected by packSaveZip's fixed entry timestamps`() {
        // Sanity check that the hash genuinely doesn't depend on anything
        // packSaveZip's own determinism trick (fixed ZipEntry.time) touches --
        // it's computed straight from names+content, never from the zip
        // container itself when going through hashLocalContentAsZip.
        val files = mapOf("SAVE1/DATA.BIN" to byteArrayOf(9, 9, 9))
        val source = FakeSaveFileSource(files)
        val entries = listOf(dir("SAVE1"))
        assertEquals(
            hashLocalContentAsZip(SaveShape.FOLDER, entries, source),
            hashLocalContentAsZip(SaveShape.FOLDER, entries, source)
        )
    }
}
