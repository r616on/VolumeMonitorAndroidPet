package com.example.volumemonitor.core.model

/** Режим управления громкостью. */
enum class VolumeControlMode {
    /** Отслеживание системной громкости через VolumeObserver (текущий режим). */
    OBSERVER,
    /** Управление через назначенные физические кнопки. */
    BUTTONS,
    /** Управление с экрана — ползунок на главной странице. */
    SCREEN,
    /** Матрица из 6 кнопок — отправка button_down/button_up с номером кнопки. */
    BUTTON_MATRIX
}
