package com.theycallmeboxy.caulker.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.theycallmeboxy.caulker.data.saves.SavePlatformConfig
import com.theycallmeboxy.caulker.data.saves.presetKeyFromJsonObject
import com.theycallmeboxy.caulker.data.saves.toJsonObject
import com.theycallmeboxy.caulker.data.sync.SyncBaseline
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "caulker_prefs")

enum class PlatformOverrideMode { DEFAULT, SLUG_OVERRIDE, MANUAL }

data class PlatformOverride(
    val mode: PlatformOverrideMode = PlatformOverrideMode.DEFAULT,
    val slug: String? = null,
    val romPath: String? = null,
    val savePath: String? = null,
    val biosPath: String? = null
)

@Singleton
class PrefsStore @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUTH_TOKEN = stringPreferencesKey("auth_token")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val ROM_BASE_PATH = stringPreferencesKey("rom_base_path")
        val SAVE_BASE_PATH = stringPreferencesKey("save_base_path")
        val BIOS_BASE_PATH = stringPreferencesKey("bios_base_path")
        val USERNAME = stringPreferencesKey("username")
        val PLATFORM_OVERRIDES = stringPreferencesKey("platform_overrides")
        val SAVE_SYNC_ENROLLED = stringPreferencesKey("save_sync_enrolled")
        val SAVE_SYNC_SLOT_PREFS = stringPreferencesKey("save_sync_slot_prefs")
        val SAVE_SYNC_BASELINE = stringPreferencesKey("save_sync_baseline")
        val SAVE_PLATFORM_CONFIGS = stringPreferencesKey("save_platform_configs")
        val INSECURE_SKIP_VERIFY = booleanPreferencesKey("insecure_skip_verify")
        val GLOBAL_ROM_SYNC_TIME = longPreferencesKey("global_rom_sync_time")
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[Keys.SERVER_URL] }
    val authToken: Flow<String?> = context.dataStore.data.map { it[Keys.AUTH_TOKEN] }
    val deviceId: Flow<String?> = context.dataStore.data.map { it[Keys.DEVICE_ID] }
    val deviceName: Flow<String?> = context.dataStore.data.map { it[Keys.DEVICE_NAME] }
    val romBasePath: Flow<String?> = context.dataStore.data.map { it[Keys.ROM_BASE_PATH] }
    val saveBasePath: Flow<String?> = context.dataStore.data.map { it[Keys.SAVE_BASE_PATH] }
    val biosBasePath: Flow<String?> = context.dataStore.data.map { it[Keys.BIOS_BASE_PATH] }
    val username: Flow<String?> = context.dataStore.data.map { it[Keys.USERNAME] }

    val saveSyncEnrolled: Flow<Set<Int>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SAVE_SYNC_ENROLLED]?.let { json ->
            try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getInt(it) }.toSet()
            } catch (_: Exception) { emptySet() }
        } ?: emptySet()
    }

    suspend fun enrollInSaveSync(romId: Int) = context.dataStore.edit { prefs ->
        val current = parseEnrolled(prefs[Keys.SAVE_SYNC_ENROLLED])
        prefs[Keys.SAVE_SYNC_ENROLLED] = serializeEnrolled(current + romId)
    }

    suspend fun unenrollFromSaveSync(romId: Int) = context.dataStore.edit { prefs ->
        val current = parseEnrolled(prefs[Keys.SAVE_SYNC_ENROLLED])
        prefs[Keys.SAVE_SYNC_ENROLLED] = serializeEnrolled(current - romId)
        removeBaselinesForRom(prefs, romId)
    }

    // Batched variants for bulk selection actions — one read-modify-write instead
    // of one per ROM (enrolling hundreds of games would otherwise mean hundreds
    // of DataStore edits/fsyncs).
    suspend fun enrollAllInSaveSync(romIds: Collection<Int>) = context.dataStore.edit { prefs ->
        val current = parseEnrolled(prefs[Keys.SAVE_SYNC_ENROLLED])
        prefs[Keys.SAVE_SYNC_ENROLLED] = serializeEnrolled(current + romIds)
    }

    suspend fun unenrollAllFromSaveSync(romIds: Collection<Int>) = context.dataStore.edit { prefs ->
        val current = parseEnrolled(prefs[Keys.SAVE_SYNC_ENROLLED])
        prefs[Keys.SAVE_SYNC_ENROLLED] = serializeEnrolled(current - romIds)
        romIds.forEach { removeBaselinesForRom(prefs, it) }
    }

    private fun parseEnrolled(json: String?): Set<Int> {
        json ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getInt(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun serializeEnrolled(ids: Set<Int>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    val platformOverrides: Flow<Map<String, PlatformOverride>> = context.dataStore.data.map { prefs ->
        parsePlatformOverrides(prefs[Keys.PLATFORM_OVERRIDES])
    }

    suspend fun getPlatformOverride(fsSlug: String?): PlatformOverride? {
        if (fsSlug == null) return null
        return platformOverrides.first()[fsSlug]
    }

    suspend fun setPlatformOverride(fsSlug: String, override: PlatformOverride) {
        context.dataStore.edit { prefs ->
            val map = parsePlatformOverrides(prefs[Keys.PLATFORM_OVERRIDES]).toMutableMap()
            if (override.mode == PlatformOverrideMode.DEFAULT) {
                map.remove(fsSlug)
            } else {
                map[fsSlug] = override
            }
            prefs[Keys.PLATFORM_OVERRIDES] = serializePlatformOverrides(map)
        }
    }

    suspend fun setServerUrl(url: String) = context.dataStore.edit { it[Keys.SERVER_URL] = url }
    suspend fun setAuthToken(token: String) = context.dataStore.edit { it[Keys.AUTH_TOKEN] = token }
    suspend fun setDeviceId(id: String) = context.dataStore.edit { it[Keys.DEVICE_ID] = id }
    suspend fun setDeviceName(name: String) = context.dataStore.edit { it[Keys.DEVICE_NAME] = name }
    suspend fun setRomBasePath(path: String) = context.dataStore.edit { it[Keys.ROM_BASE_PATH] = path }
    suspend fun setSaveBasePath(path: String) = context.dataStore.edit { it[Keys.SAVE_BASE_PATH] = path }
    suspend fun setBiosBasePath(path: String) = context.dataStore.edit { it[Keys.BIOS_BASE_PATH] = path }
    suspend fun setUsername(name: String) = context.dataStore.edit { it[Keys.USERNAME] = name }

    val insecureSkipVerify: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.INSECURE_SKIP_VERIFY] ?: false }

    suspend fun setInsecureSkipVerify(value: Boolean) =
        context.dataStore.edit { it[Keys.INSECURE_SKIP_VERIFY] = value }

    fun saveSyncSlotPref(romId: Int): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SAVE_SYNC_SLOT_PREFS]?.let { json ->
            try { JSONObject(json).optString(romId.toString(), "default") }
            catch (_: Exception) { "default" }
        } ?: "default"
    }

    suspend fun setSaveSyncSlotPref(romId: Int, slotKey: String) {
        context.dataStore.edit { prefs ->
            val obj = try {
                prefs[Keys.SAVE_SYNC_SLOT_PREFS]?.let { JSONObject(it) } ?: JSONObject()
            } catch (_: Exception) { JSONObject() }
            obj.put(romId.toString(), slotKey)
            prefs[Keys.SAVE_SYNC_SLOT_PREFS] = obj.toString()
        }
    }

    // The content hash (+ diagnostics) of the save version this device last
    // successfully synced for (romId, slotKey) — the common-ancestor baseline
    // determineSyncAction() uses for its 3-way merge. Written after every
    // successful upload/download/conflict-resolution, cleared on unenroll.
    suspend fun getSyncBaseline(romId: Int, slotKey: String): SyncBaseline? {
        val json = context.dataStore.data.first()[Keys.SAVE_SYNC_BASELINE] ?: return null
        return try {
            val entry = JSONObject(json).optJSONObject(baselineKey(romId, slotKey)) ?: return null
            val hash = entry.optString("hash").takeIf { it.isNotBlank() } ?: return null
            SyncBaseline(
                contentHash = hash,
                saveId = entry.optInt("saveId", -1).takeIf { it >= 0 },
                updatedAt = entry.optString("updatedAt").takeIf { it.isNotBlank() },
                resolvedPath = entry.optString("path").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) { null }
    }

    suspend fun setSyncBaseline(romId: Int, slotKey: String, baseline: SyncBaseline) {
        context.dataStore.edit { prefs ->
            val obj = try {
                prefs[Keys.SAVE_SYNC_BASELINE]?.let { JSONObject(it) } ?: JSONObject()
            } catch (_: Exception) { JSONObject() }
            val entry = JSONObject()
            entry.put("hash", baseline.contentHash)
            baseline.saveId?.let { entry.put("saveId", it) }
            baseline.updatedAt?.let { entry.put("updatedAt", it) }
            baseline.resolvedPath?.let { entry.put("path", it) }
            obj.put(baselineKey(romId, slotKey), entry)
            prefs[Keys.SAVE_SYNC_BASELINE] = obj.toString()
        }
    }

    private fun baselineKey(romId: Int, slotKey: String) = "$romId:$slotKey"

    // --- Per-platform save-location config (save-sync design doc, Part 2 §12
    // phase 3) -- the preset + folder a user has chosen for a platform's save
    // sync, keyed by fs slug. No entry (the default for every platform) means
    // legacy resolution: effectiveSaveDir + resolveLocalSaveFileName, exactly
    // as before this existed (§6) -- see SaveRepository/SaveSyncOrchestrator's
    // configured-vs-legacy branch, which treats a null result from
    // getSavePlatformConfig identically to "phase 3A never shipped."
    val savePlatformConfigs: Flow<Map<String, SavePlatformConfig>> = context.dataStore.data.map { prefs ->
        parseSavePlatformConfigs(prefs[Keys.SAVE_PLATFORM_CONFIGS])
    }

    fun savePlatformConfig(fsSlug: String): Flow<SavePlatformConfig?> =
        savePlatformConfigs.map { it[fsSlug] }

    suspend fun getSavePlatformConfig(fsSlug: String?): SavePlatformConfig? {
        if (fsSlug.isNullOrBlank()) return null
        return savePlatformConfigs.first()[fsSlug]
    }

    suspend fun setSavePlatformConfig(fsSlug: String, config: SavePlatformConfig) {
        context.dataStore.edit { prefs ->
            val map = parseSavePlatformConfigs(prefs[Keys.SAVE_PLATFORM_CONFIGS]).toMutableMap()
            map[fsSlug] = config
            prefs[Keys.SAVE_PLATFORM_CONFIGS] = serializeSavePlatformConfigs(map)
        }
    }

    suspend fun clearSavePlatformConfig(fsSlug: String) {
        context.dataStore.edit { prefs ->
            val map = parseSavePlatformConfigs(prefs[Keys.SAVE_PLATFORM_CONFIGS]).toMutableMap()
            map.remove(fsSlug)
            prefs[Keys.SAVE_PLATFORM_CONFIGS] = serializeSavePlatformConfigs(map)
        }
    }

    private fun parseSavePlatformConfigs(json: String?): Map<String, SavePlatformConfig> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().mapNotNull { key ->
                val entry = obj.getJSONObject(key)
                val presetKey = entry.optJSONObject("preset")?.let { presetKeyFromJsonObject(it) }
                    ?: return@mapNotNull null
                val folderPath = entry.optString("folderPath").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                key to SavePlatformConfig(presetKey, folderPath)
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun serializeSavePlatformConfigs(map: Map<String, SavePlatformConfig>): String {
        val obj = JSONObject()
        map.forEach { (key, config) ->
            val entry = JSONObject()
            entry.put("preset", config.presetKey.toJsonObject())
            entry.put("folderPath", config.folderPath)
            obj.put(key, entry)
        }
        return obj.toString()
    }

    // Drops every slot's baseline for a ROM (called from within an existing
    // dataStore.edit block so it composes with the enrollment-set update).
    private fun removeBaselinesForRom(prefs: androidx.datastore.preferences.core.MutablePreferences, romId: Int) {
        val json = prefs[Keys.SAVE_SYNC_BASELINE] ?: return
        try {
            val obj = JSONObject(json)
            val prefix = "$romId:"
            val toRemove = obj.keys().asSequence().filter { it.startsWith(prefix) }.toList()
            if (toRemove.isEmpty()) return
            toRemove.forEach { obj.remove(it) }
            prefs[Keys.SAVE_SYNC_BASELINE] = obj.toString()
        } catch (_: Exception) { /* leave baseline prefs untouched on parse failure */ }
    }

    // Cursor for the global incremental ROM sweep (updated_after). 0 = never synced.
    suspend fun getGlobalRomSyncTime(): Long =
        context.dataStore.data.first()[Keys.GLOBAL_ROM_SYNC_TIME] ?: 0L

    val globalRomSyncTime: Flow<Long> =
        context.dataStore.data.map { it[Keys.GLOBAL_ROM_SYNC_TIME] ?: 0L }

    suspend fun setGlobalRomSyncTime(timeMs: Long) =
        context.dataStore.edit { it[Keys.GLOBAL_ROM_SYNC_TIME] = timeMs }

    suspend fun clearAuth() = context.dataStore.edit {
        it.remove(Keys.AUTH_TOKEN)
        it.remove(Keys.DEVICE_ID)
        it.remove(Keys.USERNAME)
    }

    private fun parsePlatformOverrides(json: String?): Map<String, PlatformOverride> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().mapNotNull { key ->
                val entry = obj.getJSONObject(key)
                val mode = try {
                    PlatformOverrideMode.valueOf(entry.getString("mode"))
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                key to PlatformOverride(
                    mode = mode,
                    slug = entry.optString("slug").takeIf { it.isNotBlank() },
                    romPath = entry.optString("romPath").takeIf { it.isNotBlank() },
                    savePath = entry.optString("savePath").takeIf { it.isNotBlank() },
                    biosPath = entry.optString("biosPath").takeIf { it.isNotBlank() }
                )
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun serializePlatformOverrides(map: Map<String, PlatformOverride>): String {
        val obj = JSONObject()
        map.forEach { (key, override) ->
            val entry = JSONObject()
            entry.put("mode", override.mode.name)
            override.slug?.let { entry.put("slug", it) }
            override.romPath?.let { entry.put("romPath", it) }
            override.savePath?.let { entry.put("savePath", it) }
            override.biosPath?.let { entry.put("biosPath", it) }
            obj.put(key, entry)
        }
        return obj.toString()
    }
}
