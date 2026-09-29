package com.theycallmeboxy.caulker.data.saves

// Resolves a FILE_SET preset's patterns against one save's matching-key name
// (ROM stem or RomM save_target, per §3) into the basename -> resolved
// relative path map both packSaveZip and unpackSaveZip need for this shape:
//   - packSaveZip (optional): validates the preset itself isn't ambiguous
//     before zipping, independent of which member files happen to exist on
//     disk for a particular save -- see SaveZipPacker.kt's planArchive.
//   - unpackSaveZip (required for FILE_SET): a flat archive's entries are
//     named by bare filename alone (SaveZipPacker.kt point 1), so placing
//     one back on disk means resolving the SAME preset patterns against the
//     SAME name to find out which relative path that basename belongs to.
//
// Reuses SavePathSafety.resolveSafeRelativePath per pattern -- the same
// traversal guard SaveMatcher's own pattern resolution already goes through
// -- so an unsafe pattern or name is dropped rather than ever producing an
// unsafe destination path.
internal fun resolveFileSetPatterns(patterns: List<String>, resolvedName: String): Map<String, String> {
    val destinationByBaseName = LinkedHashMap<String, String>()
    for (pattern in patterns) {
        val resolved = resolveSafeRelativePath(pattern, resolvedName) ?: continue
        val baseName = resolved.substringAfterLast('/')
        val existing = destinationByBaseName[baseName]
        if (existing != null && existing != resolved) {
            // Two different patterns collapsing to the same basename means an
            // archive entry named by that basename alone could never be
            // placed unambiguously -- a preset-authoring bug (patterns that
            // don't disambiguate their members), refused eagerly here rather
            // than discovered lazily the first time such an archive shows up.
            throw DuplicateArchiveEntryException(
                "FILE_SET patterns collide once flattened to basename \"$baseName\" for name " +
                    "\"$resolvedName\": \"$existing\" and \"$resolved\""
            )
        }
        destinationByBaseName[baseName] = resolved
    }
    return destinationByBaseName
}
