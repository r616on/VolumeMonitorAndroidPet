package com.example.volumemonitor.core.volume.mode

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Состояние режима для отображения в UI. */
data class ModeState(
    val currentVolume: Int,
    val maxVolume: Int,
    val displayLabel: String
)

/** Функциональный интерфейс для отправки команд громкости в сериал порт. */
fun interface CommandSender {
    /** Отправить значение громкости 0..255 на Arduino. */
    fun sendVolume(targetVolume: Int)
}

/**
 * Базовый класс режима управления громкостью.
 *
 * Каждый наследник — самостоятельный механизм слежения за громкостью,
 * который инкапсулирует всю необходимую логику и отправляет результат
 * через [commandSender] на адаптер громкости.
 */
abstract class VolumeMode(
    protected val context: Context,
    protected val commandSender: CommandSender,
    protected val settingsRepository: SettingsRepository,
    protected val appEvents: SharedFlow<AppEvent>,
    val modeId: VolumeControlMode,
    val displayName: String,
    val description: String
) {
    protected val _state = MutableStateFlow(ModeState(0, 0, ""))
    val state: StateFlow<ModeState> = _state.asStateFlow()

    protected val modeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Запустить режим — начать слежение за громкостью. */
    abstract fun start()

    /** Остановить режим — отменить все подписки и слушатели. */
    open fun stop() {
        modeScope.cancel()
    }

    /** Вызывается сервисом при USB-подключении (для синхронизации громкости). */
    open fun onUsbConnected() {}

    /** Предоставить View с настройками режима для ModesFragment. */
    open fun createSettingsView(parent: ViewGroup): View? = null
}
