package com.theycallmeboxy.caulker.data.saves

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

// Unpacks a FILE_SET/FOLDER save zip (§2, phase 2 -- §12), verifying it
// really is the save it claims to be before writing anything. See
// SaveZipPacker.kt's file-level comment for the full §2-vs-Argosy writeup;
// the differences that matter here are:
//   - Root-name verification (below) only applies to FOLDER-shaped
//     (directory-rooted) archives -- a flat FILE_SET archive has no root
//     entry to check at all (see unpackFlatArchive).
//   - The tiers themselves (EXACT/PREFIX/CONTAINS) mirror Argosy's
//     FolderSaveHandler.matchArchiveRoot one-for-one, except comparisons
//     here are plain and case-sensitive rather than normalized
//     (dash/underscore-stripped, uppercased) the way Argosy's are -- see
//     matchArchiveRootTier below.
//   - A FILE_SET archive's flat entries are placed by resolving the
//     preset's own patterns against the save's resolved matching-key name
//     (`resolvedName`) -- see SaveZipFileSetPatterns.kt and
//     unpackFlatArchive. `resolvedName` therefore does double duty: for
//     FOLDER it's the id root-verification checks against, for FILE_SET
//     it's the {name} substitution used to resolve `fileSetPatterns`.

// Generous but bounded limits on what unpackSaveZip will extract, guarding
// against a hostile or corrupt archive rather than any real save (§2 "guard
// against zip bombs sensibly"). Argosy's own SaveArchiver has no such caps
// at all (argosy-launcher SaveArchiver.kt) -- there's no existing behavior
// to preserve here, so Caulker adds them. Real save data for every v1 system
// (§9/§10.x) is at most tens of MB (the largest are PSP/PS2 folder saves);
// 512 MiB and 20,000 entries sit far above any real save while still
// refusing an archive engineered to exhaust memory or disk during
// extraction. The byte cap is enforced against actual decompressed bytes as
// they're read, not the (spoofable) size field in an entry's header.
data class ZipSafetyLimits(
    val maxEntryCount: Int = 20_000,
    val maxTotalUncompressedBytes: Long = 512L * 1024 * 1024
) {
    companion object {
        val DEFAULT = ZipSafetyLimits()
    }
}

// How strongly an archive's root entry name corresponds to the save id it's
// being unpacked against (§2's exact/prefix/contains tiers). Ordered
// strongest-first: the ordinal is used to find the *weakest* tier across a
// multi-root archive's roots (see unpackFolderArchive) -- every root in a
// Caulker multi-root archive belongs to the same save (sibling folders of
// one save unit, e.g. PSP's prefix-matched siblings; not a container mixing
// several games' saves the way some of Argosy's per-platform handlers deal
// with), so the archive's overall confidence is only as strong as its
// weakest root, and any root that matches at no tier refuses the whole
// archive rather than partially extracting.
enum class ArchiveRootMatchTier { EXACT, PREFIX, CONTAINS }

sealed class UnpackOutcome {
    // skippedEntries: FILE_SET archive entries that matched none of
    // `fileSetPatterns` (always empty for a FOLDER unpack). Not an error --
    // see unpackFlatArchive -- but reported so a caller can log/surface it.
    data class Success(
        val filesWritten: Int,
        val rootMatch: ArchiveRootMatchTier?,
        val skippedEntries: List<String> = emptyList()
    ) : UnpackOutcome()
    data class Refused(val reason: String) : UnpackOutcome()
}

