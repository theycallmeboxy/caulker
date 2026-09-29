package com.theycallmeboxy.caulker.ui.util

fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "Unknown"
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024f)
    bytes < 1_073_741_824 -> "%.1f MB".format(bytes / 1_048_576f)
    else -> "%.2f GB".format(bytes / 1_073_741_824f)
}

fun buildCoverUrl(serverUrl: String, coverPath: String): String {
    val base = serverUrl.trimEnd('/')
    return if (coverPath.contains("/assets/romm/resources")) {
        "$base$coverPath"
    } else {
        "$base/assets/romm/resources/$coverPath"
    }
}

// Truncates a long file path from the START (keeping the tail, where the
// actually-distinguishing folder/file name usually is) instead of Compose's
// default end-ellipsis, which for a path just shows a long, useless common
// prefix (save-sync design doc, Part 2 §12 phase 3B fixes nit). No-op for
// anything already short enough.
fun ellipsizeStart(path: String, maxChars: Int = 48): String =
    if (path.length <= maxChars) path else "…" + path.takeLast(maxChars - 1)
