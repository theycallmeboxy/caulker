package com.theycallmeboxy.caulker.data.api

import org.json.JSONObject
import retrofit2.HttpException

// Thrown when a save upload returns HTTP 409 with a structured conflict payload
// from RomM. Mirrors grout's ConflictError. The server returns either
// `{error, message, save_id, current_save_time, device_sync_time}` directly or
// nested under `{detail: {...}}` (FastAPI style).
class SaveConflictException(
    val errorType: String,
    val conflictMessage: String,
    val saveId: Int,
    val currentSaveTime: String?,
    val deviceSyncTime: String?
) : Exception("Save conflict ($errorType): $conflictMessage (saveId=$saveId)") {

    companion object {
        // Returns a parsed SaveConflictException if the HttpException is a 409 with
        // a recognizable conflict body; returns null otherwise so the caller can
        // re-throw the original exception.
        fun parse(e: HttpException): SaveConflictException? {
            if (e.code() != 409) return null
            val raw = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                ?: return null
            return try {
                val root = JSONObject(raw)
                val payload = root.optJSONObject("detail") ?: root
                val errorType = payload.optString("error").takeIf { it.isNotBlank() }
                    ?: payload.optString("error_type").takeIf { it.isNotBlank() }
                    ?: return null
                SaveConflictException(
                    errorType = errorType,
                    conflictMessage = payload.optString("message").takeIf { it.isNotBlank() } ?: raw,
                    saveId = payload.optInt("save_id", 0),
                    currentSaveTime = payload.optString("current_save_time").takeIf { it.isNotBlank() },
                    deviceSyncTime = payload.optString("device_sync_time").takeIf { it.isNotBlank() }
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