fun unpackSaveZip(
    zipBytes: ByteArray,
    shape: SaveShape,
    resolvedName: String,
    sink: SaveFileSink,
    fileSetPatterns: List<String> = emptyList(),
    limits: ZipSafetyLimits = ZipSafetyLimits.DEFAULT
): UnpackOutcome {
    require(shape != SaveShape.SINGLE_FILE) { "SINGLE_FILE saves are never zipped (§2)" }
    if (!isSafeNameSegment(resolvedName)) {
        return UnpackOutcome.Refused("resolved name is not a safe name segment: \"$resolvedName\"")
    }

    val readEntries = try {
        readZipEntries(zipBytes, limits)
    } catch (e: ZipSafetyViolation) {
        return UnpackOutcome.Refused(e.message ?: "zip safety limit exceeded")
    } catch (e: Exception) {
        return UnpackOutcome.Refused("not a readable zip: ${e.message}")
    }
    if (readEntries.isEmpty()) return UnpackOutcome.Refused("archive contains no entries")

    // Traversal guard, checked against every raw entry name (directories
    // included) before any of it is trusted for grouping or writing -- a
    // malicious root name is just as dangerous as a malicious file name.
    // Reuses SavePathSafety's existing check (no separate reimplementation):
    // rejects absolute paths, Windows drive paths, and any ".." segment
    // (whichever separator introduces it), i.e. zip-slip and backslash
    // tricks both. One bad entry refuses the whole archive rather than
    // extracting everything else around it (§2).
    for (entry in readEntries) {
        if (!isSafeRelativePath(entry.name)) {
            return UnpackOutcome.Refused("unsafe entry path (traversal or absolute): \"${entry.name}\"")
        }
    }

    val fileEntries = readEntries.filterNot { it.isDirectory }
    if (fileEntries.isEmpty()) return UnpackOutcome.Refused("archive contains no files")

    return when (shape) {
        SaveShape.FOLDER -> unpackFolderArchive(fileEntries, resolvedName, sink)
        SaveShape.FILE_SET -> unpackFlatArchive(fileEntries, resolvedName, fileSetPatterns, sink)
        SaveShape.SINGLE_FILE -> error("unreachable, guarded above")
    }
}

private fun unpackFolderArchive(
    fileEntries: List<ReadZipEntry>,
    resolvedName: String,
    sink: SaveFileSink
): UnpackOutcome {
    val strayEntries = fileEntries.filterNot { it.name.contains('/') }
    if (strayEntries.isNotEmpty()) {
        return UnpackOutcome.Refused(
            "FOLDER archive mixes rooted and flat entries; refusing: ${strayEntries.map { it.name }}"
        )
    }

    val roots = fileEntries.map { it.name.substringBefore('/') }.toSet()
    if (roots.isEmpty()) {
        return UnpackOutcome.Refused("no top-level directory found; a FOLDER save's archive must be rooted")
    }

    var weakestTier = ArchiveRootMatchTier.EXACT
    for (root in roots) {
        val tier = matchArchiveRootTier(root, resolvedName)
            ?: return UnpackOutcome.Refused(
                "root \"$root\" does not match expected save id \"$resolvedName\" at any tier"
            )
        if (tier.ordinal > weakestTier.ordinal) weakestTier = tier
    }

    for (entry in fileEntries) {
        sink.writeFile(entry.name, entry.bytes)
    }
    return UnpackOutcome.Success(fileEntries.size, weakestTier)
}

