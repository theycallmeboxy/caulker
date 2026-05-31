package com.theycallmeboxy.caulker.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.theycallmeboxy.caulker.MainActivity
import com.theycallmeboxy.caulker.data.sync.SaveSyncOrchestrator
import com.theycallmeboxy.caulker.data.sync.SaveSyncOverallState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

// Foreground service that owns the progress notification while the orchestrator
// drives the actual sync. Started by the QS tile (or could be started in-app
// later). Stops itself once the orchestrator returns to Idle/Done/Error.
@AndroidEntryPoint
class SaveSyncForegroundService : android.app.Service() {

    @Inject lateinit var orchestrator: SaveSyncOrchestrator

    private var scope: CoroutineScope? = null
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            orchestrator.cancel()
            stopAndCleanup()
            return START_NOT_STICKY
        }

        NotificationChannels.ensureCreated(this)
        startForegroundWithType(buildNotification(initial = true, label = "Preparing…"))

        // Start the sync if it isn't already running. Then observe state and
        // update / stop accordingly. The orchestrator job lives in app scope
        // and survives even if this service is killed early.
        if (!orchestrator.isRunning()) orchestrator.syncAllEnrolled()

        val s = scope ?: MainScope().also { scope = it }
        observerJob?.cancel()
        observerJob = s.launch {
            orchestrator.state.collect { state -> handleState(state) }
        }
        return START_NOT_STICKY
    }

    private fun handleState(state: SaveSyncOverallState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (state) {
            is SaveSyncOverallState.Syncing -> {
                val label = "Syncing ${state.done + 1} of ${state.total}" +
                    (state.currentRomName?.let { " — $it" } ?: "")
                nm.notify(
                    NotificationChannels.SAVE_SYNC_NOTIFICATION_ID,
                    buildNotification(
                        initial = false,
                        label = label,
                        progress = state.done,
                        max = state.total
                    )
                )
            }
            is SaveSyncOverallState.Done -> {
                val checked = state.uploaded + state.downloaded + state.skipped + state.errors
                nm.notify(
                    NotificationChannels.SAVE_SYNC_NOTIFICATION_ID,
                    buildFinalNotification(
                        title = "Save sync complete",
                        body = "$checked games checked — uploaded ${state.uploaded}, downloaded ${state.downloaded}" +
                            (if (state.errors > 0) ", ${state.errors} failed" else "")
                    )
                )
                stopAndCleanup()
            }
            is SaveSyncOverallState.Error -> {
                nm.notify(
                    NotificationChannels.SAVE_SYNC_NOTIFICATION_ID,
                    buildFinalNotification(title = "Save sync failed", body = state.message)
                )
                stopAndCleanup()
            }
            SaveSyncOverallState.Idle -> {
                // StateFlow emits its current value immediately on collection.
                // If the orchestrator was just started but hasn't set Syncing
                // yet, isRunning() is already true — don't stop. Only stop
                // when we reach Idle after a real sync (or cancel) completes.
                if (!orchestrator.isRunning()) stopAndCleanup()
            }
        }
    }

    private fun stopAndCleanup() {
        observerJob?.cancel()
        observerJob = null
        scope?.cancel()
        scope = null
        // Keep the final notification visible after the service stops by detaching
        // it from foreground state rather than removing it entirely.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationChannels.SAVE_SYNC_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationChannels.SAVE_SYNC_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        initial: Boolean,
        label: String,
        progress: Int = 0,
        max: Int = 0
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SaveSyncForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannels.SAVE_SYNC_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Save sync")
            .setContentText(label)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (initial || max == 0) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(max, progress, false)
                }
            }
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    private fun buildFinalNotification(title: String, body: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationChannels.SAVE_SYNC_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        const val ACTION_CANCEL = "com.theycallmeboxy.caulker.SAVE_SYNC_CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, SaveSyncForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
