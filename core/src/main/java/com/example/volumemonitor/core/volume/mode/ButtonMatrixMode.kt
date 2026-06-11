package com.example.volumemonitor.core.volume.mode

import android.content.Context
import android.util.Log
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Режим "Матрица кнопок" — 6 кнопок (1..6), каждая отправляет button_down/button_up с номером.
 *
 * На внешнем адаптере вместо потенциометра включается резистивная матрица.
 * При нажатии кнопки (с экрана или физической клавиши) отправляется команда button_down,
 * при отпускании — button_up. Громкость в этом режиме не отслеживается.
 */
class ButtonMatrixMode(
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
    modeId = VolumeControlMode.BUTTON_MATRIX,
    displayName = "Матрица кнопок",
    description = "Управление через матрицу из 6 кнопок. Отправка button_down/button_up с номером кнопки (1..6).",
    dispatcher = dispatcher
) {
    private val TAG = "ButtonMatrixMode"

    override fun start() {
        Log.d(TAG, "Запуск ButtonMatrixMode")

        // Устанавливаем статичный ModeState — громкость в этом режиме не используется
        _state.value = ModeState(currentVolume = 0, maxVolume = 0, displayLabel = "матрица")
        AppEventBus.tryEmit(
            AppEvent.ModeStateChanged(
                modeId = VolumeControlMode.BUTTON_MATRIX,
                currentVolume = 0,
                maxVolume = 0,
                displayLabel = "матрица"
            )
        )

        // Подписка на нажатия кнопок матрицы (с экрана или физических клавиш)
        modeScope.launch {
            appEvents.collect { event ->
                when (event) {
                    is AppEvent.MatrixButtonDown -> {
                        val num = event.buttonNumber
                        Log.d(TAG, "Кнопка матрицы $num нажата")
                        commandSender.send(DeviceCommand.ButtonDown(num))
                    }
                    is AppEvent.MatrixButtonUp -> {
                        val num = event.buttonNumber
                        Log.d(TAG, "Кнопка матрицы $num отпущена")
                        commandSender.send(DeviceCommand.ButtonUp(num))
                    }
                    else -> { /* игнорируем */ }
                }
            }
        }
    }
}
