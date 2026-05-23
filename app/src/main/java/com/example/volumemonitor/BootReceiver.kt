package com.example.volumemonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.volumemonitor.core.VolumeMonitorService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Загрузка завершена, запускаем сервис")
            val serviceIntent = Intent(context, VolumeMonitorService::class.java)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> context.startForegroundService(serviceIntent)
                else -> context.startService(serviceIntent)
            }
        }
    }
}