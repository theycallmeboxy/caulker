package com.theycallmeboxy.caulker.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Pure-JVM tests for the scope-diff helper used to warn about an
// under-scoped pairing token right after pairing (LoginViewModel).
class ApiScopesTest {

    @Test
    fun `null token scopes means unknown, not missing`() {
        assertTrue(missingRequiredScopes(null).isEmpty())
    }

    @Test
    fun `token with every required scope has nothing missing`() {
        assertTrue(missingRequiredScopes(REQUIRED_TOKEN_SCOPES).isEmpty())
    }

    @Test
    fun `token with every required scope plus extras has nothing missing`() {
        val scopes = REQUIRED_TOKEN_SCOPES + listOf("roms.write", "users.read")
        assertTrue(missingRequiredScopes(scopes).isEmpty())
    }

    @Test
    fun `token missing devices scopes reports exactly those`() {
        val scopes = REQUIRED_TOKEN_SCOPES - setOf("devices.read", "devices.write")
        assertEquals(listOf("devices.read", "devices.write"), missingRequiredScopes(scopes))
    }

    @Test
    fun `empty token scopes reports everything required`() {
        assertEquals(REQUIRED_TOKEN_SCOPES, missingRequiredScopes(emptyList()))
    }
}
