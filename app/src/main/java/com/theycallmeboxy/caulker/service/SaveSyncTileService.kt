package com.theycallmeboxy.caulker.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.theycallmeboxy.caulker.MainActivity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.sync.SaveSyncOrchestrator
import com.theycallmeboxy.caulker.data.sync.SaveSyncOverallState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

// Android Quick Settings tile that triggers "sync all enrolled saves". Tap with
// no setup yet → opens the app (so the user can sign in / enroll). Tap when
// configured → starts SaveSyncForegroundService which owns the progress
// notification and observes the orchestrator's state.
@AndroidEntryPoint
class SaveSyncTileService : TileService() {

    @Inject lateinit var orchestrator: SaveSyncOrchestrator
    @Inject lateinit var prefsStore: PrefsStore

    private var scope: CoroutineScope? = null
    private var observerJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // Render the tile to match current state on every panel open.
        renderImmediate()
        val s = CoroutineScope(Dispatchers.Main).also { scope = it }
        observerJob = s.launch {
            orchestrator.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        observerJob?.cancel()
        observerJob = null
        scope = null
    }

    override fun onClick() {
        super.onClick()
        // If not configured, open the app instead of starting a sync — the tile
        // would otherwise just fail silently in the background.
        if (!isConfigured()) {
            openAppFromTile()
            return
        }
        if (orchestrator.isRunning()) {
            orchestrator.cancel()
        } else {
            SaveSyncForegroundService.start(applicationContext)
        }
    }

    private fun openAppFromTile() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // startActivityAndCollapse moved to PendingIntent in API 34+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun renderImmediate() {
        // Synchronous initial render so the tile's first paint is correct. Uses
        // runBlocking because TileService can't suspend; the read is a single
        // DataStore lookup so it's quick.
        val configured = runBlocking { configuredSnapshot() }
        val tile = qsTile ?: return
        tile.state = if (!configured) Tile.STATE_INACTIVE else Tile.STATE_INACTIVE
        tile.label = "Sync saves"
        tile.subtitle = if (!configured) "Open Caulker" else null
        tile.updateTile()
        // Then layer the orchestrator state on top.
        render(orchestrator.state.value)
    }

    private fun render(state: SaveSyncOverallState) {
        val tile = qsTile ?: return
        when (state) {
            is SaveSyncOverallState.Syncing -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Syncing saves"
                tile.subtitle = "${state.done + 1} / ${state.total}"
            }
            is SaveSyncOverallState.Done -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Sync saves"
                tile.subtitle = "Last: ${state.uploaded}↑ ${state.downloaded}↓"
            }
            is SaveSyncOverallState.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Sync saves"
                tile.subtitle = "Last sync failed"
            }
            SaveSyncOverallState.Idle -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Sync saves"
                // Keep subtitle null here so we don't overwrite the configured one set above.
            }
        }
        tile.updateTile()
    }

    private suspend fun configuredSnapshot(): Boolean {
        // We need server URL + auth token. Save base path is required too —
        // without it, hasLocalSave can't resolve files.
        val (url, token, savePath) = combine(
            prefsStore.serverUrl, prefsStore.authToken, prefsStore.saveBasePath
        ) { a, b, c -> Triple(a, b, c) }.first()
        return !url.isNullOrBlank() && !token.isNullOrBlank() && !savePath.isNullOrBlank()
    }

    private fun isConfigured(): Boolean = runBlocking { configuredSnapshot() }
}
