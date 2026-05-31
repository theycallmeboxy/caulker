package com.theycallmeboxy.caulker.data.repository

import java.io.File

sealed class DownloadProgress {
    data class InProgress(val bytesRead: Long, val totalBytes: Long) : DownloadProgress() {
        val fraction: Float get() = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else 0f
    }
    data class Done(val file: File) : DownloadProgress()
    data class Failed(val message: String) : DownloadProgress()
}
