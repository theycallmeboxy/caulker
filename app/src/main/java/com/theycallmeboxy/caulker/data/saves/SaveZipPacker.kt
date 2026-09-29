package com.theycallmeboxy.caulker.data.saves

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Zip pack/unpack for FILE_SET/FOLDER saves (save-sync design doc, Part 2 §2,
// phase 2 -- §12). Compatibility with other RomM clients' zips is the point,
// so this was built and verified against Argosy's actual archiver
// (argosy-launcher app/src/main/kotlin/com/nendo/argosy/data/sync/
// SaveArchiver.kt, PlatformSaveHandlerRegistry.kt, UnitSaveHandler.kt,
// GciSaveHandler.kt, FolderSaveHandler.kt), not just §2's prose. Where the
// two disagreed, this follows Argosy's real behavior per the phase 2
// instructions (the design doc itself is unchanged; this comment is the
// record of what was found):
//
//  1. FILE_SET archives are FLAT, not single-rooted. §2 describes "a
//     FILE_SET that collapses to one save unit" as a single-root archive
//     named after the save id. Argosy's actual FILE_SET bundles
//     (UnitSaveHandler.prepareForUpload, GciSaveHandler.createBundle) both
//     go through SaveArchiver.zipFiles, which writes every member under
//     `ZipEntry(file.name)` alone -- no directory prefix at all, even when
//     the source files live in different directories on disk (e.g. a
//     hypothetical `nvram/{name}.nv` + `hi/{name}.hi` pair would still be
//     zipped as bare "{name}.nv" + "{name}.hi", not "nvram/..."/"hi/...").
//     packSaveZip follows this for SaveShape.FILE_SET.
//  2. Root-name verification does not apply to flat FILE_SET archives, for
//     the structural reason above: there is no root entry to check. Argosy
//     doesn't attempt it either -- UnitSaveHandler.extractBundle places
//     members by content/filename matching (SaveUnitResolver.placeBundle),
//     and GciSaveHandler.extractSingleGci checks the embedded GameCube
//     header's own game id, neither of which is a zip-level check. Caulker's
//     unpack instead places each flat entry by resolving the preset's own
//     patterns against the save's matching-key name (see
//     SaveZipFileSetPatterns.kt / SaveZipUnpacker.kt) -- an entry matching no
//     pattern is skipped and reported rather than refusing the archive,
//     since a bundle (Argosy's or Caulker's own) can carry a member the
//     locally configured core/preset doesn't use.
//  3. The exact/prefix/contains tiers ARE exactly what §2 describes, and
//     match Argosy's FolderSaveHandler.ArchiveRootMatch/matchArchiveRoot
//     one-for-one in structure -- confirmed for FOLDER-shaped (single- and
//     multi-root directory) archives. One deliberate difference: Argosy
//     normalizes both strings (strip "-"/"_", uppercase) before comparing;
//     this does not, since a Caulker save id can be an arbitrary ROM stem
//     rather than a fixed console title-id alphabet, and folding
//     case/punctuation there risks two different games' stems matching each
//     other. See SaveZipUnpacker.kt.
//  4. Argosy's zips are NOT byte-deterministic: every packed entry gets
//     `ZipArchiveEntry`'s default timestamp, which is the wall-clock time of
//     the pack, not anything derived from the source files. Argosy works
//     around this for its own change detection by never hashing raw zip
//     bytes -- SaveArchiver.calculateZipHash/calculateContentHash instead
//     read each entry's own bytes and hash a sorted list of
//     (entryName, MD5-of-that-entry's-content) pairs, which is invariant
//     under timestamp noise. RomM's server does the same thing on its own
//     side (backend/handler/filesystem/assets_handler.py, hash_zip_contents)
//     for the `content_hash` Caulker's sync decision (determineSyncAction)
//     has to compare against -- see SaveZipContentHash.kt, which implements
//     that exact algorithm. IMPORTANT: sync correctness depends on
//     SaveZipContentHash.kt, not on packSaveZip's output being
//     byte-identical across runs -- a raw-byte diff was never going to match
//     RomM's own hash anyway, since RomM re-hashes decompressed entry
//     content, not zip bytes. packSaveZip still writes a fixed
//     FIXED_ENTRY_TIME_MILLIS timestamp on every entry (below) and a stable
//     sorted entry order, which is a harmless, free property (repeated packs
//     of identical input produce identical bytes, useful for e.g. a naive
//     diff or cache key) but is NOT what sync relies on for change
//     detection.
//  5. Compression: standard DEFLATE, matching §2 ("Java's built-in zip
//     writer produces a compatible file") -- confirmed, Argosy's
//     ZipArchiveOutputStream uses the same default method Commons Compress
//     and java.util.zip.ZipOutputStream share.
//  6. No manifest entry, ever: confirmed, nothing in SaveArchiver's write
//     paths (zipFolder/zipFolders/zipNamedFolders/zipFiles) writes a sidecar
//     entry of any kind.
//  7. Empty-result refusal: confirmed, every one of Argosy's zip* methods
//     checks filesWritten == 0 and deletes the (already-created) output
//     file rather than returning an empty or root-only zip.

// Thrown when packSaveZip has nothing to write for the given entries -- the
// caller must not upload an empty or root-only zip (§2). Argosy's
// zipFolder/zipFolders/zipFiles all refuse the same way, via a filesWritten
// == 0 check that returns false; this throws instead, so a caller can't
// silently ignore a Boolean the way a stray `!!`/`?:` could.
class EmptySaveArchiveException(message: String) : Exception(message)

// Thrown when a FILE_SET's members, once flattened to their own base
// filenames (see the file-level comment above), collide -- two source files
// from different directories sharing one filename can't both be represented
// in a flat archive. A malformed preset (patterns that don't disambiguate
// members) is the only realistic cause; Argosy has the identical limitation
// since it flattens the same way (zipFiles).
class DuplicateArchiveEntryException(message: String) : Exception(message)

// Fixed per-entry modification time: 1980-01-01T00:00:00, the zip/DOS
// format's own epoch (ZipEntry's DOS-date conversion isn't reliably defined
// for times before it across JDKs). Every entry packSaveZip writes gets this
// same value instead of the real pack wall-clock time. This is a nice-to-
// have (repeated packs of identical input produce identical zip bytes) --
// NOT the mechanism sync correctness depends on; that's SaveZipContentHash.kt
// (point 4 above), which is invariant to timestamps and entry order by
// construction and is kept regardless of this.
private const val FIXED_ENTRY_TIME_MILLIS = 315_532_800_000L

internal data class ZipFileEntry(val entryName: String, val bytes: ByteArray)
internal data class ArchivePlan(val explicitDirectories: List<String>, val files: List<ZipFileEntry>)

// Shape-dispatch + validation shared by packSaveZip and
// SaveZipContentHash.kt's hashLocalContentAsZip, so the two can never
// disagree about what a save's entry names/bytes are. SINGLE_FILE is never
// zipped -- it uploads raw, unchanged from today's behavior (§2) -- so this
// rejects that shape outright. When `fileSetPatterns` is given (FILE_SET
// only), the patterns are validated against `fileSetResolvedName` up front
// via resolveFileSetPatterns -- see SaveZipFileSetPatterns.kt -- so a preset
// whose patterns collide once resolved is refused at pack time too, not just
// discovered later on unpack.
internal fun planArchive(
    shape: SaveShape,
    entries: List<LocalSaveEntry>,
    source: SaveFileSource,
    fileSetPatterns: List<String> = emptyList(),
    fileSetResolvedName: String? = null
): ArchivePlan {
    require(shape != SaveShape.SINGLE_FILE) {
        "SINGLE_FILE saves upload raw and are never zipped (§2); this is for FILE_SET/FOLDER only"
    }
    if (shape == SaveShape.FILE_SET && fileSetPatterns.isNotEmpty()) {
        val name = requireNotNull(fileSetResolvedName) {
            "fileSetResolvedName is required when fileSetPatterns is passed"
        }
        resolveFileSetPatterns(fileSetPatterns, name) // validates only (throws on collision); map unused here
    }

    val plan = when (shape) {
        SaveShape.FOLDER -> planFolderArchive(entries, source)
        SaveShape.FILE_SET -> planFlatArchive(entries, source)
        SaveShape.SINGLE_FILE -> error("unreachable, guarded above")
    }
    if (plan.files.isEmpty()) {
        throw EmptySaveArchiveException(
            "No files to archive for shape=$shape, entries=${entries.map { it.relativePath }}"
        )
    }
    return plan
}

// Packs one save's matched entries (SaveMatcher's per-game entry list) into
// zip bytes. See planArchive for SINGLE_FILE rejection, empty-result
// refusal, and the optional FILE_SET pattern-collision check.
fun packSaveZip(
    shape: SaveShape,
    entries: List<LocalSaveEntry>,
    source: SaveFileSource,
    fileSetPatterns: List<String> = emptyList(),
    fileSetResolvedName: String? = null
): ByteArray {
    val plan = planArchive(shape, entries, source, fileSetPatterns, fileSetResolvedName)

    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zos ->
        // Explicit directory entries (multi-root FOLDER archives only) go
        // first, sorted, then files -- a stable order so identical content
        // always packs to identical bytes regardless of scan/HashMap order.
        for (dirName in plan.explicitDirectories.sorted()) {
            zos.putNextEntry(ZipEntry("$dirName/").apply { time = FIXED_ENTRY_TIME_MILLIS })
            zos.closeEntry()
        }
        for (file in plan.files.sortedBy { it.entryName }) {
            zos.putNextEntry(ZipEntry(file.entryName).apply { time = FIXED_ENTRY_TIME_MILLIS })
            zos.write(file.bytes)
            zos.closeEntry()
        }
    }
    return buffer.toByteArray()
}

