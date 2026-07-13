package com.theycallmeboxy.caulker.data.repository

import android.content.Context
import com.theycallmeboxy.caulker.BuildConfig
import com.theycallmeboxy.caulker.data.api.RommApiService
import com.theycallmeboxy.caulker.data.api.SaveConflictException
import com.theycallmeboxy.caulker.data.api.model.ClientSaveState
import com.theycallmeboxy.caulker.data.api.model.MarkDownloadedRequest
import com.theycallmeboxy.caulker.data.api.model.RegisterDeviceRequest
import com.theycallmeboxy.caulker.data.api.model.SaveResponse
import com.theycallmeboxy.caulker.data.api.model.SyncCompleteRequest
import com.theycallmeboxy.caulker.data.api.model.SyncNegotiateRequest
import com.theycallmeboxy.caulker.data.api.model.SyncNegotiateResponse
import com.theycallmeboxy.caulker.data.db.dao.SaveDao
import com.theycallmeboxy.caulker.data.db.entity.SaveEntity
import com.theycallmeboxy.caulker.data.prefs.PlatformOverrideMode
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.util.RootFileHelper
import com.theycallmeboxy.caulker.data.util.md5Hex
import com.theycallmeboxy.caulker.data.util.parseIsoToMs
import retrofit2.HttpException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class BackupInfo(val count: Int, val latestMs: Long)

// Local save fingerprint used to build a ClientSaveState for sync negotiation.
data class LocalSaveStat(val contentHash: String, val sizeBytes: Long, val modifiedMs: Long)

