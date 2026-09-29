package com.theycallmeboxy.caulker.data.saves

// Zip magic-byte detection for downloaded save content (Phase 3A -- save-sync
// design doc Part 2 §4: "detect by zip magic, not just name"). A server
// filename extension (".zip" vs. e.g. ".srm") is not trustworthy evidence of
// the actual payload shape -- it can be stale, come from a different client's
// convention, or simply be absent -- so shape decisions (SaveTransferPlan.kt's
// planSaveDownload) check the bytes themselves instead.
//
// Recognizes all three zip signature variants a real archive can start with
// (local file header, empty-archive central directory, and the spanned-
// archive marker), matching Argosy's own check byte-for-byte (argosy-launcher
// app/src/main/kotlin/com/nendo/argosy/data/sync/SaveArchiver.kt,
// checkZipMagic): "PK" + (0x03/0x05/0x07) + (0x04/0x06/0x08).
fun isZipBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    return bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte()) &&
        (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte() || bytes[3] == 0x08.toByte())
}
