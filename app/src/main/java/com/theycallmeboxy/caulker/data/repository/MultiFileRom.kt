package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.saves.isSafeRelativePath

// Pure helpers for multi-file (multi-disc) ROM downloads -- kept separate
// from RomRepository so the playlist rule and the path-safety computation
// are unit-testable without Retrofit/Room/Context.

// Sheet formats that describe a disc by pointing at raw track files: .cue for
// BIN/CUE, .gdi for Dreamcast, .ccd for CloneCD and .mds for Alcohol. The raw
// tracks those sheets point at, data and CDDA audio alike, aren't loadable on
// their own. Kept in step with RomM's utils/m3u.py DESCRIPTOR_EXTENSIONS /
// COMPANION_EXTENSIONS -- the server is the source of truth for what counts
// as a disc, since it also decides this when a bare-file download needs a
// generated .m3u (no_op when the rom already has one of its own).
private val DESCRIPTOR_EXTENSIONS = setOf("cue", "gdi", "ccd", "mds")
private val COMPANION_EXTENSIONS = setOf(
    "bin", "raw", "img", "sub", "mdf", "wav", "ogg", "flac", "mp3"
)

private fun extensionOf(fileName: String): String =
    fileName.substringAfterLast('.', "").lowercase()

fun isM3uFileName(fileName: String): Boolean = extensionOf(fileName) == "m3u"

// One file of a multi-file ROM's download set. `fileName` is the server's
// leaf name -- used for sorting and extension checks, matching how RomM
// itself decides playlist membership. `downloadPath` is the path Caulker
// actually wrote (or would write) the file to, relative to the platform's
// ROM directory -- see resolveDownloadRelativePath.
data class PlaylistCandidate(val fileName: String, val downloadPath: String)

// Which of a multi-file ROM's files belong on the .m3u playlist, and in what
// order. Mirrors RomM's utils/m3u.py playlist_files()/generate_m3u_content()
// exactly, so a playlist Caulker writes itself matches what the server would
// have generated:
//  - entries are sorted by file_name first (the server sorts `rom.files` this
//    way before computing the playlist)
//  - the .m3u file itself is never an entry
//  - when a descriptor sheet (cue/gdi/ccd/mds) is present among the discs,
//    its companion raw-track files are dropped too -- the sheet is the
//    loadable entry point, the raw tracks it references are not loadable
//    alone. Without a descriptor present, every non-m3u file is kept as-is
//    (e.g. a set of standalone .chd discs, or -- the edge case -- a set of
//    bare .bin files with no .cue alongside them).
fun playlistLines(candidates: List<PlaylistCandidate>): List<String> {
    val discs = candidates.sortedBy { it.fileName }.filter { !isM3uFileName(it.fileName) }
    val hasDescriptor = discs.any { extensionOf(it.fileName) in DESCRIPTOR_EXTENSIONS }
    val kept = if (!hasDescriptor) discs
        else discs.filter { extensionOf(it.fileName) !in COMPANION_EXTENSIONS }
    return kept.map { it.downloadPath }
}

// The .m3u playlist's full text content, one entry per line.
fun buildM3uContent(candidates: List<PlaylistCandidate>): String {
    val lines = playlistLines(candidates)
    return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}

// Where a multi-file ROM's individual file should be read/written locally,
// relative to the platform's ROM directory. Mirrors RomM's
// RomFile.file_name_for_download: the file's server `full_path` with the
// rom's own `"<rom full_path>/"` prefix stripped, so a file nested in a
// subfolder below the rom's own folder keeps its subpath. When the file
// isn't nested under the rom's full_path -- the common flat multi-disc
// layout, where the disc files sit directly alongside each other with no
// subfolder -- or either full_path is missing (an older server's detail
// response), this falls back to the file's own name: today's flat placement.
//
// Returns null when the resolved path would escape the platform's ROM
// directory (absolute, or containing a ".." segment) -- callers must fail
// the download/delete for that file rather than read/write outside the dir.
fun resolveDownloadRelativePath(
    romFullPath: String?,
    fileFullPath: String?,
    fileName: String
): String? {
    val prefix = if (romFullPath != null) "$romFullPath/" else null
    val candidate = if (prefix != null && fileFullPath != null && fileFullPath.startsWith(prefix)) {
        fileFullPath.removePrefix(prefix)
    } else {
        fileName
    }
    return candidate.takeIf { isSafeRelativePath(it) }
}
