package com.theycallmeboxy.caulker.data.saves

// FormatCompatibility families for the download guard (SaveDownloadGuard.kt,
// §7 "Client-side download guard (planned)"), built from §10's confirmed
// interchangeable/non-interchangeable emulator-id groups. Only pairs §10
// explicitly calls an "interchangeable family," or explicitly confirms are
// NOT interchangeable, are mapped here -- every other emulator id (including
// pairs §10 only calls "likely interoperable, not byte-diffed") is left
// unmapped. downloadGuardDecision() already treats an unmapped id as unknown
// compatibility (silent ALLOW, today's behavior) -- the correct default for
// anything §10 doesn't positively confirm either way, rather than guessing.
// Design ambiguity, called out rather than guessed at: `genesis_plus_gx` and
// `picodrive` are deliberately absent from FAMILY_BY_EMULATOR_ID below, even
// though the Sega CD row calls their two save formats there "not
// interoperable," because those same emulator ids are also what the plain
// Genesis/MD/MS/GG/SG-1000/32X family uploads, where the two cores' cart
// saves are only called "likely interoperable ... not byte-diffed"
// (unconfirmed, not a positive interchangeable-family claim). downloadGuardDecision()
// takes just an emulator id, with no system/platform context to
// tell which claim applies to a given incoming save -- mapping either id
// here would misapply one system's verdict to the other. Left unmapped
// until the guard gains enough context to disambiguate by system, not id
// alone.
object SaveFormatFamilies {
    fun build(): FormatCompatibility = FormatCompatibility(FAMILY_BY_EMULATOR_ID)

    private val FAMILY_BY_EMULATOR_ID: Map<String, String> = mapOf(
        // PS1 128 KiB memory-card family -- §10: "Interchangeable family
        // with SwanStation and PCSX ReARMed (same 128 KiB raw format)".
        "mednafen_psx" to "ps1-128k-memcard",
        "mednafen_psx_hw" to "ps1-128k-memcard",
        "swanstation" to "ps1-128k-memcard",
        "pcsx_rearmed" to "ps1-128k-memcard",

        // N64 .srm struct family -- §10: "Byte-identical struct to ParaLLEl
        // N64 -- interchangeable family".
        "mupen64plus_next" to "n64-srm-struct",
        "parallel_n64" to "n64-srm-struct",

        // PC Engine/PCE-CD .srm family -- §10: "Byte-identical layout and
        // magic header to Beetle PCE[ Fast] -- interchangeable family".
        "mednafen_pce" to "pce-srm",
        "mednafen_pce_fast" to "pce-srm",

        // Saturn: confirmed NOT interchangeable (§10: "different image
        // sizes", 32,768 B vs 65,536 B) -- distinct families so the two
        // positively conflict instead of defaulting to unknown/ALLOW.
        "mednafen_saturn" to "saturn-mednafen-32k",
        "yabause" to "saturn-yabause-64k",

        // Nintendo DS: DeSmuME's .dsv carries a proprietary footer
        // melonDS/melonDS DS's raw saves don't (§10) -- confirmed NOT
        // interchangeable, distinct families.
        "desmume" to "nds-desmume-footer",
        "desmume2015" to "nds-desmume-footer",
        "melonds" to "nds-melonds-raw",
        "melondsds" to "nds-melonds-raw"
    )
}

// Gates the download guard's wiring into the actual download path (§7's
// "planned" status -- the decision logic itself has been real since Phase 1).
// Phase 3B (this task) ships the confirmation prompt this gate was waiting
// on -- SaveLocationRepository.download() still makes the final ALLOW/WARN
// call (via downloadNeedsGuardConfirmation, SaveDownloadGuard.kt), but every
// interactive caller (SaveSyncViewModel's per-game screen, SaveSyncAllViewModel's
// bulk Revert) now offers "Download anyway" to bypass a WARN_INCOMPATIBLE
// result, and the non-interactive callers (SaveSyncOrchestrator, driving both
// the in-app "Sync All" button and the QS tile / foreground service) never
// prompt -- a guard hit there just skips that ROM's download and leaves its
// sync status showing "needs attention" on the next load, per this task's
// explicit instruction that the background path must not show UI.
const val DOWNLOAD_GUARD_ENABLED = true
