package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Test

// Pure-JVM tests for emulatorIdFor() (§7 upload `emulator` field / §13 test
// plan). The real per-emulator table (§7/§9/§10.x) is Phase 4 data; these
// fixtures are representative, not exhaustive.
class SaveEmulatorIdTest {

    @Test
    fun `no preset configured falls back to caulker (today's exact behavior)`() {
        assertEquals("caulker", emulatorIdFor(null))
        assertEquals(FALLBACK_EMULATOR_ID, emulatorIdFor(null))
    }

    @Test
    fun `a RetroArch preset uploads its libretro core slug, not 'retroarch'`() {
        val preset = SavePreset(
            key = PresetKey.RetroArchCore("n64", "mupen64plus_next"),
            mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE,
            patterns = listOf("{name}.srm"),
            matchingKey = MatchingKeyKind.ROM_STEM,
            emulatorId = "mupen64plus_next"
        )
        assertEquals("mupen64plus_next", emulatorIdFor(preset))
    }

    // Mupen64Plus-Next's GLES2/GLES3 build variants both alias to the same id
    // (§7's id table) -- modeled here as two distinct preset keys resolving to
    // the same emulatorId.
    @Test
    fun `Mupen64Plus-Next GLES2 and GLES3 variants alias to the same id`() {
        val gles2 = SavePreset(
            key = PresetKey.RetroArchCore("n64", "mupen64plus_next_gles2"),
            mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE,
            patterns = listOf("{name}.srm"),
            matchingKey = MatchingKeyKind.ROM_STEM,
            emulatorId = "mupen64plus_next"
        )
        val gles3 = gles2.copy(key = PresetKey.RetroArchCore("n64", "mupen64plus_next_gles3"))
        assertEquals(emulatorIdFor(gles2), emulatorIdFor(gles3))
    }

    @Test
    fun `a standalone emulator preset uploads its app id`() {
        val preset = SavePreset(
            key = PresetKey.Standalone("ps1", "duckstation"),
            mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE,
            patterns = listOf("{name}.mcd"),
            matchingKey = MatchingKeyKind.ROM_STEM,
            emulatorId = "duckstation"
        )
        assertEquals("duckstation", emulatorIdFor(preset))
    }

    @Test
    fun `a blank emulatorId on a configured preset still falls back to caulker`() {
        val preset = SavePreset(
            key = PresetKey.Standalone("ps1", "misconfigured"),
            mode = SaveSyncMode.DIRECT,
            shape = SaveShape.SINGLE_FILE,
            patterns = listOf("{name}.mcd"),
            matchingKey = MatchingKeyKind.ROM_STEM,
            emulatorId = ""
        )
        assertEquals(FALLBACK_EMULATOR_ID, emulatorIdFor(preset))
    }
}
