package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.saves.BACKUP_DIR_NAME
import com.theycallmeboxy.caulker.data.saves.LocalSaveUnit
import com.theycallmeboxy.caulker.data.saves.SaveDownloadPlacement
import com.theycallmeboxy.caulker.data.saves.SaveFileSink
import com.theycallmeboxy.caulker.data.saves.SaveShape
import com.theycallmeboxy.caulker.data.saves.UnpackOutcome
import com.theycallmeboxy.caulker.data.saves.hashZipContents
import com.theycallmeboxy.caulker.data.saves.isBackupPath
import com.theycallmeboxy.caulker.data.saves.planSaveDownload
import com.theycallmeboxy.caulker.data.saves.unpackSaveZip
import com.theycallmeboxy.caulker.data.util.RootFileHelper
import com.theycallmeboxy.caulker.data.util.md5Hex
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// The local-disk half of a configured-platform download: backup, write,
// stale-member cleanup, and partial-write rollback (save-sync design doc,
// Part 2 §12 phase 3; independent review's phase 3A fixes, blockers 1/2 and
// items 3/8). Deliberately depends on ONLY RootFileHelper -- no
// SaveRepository/network dependency at all -- so it's directly
// unit-testable against a real RootFileHelper over a temp directory
// (ConfiguredSaveWriterTest) without mocking the HTTP layer. SaveLocationRepository
// is the thin orchestration layer that fetches bytes over the network and
// hands them to this class.
@Singleton
class ConfiguredSaveWriter @Inject constructor(
    private val rootHelper: RootFileHelper
) {
    // Outcome of applying one downloaded save's bytes. success = false means
    // NOTHING was written to the save's own location -- a refusal (format
    // mismatch, unsafe path) writes nothing to begin with, and a write that
    // fails partway through a multi-member unpack is rolled back from the
    // backup just taken before this returns (item 8) -- so callers must
    // never record a sync baseline when success is false (blocker 1: this
    // used to be reported as a successful download). guardWarning is true
    // when the download guard (§7, DOWNLOAD_GUARD_ENABLED) skipped the
    // download outright -- set by SaveLocationRepository, never by this
    // class. writtenRelativePaths is the unit's member paths actually on
    // disk after a successful call. writtenContentHash is the hash of the
    // content actually written -- md5Hex of the raw bytes for a WriteRaw, or
    // hashZipContents of the downloaded zip for an Unpack (independent
    // review round 2, item 2): the server's own content_hash should be used
    // for the baseline whenever it's present, but the FALLBACK when it isn't
    // must be this, never a stale pre-download hash (previousUnit's), which
    // would record a baseline that doesn't describe what's actually on disk
    // now.
    data class DownloadOutcome(
        val success: Boolean,
        val reason: String? = null,
        val guardWarning: Boolean = false,
        val writtenRelativePaths: List<String> = emptyList(),
        val writtenContentHash: String? = null
    )

    // Applies one downloaded save's bytes against `configured`'s folder:
    // decides raw-vs-zip placement (SaveTransferPlan.kt's planSaveDownload),
    // backs up every member about to be overwritten or removed first (§6 --
    // see backupMember below for where those backups actually land, fixing
    // blocker 2), and removes any of `previousUnit`'s own matched members
    // the new content doesn't include (item 3). `previousUnit` is the unit
    // this ROM matched to BEFORE this download, if any -- pass null for
    // "nothing local yet" (first download); passing a stale/wrong unit only
    // affects which extra files get cleaned up, never what's written.
    suspend fun apply(
        configured: ConfiguredPlatform,
        matchingKeyName: String,
        bytes: ByteArray,
        serverFileName: String?,
        previousUnit: LocalSaveUnit?
    ): DownloadOutcome {
        val placement = planSaveDownload(configured.preset, bytes, matchingKeyName, serverFileName)
        return when (placement) {
            is SaveDownloadPlacement.WriteRaw -> writeRawWithBackup(configured, placement.relativePath, bytes, previousUnit)
            is SaveDownloadPlacement.Unpack -> unpackWithBackup(configured, placement, bytes, previousUnit)
            // Refused (format mismatch, unsafe path, ambiguous FILE_SET
            // member) -- nothing written, no baseline (blocker 1).
            is SaveDownloadPlacement.Refused -> DownloadOutcome(success = false, reason = placement.reason)
        }
    }

    private suspend fun writeRawWithBackup(
        configured: ConfiguredPlatform,
        relativePath: String,
        bytes: ByteArray,
        previousUnit: LocalSaveUnit?
    ): DownloadOutcome {
        val backup = backupMember(configured.folderPath, relativePath)
        try {
            rootHelper.writeBytes("${configured.folderPath}/$relativePath", bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val restoreFailure = restoreAll(configured.folderPath, listOf(backup))
            val reason = "write failed, restored previous content: ${e.message}" +
                (restoreFailure?.let { " (restore also failed: $it)" } ?: "")
            return DownloadOutcome(success = false, reason = reason)
        }
        removeStaleMembers(configured, previousUnit, keep = setOf(relativePath))
        return DownloadOutcome(success = true, writtenRelativePaths = listOf(relativePath), writtenContentHash = md5Hex(bytes))
    }

    // unpackSaveZip's SaveFileSink contract is synchronous (SaveZipIo.kt), so
    // this collects its planned writes into memory first via a throwaway
    // sink (verifying the archive itself is valid -- root match, traversal,
    // size caps -- before touching disk at all), then backs up + persists
    // each destination through the suspend-based RootFileHelper. If any
    // individual write fails partway through, every member already written
    // in this call is restored from the backup just taken for it (item 8)
    // before returning failure -- a partial multi-member unpack is never
    // left on disk.
    private suspend fun unpackWithBackup(
        configured: ConfiguredPlatform,
        placement: SaveDownloadPlacement.Unpack,
        zipBytes: ByteArray,
        previousUnit: LocalSaveUnit?
    ): DownloadOutcome {
        val collecting = CollectingSaveFileSink()
        val outcome = unpackSaveZip(
            zipBytes, configured.preset.shape, placement.resolvedName, collecting, placement.fileSetPatterns
        )
        // A refusal (root-name mismatch, path traversal, size-cap violation,
        // no matching FILE_SET member) means the archive itself was never
        // trusted -- nothing was written, and callers must not record a
        // baseline (blocker 1: this was previously reported as success).
        val success = outcome as? UnpackOutcome.Success
            ?: return DownloadOutcome(success = false, reason = (outcome as UnpackOutcome.Refused).reason)

        val backups = mutableListOf<MemberBackup>()
        try {
            for ((relativePath, bytes) in collecting.writes) {
                val backup = backupMember(configured.folderPath, relativePath)
                backups += backup
                rootHelper.writeBytes("${configured.folderPath}/$relativePath", bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val restoreFailure = restoreAll(configured.folderPath, backups)
            val reason = "partial write failed, restored previous content: ${e.message}" +
                (restoreFailure?.let { " (restore also failed for: $it)" } ?: "")
            return DownloadOutcome(success = false, reason = reason)
        }

        val writtenPaths = collecting.writes.map { it.first }
        removeStaleMembers(configured, previousUnit, keep = writtenPaths.toSet())
        // hashZipContents(zipBytes) -- the same value hashLocalContentAsZip
        // would compute from these files on disk (SaveZipContentHash.kt),
        // straight from the bytes already in hand rather than re-reading
        // what was just written.
        return DownloadOutcome(
            success = true, writtenRelativePaths = writtenPaths, writtenContentHash = hashZipContents(zipBytes)
        )
    }

    // Item 3: after a successful write, remove any of `previousUnit`'s own
    // matched members the new content didn't include, backing up each one
    // first. FOLDER shape needs its own expansion first -- previousUnit.
    // memberPaths for a FOLDER is just its root directory path(s)
    // (LocalSaveUnit's own convention), not the individual files inside, so
    // this lists what was actually in that root before deciding what's
    // stale. Never touches anything outside `previousUnit`'s own matched
    // paths (per the review's "only touch the unit's own matched entries"),
    // and never a `.caulker_backup` path (defense in depth; scans already
    // exclude it, but this guards the delete step specifically too).
    private suspend fun removeStaleMembers(
        configured: ConfiguredPlatform,
        previousUnit: LocalSaveUnit?,
        keep: Set<String>
    ) {
        previousUnit ?: return
        val candidates: List<String> = if (previousUnit.shape == SaveShape.FOLDER) {
            previousUnit.memberPaths.flatMap { root ->
                rootHelper.listRecursive("${configured.folderPath}/$root")
                    .filterNot { (_, isDir) -> isDir }
                    .map { (rel, _) -> "$root/$rel" }
            }
        } else {
            previousUnit.memberPaths
        }
        for (staleRelativePath in candidates) {
            if (staleRelativePath in keep) continue
            if (isBackupPath(staleRelativePath)) continue
            val fullPath = "${configured.folderPath}/$staleRelativePath"
            if (!rootHelper.fileExists(fullPath)) continue
            backupMember(configured.folderPath, staleRelativePath)
            rootHelper.deleteFile(fullPath)
        }
    }

    internal data class MemberBackup(val originalRelativePath: String, val backupRelativePath: String?)

    // Backs up `relativePath` (if it currently exists) to
    // <folderPath>/.caulker_backup/<same relative dir>/<basename>_<ts><ext>,
    // anchored at the CONFIGURED FOLDER'S OWN ROOT -- never inside the
    // member's own parent directory (blocker 2, independent review): the
    // legacy RootFileHelper.backupFile anchors the backup dir at the
    // member's own parent, which for a PSP FOLDER member (SAVEDATA/<id>/...)
    // would put `.caulker_backup` INSIDE the save itself, making the backup
    // part of the save on the next scan. Legacy's own backupFile is
    // untouched and still used for the legacy download path. The timestamp
    // includes milliseconds (independent review round 2, item 4) so two
    // backups of the same member within one second can't overwrite each
    // other, and pruneBackupsFor keeps only the newest MAX_BACKUPS, mirroring
    // RootFileHelper.backupFile's own retention count for the legacy path.
    internal suspend fun backupMember(folderPath: String, relativePath: String): MemberBackup {
        val srcFull = "$folderPath/$relativePath"
        if (!rootHelper.fileExists(srcFull)) return MemberBackup(relativePath, null)
        val dir = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val ts = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val dotIndex = name.lastIndexOf('.')
        val baseName = if (dotIndex > 0) name.substring(0, dotIndex) else name
        val ext = if (dotIndex > 0) name.substring(dotIndex) else ""
        val backupName = "${baseName}_$ts$ext"
        val backupDirRel = if (dir.isBlank()) BACKUP_DIR_NAME else "$BACKUP_DIR_NAME/$dir"
        val backupRelPath = "$backupDirRel/$backupName"
        val bytes = rootHelper.readBytes(srcFull)
        rootHelper.writeBytes("$folderPath/$backupRelPath", bytes)
        pruneBackupsFor(folderPath, backupDirRel, baseName, ext)
        return MemberBackup(relativePath, backupRelPath)
    }

    // Mirrors RootFileHelper.backupFile's own pruning (MAX_BACKUPS = 5,
    // oldest dropped first) for configured-platform backups, which live
    // under a different root (backupMember's doc comment) and so can't reuse
    // that function directly.
    private suspend fun pruneBackupsFor(folderPath: String, backupDirRel: String, baseName: String, ext: String) {
        val entries = rootHelper.listRecursive("$folderPath/$backupDirRel")
            .filterNot { (_, isDir) -> isDir }
            .map { (rel, _) -> rel }
            .filter { !it.contains('/') && it.startsWith("${baseName}_") && it.endsWith(ext) }
            .sorted() // the zero-padded yyyyMMdd_HHmmssSSS timestamp sorts chronologically
        if (entries.size <= MAX_BACKUPS) return
        for (stale in entries.dropLast(MAX_BACKUPS)) {
            rootHelper.deleteFile("$folderPath/$backupDirRel/$stale")
        }
    }

    // Restores a member from its backup (or, if there was nothing to back up
    // -- the member didn't exist before this write -- deletes whatever this
    // call just wrote, so a rolled-back write leaves no trace).
    internal suspend fun restoreMember(folderPath: String, backup: MemberBackup) {
        val destFull = "$folderPath/${backup.originalRelativePath}"
        if (backup.backupRelativePath == null) {
            rootHelper.deleteFile(destFull)
            return
        }
        val bytes = rootHelper.readBytes("$folderPath/${backup.backupRelativePath}")
        rootHelper.writeBytes(destFull, bytes)
    }

    // Restores every backup, even if one throws -- independent review round
    // 2, item 4: a rollback used to stop at the first failing restore,
    // leaving the rest of an already-written multi-member unpack in its
    // half-applied state. Returns a description of every path that failed to
    // restore, or null if all of them succeeded.
    internal suspend fun restoreAll(folderPath: String, backups: List<MemberBackup>): String? {
        val failures = mutableListOf<String>()
        for (backup in backups) {
            try {
                restoreMember(folderPath, backup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${backup.originalRelativePath} (${e.message})"
            }
        }
        return failures.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private class CollectingSaveFileSink : SaveFileSink {
        val writes = mutableListOf<Pair<String, ByteArray>>()
        override fun writeFile(destPath: String, bytes: ByteArray) {
            writes += destPath to bytes
        }
    }

    companion object {
        // Mirrors RootFileHelper's own MAX_BACKUPS (legacy retention count) --
        // independent review round 2, item 4.
        private const val MAX_BACKUPS = 5
    }
}
