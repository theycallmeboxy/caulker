package com.theycallmeboxy.caulker.data.saves

// Lightweight setup warnings for a configured platform (save-sync design doc,
// Part 2 §12 phase 3: "Setup is lightweight warnings ... not a wizard", §1).
// Pure over a SaveLocationScanResult (or a scan failure's message) so the
// decision itself is JVM-testable without touching SaveLocationRepository.scan's
// actual I/O -- the platform-settings ViewModel does the scan (off the main
// thread) and just hands this function the outcome.
data class SaveSetupWarnings(
    // Non-null when the folder itself couldn't be read at all (permission
    // denied, folder deleted since it was configured, etc.) -- scan()
    // THROWS on this rather than returning an empty result (see its own doc
    // comment), so this is populated from the caught exception's message,
    // not from a SaveLocationScanResult.
    val folderUnreadable: String? = null,
    // The folder read fine but matched nothing at all -- no units, no
    // unassigned files either. Usually means either the folder is genuinely
    // empty, or it's the wrong folder for this preset.
    val folderEmptyOrNoSaves: Boolean = false,
    // Local entries that don't belong to any enrolled game by either §3
    // matching rule -- surfaced with a count here; the actual list lives in
    // the Unassigned-files screen this links to.
    val unassignedCount: Int = 0
) {
    // True when there's nothing to show the user -- every field at its
    // default/empty value. Lets the UI skip rendering a warnings section
    // entirely instead of an empty one.
    val isEmpty: Boolean
        get() = folderUnreadable == null && !folderEmptyOrNoSaves && unassignedCount == 0
}

// Builds the warnings for a successful scan.
fun computeSaveSetupWarnings(scanResult: SaveLocationScanResult): SaveSetupWarnings = SaveSetupWarnings(
    folderEmptyOrNoSaves = scanResult.units.isEmpty() && scanResult.unassigned.isEmpty(),
    unassignedCount = scanResult.unassigned.size
)

// Builds the warnings for a scan that THREW (folder unreadable) -- reason
// should be the caught exception's own message, falling back to a generic
// one if it had none.
fun computeSaveSetupWarningsForFailure(reason: String?): SaveSetupWarnings =
    SaveSetupWarnings(folderUnreadable = reason?.takeIf { it.isNotBlank() } ?: "Folder isn't readable")
