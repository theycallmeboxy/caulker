package com.theycallmeboxy.caulker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val SAVE_SYNC_ID = "save_sync"
    const val SAVE_SYNC_NOTIFICATION_ID = 1001

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(SAVE_SYNC_ID) == null) {
            val channel = NotificationChannel(
                SAVE_SYNC_ID,
                "Save sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress and result of syncing game saves with RomM."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
