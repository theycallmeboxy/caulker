package com.theycallmeboxy.caulker.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val slug: String,
    val fsSlug: String? = null,
    val romCount: Int = 0,
    val firmwareCount: Int = 0,
    val logoPath: String? = null,
    val updatedAt: String? = null
)

@Entity(tableName = "roms")
data class RomEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val fileName: String? = null,
    val fileNameNoExt: String? = null,
    val fileSize: Long = 0,
    val platformId: Int,
    val platformSlug: String? = null,
    val platformFsSlug: String? = null,
    val slug: String? = null,
    val summary: String? = null,
    val rating: Float? = null,
    val firstReleaseDate: Long? = null,
    val genres: String = "",
    val regions: String = "",
    val languages: String = "",
    val coverPath: String? = null,
    val hasSaves: Boolean = false,
    val crcHash: String? = null,
    val md5Hash: String? = null,
    val sha1Hash: String? = null,
    val hasMultipleFiles: Boolean = false,
    // JSON-encoded list of {fileName, fileSize} pairs from the server. Null for
    // single-file ROMs. Use RomFile.parseList / serializeList to convert.
    val filesJson: String? = null,
    val updatedAt: String? = null
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val romCount: Int = 0,
    val description: String? = null,
    val coverPath: String? = null,
    val isSmart: Boolean = false,
    // JSON-encoded list of ROM IDs that belong to this collection. Caulker
    // resolves collection -> games via `RomDao.observeByIds(romIds)` rather than
    // storing collectionId on RomEntity, since a ROM can be in many collections.
    val romIdsJson: String? = null,
    val updatedAt: String? = null
)

@Entity(tableName = "saves")
data class SaveEntity(
    @PrimaryKey val id: Int,
    val romId: Int,
    val deviceId: String? = null,
    val slot: String = "default",
    val emulator: String? = null,
    val fileName: String,
    val fileSize: Long = 0,
    val updatedAt: String? = null
)
