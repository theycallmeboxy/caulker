package com.theycallmeboxy.caulker.data.saves

// File I/O behind a minimal interface so pack/unpack (SaveZipPacker.kt,
// SaveZipUnpacker.kt) are JVM-testable against in-memory fakes, with no
// Android dependency -- same rationale as SaveMatcher's LocalSaveEntry,
// whose type this reuses rather than introducing a parallel file-tree shape.
// A real Android-backed implementation (root-aware via RootFileHelper, per
// Part 1) is Phase 3's job when pack/unpack are wired into SaveRepository;
// nothing here assumes how paths are actually resolved to disk.

// Reads packable content for packSaveZip. relativePath is always relative to
// the configured save folder, matching LocalSaveEntry's own convention.
interface SaveFileSource {
    // Raw bytes of the file at `relativePath`, or null if it can't be read
    // (a caller-visible I/O failure, not a "doesn't exist" case -- an entry
    // that doesn't exist simply shouldn't have been passed to packSaveZip).
    fun readFile(relativePath: String): ByteArray?

    // `relativePath`'s immediate children (not the full subtree), or empty
    // if `relativePath` isn't a directory in this source. packSaveZip walks
    // a FOLDER root by calling this recursively.
    fun listChildren(relativePath: String): List<LocalSaveEntry>
}

// Writes unpacked content for unpackSaveZip. destPath has already passed the
// traversal guard (SavePathSafety.isSafeRelativePath) by the time it reaches
// here, and preserves the archive's own layout -- see unpackSaveZip's doc
// comment for what that means for a FOLDER save's root prefix. An
// implementation is expected to create any parent directories destPath
// implies, the same way a plain file write would.
interface SaveFileSink {
    fun writeFile(destPath: String, bytes: ByteArray)
}
