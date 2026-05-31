package com.theycallmeboxy.caulker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.ui.navigation.AppNavigation
import com.theycallmeboxy.caulker.ui.navigation.Screen
import com.theycallmeboxy.caulker.ui.theme.CaulkerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefsStore: PrefsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CaulkerTheme {
                var startDestination by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val (serverUrl, authToken, romPath) = combine(
                        prefsStore.serverUrl,
                        prefsStore.authToken,
                        prefsStore.romBasePath
                    ) { s, a, r -> Triple(s, a, r) }.first()

                    startDestination = when {
                        authToken.isNullOrBlank() || serverUrl.isNullOrBlank() -> Screen.Login.route
                        romPath.isNullOrBlank() -> Screen.Setup.route
                        else -> Screen.Dashboard.route
                    }
                }

                if (startDestination == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    AppNavigation(startDestination = startDestination!!)
                }
            }
        }
    }
}
