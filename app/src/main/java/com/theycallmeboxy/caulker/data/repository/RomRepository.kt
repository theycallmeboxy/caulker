package com.theycallmeboxy.caulker.data.repository

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.theycallmeboxy.caulker.data.api.RommApiService
import java.time.Instant
import com.theycallmeboxy.caulker.data.db.dao.PlatformDao
import com.theycallmeboxy.caulker.data.db.dao.RomDao
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.db.entity.RomFile
import com.theycallmeboxy.caulker.data.prefs.PlatformOverride
import com.theycallmeboxy.caulker.data.prefs.PlatformOverrideMode
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val ROM_PAGE_SIZE = 500

@Singleton
class RomRepository @Inject constructor(
    private val api: RommApiService,
    private val dao: RomDao,
    private val platformDao: PlatformDao,
    private val prefsStore: PrefsStore,
    @ApplicationContext private val context: Context
) {
    fun observeByPlatform(platformId: Int): Flow<List<RomEntity>> =
        dao.observeByPlatform(platformId)

    fun observeByIds(ids: List<Int>): Flow<List<RomEntity>> = dao.observeByIds(ids)

    suspend fun getById(id: Int): RomEntity? = dao.getById(id)

    private fun defaultRomFile(basePath: String, platformFsSlug: String?, fileName: String): File =
        if (!platformFsSlug.isNullOrBlank()) File(basePath, "$platformFsSlug/$fileName")
        else File(basePath, fileName)

    private fun effectiveRomFile(
        basePath: String,
        platformFsSlug: String?,
        fileName: String,
        override: PlatformOverride?
    ): File? = when (override?.mode) {
        PlatformOverrideMode.SLUG_OVERRIDE ->
            File(basePath, "${(override.slug ?: platformFsSlug) ?: ""}/$fileName")
        PlatformOverrideMode.MANUAL ->
            override.romPath?.let { File(it, fileName) }
        else -> defaultRomFile(basePath, platformFsSlug, fileName)
    }

    // For multi-file ROMs (multi-disc PS1, etc.) the "primary" local path is the
    // .m3u playlist alongside the individual files. For single-file ROMs it's the
    // file itself. Matches grout's resolution in Rom.GetLocalPath.
    private fun primaryLocalFileName(rom: RomEntity): String {
        return if (rom.hasMultipleFiles) {
            val base = rom.fileNameNoExt
                ?: rom.fileName?.substringBeforeLast('.', "")?.takeIf { it.isNotBlank() }
                ?: rom.name
            "$base.m3u"
        } else {
            rom.fileName ?: rom.name
        }
    }

    suspend fun localFile(rom: RomEntity): File? {
        val basePath = prefsStore.romBasePath.first() ?: return null
        val override = prefsStore.getPlatformOverride(rom.platformFsSlug)
        val file = effectiveRomFile(basePath, rom.platformFsSlug, primaryLocalFileName(rom), override)
            ?: return null
        return if (file.exists()) file else null
    }

    suspend fun getInstalledRomIds(roms: List<RomEntity>): Set<Int> = withContext(Dispatchers.IO) {
        if (roms.isEmpty()) return@withContext emptySet()
        val basePath = prefsStore.romBasePath.first() ?: return@withContext emptySet()
        val overrideCache = mutableMapOf<String?, PlatformOverride?>()

        // Group ROMs by their resolved directory, then list each directory once
        // and build a filename set. Resolving 1500 ROMs becomes one listFiles()
        // per platform directory instead of 1500 File.exists() syscalls.
        val dirFilesets = mutableMapOf<String, Set<String>>()
        roms.filter { rom ->
            val override = overrideCache.getOrPut(rom.platformFsSlug) {
                prefsStore.getPlatformOverride(rom.platformFsSlug)
            }
            val file = effectiveRomFile(basePath, rom.platformFsSlug, primaryLocalFileName(rom), override)
                ?: return@filter false
            val dir = file.parent ?: return@filter false
            val names = dirFilesets.getOrPut(dir) {
                File(dir).listFiles()?.mapTo(HashSet()) { it.name } ?: emptySet()
            }
            file.name in names
        }.map { it.id }.toSet()
    }

    suspend fun deleteLocalRom(rom: RomEntity) {
        val basePath = prefsStore.romBasePath.first() ?: return
        val override = prefsStore.getPlatformOverride(rom.platformFsSlug)
        if (rom.hasMultipleFiles) {
            // Always remove a Caulker-written playlist at the canonical name
            // first -- harmless no-op when the server's own .m3u (a different
            // name) was downloaded instead; that one is deleted below along
            // with the rest of the server's file list.
            effectiveRomFile(basePath, rom.platformFsSlug, primaryLocalFileName(rom), override)?.delete()

            // The detail endpoint is the authoritative file list (with the
            // paths needed to resolve nested files); fall back to the stale
            // flat filesJson from the last library sync if it's unreachable
            // so a delete still cleans up what it can rather than doing
            // nothing.
            val relativePaths: List<String> = try {
                val detail = api.getRom(rom.id)
                // A path that fails the safety check is skipped rather than
                // falling back to the raw file name -- that fallback is only
                // safe for the "not nested" case resolveDownloadRelativePath
                // already handles internally.
                detail.files.mapNotNull { f ->
                    resolveDownloadRelativePath(detail.fullPath, f.fullPath, f.fileName)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RomFile.parseList(rom.filesJson).map { it.fileName }
            }
            relativePaths.forEach { relativePath ->
                effectiveRomFile(basePath, rom.platformFsSlug, relativePath, override)?.delete()
            }
        } else {
            effectiveRomFile(basePath, rom.platformFsSlug, rom.fileName ?: rom.name, override)?.delete()
        }
    }

    suspend fun search(platformId: Int, query: String): List<RomEntity> =
        dao.searchInPlatform(platformId, query)

    // Iterates platforms sequentially and pages each one's ROMs with a
    // platform_id filter. Sequential (not parallel) so the progress bar advances
    // visibly with every completion and the screen surfaces the current
    // system's name — parallelism on a slow link just made the bar look stuck
    // at 0 until the first concurrent fetch finished.
    //
    // RomM's /api/roms requires a filter — an unfiltered call returns empty —
    // so we can't do a single global sweep (this matches grout's per-platform
    // pattern). Uses a single global updated_after cursor: first run is full,
    // subsequent runs are incremental and platforms with no changes return one
    // empty page each.
    //
    // onProgress reports (platformsCompleted, platformsTotal, currentPlatformName?).
    suspend fun syncAllRoms(
        onProgress: (done: Int, total: Int, currentPlatform: String?, rowsInCurrent: Int) -> Unit
    ) {
        val platforms = platformDao.getAllOnce()
        if (platforms.isEmpty()) {
            onProgress(0, 0, null, 0)
            return
        }
        val lastSync = prefsStore.getGlobalRomSyncTime()
        val localRomCount = dao.countAll()
        val serverTotal = platforms.sumOf { it.romCount }
        // The cursor goes stale in two cases that have to trigger a full re-fetch:
        //   1. Room DB was wiped (destructive migration) — localRomCount == 0
        //      while the cursor still reflects a past successful sync.
        //   2. Local trails the server materially — a previous sync truncated
        //      pagination or an over-aggressive reconcile pass removed rows.
        //      Using the cursor here would only ask for deltas, never refilling
        //      the missing rows. Cap at >90% so a single deletion server-side
        //      doesn't force a full re-fetch needlessly.
        val cursorIsStale = lastSync > 0L && (
            localRomCount == 0 ||
            (serverTotal > 0 && localRomCount < (serverTotal * 9 / 10))
        )
        val updatedAfter = if (lastSync > 0L && !cursorIsStale)
            Instant.ofEpochMilli(lastSync).toString() else null

        // Skip platforms the server reports as empty — saves a round trip per
        // empty system, which adds up on a slow link with many platforms.
        // `romCount` is fresh because PlatformRepository.sync() just ran.
        val toSync = platforms.filter { it.romCount > 0 }
        val total = toSync.size
        if (total == 0) {
            onProgress(0, 0, null, 0)
            prefsStore.setGlobalRomSyncTime(System.currentTimeMillis())
            return
        }
        // Collect all seen IDs across the sweep so we can delete orphans (ROMs
        // removed server-side) without any extra API call — we already have the
        // full picture from the pages we just fetched.
        val seenIds = HashSet<Int>()
        toSync.forEachIndexed { idx, platform ->
            onProgress(idx, total, platform.name, 0)
            syncOnePlatformInternal(platform.id, platform.romCount, updatedAfter, seenIds) { rowsSoFar ->
                onProgress(idx, total, platform.name, rowsSoFar)
            }
            onProgress(idx + 1, total, platform.name, 0)
        }

        // Remove local rows whose IDs we never saw from the server. Runs in
        // memory — no extra network request needed.
        val allLocalIds = dao.getAllIds()
        val orphaned = allLocalIds.filterNot { it in seenIds }
        if (orphaned.isNotEmpty()) dao.deleteByIds(orphaned)

        prefsStore.setGlobalRomSyncTime(System.currentTimeMillis())
    }

    private suspend fun syncOnePlatformInternal(
        platformId: Int,
        expectedCount: Int,
        updatedAfter: String?,
        seenIds: HashSet<Int>,
        onPageProgress: (rowsFetched: Int) -> Unit
    ) {
        var offset = 0
        var fetched = 0
        while (true) {
            val page = api.getRoms(
                platformId = platformId,
                offset = offset,
                limit = ROM_PAGE_SIZE,
                updatedAfter = updatedAfter
            )
            dao.upsertAll(page.items.map { it.toEntity() })
            page.items.forEach { seenIds += it.id }
            fetched += page.items.size
            offset += page.items.size
            onPageProgress(fetched)
            if (page.items.isEmpty()) break
            if (page.items.size < ROM_PAGE_SIZE) break
            if (expectedCount > 0 && fetched >= expectedCount) break
        }
    }


    // Full re-fetch of a single platform for an explicit per-platform refresh.
    // Collects the server's IDs for this platform and deletes any local rows for the
    // platform not returned — self-contained deletion reconcile, no global id fetch.
    // Caps at the platform's reported romCount because RomM's response `total`
    // field has been observed to over-report and the server keeps serving full
    // pages past the actual platform end (see syncOnePlatformInternal comment).
    suspend fun syncPlatform(platformId: Int) {
        var offset = 0
        val seen = HashSet<Int>()
        while (true) {
            val page = api.getRoms(platformId = platformId, offset = offset, limit = ROM_PAGE_SIZE)
            dao.upsertAll(page.items.map { it.toEntity() })
            page.items.forEach { seen += it.id }
            offset += page.items.size
            if (page.items.isEmpty()) break
            if (page.items.size < ROM_PAGE_SIZE) break
        }
        val orphaned = dao.getIdsByPlatform(platformId).filterNot { it in seen }
        if (orphaned.isNotEmpty()) dao.deleteByIds(orphaned)
    }

fun downloadRom(rom: RomEntity): Flow<DownloadProgress> = flow {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            emit(DownloadProgress.Failed("All Files Access permission required — grant it in Settings"))
            return@flow
        }
        val basePath = prefsStore.romBasePath.first()
        if (basePath.isNullOrBlank()) {
            emit(DownloadProgress.Failed("ROM folder not configured — go to Settings"))
            return@flow
        }
        val override = prefsStore.getPlatformOverride(rom.platformFsSlug)

        if (rom.hasMultipleFiles) {
            downloadMultiFileRom(rom, basePath, override, this)
        } else {
            val fileName = rom.fileName ?: rom.name
            val destFile = effectiveRomFile(basePath, rom.platformFsSlug, fileName, override)
            if (destFile == null) {
                emit(DownloadProgress.Failed("ROM path not configured for this platform"))
                return@flow
            }
            val ok = downloadSingleFile(rom.id, fileName, destFile, contentLength = rom.fileSize, emitter = this)
            if (ok) emit(DownloadProgress.Done(destFile))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadSingleFile(
        romId: Int,
        fileName: String,
        destFile: File,
        contentLength: Long,
        emitter: kotlinx.coroutines.flow.FlowCollector<DownloadProgress>,
        progressOffsetBytes: Long = 0,
        progressTotalBytes: Long = -1,
        // Selects one file of a multi-part rom via RomM's content endpoint.
        // Omitted for single-file roms. Without it the endpoint zips up every
        // file the rom has (plus a generated m3u) instead of serving the one
        // file requested by name -- see downloadMultiFileRom.
        fileId: Int? = null
    ): Boolean {
        val parent = destFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            emitter.emit(DownloadProgress.Failed("Cannot create directory: ${parent.absolutePath}"))
            return false
        }

        val url = "api/roms/$romId/content/${Uri.encode(fileName)}" +
            if (fileId != null) "?file_ids=$fileId" else ""
        val response = try {
            api.downloadFile(url)
        } catch (e: Exception) {
            emitter.emit(DownloadProgress.Failed(e.message ?: "Network error"))
            return false
        }
        if (!response.isSuccessful) {
            emitter.emit(DownloadProgress.Failed("Server error ${response.code()}"))
            return false
        }
        val body = response.body() ?: run {
            emitter.emit(DownloadProgress.Failed("Empty response from server"))
            return false
        }

        val perFileTotal = body.contentLength().takeIf { it > 0 } ?: contentLength
        val total = if (progressTotalBytes > 0) progressTotalBytes else perFileTotal
        var bytesRead = 0L
        try {
            coroutineScope {
                // A stalled socket read blocks the IO thread and can't be interrupted
                // by cooperative coroutine cancellation. invokeOnCompletion only fires
                // once a job reaches a *final* state, which never happens while it's
                // blocked in that read — so it can't abort a hung transfer. A sibling
                // watcher coroutine that closes the body the instant cancellation
                // begins does: closing the stream makes the blocked read() throw
                // immediately.
                val watcher = launch { try { awaitCancellation() } finally { body.close() } }
                try {
                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytes: Int
                            while (input.read(buffer).also { bytes = it } != -1) {
                                output.write(buffer, 0, bytes)
                                bytesRead += bytes
                                emitter.emit(DownloadProgress.InProgress(progressOffsetBytes + bytesRead, total))
                            }
                        }
                    }
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            destFile.delete()
            throw e
        } catch (e: Exception) {
            destFile.delete()
            // If cancellation caused the read to throw (via the watcher closing the
            // body), rethrow CancellationException instead of reporting a failure.
            currentCoroutineContext().ensureActive()
            emitter.emit(DownloadProgress.Failed("${e.javaClass.simpleName}: ${e.message ?: "unknown"} — path: ${destFile.absolutePath}"))
            return false
        }
        return true
    }

    // Multi-file ROMs (multi-disc games): the library sync never populates
    // RomEntity.filesJson (RomM's list endpoint omits `files` unless
    // `with_files=true`, which Caulker doesn't ask for to keep the sync
    // lean), so the file list -- with the per-file ids and paths a correct
    // download needs -- is fetched fresh from the detail endpoint here.
    // Downloads each file in order via `file_ids` (RomM's content endpoint
    // ignores the file_name path segment for selection and otherwise zips up
    // every file the rom has), emits unified progress across all of them,
    // then writes an .m3u playlist alongside them -- unless the server's own
    // file list already has one, in which case it was just downloaded like
    // any other file and Caulker writes none of its own.
    private suspend fun downloadMultiFileRom(
        rom: RomEntity,
        basePath: String,
        override: PlatformOverride?,
        emitter: kotlinx.coroutines.flow.FlowCollector<DownloadProgress>
    ) {
        val detail = try {
            api.getRom(rom.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emitter.emit(DownloadProgress.Failed("Could not fetch ROM file list — ${e.message ?: e.javaClass.simpleName}"))
            return
        }
        if (detail.files.isEmpty()) {
            emitter.emit(DownloadProgress.Failed("ROM is marked multi-file but has no file list"))
            return
        }

        // Resolve every file's local path up front -- one unsafe entry fails
        // the whole download before anything is written, rather than partway
        // through.
        data class Resolved(val id: Int, val fileName: String, val fileSize: Long, val relativePath: String, val destFile: File)
        val resolved = mutableListOf<Resolved>()
        for (f in detail.files) {
            val relativePath = resolveDownloadRelativePath(detail.fullPath, f.fullPath, f.fileName)
            if (relativePath == null) {
                emitter.emit(DownloadProgress.Failed("Unsafe file path from server: ${f.fileName}"))
                return
            }
            val destFile = effectiveRomFile(basePath, rom.platformFsSlug, relativePath, override)
                ?: run {
                    emitter.emit(DownloadProgress.Failed("ROM path not configured for this platform"))
                    return
                }
            resolved += Resolved(f.id, f.fileName, f.fileSize, relativePath, destFile)
        }

        val totalBytes = resolved.sumOf { it.fileSize.coerceAtLeast(0) }
            .takeIf { it > 0 } ?: -1L
        var bytesDone = 0L

        for (r in resolved) {
            val ok = downloadSingleFile(
                romId = rom.id,
                fileName = r.fileName,
                destFile = r.destFile,
                contentLength = r.fileSize,
                emitter = emitter,
                progressOffsetBytes = bytesDone,
                progressTotalBytes = totalBytes,
                fileId = r.id
            )
            if (!ok) return
            bytesDone += r.fileSize.coerceAtLeast(0)
        }

        val serverM3u = resolved.firstOrNull { isM3uFileName(it.fileName) }
        if (serverM3u != null) {
            // The server's own playlist was just downloaded above like any
            // other file. NOTE: if its name doesn't match
            // primaryLocalFileName(rom) (fs_name_no_ext + ".m3u"), Caulker's
            // installed-detection (localFile / getInstalledRomIds, which only
            // look for that exact name) won't recognize this ROM as
            // installed even though every file is on disk.
            emitter.emit(DownloadProgress.Done(serverM3u.destFile))
            return
        }

        val m3uFile = effectiveRomFile(basePath, rom.platformFsSlug, primaryLocalFileName(rom), override)
        if (m3uFile == null) {
            emitter.emit(DownloadProgress.Failed("Could not write m3u file"))
            return
        }
        try {
            val content = buildM3uContent(resolved.map { PlaylistCandidate(it.fileName, it.relativePath) })
            m3uFile.writeText(content)
        } catch (e: Exception) {
            emitter.emit(DownloadProgress.Failed("Failed to write m3u: ${e.message}"))
            return
        }
        emitter.emit(DownloadProgress.Done(m3uFile))
    }

    private suspend fun effectiveBiosDir(platformFsSlug: String?): String? {
        val platformOverride = prefsStore.getPlatformOverride(platformFsSlug)
        return platformOverride?.biosPath?.takeIf { it.isNotBlank() }
            ?: prefsStore.biosBasePath.first()?.takeIf { it.isNotBlank() }
            ?: prefsStore.romBasePath.first()?.let { "$it/bios" }
    }

    suspend fun localFirmwareFile(fileName: String, platformFsSlug: String?): File? {
        val dir = effectiveBiosDir(platformFsSlug) ?: return null
        val file = File(dir, fileName)
        return if (file.exists()) file else null
    }

    suspend fun deleteLocalFirmware(fileName: String, platformFsSlug: String?) {
        val dir = effectiveBiosDir(platformFsSlug) ?: return
        File(dir, fileName).delete()
    }

    fun downloadFirmware(firmwareId: Int, fileName: String, platformFsSlug: String? = null): Flow<DownloadProgress> = flow {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            emit(DownloadProgress.Failed("All Files Access permission required — grant it in Settings"))
            return@flow
        }
        val biosDir = effectiveBiosDir(platformFsSlug)
        if (biosDir.isNullOrBlank()) {
            emit(DownloadProgress.Failed("ROM folder not configured — go to Settings"))
            return@flow
        }
        val destFile = File(biosDir, fileName)
        val parent = destFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            emit(DownloadProgress.Failed("Cannot create directory: ${parent.absolutePath}"))
            return@flow
        }

        val response = try {
            api.downloadFile("api/firmware/$firmwareId/content/${Uri.encode(fileName)}")
        } catch (e: Exception) {
            emit(DownloadProgress.Failed(e.message ?: "Network error"))
            return@flow
        }

        if (!response.isSuccessful) {
            emit(DownloadProgress.Failed("Server error ${response.code()}"))
            return@flow
        }

        val body = response.body()
        if (body == null) {
            emit(DownloadProgress.Failed("Empty response from server"))
            return@flow
        }

        val totalBytes = body.contentLength()
        var bytesRead = 0L
        try {
            // See downloadSingleFile for why this needs a watcher coroutine rather
            // than invokeOnCompletion to abort a stalled blocking read() on cancel.
            coroutineScope {
                val watcher = launch { try { awaitCancellation() } finally { body.close() } }
                try {
                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytes: Int
                            while (input.read(buffer).also { bytes = it } != -1) {
                                output.write(buffer, 0, bytes)
                                bytesRead += bytes
                                emit(DownloadProgress.InProgress(bytesRead, totalBytes))
                            }
                        }
                    }
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            destFile.delete()
            throw e
        } catch (e: Exception) {
            destFile.delete()
            // If cancellation caused the read to throw (via the watcher closing the
            // body), rethrow CancellationException instead of reporting a failure.
            currentCoroutineContext().ensureActive()
            emit(DownloadProgress.Failed("${e.javaClass.simpleName}: ${e.message ?: "unknown"} — path: ${destFile.absolutePath}"))
            return@flow
        }

        emit(DownloadProgress.Done(destFile))
    }.flowOn(Dispatchers.IO)
}

