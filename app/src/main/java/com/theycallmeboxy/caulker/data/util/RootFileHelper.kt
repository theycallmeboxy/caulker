package com.theycallmeboxy.caulker.data.util

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootFileHelper @Inject constructor() {

    fun isRootAvailable(): Boolean = Shell.isAppGrantedRoot() == true

    suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.exists()) return@withContext true
        if (!isRootAvailable()) return@withContext false
        Shell.cmd("[ -f ${q(path)} ] && echo 1 || echo 0").exec()
            .out.firstOrNull()?.trim() == "1"
    }

    suspend fun lastModifiedMs(path: String): Long = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.exists()) return@withContext f.lastModified()
        if (!isRootAvailable()) return@withContext 0L
        Shell.cmd("stat -c %Y ${q(path)} 2>/dev/null").exec()
            .out.firstOrNull()?.trim()?.toLongOrNull()?.times(1000L) ?: 0L
    }

    suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        try { return@withContext File(path).readBytes() } catch (_: Exception) {}
        check(isRootAvailable()) { "Cannot read $path — root access required but not granted" }
        val tmp = File.createTempFile("ck_rr_", null)
        try {
            val result = Shell.cmd("cp ${q(path)} ${q(tmp.absolutePath)}").exec()
            check(result.isSuccess) { "Root read failed: ${result.err.joinToString()}" }
            tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    suspend fun writeBytes(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val f = File(path)
        try {
            f.parentFile?.mkdirs()
            f.writeBytes(data)
            return@withContext
        } catch (_: Exception) {}
        check(isRootAvailable()) { "Cannot write to $path — root access required but not granted" }
        val tmp = File.createTempFile("ck_rw_", null)
        try {
            tmp.writeBytes(data)
            val result = Shell.cmd(
                "mkdir -p ${q(f.parent ?: "")}",
                "cp ${q(tmp.absolutePath)} ${q(f.absolutePath)}",
                "chmod 644 ${q(f.absolutePath)}"
            ).exec()
            check(result.isSuccess) { "Root write failed: ${result.err.joinToString()}" }
        } finally {
            tmp.delete()
        }
    }

    suspend fun setLastModified(path: String, timeMs: Long) = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.exists() && f.setLastModified(timeMs)) return@withContext
        if (!isRootAvailable()) return@withContext
        Shell.cmd("touch -m -d @${timeMs / 1000} ${q(path)}").exec()
    }

    // Copies the file to a .caulker_backup/ subdirectory with a timestamp suffix, then prunes
    // old backups so at most MAX_BACKUPS are kept for that base filename.
    suspend fun backupFile(path: String) = withContext(Dispatchers.IO) {
        if (!fileExists(path)) return@withContext
        val f = File(path)
        val backupDir = File(f.parent, ".caulker_backup")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = f.extension.let { if (it.isNotBlank()) ".$it" else "" }
        val baseName = f.nameWithoutExtension
        val backupFile = File(backupDir, "${baseName}_$ts$ext")

        if (f.canRead()) {
            backupDir.mkdirs()
            f.copyTo(backupFile, overwrite = true)
        } else if (isRootAvailable()) {
            Shell.cmd(
                "mkdir -p ${q(backupDir.absolutePath)}",
                "cp ${q(path)} ${q(backupFile.absolutePath)}"
            ).exec()
        } else {
            return@withContext
        }

        pruneBackups(backupDir, baseName, ext)
    }

    suspend fun listBackupTimestamps(savePath: String): List<Long> = withContext(Dispatchers.IO) {
        val f = File(savePath)
        val backupDir = File(f.parent, ".caulker_backup")
        val baseName = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isNotBlank()) ".$it" else "" }
        backupDir.listFiles { file ->
            file.name.startsWith("${baseName}_") && file.name.endsWith(ext)
        }?.map { it.lastModified() }?.sortedDescending() ?: emptyList()
    }

    // Returns the name (not full path) of the newest file in `dirPath` matching `predicate`,
    // or null if none match. Falls back to root shell when the directory isn't directly readable.
    suspend fun findNewestFile(dirPath: String, predicate: (String) -> Boolean): String? =
        withContext(Dispatchers.IO) {
            val d = File(dirPath)
            if (d.exists() && d.canRead()) {
                return@withContext d.listFiles { f -> f.isFile && predicate(f.name) }
                    ?.maxByOrNull { it.lastModified() }
                    ?.name
            }
            if (!isRootAvailable()) return@withContext null
            val names = Shell.cmd("ls -1 ${q(dirPath)} 2>/dev/null").exec().out
                .filter(predicate)
            if (names.isEmpty()) return@withContext null
            names.maxByOrNull { name ->
                Shell.cmd("stat -c %Y ${q("$dirPath/$name")} 2>/dev/null").exec()
                    .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            }
        }

    private fun pruneBackups(backupDir: File, baseName: String, ext: String) {
        val backups = backupDir.listFiles { f ->
            f.name.startsWith("${baseName}_") && f.name.endsWith(ext)
        } ?: return
        if (backups.size <= MAX_BACKUPS) return
        backups.sortedBy { it.lastModified() }
            .dropLast(MAX_BACKUPS)
            .forEach { it.delete() }
    }

    // Single-quote-escape a string for safe shell interpolation:
    // wraps in single quotes and replaces internal ' with '\''.
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        private const val MAX_BACKUPS = 5
    }
}