// A FILE_SET archive is flat -- no root prefix, only bare filenames (see
// SaveZipPacker.kt point 1). Each entry is placed at the relative path of
// whichever preset pattern resolves (for `resolvedName`) to that same
// basename -- e.g. a MAME 2003-Plus bundle's flat "sf2.nv" goes to
// "nvram/sf2.nv" because that's what pattern `nvram/{name}.nv` resolves to
// for name "sf2" (see SaveZipFileSetPatterns.kt). This can throw
// DuplicateArchiveEntryException if `fileSetPatterns` itself is ambiguous
// (two patterns resolving to the same basename) -- same failure, same
// exception type, as packSaveZip's own optional pattern check (see
// SaveZipPacker.kt's planArchive), so a broken preset is refused
// identically at both ends.
//
// An entry whose basename matches no pattern is skipped and reported back
// (UnpackOutcome.Success.skippedEntries) rather than refusing the whole
// unpack -- a FILE_SET bundle (Argosy's or Caulker's own) can carry a member
// the locally configured core/preset doesn't use (e.g. an RTC file for a
// non-RTC cart), and that alone isn't a sign of a wrong-game archive. If
// NOTHING in the archive matches any pattern, though, it doesn't actually
// hold this save's data under any name this preset recognizes, so the whole
// unpack is refused (§2's "no match ... refuses the unpack rather than
// guessing", applied to this shape's placement step instead of a root name).
private fun unpackFlatArchive(
    fileEntries: List<ReadZipEntry>,
    resolvedName: String,
    fileSetPatterns: List<String>,
    sink: SaveFileSink
): UnpackOutcome {
    val destinationByBaseName = resolveFileSetPatterns(fileSetPatterns, resolvedName)

    val skipped = mutableListOf<String>()
    var placed = 0
    for (entry in fileEntries) {
        if (entry.name.contains('/')) {
            return UnpackOutcome.Refused(
                "FILE_SET archive must be flat; found a directory-shaped entry \"${entry.name}\""
            )
        }
        val destination = destinationByBaseName[entry.name]
        if (destination == null) {
            skipped += entry.name
            continue
        }
        sink.writeFile(destination, entry.bytes)
        placed++
    }

    if (placed == 0) {
        return UnpackOutcome.Refused(
            "no archive entry matched any of this save's FILE_SET patterns " +
                "(patterns=$fileSetPatterns, name=\"$resolvedName\", archive entries=${fileEntries.map { it.name }})"
        )
    }
    return UnpackOutcome.Success(placed, rootMatch = null, skippedEntries = skipped)
}

// Root-name verification tiers (§2). Structurally identical to Argosy's
// FolderSaveHandler.matchArchiveRoot (argosy-launcher app/src/main/kotlin/
// com/nendo/argosy/data/sync/platform/FolderSaveHandler.kt): exact equality,
// then "root starts with the expected id" (the PSP-style prefix case -- the
// archive root is the longer, more specific string), then plain substring
// containment, in that order. Deliberately NOT normalized the way Argosy's
// is (Argosy strips "-"/"_" and uppercases both sides first): a Caulker save
// id can be an arbitrary ROM stem rather than a fixed console title-id
// alphabet, and folding case/punctuation there risks two different games'
// stems matching each other -- worth revisiting once phase 3/4 have real
// preset data to check it against, but the safer default until then.
fun matchArchiveRootTier(rootName: String, expectedSaveId: String): ArchiveRootMatchTier? = when {
    rootName == expectedSaveId -> ArchiveRootMatchTier.EXACT
    rootName.startsWith(expectedSaveId) -> ArchiveRootMatchTier.PREFIX
    rootName.contains(expectedSaveId) -> ArchiveRootMatchTier.CONTAINS
    else -> null
}

private class ZipSafetyViolation(message: String) : Exception(message)

private data class ReadZipEntry(val name: String, val isDirectory: Boolean, val bytes: ByteArray)

private fun readZipEntries(zipBytes: ByteArray, limits: ZipSafetyLimits): List<ReadZipEntry> {
    val result = mutableListOf<ReadZipEntry>()
    var totalUncompressed = 0L
    var entryCount = 0
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            entryCount++
            if (entryCount > limits.maxEntryCount) {
                throw ZipSafetyViolation("archive holds more than ${limits.maxEntryCount} entries; refusing")
            }
            if (entry.isDirectory) {
                result += ReadZipEntry(entry.name, isDirectory = true, bytes = ByteArray(0))
            } else {
                val bytes = readBounded(zis, limits, totalUncompressed)
                totalUncompressed += bytes.size
                result += ReadZipEntry(entry.name, isDirectory = false, bytes = bytes)
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return result
}

// Reads one entry's full decompressed content while enforcing the archive-
// wide total-bytes cap against what's actually coming out of the inflater,
// not the entry's own (attacker-controlled) declared size.
private fun readBounded(input: InputStream, limits: ZipSafetyLimits, alreadyRead: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = alreadyRead
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        if (total > limits.maxTotalUncompressedBytes) {
            throw ZipSafetyViolation(
                "archive's total uncompressed size exceeds ${limits.maxTotalUncompressedBytes} bytes; refusing"
            )
        }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
