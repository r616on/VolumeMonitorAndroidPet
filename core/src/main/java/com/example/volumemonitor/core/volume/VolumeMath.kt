package com.example.volumemonitor.core.volume

import com.example.volumemonitor.core.Constants
import kotlin.math.roundToInt

/**
 * Чистые функции для расчёта значений громкости.
 * Вынесены из [ButtonsMode] и [VolumeObserver] для тестирования без Android-зависимостей.
 */
object VolumeMath {

    /**
     * Конвертирует громкость кнопок (0..maxVolume) в значение порта (0..maxTarget).
     */
    fun buttonToPort(volume: Int, maxVolume: Int, maxTarget: Int = Constants.MAX_VOLUME_TARGET): Int =
        (volume * maxTarget.toDouble() / maxVolume.coerceAtLeast(1))
            .roundToInt()
            .coerceIn(0, maxTarget)

    /**
     * Конвертирует системную громкость (0..maxVolume) в целевое значение для Arduino (0..maxTarget).
     * Если текущая громкость равна 0, то target всегда 0.
     */
    fun observerToTarget(current: Int, max: Int, maxTarget: Int = Constants.MAX_VOLUME_TARGET): Int {
        if (current == 0) return 0
        return (current * maxTarget.toDouble() / max.coerceAtLeast(1))
            .roundToInt()
            .coerceIn(0, maxTarget)
    }
}
