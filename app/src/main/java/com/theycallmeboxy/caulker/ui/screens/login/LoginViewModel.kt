package com.theycallmeboxy.caulker.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.api.RommApiService
import com.theycallmeboxy.caulker.data.api.TlsConfig
import com.theycallmeboxy.caulker.data.api.model.ExchangeCodeRequest
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

data class LoginState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val success: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val api: RommApiService,
    private val prefsStore: PrefsStore,
    private val tlsConfig: TlsConfig
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    val insecureSkipVerify = prefsStore.insecureSkipVerify
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setInsecureSkipVerify(value: Boolean) {
        viewModelScope.launch { tlsConfig.setInsecureSkipVerify(value) }
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is UnknownHostException -> "Server not found — check the URL"
        is ConnectException -> "Could not connect to server"
        is SocketTimeoutException -> "Connection timed out"
        is SSLHandshakeException ->
            "TLS handshake failed — enable \"Trust self-signed certs\" if your server uses one"
        is SSLException ->
            "TLS error: ${e.message ?: "unknown"} — try enabling \"Trust self-signed certs\""
        is HttpException -> when (e.code()) {
            401 -> "Invalid pairing code"
            403 -> "Access denied — check pairing code"
            404 -> "Server not found at that URL"
            else -> "Server error (${e.code()})"
        }
        else -> e.message ?: "Connection failed"
    }

    fun connect(serverUrl: String, pairingCode: String) {
        viewModelScope.launch {
            _state.value = LoginState(isLoading = true)
            try {
                prefsStore.setServerUrl(serverUrl.trimEnd('/') + "/")
                val token = api.exchangeCode(ExchangeCodeRequest(pairingCode))
                prefsStore.setAuthToken(token.accessToken)
                val user = api.getCurrentUser()
                prefsStore.setUsername(user.username)
                _state.value = LoginState(success = true)
            } catch (e: Exception) {
                _state.value = LoginState(error = friendlyError(e))
            }
        }
    }
}
