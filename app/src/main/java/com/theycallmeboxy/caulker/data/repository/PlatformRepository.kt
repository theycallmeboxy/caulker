package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.api.RommApiService
import com.theycallmeboxy.caulker.data.db.dao.PlatformDao
import com.theycallmeboxy.caulker.data.db.entity.PlatformEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlatformRepository @Inject constructor(
    private val api: RommApiService,
    private val dao: PlatformDao
) {
    fun observePlatforms(): Flow<List<PlatformEntity>> = dao.observeAll()

    suspend fun getById(id: Int): PlatformEntity? = dao.getById(id)

    suspend fun sync() {
        val remote = api.getPlatforms()
        // When two platforms share a display name (e.g., multiple "Arcade" entries),
        // append the fs_slug so the UI can tell them apart: "Arcade (fbneo)".
        val nameCounts = remote.groupingBy { it.name }.eachCount()
        dao.upsertAll(remote.map {
            val displayName = if ((nameCounts[it.name] ?: 0) > 1 && !it.fsSlug.isNullOrBlank())
                "${it.name} (${it.fsSlug})"
            else it.name
            PlatformEntity(
                id = it.id,
                name = displayName,
                slug = it.slug,
                fsSlug = it.fsSlug,
                romCount = it.romCount,
                firmwareCount = it.firmwareCount,
                logoPath = it.logoPath,
                updatedAt = it.updatedAt
            )
        })
        reconcileDeletions()
    }

    // Pulls the canonical list of platform IDs from the server and drops local
    // rows whose IDs don't appear — handles the case where a platform was
    // deleted server-side. updated_after sync alone can't see deletions.
    private suspend fun reconcileDeletions() {
        val serverIds = try { api.getPlatformIdentifiers().toSet() } catch (_: Exception) { return }
        val localIds = dao.getAllIds()
        val orphaned = localIds.filterNot { it in serverIds }
        if (orphaned.isNotEmpty()) dao.deleteByIds(orphaned)
    }
}
