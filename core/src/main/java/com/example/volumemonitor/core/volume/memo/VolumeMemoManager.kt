package com.example.volumemonitor.core.volume.memo

import android.util.Log
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.volume.mode.CommandSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounce-декоратор над [CommandSender].
 *
 * При получении [DeviceCommand.SetVolume] запускает отложенную отправку
 * [DeviceCommand.SetVolumeMemo] через [Constants.MEMO_DEBOUNCE_MS].
 * Каждый новый SetVolume сбрасывает таймер.
 * SetVolumeMemo отправляется только если значение изменилось с момента
 * последней успешной записи (хранится в [SettingsRepository]).
 *
 * Все остальные команды пробрасываются прозрачно без debounce.
 */
class VolumeMemoManager(
    private val delegate: CommandSender,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope
) : CommandSender {

    private val TAG = "VolumeMemoManager"
    private var memoJob: Job? = null

    override fun send(command: DeviceCommand) {
        // Пробрасываем команду дальше в любом случае
        delegate.send(command)

        // Отслеживаем только SetVolume для debounce-отправки SetVolumeMemo
        if (command is DeviceCommand.SetVolume) {
            scheduleMemo(command.value)
        }
    }

    private fun scheduleMemo(portValue: Int) {
        memoJob?.cancel()
        memoJob = scope.launch {
            delay(Constants.MEMO_DEBOUNCE_MS)
            val lastMemo = settingsRepository.getLastMemoVolume()
            if (portValue != lastMemo) {
                Log.d(TAG, "Отправка SetVolumeMemo: value=$portValue (было $lastMemo)")
                delegate.send(DeviceCommand.SetVolumeMemo(portValue))
                settingsRepository.saveLastMemoVolume(portValue)
            } else {
                Log.d(TAG, "SetVolumeMemo не требуется: значение $portValue уже сохранено")
            }
        }
    }

    /** Отменить ожидающий memo-job (например, при остановке сервиса). */
    fun cancelPending() {
        memoJob?.cancel()
    }
}
