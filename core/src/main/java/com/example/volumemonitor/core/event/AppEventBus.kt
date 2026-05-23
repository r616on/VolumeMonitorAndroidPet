package com.example.volumemonitor.core.event

import com.example.volumemonitor.core.usb.UsbPortState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Единая шина событий вместо множества BroadcastReceiver action-строк.
 * Все события — иммутабельные sealed class, потокобезопасная доставка через SharedFlow.
 */
object AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    suspend fun emit(event: AppEvent) { _events.emit(event) }
    fun tryEmit(event: AppEvent) { _events.tryEmit(event) }
}

// ── События приложения ──

sealed class AppEvent {
    /** Изменение громкости: current — системное значение (0..max), target — для Arduino (0..255) */
    data class VolumeChanged(val current: Int, val target: Int) : AppEvent()

    /** Статус USB-подключения */
    data class UsbStatusChanged(val status: UsbPortState) : AppEvent()

    /** Сырая строка от Arduino */
    data class ArduinoResponse(val rawLine: String) : AppEvent()
}
