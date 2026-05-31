package com.theycallmeboxy.caulker.data.api.interceptor

import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val prefsStore: PrefsStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { prefsStore.authToken.first() }
        if (token.isNullOrBlank()) return chain.proceed(chain.request())

        val serverHost = runBlocking { prefsStore.serverUrl.first() }
            ?.toHttpUrlOrNull()?.host
        val requestHost = chain.request().url.host
        // Only add auth to our RomM server; don't leak token to external image hosts
        val isOurServer = serverHost == null || requestHost == serverHost || requestHost == "localhost"

        val request = chain.request().newBuilder().apply {
            if (isOurServer) addHeader("Authorization", "Bearer $token")
        }.build()
        return chain.proceed(request)
    }
}
