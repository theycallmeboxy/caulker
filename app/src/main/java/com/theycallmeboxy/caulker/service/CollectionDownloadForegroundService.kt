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
import com.theycallmeboxy.caulker.data.download.CollectionDownloadOrchestrator
import com.theycallmeboxy.caulker.data.download.CollectionDownloadState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

// Owns the progress notification while the orchestrator downloads a collection.
// Unlike the save-sync service, it does not start the work — the ViewModel kicks
// off the orchestrator (which needs the collection's ROM ids) and then starts
// this service purely to keep the process alive and show progress. Stops itself
// once the orchestrator returns to Idle/Done/Error.
@AndroidEntryPoint
class CollectionDownloadForegroundService : android.app.Service() {

    @Inject lateinit var orchestrator: CollectionDownloadOrchestrator

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
        startForegroundWithType(buildNotification(initial = true, title = "Downloading collection", label = "Preparing…"))

        val s = scope ?: MainScope().also { scope = it }
        observerJob?.cancel()
        observerJob = s.launch {
            orchestrator.state.collect { state -> handleState(state) }
        }
        return START_NOT_STICKY
    }

    private fun handleState(state: CollectionDownloadState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (state) {
            is CollectionDownloadState.Downloading -> {
                val label = "Downloading ${state.done + 1} of ${state.total}" +
                    (state.currentRomName?.let { " — $it" } ?: "")
                nm.notify(
                    NotificationChannels.DOWNLOAD_NOTIFICATION_ID,
                    buildNotification(
                        initial = false,
                        title = state.collectionName,
                        label = label,
                        progress = state.done,
                        max = state.total
                    )
                )
            }
            is CollectionDownloadState.Done -> {
                val body = buildString {
                    append("Downloaded ${state.downloaded}")
                    if (state.skipped > 0) append(", ${state.skipped} already present")
                    if (state.failed > 0) append(", ${state.failed} failed")
                }
                nm.notify(
                    NotificationChannels.DOWNLOAD_NOTIFICATION_ID,
                    buildFinalNotification(title = "${state.collectionName} downloaded", body = body)
                )
                stopAndCleanup()
            }
            is CollectionDownloadState.Error -> {
                nm.notify(
                    NotificationChannels.DOWNLOAD_NOTIFICATION_ID,
                    buildFinalNotification(title = "Collection download failed", body = state.message)
                )
                stopAndCleanup()
            }
            CollectionDownloadState.Idle -> {
                // StateFlow replays its current value on collection. Only stop once
                // the orchestrator is genuinely idle (finished or cancelled), not
                // in the brief window before it sets Downloading.
                if (!orchestrator.isRunning()) stopAndCleanup()
            }
        }
    }

    private fun stopAndCleanup() {
        observerJob?.cancel()
        observerJob = null
        scope?.cancel()
        scope = null
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
                NotificationChannels.DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationChannels.DOWNLOAD_NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        initial: Boolean,
        title: String,
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
            Intent(this, CollectionDownloadForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationChannels.DOWNLOAD_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
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
        return NotificationCompat.Builder(this, NotificationChannels.DOWNLOAD_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        const val ACTION_CANCEL = "com.theycallmeboxy.caulker.COLLECTION_DOWNLOAD_CANCEL"

        fun start(context: Context) {
            val intent = Intent(context, CollectionDownloadForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
