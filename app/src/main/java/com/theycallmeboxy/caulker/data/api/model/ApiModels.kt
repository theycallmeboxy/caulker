package com.theycallmeboxy.caulker.data.api.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// RomM 4.9 heartbeat is a nested structure ({SYSTEM:{VERSION,...}, METADATA_SOURCES:{...}}).
// The `version` / `anySourceSupported` accessors keep the old flat call-sites working.
@JsonClass(generateAdapter = true)
data class HeartbeatResponse(
    @Json(name = "SYSTEM") val system: HeartbeatSystem? = null,
    @Json(name = "METADATA_SOURCES") val metadataSources: HeartbeatMetadataSources? = null
) {
    val version: String get() = system?.version ?: "unknown"
    val anySourceSupported: Boolean get() = metadataSources?.anySourceEnabled ?: false
}

@JsonClass(generateAdapter = true)
data class HeartbeatSystem(
    @Json(name = "VERSION") val version: String? = null,
    @Json(name = "SHOW_SETUP_WIZARD") val showSetupWizard: Boolean = false
)

@JsonClass(generateAdapter = true)
data class HeartbeatMetadataSources(
    @Json(name = "ANY_SOURCE_ENABLED") val anySourceEnabled: Boolean = false
)

@JsonClass(generateAdapter = true)
data class UserResponse(
    val id: Int,
    val username: String,
    val role: String,
    val avatar: String? = null
)

