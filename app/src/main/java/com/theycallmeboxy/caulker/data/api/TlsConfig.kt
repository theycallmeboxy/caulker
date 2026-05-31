package com.theycallmeboxy.caulker.data.api

import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

// Holds the runtime TLS verification mode. Backed by a DataStore pref but
// exposed as an AtomicBoolean so the OkHttp trust manager / hostname verifier
// can read the latest value on every handshake without suspending.
//
// Initial value is read synchronously at construction so OkHttp comes up with
// the user's setting on the first request. setInsecureSkipVerify writes through
// to the pref AND updates the flag, so toggling in the UI takes effect for the
// next connection without needing an app restart.
@Singleton
class TlsConfig @Inject constructor(
    private val prefsStore: PrefsStore
) {
    private val insecure = AtomicBoolean(
        runBlocking { prefsStore.insecureSkipVerify.first() }
    )

    fun isInsecure(): Boolean = insecure.get()

    suspend fun setInsecureSkipVerify(value: Boolean) {
        prefsStore.setInsecureSkipVerify(value)
        insecure.set(value)
    }
}
