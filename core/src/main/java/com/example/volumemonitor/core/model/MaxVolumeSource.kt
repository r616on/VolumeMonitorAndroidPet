package com.example.volumemonitor.core.model

/** Источник значения максимальной громкости для режима OBSERVER. */
enum class MaxVolumeSource {
    /** Использовать системную максимальную громкость (AudioManager.getStreamMaxVolume). */
    SYSTEM,
    /** Использовать пользовательское значение максимальной громкости. */
    CUSTOM
}
