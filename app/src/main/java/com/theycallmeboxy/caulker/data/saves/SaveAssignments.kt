package com.theycallmeboxy.caulker.data.saves

import org.json.JSONObject

// Wire format for one platform's manual Unassigned-file assignments (save-sync
// design doc, Part 2 §3 rule 3 / §12 phase 3): romId -> the relative path (within
// that platform's configured folder) the user pointed at that ROM. Kept as pure
// JSON (de)serialization, same split as SavePlatformConfig.kt's
// toJsonObject/presetKeyFromJsonObject -- PrefsStore owns the actual DataStore
// key/read/write (one platform's assignments live inside a larger per-platform
// JSONObject there), this file only owns the wire shape so it's JVM-testable
// without a DataStore/Context.

// Parses one platform's assignment JSONObject ({"<romId>": "<relativePath>", ...})
// into a Map<Int, String>. Any entry with a non-integer key, a blank path, or an
// unsafe path (SavePathSafety.kt -- defense in depth; the path should already be
// a real scanned relative path when it was written, but this is a stored value
// read back later, not a value just validated at the point of use) is dropped
// rather than failing the whole platform's map. A totally unparseable object
// (or null) yields an empty map -- indistinguishable from "no assignments yet."
fun parseSaveAssignments(obj: JSONObject?): Map<Int, String> {
    obj ?: return emptyMap()
    return obj.keys().asSequence().mapNotNull { key ->
        val romId = key.toIntOrNull() ?: return@mapNotNull null
        val path = obj.optString(key).takeIf { it.isNotBlank() && isSafeRelativePath(it) } ?: return@mapNotNull null
        romId to path
    }.toMap()
}

fun serializeSaveAssignments(map: Map<Int, String>): JSONObject {
    val obj = JSONObject()
    map.forEach { (romId, path) -> obj.put(romId.toString(), path) }
    return obj
}
