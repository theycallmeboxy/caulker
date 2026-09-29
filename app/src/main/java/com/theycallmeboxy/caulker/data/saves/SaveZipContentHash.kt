package com.theycallmeboxy.caulker.data.saves

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// Content hash matching RomM 5.3.1's own `hash_zip_contents` (backend/
// handler/filesystem/assets_handler.py) exactly. Caulker's sync decision
// (determineSyncAction / SyncBaseline, data/sync/SyncAction.kt) compares its
// locally-computed hash against the server's `content_hash` field for a
// save -- for a zip-shaped save (FILE_SET/FOLDER) that comparison is only
// meaningful if both sides compute the SAME hash, which is NOT a hash of the
// zip's raw bytes (see SaveZipPacker.kt point 4: neither Caulker's nor
// Argosy's zip bytes are byte-identical to RomM's own repacking, and
// wouldn't need to be for this scheme to work). Argosy's own
// SaveArchiver.calculateZipHash/calculateContentHash implements the
// identical algorithm client-side, for the same reason.
//
// Algorithm (verified against RomM's Python source):
//   1. Walk entries in ascending full-entry-name order (RomM's
//      `sorted(zf.namelist())`), skipping any name ending in "/".
//   2. For each file entry, compute the lowercase-hex MD5 of its
//      DECOMPRESSED bytes.
//   3. Join "$name:$md5" lines with "\n" (no trailing newline).
//   4. Return the lowercase-hex MD5 of that joined string, UTF-8 encoded.
//
// Sort-order note: Python's `sorted()` compares strings by Unicode code
// point; Kotlin/JVM `String` comparison (the `Comparable` used by
// `sortedBy`) compares by UTF-16 code unit. These two orders only diverge
// for names containing characters outside the Basic Multilingual Plane
// (a code point there is a surrogate *pair* in UTF-16, which can sort
// differently than the single code point does) -- no v1 save filename is
// expected to contain one, so this is left unhandled rather than adding a
// code-point-aware comparator here.
fun hashZipContents(zipBytes: ByteArray): String {
    val entries = mutableListOf<Pair<String, String>>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.name.endsWith("/")) {
                entries += entry.name to md5Hex(zis.readBytes())
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return hashEntryList(entries)
}

// The same value hashZipContents(packSaveZip(shape, entries, source)) would
// return, computed directly from local files without packing a zip at all --
// lets Caulker compare against RomM's server-side hash (or decide whether an
// upload is even necessary) before doing any zip work. Entry names come from
// the exact same planArchive shape-dispatch packSaveZip itself uses (see
// SaveZipPacker.kt), so the two can never silently disagree about what a
// save's entry names are.
fun hashLocalContentAsZip(
    shape: SaveShape,
    entries: List<LocalSaveEntry>,
    source: SaveFileSource,
    fileSetPatterns: List<String> = emptyList(),
    fileSetResolvedName: String? = null
): String {
    val plan = planArchive(shape, entries, source, fileSetPatterns, fileSetResolvedName)
    return hashEntryList(plan.files.map { it.entryName to md5Hex(it.bytes) })
}

private fun hashEntryList(entries: List<Pair<String, String>>): String =
    md5Hex(
        entries.sortedBy { it.first }
            .joinToString("\n") { (name, md5) -> "$name:$md5" }
            .toByteArray(Charsets.UTF_8)
    )

private fun md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
