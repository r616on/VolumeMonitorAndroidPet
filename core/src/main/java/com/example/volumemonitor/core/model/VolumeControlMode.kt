package com.example.volumemonitor.core.model

/** Режим управления громкостью. */
enum class VolumeControlMode {
    /** Отслеживание системной громкости через VolumeObserver (текущий режим). */
    OBSERVER,
    /** Управление через назначенные физические кнопки. */
    BUTTONS
}
