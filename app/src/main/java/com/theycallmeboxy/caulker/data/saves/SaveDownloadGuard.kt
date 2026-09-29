package com.theycallmeboxy.caulker.data.saves

// Outcome of the client-side download guard (§7 "Client-side download guard
// (planned)"). Pure decision logic only -- Phase 1 does not wire this into
// any download path or UI; when a later phase does, it must be gated behind a
// constant flag defaulting off per the design doc.
enum class DownloadGuardDecision { ALLOW, WARN_INCOMPATIBLE }

// A save-format compatibility lookup: maps an emulator id (§7's id table) to
// its "family" -- two ids sharing a family produce interchangeable save data
// (e.g. Beetle PSX / SwanStation / PCSX ReARMed's 128 KiB PS1 memory card, per
// §10). Ids absent from the map are of unknown compatibility.
class FormatCompatibility(private val familyById: Map<String, String>) {
    fun familyOf(emulatorId: String): String? = familyById[emulatorId]
}

// Decides whether an incoming save's `emulator` tag warrants a warning before
// it overwrites the local file, given the device's configured emulator/core
// id for that platform. Per §7: the guard only fires when the incoming tag
// positively identifies a format known to be incompatible with what's
// configured locally -- an unknown id, a null tag, "caulker", or no preset
// configured locally all silently allow, exactly like today.
fun downloadGuardDecision(
    incomingEmulatorId: String?,
    configuredEmulatorId: String?,
    compatibility: FormatCompatibility
): DownloadGuardDecision {
    if (incomingEmulatorId.isNullOrBlank()) return DownloadGuardDecision.ALLOW
    if (incomingEmulatorId == FALLBACK_EMULATOR_ID) return DownloadGuardDecision.ALLOW
    if (configuredEmulatorId.isNullOrBlank()) return DownloadGuardDecision.ALLOW
    if (configuredEmulatorId == FALLBACK_EMULATOR_ID) return DownloadGuardDecision.ALLOW
    if (incomingEmulatorId == configuredEmulatorId) return DownloadGuardDecision.ALLOW

    val incomingFamily = compatibility.familyOf(incomingEmulatorId) ?: return DownloadGuardDecision.ALLOW
    val configuredFamily = compatibility.familyOf(configuredEmulatorId) ?: return DownloadGuardDecision.ALLOW

    return if (incomingFamily == configuredFamily) {
        DownloadGuardDecision.ALLOW
    } else {
        DownloadGuardDecision.WARN_INCOMPATIBLE
    }
}
