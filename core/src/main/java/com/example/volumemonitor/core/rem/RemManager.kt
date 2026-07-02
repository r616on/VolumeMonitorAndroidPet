package com.example.volumemonitor.core.rem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.usb.UsbPortState
import com.example.volumemonitor.core.volume.mode.CommandSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Инкапсулированная логика автоматического управления REM.
 *
 * Отслеживает:
 * - Подключение/отключение USB (через AppEventBus)
 * - Включение/выключение экрана (через BroadcastReceiver)
 *
 * Отправляет команды change_rem enable/disable на устройство
 * в соответствии с правилами автоматического управления.
 */
class RemManager(
    private val context: Context,
    private val commandSender: CommandSender,
    private val appEvents: SharedFlow<AppEvent>
) {
    private val TAG = "RemManager"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var isScreenOn = true          // начальное состояние — экран включен
    private var lastRemState: Boolean? = null  // последнее отправленное состояние REM

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Экран выключен → disable REM")
                    isScreenOn = false
                    sendRemCommand(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Экран включен → enable REM")
                    isScreenOn = true
                    sendRemCommand(true)
                }
            }
        }
    }

    fun start() {
        Log.d(TAG, "Запуск RemManager")

        // Регистрируем приёмник экрана
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)

        // Подписка на события USB
        scope.launch {
            appEvents.collect { event ->
                when (event) {
                    is AppEvent.UsbStatusChanged -> {
                        when (event.status) {
                            is UsbPortState.Connected -> {
                                Log.d(TAG, "USB подключено → enable REM")
                                // Сбрасываем lastRemState, чтобы гарантированно отправить enable
                                // после переподключения (состояние устройства сбросилось)
                                lastRemState = null
                                sendRemCommand(true)
                            }
                            is UsbPortState.Disconnected -> {
                                Log.d(TAG, "USB отключено")
                            }
                            else -> { /* Initializing — ничего не делаем */ }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Остановка RemManager")

        // Отключаем REM при остановке
        sendRemCommand(false)

        // Снимаем приёмник экрана
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}

        scope.cancel()
    }

    private fun sendRemCommand(enable: Boolean) {
        if (lastRemState == enable) {
            Log.d(TAG, "sendRemCommand($enable): состояние не изменилось, пропускаем")
            return
        }
        lastRemState = enable
        Log.d(TAG, "→ Отправка change_rem ${if (enable) "enable" else "disable"}")
        commandSender.send(DeviceCommand.ChangeRem(enable))
    }
}
