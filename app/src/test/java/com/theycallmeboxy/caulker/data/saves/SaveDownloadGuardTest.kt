package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Test

// Pure-JVM tests for downloadGuardDecision() (§7 "Client-side download guard
// (planned)" / §13 test plan). Not wired into any download path in Phase 1.
class SaveDownloadGuardTest {

    // A representative slice of §10's interchangeable/non-interchangeable
    // families -- the real table is Phase 4 data.
    private val compatibility = FormatCompatibility(
        mapOf(
            // PS1 128 KiB memory card -- interchangeable family.
            "mednafen_psx_hw" to "ps1_memcard",
            "mednafen_psx" to "ps1_memcard",
            "swanstation" to "ps1_memcard",
            "pcsx_rearmed" to "ps1_memcard",
            // Nintendo DS -- melonDS/melonDS DS raw saves are one family;
            // DeSmuME's footer-carrying .dsv is a different, incompatible one.
            "melonds" to "nds_raw",
            "melondsds" to "nds_raw",
            "desmume" to "nds_dsv",
            "desmume2015" to "nds_dsv",
            // Saturn -- Beetle Saturn and Yabause are confirmed NOT interchangeable.
            "mednafen_saturn" to "saturn_beetle",
            "yabause" to "saturn_yabause"
        )
    )

    @Test
    fun `known interchangeable pair silently allows`() {
        val result = downloadGuardDecision("swanstation", "mednafen_psx_hw", compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    @Test
    fun `known incompatible pair warns - DeSmuME onto melonDS`() {
        val result = downloadGuardDecision("desmume", "melonds", compatibility)
        assertEquals(DownloadGuardDecision.WARN_INCOMPATIBLE, result)
    }

    @Test
    fun `known incompatible pair warns - Beetle Saturn onto Yabause`() {
        val result = downloadGuardDecision("mednafen_saturn", "yabause", compatibility)
        assertEquals(DownloadGuardDecision.WARN_INCOMPATIBLE, result)
    }

    @Test
    fun `same id always allows`() {
        val result = downloadGuardDecision("melonds", "melonds", compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    @Test
    fun `unknown incoming id allows`() {
        val result = downloadGuardDecision("some_future_core", "melonds", compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    @Test
    fun `unknown configured id allows`() {
        val result = downloadGuardDecision("desmume", "some_future_core", compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    @Test
    fun `null incoming tag allows (today's behavior, unchanged)`() {
        val result = downloadGuardDecision(null, "melonds", compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    @Test
    fun `caulker tag allows regardless of what's configured`() {
        val result = downloadGuardDecision("caulker", "melonds", compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    @Test
    fun `no preset configured locally allows`() {
        val result = downloadGuardDecision("desmume", null, compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    @Test
    fun `configured id still caulker allows`() {
        val result = downloadGuardDecision("desmume", "caulker", compatibility)
        assertEquals(DownloadGuardDecision.ALLOW, result)
    }

    // --- downloadNeedsGuardConfirmation (Phase 3B: the flag is now on, and
    // this is what SaveLocationRepository.download() and the ViewModels
    // deciding whether to show the confirmation dialog both call) ----------

    @Test
    fun `needs confirmation mirrors WARN_INCOMPATIBLE when the gate is enabled`() {
        // DOWNLOAD_GUARD_ENABLED is a compile-time const, true as of Phase
        // 3B -- this asserts downloadNeedsGuardConfirmation agrees with
        // downloadGuardDecision rather than re-deciding independently.
        assertEquals(true, DOWNLOAD_GUARD_ENABLED)
        assertEquals(true, downloadNeedsGuardConfirmation("desmume", "melonds", compatibility))
    }

    @Test
    fun `needs confirmation is false for an interchangeable pair`() {
        assertEquals(false, downloadNeedsGuardConfirmation("swanstation", "mednafen_psx_hw", compatibility))
    }

    @Test
    fun `needs confirmation is false with no configured preset`() {
        assertEquals(false, downloadNeedsGuardConfirmation("desmume", null, compatibility))
    }

    @Test
    fun `needs confirmation defaults to the real SaveFormatFamilies table when none is passed`() {
        // Uses the default `compatibility` param (SaveFormatFamilies.build())
        // instead of this test's representative slice -- exercises the real
        // Phase 4 preset data's DeSmuME/melonDS split.
        assertEquals(true, downloadNeedsGuardConfirmation("desmume", "melonds"))
    }
}
