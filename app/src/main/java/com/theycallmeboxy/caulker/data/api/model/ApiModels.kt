package com.theycallmeboxy.caulker.data.api.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HeartbeatResponse(
    val version: String,
    @Json(name = "any_source_supported") val anySourceSupported: Boolean = false
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
    @Json(name = "device_id") val deviceId: String? = null,
    val slot: String? = null,
    val emulator: String? = null,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size") val fileSize: Long = 0,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "device_syncs") val deviceSyncs: List<DeviceSaveSync> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SaveSummaryResponse(
    @Json(name = "rom_id") val romId: Int? = null,
    val slots: List<SaveSlotResponse> = emptyList()
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
    val name: String,
    val platform: String? = null,
    val client: String = "caulker",
    @Json(name = "client_version") val clientVersion: String? = null,
    @Json(name = "sync_enabled") val syncEnabled: Boolean = true,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterDeviceRequest(
    val name: String,
    val platform: String = "android",
    val client: String = "caulker",
    @Json(name = "client_version") val clientVersion: String
)

@JsonClass(generateAdapter = true)
data class FirmwareResponse(
    val id: Int,
    @Json(name = "platform_id") val platformId: Int? = null,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size") val fileSize: Long = 0,
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
