package com.example.volumemonitor.core.volume.mode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.volume.VolumeMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Режим перехвата громкости для устройств Teyes (SPRD uis8581a2h10, Android 10 Automotive).
 */
class TeyesMode(
    context: Context,
    commandSender: CommandSender,
    settingsRepository: SettingsRepository,
    appEvents: SharedFlow<AppEvent>
) : VolumeMode(
    context = context,
    commandSender = commandSender,
    settingsRepository = settingsRepository,
    appEvents = appEvents,
    modeId = VolumeControlMode.TEYES,
    displayName = "Teyes (перехват громкости)",
    description = "Режим для магнитол Teyes. Громкость управляется с экрана (ползунок +/-). " +
        "При нажатии кнопок на руле Teyes происходит синхронизация с Arduino. " +
        "Диапазон громкости настраивается (по умолчанию 0..36)."
) {
    private val TAG = "TeyesMode"

    @Volatile
    private var screenVolume: Int = 0
    @Volatile
    private var maxVolume: Int = Constants.DEFAULT_TEYES_MAX_VOLUME
    private var volumeSaveJob: Job? = null
    private var broadcastCount = 0
    private var syncJob: Job? = null

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != "android.media.VOLUME_CHANGED_ACTION") return
            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (streamType != AudioManager.STREAM_MUSIC) return
            broadcastCount++

            // Читаем актуальную громкость из Intent вместо кэшированного screenVolume.
            // При нажатии кнопок на руле система посылает broadcast с EXTRA_VOLUME_STREAM_VALUE,
            // который содержит реальное текущее значение STREAM_MUSIC. Без этого чтения
            // onVolumeEventDetected() отправлял бы в Arduino устаревшее кэшированное значение.
            val receivedVolume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            if (receivedVolume >= 0) {
                val clamped = receivedVolume.coerceIn(0, maxVolume)
                if (clamped != screenVolume) {
                    screenVolume = clamped
                    scheduleVolumeSave()
                }
            }

            Log.d(TAG, "VOLUME_CHANGED_ACTION #$broadcastCount (STREAM_MUSIC), vol=$screenVolume/$maxVolume")
            syncJob?.cancel()
            syncJob = modeScope.launch {
                delay(DEBOUNCE_MS)
                onVolumeEventDetected()
            }
        }
    }

    override fun start() {
        Log.d(TAG, "Запуск TeyesMode v2")
        maxVolume = settingsRepository.getTeyesMaxVolume().coerceIn(1, 100)
        screenVolume = settingsRepository.getTeyesCurrentVolume().coerceIn(0, maxVolume)
        val modeState = ModeState(screenVolume, maxVolume, "teyes")
        _state.value = modeState
        emitModeState(modeState)
        Log.d(TAG, "Восстановлена громкость Teyes: $screenVolume/$maxVolume")
        registerVolumeReceiver()
        modeScope.launch {
            appEvents.collect { event ->
                when (event) {
                    is AppEvent.ScreenVolumeChanged -> handleScreenVolumeChange(event.value)
                    is AppEvent.TeyesSettingsChanged -> reloadTeyesSettings()
                    is AppEvent.TeyesVolumeRead -> handleExternalVolumeRead(event.volume)
                    else -> {}
                }
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Остановка TeyesMode (broadcast получено: $broadcastCount)")
        unregisterVolumeReceiver()
        super.stop()
    }

    override fun onUsbConnected() {
        syncScreenVolumeToPort()
        emitModeState()
    }

    private fun registerVolumeReceiver() {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(volumeReceiver, filter)
        }
        Log.d(TAG, "BroadcastReceiver для VOLUME_CHANGED_ACTION зарегистрирован")
    }

    private fun unregisterVolumeReceiver() {
        try { context.unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
    }

    private fun onVolumeEventDetected() {
        Log.d(TAG, "Обнаружено событие громкости Teyes (broadcast #$broadcastCount), " +
            "текущая громкость: $screenVolume/$maxVolume")
        val portValue = screenVolumeToPort(screenVolume)
        commandSender.send(DeviceCommand.SetVolume(portValue))
        AppEventBus.tryEmit(AppEvent.VolumeChanged(screenVolume, portValue))
        val modeState = ModeState(screenVolume, maxVolume, "teyes")
        _state.value = modeState
        emitModeState(modeState)
        Log.d(TAG, "Синхронизация: vol=$screenVolume/$maxVolume -> port=$portValue")
    }

    private fun screenVolumeToPort(volume: Int): Int =
        VolumeMath.buttonToPort(volume, maxVolume)

    private fun syncScreenVolumeToPort() {
        val portValue = screenVolumeToPort(screenVolume)
        Log.d(TAG, "Синхронизация громкости Teyes: vol=$screenVolume/$maxVolume -> port=$portValue")
        commandSender.send(DeviceCommand.SetVolume(portValue))
    }

    private fun handleScreenVolumeChange(value: Int) {
        val clamped = value.coerceIn(0, maxVolume)
        if (clamped != screenVolume) {
            screenVolume = clamped
            scheduleVolumeSave()
            val portValue = screenVolumeToPort(screenVolume)
            Log.d(TAG, "Ползунок Teyes: pos=$screenVolume/$maxVolume -> port=$portValue")
            commandSender.send(DeviceCommand.SetVolume(portValue))
            AppEventBus.tryEmit(AppEvent.VolumeChanged(screenVolume, portValue))
            val modeState = ModeState(screenVolume, maxVolume, "teyes")
            _state.value = modeState
            emitModeState(modeState)
        }
    }

    /**
     * Обрабатывает внешнее чтение громкости Teyes из SystemUI (Accessibility).
     * При нажатии кнопок на руле SystemUI обновляет vol_text, Accessibility читает
     * новое значение и эмитит [AppEvent.TeyesVolumeRead]. Метод обновляет
     * внутренний счётчик и синхронизирует громкость с Arduino.
     */
    private fun handleExternalVolumeRead(volume: Int) {
        val clamped = volume.coerceIn(0, maxVolume)
        if (clamped != screenVolume) {
            screenVolume = clamped
            scheduleVolumeSave()
            val portValue = screenVolumeToPort(screenVolume)
            Log.d(TAG, "Внешняя громкость Teyes (Accessibility): vol=$screenVolume/$maxVolume -> port=$portValue")
            commandSender.send(DeviceCommand.SetVolume(portValue))
            AppEventBus.tryEmit(AppEvent.VolumeChanged(screenVolume, portValue))
            val modeState = ModeState(screenVolume, maxVolume, "teyes")
            _state.value = modeState
            emitModeState(modeState)
        }
    }

    private fun reloadTeyesSettings() {
        val newMax = settingsRepository.getTeyesMaxVolume().coerceIn(1, 100)
        if (newMax != maxVolume) {
            maxVolume = newMax
            if (screenVolume > maxVolume) {
                screenVolume = maxVolume
                scheduleVolumeSave()
            }
            val modeState = ModeState(screenVolume, maxVolume, "teyes")
            _state.value = modeState
            emitModeState(modeState)
            Log.d(TAG, "TeyesSettingsChanged: maxVol=$maxVolume, vol=$screenVolume")
        }
    }

    private fun emitModeState(state: ModeState = _state.value) {
        AppEventBus.tryEmit(
            AppEvent.ModeStateChanged(
                modeId = VolumeControlMode.TEYES,
                currentVolume = state.currentVolume,
                maxVolume = state.maxVolume,
                displayLabel = state.displayLabel
            )
        )
    }

    private fun scheduleVolumeSave() {
        volumeSaveJob?.cancel()
        volumeSaveJob = modeScope.launch {
            delay(500L)
            settingsRepository.saveTeyesCurrentVolume(screenVolume)
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L
    }
}
