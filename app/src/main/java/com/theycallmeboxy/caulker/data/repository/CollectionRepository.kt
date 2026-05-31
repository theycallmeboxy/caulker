package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.api.RommApiService
import com.theycallmeboxy.caulker.data.api.model.CollectionResponse
import com.theycallmeboxy.caulker.data.db.dao.CollectionDao
import com.theycallmeboxy.caulker.data.db.entity.CollectionEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionRepository @Inject constructor(
    private val api: RommApiService,
    private val dao: CollectionDao
) {
    fun observeAll(): Flow<List<CollectionEntity>> = dao.observeAll()

    suspend fun getById(id: Int): CollectionEntity? = dao.getById(id)

    suspend fun sync() {
        // Fetch both user-defined and smart collections. The API returns the same
        // shape from both endpoints; we tag isSmart on insert so the UI can group
        // or label them later. Smart collections override is_smart from the
        // response just in case the server doesn't set it on the smart endpoint.
        val user = api.getCollections().map { it.toEntity(forceSmart = false) }
        val smart = api.getSmartCollections().map { it.toEntity(forceSmart = true) }
        dao.upsertAll(user + smart)
        reconcileDeletions(user + smart)
    }

    private suspend fun reconcileDeletions(synced: List<CollectionEntity>) {
        val syncedIds = synced.map { it.id }.toSet()
        val orphaned = dao.getAllIds().filterNot { it in syncedIds }
        if (orphaned.isNotEmpty()) dao.deleteByIds(orphaned)
    }
}

private fun CollectionResponse.toEntity(forceSmart: Boolean) = CollectionEntity(
    id = id,
    name = name,
    romCount = romCount,
    description = description,
    coverPath = coverPathSmall ?: coverPathLarge,
    isSmart = forceSmart || isSmart,
    romIdsJson = if (romIds.isEmpty()) null else
        JSONArray().also { arr -> romIds.forEach { arr.put(it) } }.toString(),
    updatedAt = updatedAt
)
