package com.theycallmeboxy.caulker.data.db.dao

import androidx.room.*
import com.theycallmeboxy.caulker.data.db.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlatformDao {
    @Query("SELECT * FROM platforms ORDER BY name ASC")
    fun observeAll(): Flow<List<PlatformEntity>>

    @Query("SELECT * FROM platforms WHERE id = :id")
    suspend fun getById(id: Int): PlatformEntity?

    @Query("SELECT * FROM platforms")
    suspend fun getAllOnce(): List<PlatformEntity>

    @Query("SELECT id FROM platforms")
    suspend fun getAllIds(): List<Int>

    @Upsert
    suspend fun upsertAll(platforms: List<PlatformEntity>)

    @Query("DELETE FROM platforms WHERE id IN (:ids)")
    suspend fun deleteByIdsBatch(ids: List<Int>)

    suspend fun deleteByIds(ids: List<Int>) {
        ids.chunked(999).forEach { deleteByIdsBatch(it) }
    }

    @Query("DELETE FROM platforms")
    suspend fun deleteAll()
}

@Dao
interface RomDao {
    @Query("SELECT * FROM roms WHERE platformId = :platformId ORDER BY name ASC")
    fun observeByPlatform(platformId: Int): Flow<List<RomEntity>>

    @Query("SELECT * FROM roms WHERE id IN (:ids) ORDER BY name ASC")
    fun observeByIds(ids: List<Int>): Flow<List<RomEntity>>

    @Query("SELECT * FROM roms WHERE id = :id")
    suspend fun getById(id: Int): RomEntity?

    @Query("SELECT * FROM roms WHERE platformId = :platformId AND name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchInPlatform(platformId: Int, query: String): List<RomEntity>

    @Upsert
    suspend fun upsertAll(roms: List<RomEntity>)

    @Query("DELETE FROM roms WHERE platformId = :platformId")
    suspend fun deleteByPlatform(platformId: Int)

    @Query("SELECT id FROM roms WHERE hasSaves = 1")
    suspend fun getIdsWithSaves(): List<Int>

    @Query("SELECT id FROM roms")
    suspend fun getAllIds(): List<Int>

    @Query("SELECT COUNT(*) FROM roms")
    suspend fun countAll(): Int

    @Query("SELECT id FROM roms WHERE platformId = :platformId")
    suspend fun getIdsByPlatform(platformId: Int): List<Int>

    @Query("DELETE FROM roms WHERE id IN (:ids)")
    suspend fun deleteByIdsBatch(ids: List<Int>)

    // SQLite hard-limits to 999 bind variables per query.
    // Chunk the list so we never exceed that.
    suspend fun deleteByIds(ids: List<Int>) {
        ids.chunked(999).forEach { deleteByIdsBatch(it) }
    }

    @Query("DELETE FROM roms")
    suspend fun deleteAll()
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name ASC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Int): CollectionEntity?

    @Query("SELECT id FROM collections")
    suspend fun getAllIds(): List<Int>

    @Upsert
    suspend fun upsertAll(collections: List<CollectionEntity>)

    @Query("DELETE FROM collections WHERE id IN (:ids)")
    suspend fun deleteByIdsBatch(ids: List<Int>)

    suspend fun deleteByIds(ids: List<Int>) {
        ids.chunked(999).forEach { deleteByIdsBatch(it) }
    }

    @Query("DELETE FROM collections")
    suspend fun deleteAll()
}

@Dao
interface SaveDao {
    @Query("SELECT * FROM saves WHERE romId = :romId")
    fun observeByRom(romId: Int): Flow<List<SaveEntity>>

    @Query("SELECT * FROM saves WHERE romId = :romId")
    suspend fun getByRom(romId: Int): List<SaveEntity>

    @Upsert
    suspend fun upsertAll(saves: List<SaveEntity>)

    @Query("DELETE FROM saves WHERE romId = :romId")
    suspend fun deleteByRom(romId: Int)
}
