package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.saves.LocalSaveEntry
import com.theycallmeboxy.caulker.data.saves.SaveFolderScanner
import com.theycallmeboxy.caulker.data.saves.isBackupPath
import com.theycallmeboxy.caulker.data.util.RootFileHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

// Real Android-backed SaveFolderScanner (save-sync design doc, Part 2 §12
// phase 3): root-aware via RootFileHelper (Part 1, unchanged -- v1 adds no
// new root-tier expansion, §1). A scan snapshots the folder's listing and
// mtimes only; file bytes are read lazily, on first readFile(), and cached
// for the rest of the scan. Only files that belong to a matched save unit are
// ever read (for hashing or upload) -- a configured folder can hold far more
// than saves (e.g. the PPSSPP core's whole PSP/ tree, or a folder the user
// picked too broadly), and neither Unassigned files nor backups should cost
// memory or be able to fail the scan. The pure SaveFolderScanner interface
// (data/saves/SaveLocationResolver.kt) stays synchronous and Android-free, so
// readFile bridges to RootFileHelper's suspend read with runBlocking; every
// caller runs on Dispatchers.IO -- SaveLocationRepository's public functions
// (scan/unitFor/unitsFor/upload/download) all wrap themselves in
// withContext(Dispatchers.IO), so this is genuinely true, not just assumed
// (independent review round 2, item 3 -- this comment was inaccurate before
// that wiring existed). readFile also checks the calling scan's own Job
// (callerJob) before starting a new read, so a cancelled sync doesn't keep
// reading files it no longer needs.
class AndroidSaveFolderScanner private constructor(
    private val rootHelper: RootFileHelper,
    private val folderPath: String,
    private val entries: List<LocalSaveEntry>,
    private val mtimes: Map<String, Long>,
    // The Job active when snapshot() was called -- captured so readFile()
    // (a plain synchronous function, not itself suspend) can still notice
    // the calling sync was cancelled and refuse to start another read
    // (independent review round 2, item 3: "make sure a cancelled sync
    // doesn't start new reads"). Null in a context with no Job (e.g. a
    // caller that isn't itself a coroutine), in which case this check is
    // simply skipped.
    private val callerJob: Job?
) : SaveFolderScanner {

    private val fileBytes = HashMap<String, ByteArray>()
    private val fileRelPaths = entries.filterNot { it.isDirectory }.mapTo(HashSet()) { it.relativePath }

    // An unreadable file THROWS (wrapped with its path) rather than returning
    // null: a unit with an unreadable member must surface as a real sync error
    // for that ROM, never as a silently-missing "no local save" (independent
    // review, phase 3A item 6).
    override fun readFile(relativePath: String): ByteArray? {
        if (relativePath !in fileRelPaths) return null
        synchronized(fileBytes) { fileBytes[relativePath]?.let { return it } }
        callerJob?.ensureActive() // don't start a new read once the sync itself has been cancelled
        val bytes = try {
            runBlocking { rootHelper.readBytes("$folderPath/$relativePath") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SaveFolderScanException("Cannot read \"$relativePath\" under \"$folderPath\": ${e.message}", e)
        }
        synchronized(fileBytes) { fileBytes[relativePath] = bytes }
        return bytes
    }

    override fun listChildren(relativePath: String): List<LocalSaveEntry> {
        val prefix = if (relativePath.isEmpty()) "" else "$relativePath/"
        return entries.filter { entry ->
            entry.relativePath != relativePath &&
                entry.relativePath.startsWith(prefix) &&
                !entry.relativePath.removePrefix(prefix).contains('/')
        }
    }

    override fun listAllEntries(): List<LocalSaveEntry> = entries

    override fun lastModifiedMs(relativePath: String): Long = mtimes[relativePath] ?: 0L

    companion object {
        // Lists the folder once (entries under a `.caulker_backup` directory
        // excluded, so backups are never matched, hashed or read -- independent
        // review, phase 3A blocker 2) and records mtimes. No file contents are
        // read here; see readFile.
        suspend fun snapshot(rootHelper: RootFileHelper, folderPath: String): AndroidSaveFolderScanner {
            val listed = rootHelper.listRecursive(folderPath).filterNot { (rel, _) -> isBackupPath(rel) }
            val entries = listed.map { (rel, isDir) -> LocalSaveEntry(rel, isDir) }
            val mtimes = HashMap<String, Long>()
            // TODO(phase-3B): one lastModifiedMs call per entry means one `su`
            // invocation per file on a root-only folder -- independent review
            // round 2, item 6 (left as a TODO, not fixed here). A batched
            // `stat` over the whole tree in one shell call would remove this.
            for ((rel, _) in listed) {
                mtimes[rel] = rootHelper.lastModifiedMs("$folderPath/$rel")
            }
            return AndroidSaveFolderScanner(rootHelper, folderPath, entries, mtimes, currentCoroutineContext()[Job])
        }
    }
}

// Thrown by AndroidSaveFolderScanner.readFile when a file it listed can't be
// read -- callers (SaveLocationRepository.scan/unitFor) must let this
// propagate as a real error, never catch-and-fall-back-to-legacy or
// catch-and-treat-as-"no local save" (independent review, phase 3A fixes
// item 6).
class SaveFolderScanException(message: String, cause: Throwable) : Exception(message, cause)
