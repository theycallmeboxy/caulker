package com.theycallmeboxy.caulker.data.saves

// Token substituted with a game's resolved matching-key name (ROM stem or
// RomM's save_target) inside a preset's relative path patterns.
const val NAME_TOKEN = "{name}"

// True if `name` is safe to substitute into a pattern as a single path
// segment: non-blank, no path separators, not "." or "..". Names can come
// from server-supplied data (ROM stem, RomM's save_target) as well as local
// filenames -- never trust either to stay inside the configured save folder
// without this check.
fun isSafeNameSegment(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    if (name.contains('/') || name.contains('\\')) return false
    if (name == "." || name == "..") return false
    return true
}

// True if `path` is a safe relative path to join onto the configured save
// folder: not absolute (leading slash, or a Windows drive letter -- Caulker
// is Android-only, but this is defense in depth against a badly authored
// preset pattern), and no ".." segment anywhere.
fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank()) return false
    if (path.startsWith("/") || path.startsWith("\\")) return false
    if (path.length >= 2 && path[1] == ':') return false
    return path.split('/', '\\').none { it == ".." }
}

// Substitutes NAME_TOKEN in `pattern` with `name` and returns the result only
// if both the name and the resolved path are safe (see above); returns null
// otherwise so a resolved path from a server-provided save_target, or from a
// preset's own patterns, can never escape the configured folder.
fun resolveSafeRelativePath(pattern: String, name: String): String? {
    if (!isSafeNameSegment(name)) return null
    val resolved = pattern.replace(NAME_TOKEN, name)
    return resolved.takeIf { isSafeRelativePath(it) }
}
