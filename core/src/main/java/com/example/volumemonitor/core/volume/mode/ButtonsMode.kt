package com.example.volumemonitor.core.volume.mode

import android.content.Context
import android.util.Log
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.ButtonAction
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.volume.VolumeMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Режим управления громкостью через назначенные физические кнопки.
 *
 * Получает нажатия кнопок из [AppEventBus] (независимый слой ввода),
 * изменяет внутреннюю громкость и отправляет её на Arduino через [commandSender].
 */
class ButtonsMode(
    context: Context,
    commandSender: CommandSender,
    settingsRepository: SettingsRepository,
    appEvents: SharedFlow<AppEvent>
) : VolumeMode(
    context = context,
    commandSender = commandSender,
    settingsRepository = settingsRepository,
    appEvents = appEvents,
    modeId = VolumeControlMode.BUTTONS,
    displayName = "Управление через кнопки",
    description = "Громкость изменяется на ±1 при каждом нажатии назначенной кнопки. Удерживайте кнопку для непрерывного изменения."
) {
    private val TAG = "ButtonsMode"

    /** Текущая громкость в режиме BUTTONS (0..maxVolumeValue). */
    private var buttonCurrentVolume: Int = 0

    private var buttonVolumeSaveJob: Job? = null

    override fun start() {
        Log.d(TAG, "Запуск ButtonsMode")

        // Восстанавливаем сохранённую громкость
        buttonCurrentVolume = settingsRepository.getButtonCurrentVolume()
        val maxVol = settingsRepository.getMaxVolumeValue().coerceAtLeast(1)
        val modeState = ModeState(buttonCurrentVolume, maxVol, "кнопки")
        _state.value = modeState
        emitModeState(modeState)
        Log.d(TAG, "Восстановлена громкость кнопок: $buttonCurrentVolume/$maxVol")

        // Подписка на нажатия кнопок (независимый слой ввода)
        modeScope.launch {
            appEvents.collect { event ->
                if (event is AppEvent.ButtonPressed) {
                    handleButtonPress(event.action)
                }
            }
        }

        // Подписка на изменение настроек кнопок (maxVolume и др.)
        modeScope.launch {
            appEvents.collect { event ->
                if (event is AppEvent.ButtonSettingsChanged) {
                    val maxVol = settingsRepository.getMaxVolumeValue().coerceAtLeast(1)
                    if (buttonCurrentVolume > maxVol) {
                        buttonCurrentVolume = maxVol
                    }
                    val modeState = ModeState(buttonCurrentVolume, maxVol, "кнопки")
                    _state.value = modeState
                    emitModeState(modeState)
                    Log.d(TAG, "ButtonSettingsChanged: maxVol=$maxVol")
                }
            }
        }
    }

    override fun onUsbConnected() {
        syncButtonVolumeToPort()
        emitModeState()
    }

    // ── Приватные методы ──

    /** Конвертирует громкость кнопок (0..maxVolume) в значение порта (0..255). */
    private fun buttonVolumeToPort(volume: Int, maxVolume: Int): Int =
        VolumeMath.buttonToPort(volume, maxVolume)

    /** Отправить текущий buttonCurrentVolume на устройство. */
    private fun syncButtonVolumeToPort() {
        val maxVol = settingsRepository.getMaxVolumeValue().coerceAtLeast(1)
        val portValue = buttonVolumeToPort(buttonCurrentVolume, maxVol)
        Log.d(TAG, "Синхронизация громкости кнопок: vol=$buttonCurrentVolume/$maxVol → port=$portValue")
        commandSender.sendVolume(portValue)
    }

    /** Обрабатывает нажатие кнопки: ±1, конвертирует в порт, отправляет. */
    private fun handleButtonPress(action: ButtonAction) {
        val maxVol = settingsRepository.getMaxVolumeValue().coerceAtLeast(1)
        val newVolume = when (action) {
            ButtonAction.VOLUME_UP -> (buttonCurrentVolume + 1).coerceIn(0, maxVol)
            ButtonAction.VOLUME_DOWN -> (buttonCurrentVolume - 1).coerceIn(0, maxVol)
        }
        if (newVolume != buttonCurrentVolume) {
            buttonCurrentVolume = newVolume
            scheduleButtonVolumeSave()
            val portValue = buttonVolumeToPort(buttonCurrentVolume, maxVol)
            Log.d(TAG, "Кнопка: $action → vol=$buttonCurrentVolume/$maxVol → port=$portValue")
            commandSender.sendVolume(portValue)
            val modeState = ModeState(buttonCurrentVolume, maxVol, "кнопки")
            _state.value = modeState
            emitModeState(modeState)
        }
    }

    /** Сохраняет громкость с дебаунсом 500 мс. */
    private fun emitModeState(state: ModeState = _state.value) {
        AppEventBus.tryEmit(
            AppEvent.ModeStateChanged(
                modeId = VolumeControlMode.BUTTONS,
                currentVolume = state.currentVolume,
                maxVolume = state.maxVolume,
                displayLabel = state.displayLabel
            )
        )
    }

    private fun scheduleButtonVolumeSave() {
        buttonVolumeSaveJob?.cancel()
        buttonVolumeSaveJob = modeScope.launch {
            delay(500L)
            settingsRepository.saveButtonCurrentVolume(buttonCurrentVolume)
        }
    }
}
