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
class BaseUrlInterceptor @Inject constructor(
    private val prefsStore: PrefsStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Only rewrite the localhost placeholder used by Retrofit; pass real URLs through unchanged
        if (original.url.host != "localhost") return chain.proceed(original)

        val serverUrl = runBlocking { prefsStore.serverUrl.first() }
            ?.toHttpUrlOrNull()
            ?: return chain.proceed(original)

        // Prepend the configured server URL's own path so a RomM instance served
        // under a sub-path (e.g. behind a reverse proxy at https://host/romm/)
        // isn't silently dropped — Retrofit's requests are built against
        // "http://localhost/api/..." with no path prefix of their own.
        val basePath = serverUrl.encodedPath.trimEnd('/')
        val rewritten = original.url.newBuilder()
            .scheme(serverUrl.scheme)
            .host(serverUrl.host)
            .port(serverUrl.port)
            .encodedPath(basePath + original.url.encodedPath)
            .build()

        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}
