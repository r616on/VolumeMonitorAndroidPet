package com.example.volumemonitor.core.volume.mode

import android.content.Context
import android.util.Log
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.volume.VolumeMath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Режим управления громкостью через ползунок на главном экране.
 *
 * Получает события [AppEvent.ScreenVolumeChanged] из [AppEventBus] (эмитит MainFragment),
 * конвертирует позицию ползунка (0..14) в значение порта (0..255) и отправляет на Arduino.
 */
class ScreenMode(
    context: Context,
    commandSender: CommandSender,
    settingsRepository: SettingsRepository,
    appEvents: SharedFlow<AppEvent>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : VolumeMode(
    context = context,
    commandSender = commandSender,
    settingsRepository = settingsRepository,
    appEvents = appEvents,
    modeId = VolumeControlMode.SCREEN,
    displayName = "Управление с экрана",
    description = "Громкость регулируется ползунком на главном экране. Значение сразу отправляется на Arduino.",
    dispatcher = dispatcher
) {
    private val TAG = "ScreenMode"

    /** Текущая громкость в режиме SCREEN (0..SCREEN_MAX_POSITION). */
    private var screenCurrentVolume: Int = 0

    private var screenVolumeSaveJob: Job? = null

    override fun start() {
        Log.d(TAG, "Запуск ScreenMode")

        // Восстанавливаем сохранённую громкость
        screenCurrentVolume = settingsRepository.getScreenCurrentVolume()
            .coerceIn(0, Constants.SCREEN_MAX_POSITION)
        val maxPos = Constants.SCREEN_MAX_POSITION
        val modeState = ModeState(screenCurrentVolume, maxPos, "экран")
        _state.value = modeState
        emitModeState(modeState)
        Log.d(TAG, "Восстановлена громкость экрана: $screenCurrentVolume/$maxPos")

        // Подписка на движения ползунка
        modeScope.launch {
            appEvents.collect { event ->
                if (event is AppEvent.ScreenVolumeChanged) {
                    handleScreenVolumeChange(event.value)
                }
            }
        }
    }

    override fun onUsbConnected() {
        syncScreenVolumeToPort()
        emitModeState()
    }

    // ── Приватные методы ──

    /** Конвертирует позицию ползунка (0..SCREEN_MAX_POSITION) в значение порта (0..255). */
    private fun screenPositionToPort(position: Int): Int =
        VolumeMath.buttonToPort(position, Constants.SCREEN_MAX_POSITION)

    /** Отправить текущий screenCurrentVolume на устройство. */
    private fun syncScreenVolumeToPort() {
        val portValue = screenPositionToPort(screenCurrentVolume)
        Log.d(TAG, "Синхронизация громкости экрана: pos=$screenCurrentVolume/${Constants.SCREEN_MAX_POSITION} → port=$portValue")
        commandSender.send(DeviceCommand.SetVolume(portValue))
    }

    /** Обрабатывает изменение ползунка: конвертирует в порт, отправляет, сохраняет. */
    private fun handleScreenVolumeChange(value: Int) {
        val clamped = value.coerceIn(0, Constants.SCREEN_MAX_POSITION)
        if (clamped != screenCurrentVolume) {
            screenCurrentVolume = clamped
            scheduleScreenVolumeSave()
            val portValue = screenPositionToPort(screenCurrentVolume)
            Log.d(TAG, "Ползунок: pos=$screenCurrentVolume/${Constants.SCREEN_MAX_POSITION} → port=$portValue")
            commandSender.send(DeviceCommand.SetVolume(portValue))
            val modeState = ModeState(screenCurrentVolume, Constants.SCREEN_MAX_POSITION, "экран")
            _state.value = modeState
            emitModeState(modeState)
        }
    }

    /** Сохраняет громкость с дебаунсом 500 мс. */
    private fun emitModeState(state: ModeState = _state.value) {
        AppEventBus.tryEmit(
            AppEvent.ModeStateChanged(
                modeId = VolumeControlMode.SCREEN,
                currentVolume = state.currentVolume,
                maxVolume = state.maxVolume,
                displayLabel = state.displayLabel
            )
        )
    }

    private fun scheduleScreenVolumeSave() {
        screenVolumeSaveJob?.cancel()
        screenVolumeSaveJob = modeScope.launch {
            delay(500L)
            settingsRepository.saveScreenCurrentVolume(screenCurrentVolume)
        }
    }
}
