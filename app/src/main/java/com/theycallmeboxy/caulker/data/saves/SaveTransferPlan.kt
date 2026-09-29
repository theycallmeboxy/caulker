package com.theycallmeboxy.caulker.data.saves

// Upload/download shape decisions for a configured platform's save units
// (save-sync design doc, Part 2 §12 phase 3 wiring). Pure decision logic --
// callers (data/repository, phase 3) do the actual I/O (multipart upload,
// file write), this just decides raw-vs-zip and where bytes land.
//
// Verified against Argosy (argosy-launcher), per this task's instructions:
//  (a) server file_name for a zip upload: SaveSyncApiClient.computeUploadFileName
//      (data/repository/SaveSyncApiClient.kt, companion object) -- called
//      from SaveUploader.kt's uploadSave with localSavePath=null when the
//      prepared file is a zipped "unit bundle" (isUnitBundle, ~SaveUploader.kt
//      line 269-275), which makes computeUploadFileName fall through to its
//      "zip" default extension appended to the ROM base name (or channel/slot
//      name for a named slot): "<romBaseName>.zip". zipUploadFileName below
//      follows this.
//  (b) a FILE_SET unit with only one member present travels RAW, not
//      zipped: UnitSaveHandler.kt's own doc comment ("A unit with one member
//      travels raw through the legacy handler; two or more travel as a flat
//      zip") and its prepareForUpload (~line 47-61): `if (resolved == null
//      || !resolved.isMulti) return fallbackFor(context).prepareForUpload(...)`
//      -- only 2+ present members go through saveArchiver.zipFiles.
//      planSaveUpload's FILE_SET branch mirrors this exactly.
//  (c) mismatch handling on download:
//      (i) raw bytes arrive for what's locally expected to be a multi-file
//          FILE_SET unit: Argosy's UnitSaveHandler.bundleDestinations
//          (~line 85-98) returns null when `!saveArchiver.isZipArchive(tempFile)`
//          (a content, not filename, check -- see isZipBytes in SaveZipMagic.kt, which
//          mirrors SaveArchiver.checkZipMagic ~line 1082-1091's magic-byte
//          test), so UnitSaveHandler.extractBundle also returns null and
//          SaveDownloader.kt's plain-file branch (~line 662-683) falls
//          through to writing the bytes as an ordinary single save file --
//          not an error. Caulker extends this per the task's explicit
//          instruction: rather than a generic single-file fallback, place
//          the raw bytes at whichever of the preset's own FILE_SET patterns
//          has a matching file extension (using the server's reported file
//          name for the extension, the same value Argosy's frontend-agnostic
//          write would have used as the target's basename); no matching
//          pattern refuses instead of guessing.
//      (ii) zip bytes arrive for what's locally expected to be SINGLE_FILE:
//          Argosy has no content-shape check at all on this path for a
//          non-"unit" emulator -- SaveDownloader.kt's plain-file branch
//          (~line 606-683) just writes whatever bytes the server returned
//          to the target path unconditionally. This is the specific case
//          the task calls out as "where Argosy is silent" -- silently
//          writing zip bytes as a raw save would corrupt it, so Caulker
//          refuses instead.
//  Also noted: Argosy's zip *content* detection (isZipArchive/checkZipMagic)
//  is magic-byte based, but its top-level folder-vs-file download dispatch
//  (SaveDownloader.kt line ~213-214, `isFolderBased`) is filename-based
//  (`serverSave.fileName.endsWith(".zip")`) -- the two are used for
//  different decisions in Argosy's code, not interchangeably. Caulker's
//  planSaveDownload below is magic-byte based throughout, since a
//  configured platform's downloads never have a trustworthy filename to
//  begin with (RomM's stored file_name is often just a timestamp tag).

sealed class SaveUploadPlan {
    // SINGLE_FILE always, or a FILE_SET with exactly one member present on
    // disk (§7's "b" finding above). relativePath is the member's path
    // within the configured folder -- also the filename uploaded, matching
    // today's raw single-file upload convention.
    data class Raw(val relativePath: String, val bytes: ByteArray) : SaveUploadPlan()

    // FOLDER always, or a FILE_SET with 2+ members present. fileName is the
    // server file_name to upload (§7's "a" finding above).
    data class Zip(val fileName: String, val bytes: ByteArray) : SaveUploadPlan()
}

// Builds the upload plan for one save unit. Returns null if there's nothing
// uploadable (no entries, or a required file can no longer be read -- e.g.
// removed between scan and upload; the caller should treat this the same as
// "no local save," not as an error).
fun planSaveUpload(
    shape: SaveShape,
    entries: List<LocalSaveEntry>,
    source: SaveFileSource,
    matchingKeyName: String,
    fileSetPatterns: List<String> = emptyList()
): SaveUploadPlan? {
    if (entries.isEmpty()) return null
    return when (shape) {
        SaveShape.SINGLE_FILE -> rawPlanForSingleEntry(entries, source)
        SaveShape.FILE_SET -> {
            val fileEntries = entries.filterNot { it.isDirectory }
            if (fileEntries.size == 1) {
                rawPlanForSingleEntry(fileEntries, source)
            } else {
                zipPlan(shape, entries, source, matchingKeyName, fileSetPatterns)
            }
        }
        SaveShape.FOLDER -> zipPlan(shape, entries, source, matchingKeyName)
    }
}

private fun rawPlanForSingleEntry(entries: List<LocalSaveEntry>, source: SaveFileSource): SaveUploadPlan.Raw? {
    val entry = entries.singleOrNull() ?: return null
    val bytes = source.readFile(entry.relativePath) ?: return null
    return SaveUploadPlan.Raw(entry.relativePath, bytes)
}

