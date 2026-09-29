package com.theycallmeboxy.caulker.data.saves

import org.junit.Assert.assertEquals
import org.junit.Test

// Pure-JVM tests for SaveFormatFamilies.build() against downloadGuardDecision
// (save-sync design doc, Part 2 §7/§10, §13 test plan "download guard
// decision table"): §10's confirmed interchangeable families silently
// allow, confirmed non-interchangeable pairs warn, and an id §10 only calls
// "likely interoperable, not byte-diffed" (Genesis Plus GX vs. PicoDrive)
// stays unmapped -- unknown compatibility, same silent-allow behavior as
// today, not a guess in either direction.
class SaveFormatFamiliesTest {
    private val compatibility = SaveFormatFamilies.build()

    @Test
    fun `PS1 128 KiB memory-card family allows across all four cores`() {
        val ids = listOf("mednafen_psx", "mednafen_psx_hw", "swanstation", "pcsx_rearmed")
        for (incoming in ids) {
            for (configured in ids) {
                assertEquals(
                    "$incoming -> $configured should ALLOW",
                    DownloadGuardDecision.ALLOW,
                    downloadGuardDecision(incoming, configured, compatibility)
                )
            }
        }
    }

    @Test
    fun `N64 srm struct family allows between Mupen64Plus-Next and ParaLLEl N64`() {
        assertEquals(
            DownloadGuardDecision.ALLOW,
            downloadGuardDecision("mupen64plus_next", "parallel_n64", compatibility)
        )
    }

    @Test
    fun `PCE srm family allows between Beetle PCE and Beetle PCE Fast`() {
        assertEquals(
            DownloadGuardDecision.ALLOW,
            downloadGuardDecision("mednafen_pce", "mednafen_pce_fast", compatibility)
        )
    }

    @Test
    fun `Saturn cores warn -- confirmed not interchangeable`() {
        assertEquals(
            DownloadGuardDecision.WARN_INCOMPATIBLE,
            downloadGuardDecision("mednafen_saturn", "yabause", compatibility)
        )
    }

    @Test
    fun `DeSmuME onto a melonDS-configured device warns -- confirmed not interchangeable`() {
        assertEquals(
            DownloadGuardDecision.WARN_INCOMPATIBLE,
            downloadGuardDecision("desmume", "melondsds", compatibility)
        )
        assertEquals(
            DownloadGuardDecision.WARN_INCOMPATIBLE,
            downloadGuardDecision("desmume2015", "melonds", compatibility)
        )
    }

    @Test
    fun `Genesis Plus GX vs PicoDrive is unconfirmed, not mapped, stays a silent allow`() {
        // §10 only calls this pair "likely interoperable ... not byte-diffed"
        // for the plain Genesis/MD family (as opposed to Sega CD's confirmed
        // non-interoperable .brm/.srm) -- deliberately absent from the table
        // (SaveFormatFamilies.kt's own doc comment), so this exercises
        // downloadGuardDecision's unknown-id fallback, unchanged from today.
        assertEquals(
            DownloadGuardDecision.ALLOW,
            downloadGuardDecision("genesis_plus_gx", "picodrive", compatibility)
        )
    }
}
