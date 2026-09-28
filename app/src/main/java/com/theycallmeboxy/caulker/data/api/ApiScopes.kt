package com.theycallmeboxy.caulker.data.api

// The RomM pairing-token scopes Caulker needs for full functionality. Kept as
// one constant so the required-scope list only needs updating in one place.
//
// devices.read/devices.write are required in addition to assets.* because
// every save read/write Caulker sends carries a device_id, and RomM's
// _resolve_device (backend/endpoints/saves.py) requires those device scopes
// to resolve it — a token created with only assets.* gets 403s on save sync
// and device registration even though the user granted "save" access.
val REQUIRED_TOKEN_SCOPES: List<String> = listOf(
    "me.read",
    "platforms.read",
    "roms.read",
    "collections.read",
    "firmware.read",
    "assets.read",
    "assets.write",
    "devices.read",
    "devices.write"
)

// Pure helper so the scope-diff logic is unit-testable without a token/network
// round trip. Returns the required scopes absent from `tokenScopes`, in
// REQUIRED_TOKEN_SCOPES order. A null `tokenScopes` (server/response doesn't
// expose scopes) yields an empty list rather than a false-positive warning.
fun missingRequiredScopes(tokenScopes: List<String>?): List<String> {
    if (tokenScopes == null) return emptyList()
    val have = tokenScopes.toSet()
    return REQUIRED_TOKEN_SCOPES.filter { it !in have }
}

private fun scopesMessage(): String =
    "Your RomM pairing token is missing permissions. Create a new pairing code with: " +
        REQUIRED_TOKEN_SCOPES.joinToString(", ")

// Thrown in place of a bare HttpException when a saves/devices call gets a 403.
// RomM's scope guard (decorators/auth.py `_raise_auth_error`) returns a plain
// `{"detail": "Forbidden"}` with no scope names, so we can't report which scope
// was missing specifically — only point the user at the full required list.
class MissingScopesException : Exception(scopesMessage())