private fun zipPlan(
    shape: SaveShape,
    entries: List<LocalSaveEntry>,
    source: SaveFileSource,
    matchingKeyName: String,
    fileSetPatterns: List<String> = emptyList()
): SaveUploadPlan.Zip {
    val bytes = packSaveZip(shape, entries, source, fileSetPatterns, matchingKeyName)
    return SaveUploadPlan.Zip(zipUploadFileName(matchingKeyName), bytes)
}

// See (a) above: "<matching-key name>.zip".
private fun zipUploadFileName(matchingKeyName: String): String = "$matchingKeyName.zip"

// Content-based zip detection: see SaveZipMagic.kt's isZipBytes (mirrors
// Argosy's SaveArchiver.checkZipMagic) -- kept in its own file rather than
// duplicated here so it has one definition shared by every caller.

sealed class SaveDownloadPlacement {
    // Write `bytes` verbatim to this one relative path.
    data class WriteRaw(val relativePath: String) : SaveDownloadPlacement()
    // `bytes` is a zip; unpack via the existing unpackSaveZip (SaveZipUnpacker.kt).
    data class Unpack(val resolvedName: String, val fileSetPatterns: List<String>) : SaveDownloadPlacement()
    // Refuse -- never guess. Surfaced by the caller as a conflict/error, per
    // this task's explicit instruction for the cases isZipBytes's content
    // check disagrees with the configured shape.
    data class Refused(val reason: String) : SaveDownloadPlacement()
}

// Decides how to place downloaded bytes against the locally configured
// preset shape. serverFileName (RomM's reported file_name for the save, if
// any) is only consulted for the FILE_SET raw-arrival mismatch case (c-i
// above) -- everywhere else the decision is content-only (isZipBytes),
// exactly like Argosy's own bundle-vs-raw check.
fun planSaveDownload(
    preset: SavePreset,
    bytes: ByteArray,
    matchingKeyName: String,
    serverFileName: String? = null
): SaveDownloadPlacement {
    val contentIsZip = isZipBytes(bytes)
    return when (preset.shape) {
        SaveShape.SINGLE_FILE -> when {
            contentIsZip -> SaveDownloadPlacement.Refused(
                "downloaded content is a zip archive but this save is configured as SINGLE_FILE " +
                    "(name=\"$matchingKeyName\"); refusing rather than writing zip bytes as the raw save (c-ii)"
            )
            else -> {
                val pattern = preset.patterns.singleOrNull()
                    ?: return SaveDownloadPlacement.Refused(
                        "SINGLE_FILE preset for \"$matchingKeyName\" must declare exactly one pattern"
                    )
                val path = resolveSafeRelativePath(pattern, matchingKeyName)
                    ?: return SaveDownloadPlacement.Refused("unsafe resolved path for name \"$matchingKeyName\"")
                SaveDownloadPlacement.WriteRaw(path)
            }
        }
        SaveShape.FOLDER -> when {
            contentIsZip -> SaveDownloadPlacement.Unpack(matchingKeyName, emptyList())
            else -> SaveDownloadPlacement.Refused(
                "downloaded content is a raw file but this save is configured as FOLDER " +
                    "(name=\"$matchingKeyName\"); a raw file can't be placed as a folder save"
            )
        }
        SaveShape.FILE_SET -> when {
            contentIsZip -> SaveDownloadPlacement.Unpack(matchingKeyName, preset.patterns)
            else -> placeRawFileSetMember(preset.patterns, matchingKeyName, serverFileName)
        }
    }
}

// (c-i) above: places a raw arrival for an expected FILE_SET at whichever
// pattern's resolved extension matches the server's reported file
// extension. No server filename, no pattern with a matching extension, OR
// MORE THAN ONE pattern sharing that extension (e.g. melonDS DS's
// `.public.sav`/`.private.sav`/`.banner.sav` all share `.sav`; RomM also
// renames uploads with a datetime tag, so the filename's own base is never a
// reliable tiebreaker either) all refuse rather than guessing (independent
// review, phase 3A fixes item 4) -- Argosy's own generic single-file
// fallback (see this file's header comment) is not followed here precisely
// because it has no equivalent "which member is this" check at all, and
// guessing wrong would silently misplace a save.
private fun placeRawFileSetMember(
    patterns: List<String>,
    matchingKeyName: String,
    serverFileName: String?
): SaveDownloadPlacement {
    val ext = serverFileName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        ?: return SaveDownloadPlacement.Refused(
            "raw content arrived for a FILE_SET save (name=\"$matchingKeyName\") but no server file " +
                "extension was given to match against a pattern; refusing rather than guessing"
        )
    val matches = patterns.mapNotNull { pattern ->
        resolveSafeRelativePath(pattern, matchingKeyName)?.takeIf { it.substringAfterLast('.', "") == ext }
    }.distinct()
    return when (matches.size) {
        1 -> SaveDownloadPlacement.WriteRaw(matches.single())
        0 -> SaveDownloadPlacement.Refused(
            "raw content (.$ext) arrived for a FILE_SET save (name=\"$matchingKeyName\") but matches none " +
                "of this preset's patterns ($patterns); refusing rather than guessing"
        )
        else -> SaveDownloadPlacement.Refused(
            "raw content (.$ext) arrived for a FILE_SET save (name=\"$matchingKeyName\") but matches more than " +
                "one of this preset's patterns ($matches) -- ambiguous, refusing rather than guessing"
        )
    }
}
