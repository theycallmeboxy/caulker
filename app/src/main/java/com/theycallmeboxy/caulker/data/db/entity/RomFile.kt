package com.theycallmeboxy.caulker.data.db.entity

import org.json.JSONArray
import org.json.JSONObject

// Lightweight projection of a ROM's `files[]` entry, stored on RomEntity as
// JSON in `filesJson`. Only the fields Caulker actually consumes are kept.
data class RomFile(
    val fileName: String,
    val fileSize: Long
) {
    companion object {
        fun parseList(json: String?): List<RomFile> {
            json ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = obj.optString("fileName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    RomFile(name, obj.optLong("fileSize", 0L))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun serializeList(files: List<RomFile>): String? {
            if (files.isEmpty()) return null
            val arr = JSONArray()
            files.forEach {
                arr.put(JSONObject().apply {
                    put("fileName", it.fileName)
                    put("fileSize", it.fileSize)
                })
            }
            return arr.toString()
        }
    }
}
