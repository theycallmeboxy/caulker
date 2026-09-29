package com.theycallmeboxy.caulker.data.saves

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// In-memory SaveFileSource/SaveFileSink fakes, and a raw-zip builder, shared
// by SaveZipPackerTest, SaveZipUnpackerTest, and SaveZipContentHashTest --
// no Android dependency, no real filesystem I/O.

// Derives directory structure purely from the keys of `files` (a map of
// relative file path -> content); there's no representation of an explicit
// empty directory, which is fine for these tests since a save is never
// usefully an empty directory.
class FakeSaveFileSource(private val files: Map<String, ByteArray>) : SaveFileSource {
    override fun readFile(relativePath: String): ByteArray? = files[relativePath]

    override fun listChildren(relativePath: String): List<LocalSaveEntry> {
        val prefix = if (relativePath.isEmpty()) "" else "$relativePath/"
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<LocalSaveEntry>()
        for (path in files.keys) {
            if (path == relativePath || !path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            val childName = rest.substringBefore('/')
            val childPath = "$prefix$childName"
            if (seen.add(childPath)) {
                result += LocalSaveEntry(childPath, isDirectory = rest.contains('/'))
            }
        }
        return result
    }
}

class FakeSaveFileSink : SaveFileSink {
    val written = mutableMapOf<String, ByteArray>()
    override fun writeFile(destPath: String, bytes: ByteArray) {
        written[destPath] = bytes
    }
}

// Builds a raw zip directly (bypassing packSaveZip) so tests can shape
// exactly the archive they want to feed unpackSaveZip/hashZipContents,
// including layouts packSaveZip itself would never produce (traversal
// attempts, a foreign client's exact layout, a known-answer fixture built to
// match a Python-generated one). `null` bytes writes a directory entry.
internal fun buildRawZip(entries: List<Pair<String, ByteArray?>>): ByteArray {
    val buffer = ByteArrayOutputStream()
    ZipOutputStream(buffer).use { zos ->
        for ((name, bytes) in entries) {
            if (bytes == null) {
                zos.putNextEntry(ZipEntry(if (name.endsWith("/")) name else "$name/"))
                zos.closeEntry()
            } else {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
    return buffer.toByteArray()
}
