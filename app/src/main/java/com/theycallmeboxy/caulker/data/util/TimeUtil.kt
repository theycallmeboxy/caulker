package com.theycallmeboxy.caulker.data.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

fun parseIsoToMs(iso: String?): Long? {
    iso ?: return null
    return try { Instant.parse(iso).toEpochMilli() }
    catch (_: Exception) {
        try { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        catch (_: Exception) {
            try { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }
            catch (_: Exception) { null }
        }
    }
}

// Epoch millis -> ISO-8601 UTC string (e.g. 2026-06-06T12:34:56.789Z), which
// RomM's pydantic datetime fields accept. Used for sync negotiation / play sessions.
fun msToIso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

fun formatTimestamp(ms: Long): String {
    if (ms == 0L) return "Unknown"
    val ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    return DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault()).format(ldt)
}
