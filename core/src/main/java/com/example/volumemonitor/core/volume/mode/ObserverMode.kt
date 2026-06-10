package com.example.volumemonitor.core.volume.mode

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.MaxVolumeSource
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.volume.VolumeObserver
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Режим отслеживания системной громкости Android.
 * Слушает изменения громкости через [VolumeObserver] и отправляет их на Arduino
 * через [commandSender].
 */
class ObserverMode(
    context: Context,
    commandSender: CommandSender,
    settingsRepository: SettingsRepository,
    appEvents: SharedFlow<AppEvent>
) : VolumeMode(
    context = context,
    commandSender = commandSender,
    settingsRepository = settingsRepository,
    appEvents = appEvents,
    modeId = VolumeControlMode.OBSERVER,
    displayName = "Отслеживание системной громкости",
    description = "Громкость считывается из системы Android и отправляется на Arduino при каждом изменении."
) {
    private val TAG = "ObserverMode"
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val observerMaxOverride: Int?
        get() {
            if (settingsRepository.getObserverMaxVolumeSource() == MaxVolumeSource.CUSTOM) {
                val customMax = settingsRepository.getObserverCustomMaxVolume()
                return if (customMax > 0) customMax
                else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            }
            return null
        }

    private val volumeObserver: VolumeObserver by lazy {
        VolumeObserver(context, audioManager, observerMaxOverride)
    }

    override fun start() {
        Log.d(TAG, "Запуск ObserverMode")
        volumeObserver.register()

        modeScope.launch {
            volumeObserver.volume.collect { data ->
                AppEventBus.tryEmit(AppEvent.VolumeChanged(data.current, data.target))
                commandSender.sendVolume(data.target)
                val modeState = ModeState(
                    currentVolume = data.current,
                    maxVolume = data.max,
                    displayLabel = "системная"
                )
                _state.value = modeState
                AppEventBus.tryEmit(
                    AppEvent.ModeStateChanged(
                        modeId = VolumeControlMode.OBSERVER,
                        currentVolume = modeState.currentVolume,
                        maxVolume = modeState.maxVolume,
                        displayLabel = modeState.displayLabel
                    )
                )
            }
        }

        // Подписка на изменение настроек
        modeScope.launch {
            appEvents.collect { event ->
                if (event is AppEvent.ObserverSettingsChanged) {
                    val override = observerMaxOverride
                    volumeObserver.setMaxVolumeOverride(override)
                    Log.d(TAG, "ObserverSettingsChanged: override=$override")
                }
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Остановка ObserverMode")
        volumeObserver.unregister()
        super.stop()
    }

    override fun onUsbConnected() {
        val data = volumeObserver.currentVolumeData
        AppEventBus.tryEmit(AppEvent.VolumeChanged(data.current, data.target))
        commandSender.sendVolume(data.target)
        Log.d(TAG, "USB Connected: синхронизация громкости ${data.current}/${data.max} → target=${data.target}")
    }
}
