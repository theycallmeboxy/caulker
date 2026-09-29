package com.theycallmeboxy.caulker.data.saves

// The upload `emulator` id sent when no preset is configured for a platform
// (§6/§7 backward compat) -- unchanged from today's hardcoded value
// (previously inlined in SaveRepository.uploadBytes).
const val FALLBACK_EMULATOR_ID = "caulker"

// Resolves the id to upload in the `emulator` field (§7): the configured
// preset's own id, or the backward-compatible fallback when no preset is
// configured for the platform.
fun emulatorIdFor(preset: SavePreset?): String =
    preset?.emulatorId?.takeIf { it.isNotBlank() } ?: FALLBACK_EMULATOR_ID
