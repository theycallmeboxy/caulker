package com.theycallmeboxy.caulker.data.util

import java.security.MessageDigest

// Lowercase MD5 hex of the given bytes. Matches RomM's server-side
// `content_hash` for plain (non-zip) save files (hashlib.md5 hexdigest), so the
// sync negotiation can short-circuit identical content as a no-op.
fun md5Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(bytes)
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4])
        sb.append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}

private const val HEX_CHARS = "0123456789abcdef"