private fun com.theycallmeboxy.caulker.data.api.model.RomResponse.toEntity() = RomEntity(
    id = id,
    // `name` is null for unmatched/manually-added ROMs (RomM's RomSchema.name
    // is str | None). Fall back to a filename-derived display name so the rest
    // of the app (RomEntity.name is non-null) never has to null-check this.
    name = name ?: fileNameNoExt ?: fileName ?: "Unknown",
    fileName = fileName,
    fileNameNoExt = fileNameNoExt,
    fileSize = fileSize,
    platformId = platformId,
    platformSlug = platformSlug,
    platformFsSlug = platformFsSlug,
    slug = slug,
    summary = summary,
    // rating/genres/release date live under `metadatum` server-side, not at the
    // response's top level.
    rating = metadatum?.averageRating,
    firstReleaseDate = metadatum?.firstReleaseDate,
    genres = (metadatum?.genres ?: emptyList()).joinToString(","),
    regions = regions.joinToString(","),
    languages = languages.joinToString(","),
    coverPath = coverPath,
    crcHash = crcHash,
    md5Hash = md5Hash,
    sha1Hash = sha1Hash,
    hasMultipleFiles = hasMultipleFiles,
    filesJson = com.theycallmeboxy.caulker.data.db.entity.RomFile.serializeList(
        files.map { com.theycallmeboxy.caulker.data.db.entity.RomFile(it.fileName, it.fileSize) }
    ),
    updatedAt = updatedAt,
    titleId = titleId,
    saveTarget = saveTarget,
    saveTargetLayout = saveTargetLayout
)