@Singleton
class SaveRepository @Inject constructor(
    private val api: RommApiService,
    private val dao: SaveDao,
    private val prefsStore: PrefsStore,
    private val rootHelper: RootFileHelper,
    @ApplicationContext private val context: Context
) {
    fun observeSavesForRom(romId: Int): Flow<List<SaveEntity>> = dao.observeByRom(romId)

    suspend fun getOrRegisterDeviceId(): String {
        val existing = prefsStore.deviceId.first()
        if (!existing.isNullOrBlank()) return existing
        val name = prefsStore.deviceName.first()
            ?.takeIf { it.isNotBlank() }
            ?: android.os.Build.MODEL
        val device = api.registerDevice(
            RegisterDeviceRequest(
                name = name,
                clientVersion = BuildConfig.VERSION_NAME
            )
        )
        prefsStore.setDeviceId(device.id)
        return device.id
    }

    // Resolves the save directory for a platform, respecting per-platform overrides.
    private suspend fun effectiveSaveDir(platformFsSlug: String?): String? {
        val override = prefsStore.getPlatformOverride(platformFsSlug)
        if (override?.savePath?.isNotBlank() == true) return override.savePath
        val base = prefsStore.saveBasePath.first() ?: return null
        val effectiveSlug = when (override?.mode) {
            PlatformOverrideMode.SLUG_OVERRIDE ->
                override.slug?.takeIf { it.isNotBlank() } ?: platformFsSlug
            else -> platformFsSlug
        }
        return if (!effectiveSlug.isNullOrBlank()) "$base/$effectiveSlug" else base
    }

    suspend fun hasLocalSave(fileName: String, platformFsSlug: String?): Boolean {
        val dir = effectiveSaveDir(platformFsSlug) ?: return false
        return rootHelper.fileExists("$dir/$fileName")
    }

    suspend fun localSaveModifiedMs(fileName: String, platformFsSlug: String?): Long {
        val dir = effectiveSaveDir(platformFsSlug) ?: return 0L
        return rootHelper.lastModifiedMs("$dir/$fileName")
    }

    // Reads the local save once and returns its MD5 (matching RomM's content_hash),
    // size, and mtime — used to build a ClientSaveState for /api/sync/negotiate.
    // Returns null if no save folder is configured or the file can't be read.
    suspend fun localSaveStat(fileName: String, platformFsSlug: String?): LocalSaveStat? {
        val dir = effectiveSaveDir(platformFsSlug) ?: return null
        val path = "$dir/$fileName"
        return withContext(Dispatchers.IO) {
            try {
                val bytes = rootHelper.readBytes(path)
                LocalSaveStat(md5Hex(bytes), bytes.size.toLong(), rootHelper.lastModifiedMs(path))
            } catch (_: Exception) {
                null
            }
        }
    }

    // Resolves which local filename to use for a save, given the ROM's filename and an optional
    // extension hint (from the server's filename). Preference order:
    //   1. Stable name `$romBase.$ext` if it exists.
    //   2. Legacy timestamped name `$romBase [*].$ext` (newest mtime) — handles migration from
    //      pre-fix Caulker downloads.
    //   3. The stable name as a target (file doesn't exist yet but is where downloads will go).
    // If no extension is known, scans for any file whose name starts with `$romBase.` or
    // `$romBase [`. Returns null only when no ROM base is available.
    suspend fun resolveLocalSaveFileName(
        serverFileName: String?,
        romFileName: String?,
        platformFsSlug: String?
    ): String? {
        val romBase = romFileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: return serverFileName
        val ext = serverFileName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
        val dir = effectiveSaveDir(platformFsSlug)

        if (ext != null) {
            val stable = "$romBase.$ext"
            if (dir != null) {
                if (rootHelper.fileExists("$dir/$stable")) return stable
                val legacy = rootHelper.findNewestFile(dir) { name ->
                    name.startsWith("$romBase [") && name.endsWith(".$ext")
                }
                if (legacy != null) return legacy
            }
            return stable
        }

        if (dir == null) return null
        return rootHelper.findNewestFile(dir) { name ->
            val nameExt = name.substringAfterLast('.', "")
            nameExt.isNotBlank() &&
                (name.startsWith("$romBase.") || name.startsWith("$romBase ["))
        }
    }

    // Downloads a save from the server, backing up the existing local file first.
    // remoteUpdatedAtMs is applied as the file mtime after writing for future comparisons.
    suspend fun downloadSave(
        saveId: Int,
        fileName: String,
        platformFsSlug: String?,
        remoteUpdatedAtMs: Long? = null,
        sessionId: Int? = null
    ) {
        val deviceId = getOrRegisterDeviceId()
        val dir = effectiveSaveDir(platformFsSlug)
            ?: error("Save folder not configured — go to Settings")
        val destPath = "$dir/$fileName"

        rootHelper.backupFile(destPath)

        val response = api.downloadSave(saveId, deviceId, sessionId)
        val body = response.body() ?: error("Empty save response from server")

        withContext(Dispatchers.IO) {
            val bytes = body.bytes()
            rootHelper.writeBytes(destPath, bytes)
            remoteUpdatedAtMs?.let { rootHelper.setLastModified(destPath, it) }
        }

        api.markSaveDownloaded(saveId, MarkDownloadedRequest(deviceId))
    }

    // Uploads a save that already exists on-device at the resolved save path.
    // Uses root access if the path requires it. Returns the server's recorded updatedAt as
    // epoch ms, which is also applied as the local file's mtime so the two clocks agree on
    // the next sync comparison.
    suspend fun uploadSaveFromDisk(
        romId: Int,
        slotKey: String,
        fileName: String,
        platformFsSlug: String?,
        sessionId: Int? = null,
        autocleanup: Boolean = true,
        autocleanupLimit: Int = 10
    ): Long {
        val dir = effectiveSaveDir(platformFsSlug)
            ?: error("Save folder not configured — go to Settings")
        val bytes = rootHelper.readBytes("$dir/$fileName")
        val deviceId = getOrRegisterDeviceId()
        val saved = uploadBytes(
            romId, slotKey, fileName, deviceId, bytes, sessionId, autocleanup, autocleanupLimit
        )
        val serverMs = parseIsoToMs(saved.updatedAt) ?: System.currentTimeMillis()
        rootHelper.setLastModified("$dir/$fileName", serverMs)
        return serverMs
    }

    // Uploads save bytes from an arbitrary source (e.g., file picker). Returns the server's
    // updatedAt as epoch ms (or current time if the server timestamp can't be parsed).
    suspend fun uploadSaveFromBytes(
        romId: Int,
        slotKey: String,
        fileName: String,
        data: ByteArray
    ): Long {
        val deviceId = getOrRegisterDeviceId()
        val saved = uploadBytes(romId, slotKey, fileName, deviceId, data)
        return parseIsoToMs(saved.updatedAt) ?: System.currentTimeMillis()
    }

    // Uploads via POST /api/saves. In RomM 4.9, slot uploads are datetime-tagged
    // server-side into per-slot history; identical content (matching content_hash)
    // is de-duplicated, and autocleanup trims the slot to the most recent N. The
    // POST also records this device's sync state, so no separate "downloaded" call
    // is needed. A 409 is surfaced as a SaveConflictException when parseable.
    private suspend fun uploadBytes(
        romId: Int,
        slotKey: String,
        fileName: String,
        deviceId: String,
        data: ByteArray,
        sessionId: Int? = null,
        autocleanup: Boolean = true,
        autocleanupLimit: Int = 10
    ): SaveResponse = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "save_upload_$fileName")
        try {
            tmp.writeBytes(data)
            val requestFile = tmp.asRequestBody("application/octet-stream".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("saveFile", fileName, requestFile)
            try {
                api.uploadSave(
                    romId = romId,
                    deviceId = deviceId,
                    slot = slotKey,
                    emulator = "caulker",
                    overwrite = false,
                    sessionId = sessionId,
                    autocleanup = autocleanup,
                    autocleanupLimit = autocleanupLimit,
                    saveFile = part
                )
            } catch (e: HttpException) {
                throw SaveConflictException.parse(e) ?: e
            }
        } finally {
            tmp.delete()
        }
    }

    // --- Sync engine (RomM 4.9) ---

    // Asks the server to compute per-save sync actions for this device given the
    // client's current save state. Returns a session id + operations to execute.
    suspend fun negotiate(deviceId: String, saves: List<ClientSaveState>): SyncNegotiateResponse =
        api.negotiateSync(SyncNegotiateRequest(deviceId, saves))

    // Marks a sync session complete.
    suspend fun completeSession(
        sessionId: Int,
        operationsCompleted: Int,
        operationsFailed: Int
    ) {
        api.completeSyncSession(
            sessionId,
            SyncCompleteRequest(
                operationsCompleted = operationsCompleted,
                operationsFailed = operationsFailed
            )
        )
    }

    // Re-enable / disable sync tracking for a specific save on this device.
    suspend fun trackSave(saveId: Int, deviceId: String): SaveResponse =
        api.trackSave(saveId, MarkDownloadedRequest(deviceId))

    suspend fun untrackSave(saveId: Int, deviceId: String): SaveResponse =
        api.untrackSave(saveId, MarkDownloadedRequest(deviceId))

    suspend fun getLocalFilePath(fileName: String, platformFsSlug: String?): String? {
        val dir = effectiveSaveDir(platformFsSlug) ?: return null
        return "$dir/$fileName"
    }

    suspend fun getBackupInfo(fileName: String, platformFsSlug: String?): BackupInfo {
        val dir = effectiveSaveDir(platformFsSlug) ?: return BackupInfo(0, 0L)
        val timestamps = rootHelper.listBackupTimestamps("$dir/$fileName")
        return BackupInfo(timestamps.size, timestamps.firstOrNull() ?: 0L)
    }

    suspend fun getByRom(romId: Int) = dao.getByRom(romId)

    suspend fun getSaveBySlot(romId: Int, slotKey: String) =
        dao.getByRom(romId).filter { it.slot == slotKey }.maxByOrNull { it.updatedAt ?: "" }

    // Fetches all saves for this ROM from all devices, stores in DB, and returns the raw responses.
    suspend fun syncSavesForRom(romId: Int): List<SaveResponse> {
        val remote = api.getSaves(romId = romId)
        dao.upsertAll(remote.map {
            SaveEntity(
                id = it.id,
                romId = it.romId,
                deviceId = it.deviceId,
                slot = it.slot?.takeIf { s -> s.isNotBlank() } ?: "default",
                emulator = it.emulator,
                fileName = it.fileName,
                fileSize = it.fileSize,
                updatedAt = it.updatedAt
            )
        })
        return remote
    }
}