@JsonClass(generateAdapter = true)
data class PlatformResponse(
    val id: Int,
    val name: String,
    val slug: String,
    @Json(name = "fs_slug") val fsSlug: String? = null,
    @Json(name = "rom_count") val romCount: Int = 0,
    @Json(name = "firmware_count") val firmwareCount: Int = 0,
    @Json(name = "logo_path") val logoPath: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class RomResponse(
    val id: Int,
    val name: String,
    @Json(name = "fs_name") val fileName: String? = null,
    @Json(name = "fs_name_no_ext") val fileNameNoExt: String? = null,
    @Json(name = "fs_size_bytes") val fileSize: Long = 0,
    @Json(name = "platform_id") val platformId: Int,
    @Json(name = "platform_slug") val platformSlug: String? = null,
    @Json(name = "platform_fs_slug") val platformFsSlug: String? = null,
    val slug: String? = null,
    val summary: String? = null,
    val rating: Float? = null,
    @Json(name = "first_release_date") val firstReleaseDate: Long? = null,
    val genres: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    @Json(name = "path_cover_small") val coverPath: String? = null,
    @Json(name = "has_saves") val hasSaves: Boolean = false,
    @Json(name = "crc_hash") val crcHash: String? = null,
    @Json(name = "md5_hash") val md5Hash: String? = null,
    @Json(name = "sha1_hash") val sha1Hash: String? = null,
    @Json(name = "has_multiple_files") val hasMultipleFiles: Boolean = false,
    val files: List<RomFileResponse> = emptyList(),
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class RomFileResponse(
    val id: Int = 0,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSize: Long = 0
)

@JsonClass(generateAdapter = true)
data class RomPageResponse(
    val items: List<RomResponse>,
    val total: Int,
    val offset: Int,
    val limit: Int
)

// Virtual collections (RomM 4.9) are auto-generated groupings with STRING ids
// (e.g. "genre:rpg") and a `type`. They are browse-only and not cached to Room
// (which is int-keyed); ROMs are listed via getRoms(virtualCollectionId = id).
@JsonClass(generateAdapter = true)
data class VirtualCollectionResponse(
    val id: String,
    val name: String,
    val type: String? = null,
    @Json(name = "rom_count") val romCount: Int = 0,
    @Json(name = "rom_ids") val romIds: List<Int> = emptyList(),
    val description: String? = null,
    @Json(name = "path_cover_small") val coverPathSmall: String? = null,
    @Json(name = "path_cover_large") val coverPathLarge: String? = null
)

@JsonClass(generateAdapter = true)
data class CollectionResponse(
    val id: Int,
    val name: String,
    @Json(name = "rom_count") val romCount: Int = 0,
    val description: String? = null,
    @Json(name = "path_cover_small") val coverPathSmall: String? = null,
    @Json(name = "path_cover_large") val coverPathLarge: String? = null,
    @Json(name = "rom_ids") val romIds: List<Int> = emptyList(),
    @Json(name = "is_smart") val isSmart: Boolean = false,
    @Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class DeviceSaveSync(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_name") val deviceName: String? = null,
    @Json(name = "last_synced_at") val lastSyncedAt: String? = null,
    @Json(name = "is_untracked") val isUntracked: Boolean = false,
    @Json(name = "is_current") val isCurrent: Boolean = false
)

@JsonClass(generateAdapter = true)
data class SaveResponse(
    val id: Int,
    @Json(name = "rom_id") val romId: Int,
    // RomM <=4.9 exposed the origin device as top-level `device_id`; 5.0 renamed
    // it to `origin_device_id`. Read both so attribution survives either server.
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "origin_device_id") val originDeviceId: String? = null,
    val slot: String? = null,
    val emulator: String? = null,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSize: Long = 0,
    @Json(name = "content_hash") val contentHash: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "device_syncs") val deviceSyncs: List<DeviceSaveSync> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SaveSlotResponse(
    val slot: String? = null,
    val emulator: String? = null,
    @Json(name = "has_local") val hasLocal: Boolean = false,
    @Json(name = "has_remote") val hasRemote: Boolean = false,
    @Json(name = "local_updated_at") val localUpdatedAt: String? = null,
    @Json(name = "remote_updated_at") val remoteUpdatedAt: String? = null
) {
    val slotKey: String get() = slot?.takeIf { it.isNotBlank() } ?: "default"
    val slotDisplay: String get() = when {
        slot.isNullOrBlank() || slot == "default" || slot == "0" -> "Default"
        slot.toIntOrNull() != null -> "Slot $slot"
        else -> slot.replaceFirstChar { it.uppercaseChar() }
    }
}

@JsonClass(generateAdapter = true)
data class DeviceResponse(
    @Json(name = "device_id") val id: String,
    val name: String? = null,
    val platform: String? = null,
    val client: String = "caulker",
    @Json(name = "client_version") val clientVersion: String? = null,
    @Json(name = "sync_mode") val syncMode: String? = null,
    @Json(name = "sync_enabled") val syncEnabled: Boolean = true,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    val name: String,
    val platform: String = "android",
    val client: String = "caulker",
    @Json(name = "client_version") val clientVersion: String,
    // RomM 4.9 sync modes: api | file_transfer | push_pull. Caulker uses the
    // API-coordinated mode (sync_enabled defaults to true server-side).
    @Json(name = "sync_mode") val syncMode: String = "api"
)

@JsonClass(generateAdapter = true)
data class FirmwareResponse(
    val id: Int,
    @Json(name = "platform_id") val platformId: Int? = null,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSize: Long = 0,
    val crc: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class MarkDownloadedRequest(
    @Json(name = "device_id") val deviceId: String
)

@JsonClass(generateAdapter = true)
data class ExchangeCodeRequest(
    val code: String
)

@JsonClass(generateAdapter = true)
data class TokenResponse(
    @Json(name = "raw_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String = "bearer"
)

// --- Save sync engine (RomM 4.9) ---

// One local save's state, sent to /api/sync/negotiate so the server can decide
// the per-save action. Server pairs client↔server saves on (rom_id, slot).
@JsonClass(generateAdapter = true)
data class ClientSaveState(
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_name") val fileName: String,
    val slot: String? = null,
    val emulator: String? = null,
    // MD5 hex of the file — lets the server short-circuit identical content as no_op.
    @Json(name = "content_hash") val contentHash: String? = null,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long = 0
)

@JsonClass(generateAdapter = true)
data class SyncNegotiateRequest(
    @Json(name = "device_id") val deviceId: String,
    val saves: List<ClientSaveState>
)

@JsonClass(generateAdapter = true)
data class SyncOperation(
    val action: String, // upload | download | conflict | no_op
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "save_id") val saveId: Int? = null,
    @Json(name = "file_name") val fileName: String,
    val slot: String? = null,
    val emulator: String? = null,
    val reason: String? = null,
    @Json(name = "server_updated_at") val serverUpdatedAt: String? = null,
    @Json(name = "server_content_hash") val serverContentHash: String? = null
)

@JsonClass(generateAdapter = true)
data class SyncNegotiateResponse(
    @Json(name = "session_id") val sessionId: Int,
    val operations: List<SyncOperation> = emptyList(),
    @Json(name = "total_upload") val totalUpload: Int = 0,
    @Json(name = "total_download") val totalDownload: Int = 0,
    @Json(name = "total_conflict") val totalConflict: Int = 0,
    @Json(name = "total_no_op") val totalNoOp: Int = 0
)

@JsonClass(generateAdapter = true)
data class SyncCompleteRequest(
    @Json(name = "operations_completed") val operationsCompleted: Int = 0,
    @Json(name = "operations_failed") val operationsFailed: Int = 0
)

@JsonClass(generateAdapter = true)
data class SyncSessionResponse(
    val id: Int,
    @Json(name = "device_id") val deviceId: String? = null,
    val status: String? = null,
    @Json(name = "operations_planned") val operationsPlanned: Int = 0,
    @Json(name = "operations_completed") val operationsCompleted: Int = 0,
    @Json(name = "operations_failed") val operationsFailed: Int = 0
)

@JsonClass(generateAdapter = true)
data class SyncCompleteResponse(
    val session: SyncSessionResponse? = null
)
