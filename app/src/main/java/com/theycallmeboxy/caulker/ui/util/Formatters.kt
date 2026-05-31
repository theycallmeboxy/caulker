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
