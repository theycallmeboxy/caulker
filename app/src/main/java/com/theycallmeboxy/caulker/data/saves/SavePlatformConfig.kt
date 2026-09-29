package com.theycallmeboxy.caulker.data.saves

import org.json.JSONObject

// Per-platform save-location configuration (save-sync design doc, Part 2 §12
// phase 3): the preset + folder a user has picked for a platform, keyed by
// that platform's fs slug in PrefsStore (data/prefs/PrefsStore.kt), kept
// separate from PlatformOverride (Part 1 -- unrelated ROM/BIOS path
// overrides, §6). No entry for a platform means legacy resolution (§6) --
// SaveRepository.effectiveSaveDir + resolveLocalSaveFileName, byte-for-byte
// unchanged.
data class SavePlatformConfig(
    val presetKey: PresetKey,
    val folderPath: String
)

// JSON (de)serialization for PresetKey/SavePlatformConfig, used by PrefsStore.
// Kept here, next to PresetKey's definition, rather than inlined in
// PrefsStore -- one source of truth for its two subtypes' wire shape,
// matching how PrefsStore's other JSON blobs (PlatformOverride,
// SyncBaseline) are hand-rolled with org.json rather than a serialization
// library, per the surrounding style.
internal fun PresetKey.toJsonObject(): JSONObject {
    val obj = JSONObject()
    when (this) {
        is PresetKey.RetroArchCore -> {
            obj.put("type", "RetroArchCore")
            obj.put("system", system)
            obj.put("core", core)
        }
        is PresetKey.Standalone -> {
            obj.put("type", "Standalone")
            obj.put("system", system)
            obj.put("emulatorApp", emulatorApp)
        }
    }
    return obj
}

// Returns null on anything unparseable (unknown/missing "type", missing
// field) -- an unset config is exactly what a platform with no v1 save
// config looks like (§6), so a corrupt/foreign entry degrades to "not
// configured" rather than crashing PrefsStore's read.
internal fun presetKeyFromJsonObject(obj: JSONObject): PresetKey? = try {
    when (obj.getString("type")) {
        "RetroArchCore" -> PresetKey.RetroArchCore(obj.getString("system"), obj.getString("core"))
        "Standalone" -> PresetKey.Standalone(obj.getString("system"), obj.getString("emulatorApp"))
        else -> null
    }
} catch (_: Exception) {
    null
}