// FOLDER shape: entries are the save's root directory(ies) (matched by
// SaveMatcher against a folder-shaped preset, so every entry here is
// expected to be a directory). One root -> no explicit directory entry for
// THAT root, just an implicit path prefix on every child (mirrors
// SaveArchiver.zipFolder, which starts recursion at the folder's own name
// without writing an entry for it first). More than one root -> each root
// DOES get its own explicit "name/" entry before its contents (mirrors
// SaveArchiver.zipNamedFolders/zipFolders). Either way, every directory
// found *below* a root -- any nested subdirectory, at any depth -- gets its
// own explicit entry regardless of root count; only the single top-level
// root is special-cased to have none. This mirrors zipFolderRecursive, which
// writes a directory entry for every directory it recurses into.
private fun planFolderArchive(entries: List<LocalSaveEntry>, source: SaveFileSource): ArchivePlan {
    val roots = entries.filter { it.isDirectory }
    val multiRoot = roots.size > 1
    val explicitDirs = mutableListOf<String>()
    val files = mutableListOf<ZipFileEntry>()
    for (root in roots) {
        val rootName = root.relativePath.substringAfterLast('/')
        if (multiRoot) explicitDirs += rootName
        collectFolderFiles(root.relativePath, rootName, source, files, explicitDirs)
    }
    return ArchivePlan(explicitDirs, files)
}

