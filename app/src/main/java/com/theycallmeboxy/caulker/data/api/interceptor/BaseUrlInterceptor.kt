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

        val rewritten = original.url.newBuilder()
            .scheme(serverUrl.scheme)
            .host(serverUrl.host)
            .port(serverUrl.port)
            .build()

        return chain.proceed(original.newBuilder().url(rewritten).build())
    }
}
