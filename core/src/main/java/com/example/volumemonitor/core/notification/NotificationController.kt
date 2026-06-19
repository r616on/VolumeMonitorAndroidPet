package com.example.volumemonitor.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.volumemonitor.core.Constants

class NotificationController(
    private val context: Context,
    private val title: String = "Монитор громкости",
    private val contentText: String = "Отслеживание громкости активно",
    private val channelDescription: String = "Сервис отслеживает громкость и отправляет данные на Arduino"
) {

    fun build(contentIntent: PendingIntent? = null): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                title,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = channelDescription }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)

        val pi = contentIntent ?: run {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                PendingIntent.getActivity(context, 0, launchIntent, flags)
            } else null
        }
        pi?.let { builder.setContentIntent(it) }

        return builder.build()
    }
}