private fun collectFolderFiles(
    sourcePath: String,
    entryPrefix: String,
    source: SaveFileSource,
    files: MutableList<ZipFileEntry>,
    explicitDirs: MutableList<String>
) {
    for (child in source.listChildren(sourcePath)) {
        val childEntryName = "$entryPrefix/${child.relativePath.substringAfterLast('/')}"
        if (child.isDirectory) {
            explicitDirs += childEntryName
            collectFolderFiles(child.relativePath, childEntryName, source, files, explicitDirs)
        } else {
            val bytes = source.readFile(child.relativePath) ?: continue
            files += ZipFileEntry(childEntryName, bytes)
        }
    }
}

// FILE_SET shape: entries are the save's matched files (never directories --
// a FILE_SET preset's patterns only ever resolve to files, per SaveMatcher).
// Flattened to bare filenames with no directory prefix at all, per point 1
// of the file-level comment above -- this mirrors Argosy's zipFiles exactly,
// including its limitation: two members that share a base filename (only
// possible if they come from different subdirectories, e.g. a badly
// authored preset) can't both be represented in a flat archive.
private fun planFlatArchive(entries: List<LocalSaveEntry>, source: SaveFileSource): ArchivePlan {
    val files = mutableListOf<ZipFileEntry>()
    val seenNames = mutableSetOf<String>()
    for (entry in entries) {
        if (entry.isDirectory) continue // shouldn't occur for FILE_SET; defensive only
        val baseName = entry.relativePath.substringAfterLast('/')
        if (!seenNames.add(baseName)) {
            throw DuplicateArchiveEntryException(
                "FILE_SET members collide once flattened to a base filename: \"$baseName\" " +
                    "(from \"${entry.relativePath}\")"
            )
        }
        val bytes = source.readFile(entry.relativePath) ?: continue
        files += ZipFileEntry(baseName, bytes)
    }
    return ArchivePlan(emptyList(), files)
}
