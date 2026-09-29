package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for SavePresetRegistry (save-sync design doc, Part 2
// §10.x/§7, §13 test plan): every pattern is safe and contains {name}, every
// preset's emulator id matches §7's table, and the RomM slug -> preset
// lookup behaves per §6 (unmapped slug -> empty/no preset, never a guess).
class SavePresetRegistryTest {

    private fun allPresets(): List<SavePreset> =
        listOf(
            "nes", "famicom", "snes", "sfam", "gb", "gbc", "gba",
            "genesis", "sms", "gamegear", "sg1000", "sega32",
            "segacd", "segacd32", "tg16", "turbografx-cd",
            "n64", "psx", "saturn", "dc", "nds", "psp"
        ).flatMap { SavePresetRegistry.presetsForPlatform(it) }.map { it.preset }

    @Test
    fun `every preset's patterns are safe and contain the name token`() {
        val presets = allPresets()
        assertTrue("expected at least one preset in the registry", presets.isNotEmpty())
        for (preset in presets) {
            for (pattern in preset.patterns) {
                assertTrue(
                    "${preset.key} pattern \"$pattern\" must contain $NAME_TOKEN",
                    pattern.contains(NAME_TOKEN)
                )
                assertNotNull(
                    "${preset.key} pattern \"$pattern\" resolved to an unsafe path",
                    resolveSafeRelativePath(pattern, "Some Game (USA)")
                )
            }
        }
    }

    @Test
    fun `SAVE_TARGET presets declare a saveTargetLayout`() {
        for (preset in allPresets()) {
            if (preset.matchingKey == MatchingKeyKind.SAVE_TARGET) {
                assertNotNull("${preset.key} needs a saveTargetLayout", preset.saveTargetLayout)
            }
        }
    }

    @Test
    fun `no two presets in the same platform's list share a core`() {
        val slugs = listOf("nes", "snes", "gb", "gba", "genesis", "segacd", "tg16", "n64", "psx", "saturn", "dc", "nds", "psp")
        for (slug in slugs) {
            val cores = SavePresetRegistry.presetsForPlatform(slug).map { it.preset.key }
            assertEquals("$slug has duplicate preset keys", cores.size, cores.toSet().size)
        }
    }

    // Section 7's id table, RetroArch cores -- every preset this registry
    // declares must upload exactly this id.
    private val expectedEmulatorIds: Map<String, String> = mapOf(
        "fceumm" to "fceumm",
        "nestopia" to "nestopia",
        "mesen" to "mesen",
        "snes9x" to "snes9x",
        "snes9x2010" to "snes9x2010",
        "bsnes" to "bsnes",
        "gambatte" to "gambatte",
        "sameboy" to "sameboy",
        "gearboy" to "gearboy",
        "mgba" to "mgba",
        "gpsp" to "gpsp",
        "vbam" to "vbam",
        "genesis_plus_gx" to "genesis_plus_gx",
        "picodrive" to "picodrive",
        "mednafen_pce" to "mednafen_pce",
        "mednafen_pce_fast" to "mednafen_pce_fast",
        "mupen64plus_next" to "mupen64plus_next",
        "parallel_n64" to "parallel_n64",
        "mednafen_psx" to "mednafen_psx",
        "mednafen_psx_hw" to "mednafen_psx_hw",
        "swanstation" to "swanstation",
        "pcsx_rearmed" to "pcsx_rearmed",
        "mednafen_saturn" to "mednafen_saturn",
        "yabause" to "yabause",
        "flycast" to "flycast",
        "melondsds" to "melondsds",
        "melonds" to "melonds",
        "desmume" to "desmume",
        "desmume2015" to "desmume2015",
        "ppsspp" to "ppsspp"
    )

    @Test
    fun `every preset uploads the id from paragraph 7's table`() {
        for (preset in allPresets()) {
            val core = (preset.key as PresetKey.RetroArchCore).core
            val expected = expectedEmulatorIds[core]
            assertNotNull("no expected id recorded for core \"$core\" -- update this test's table", expected)
            assertEquals("${preset.key} uploads the wrong emulator id", expected, preset.emulatorId)
        }
    }

    @Test
    fun `an unmapped RomM slug returns no presets rather than guessing`() {
        assertTrue(SavePresetRegistry.presetsForPlatform("some-platform-not-in-v1").isEmpty())
        assertTrue(SavePresetRegistry.presetsForPlatform(null).isEmpty())
        assertNull(SavePresetRegistry.presetFor("some-platform-not-in-v1", PresetKey.RetroArchCore("nes", "fceumm")))
    }

    @Test
    fun `presetFor resolves a known key back to its preset`() {
        val preset = SavePresetRegistry.presetFor("nes", PresetKey.RetroArchCore("nes", "fceumm"))
        assertNotNull(preset)
        assertEquals("fceumm", preset?.emulatorId)
    }

    @Test
    fun `segacd32 does not offer Genesis Plus GX, which has no 32X support`() {
        val cores = SavePresetRegistry.presetsForPlatform("segacd32").map { (it.preset.key as PresetKey.RetroArchCore).core }
        assertTrue(cores.contains("picodrive"))
        assertTrue("segacd32 must not offer genesis_plus_gx", !cores.contains("genesis_plus_gx"))
        // Plain Sega CD is unaffected -- it keeps both cores.
        val segaCdCores = SavePresetRegistry.presetsForPlatform("segacd").map { (it.preset.key as PresetKey.RetroArchCore).core }
        assertTrue(segaCdCores.contains("genesis_plus_gx"))
    }

    @Test
    fun `slug aliases map to the same preset list as their canonical slug`() {
        assertEquals(
            SavePresetRegistry.presetsForPlatform("nes").map { it.preset.key },
            SavePresetRegistry.presetsForPlatform("famicom").map { it.preset.key }
        )
        assertEquals(
            SavePresetRegistry.presetsForPlatform("snes").map { it.preset.key },
            SavePresetRegistry.presetsForPlatform("sfam").map { it.preset.key }
        )
    }
}
