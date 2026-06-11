package com.example.volumemonitor.core

object Constants {
    const val ACTION_USB_PERMISSION = "com.example.volumemonitor.USB_PERMISSION"

    const val PREFS_NAME_USB = "UsbDevicePrefs"
    const val PREFS_NAME_BASS = "BassPrefs"
    const val KEY_VENDOR_ID = "vendorId"
    const val KEY_PRODUCT_ID = "productId"
    const val KEY_BASS_POSITION = "bass_position"

    // ── Общие настройки ──
    const val PREFS_NAME_GENERAL = "GeneralPrefs"
    const val KEY_VOLUME_CONTROL_MODE = "volume_control_mode"
    const val KEY_OBSERVER_MAX_VOLUME_SOURCE = "observer_max_volume_source"
    const val KEY_OBSERVER_CUSTOM_MAX_VOLUME = "observer_custom_max_volume"

    // ── Настройки кнопок ──
    const val PREFS_NAME_BUTTONS = "ButtonPrefs"
    const val KEY_BUTTON_VOLUME_UP = "button_volume_up"
    const val KEY_BUTTON_VOLUME_DOWN = "button_volume_down"
    const val KEY_BUTTON_PRESET_NEXT = "button_preset_next"
    const val KEY_MAX_VOLUME_VALUE = "max_volume_value"
    const val KEY_BUTTON_CURRENT_VOLUME = "button_current_volume"
    const val KEY_LONG_PRESS_DELAY_MS = "long_press_delay_ms"
    const val KEY_BUTTON_LEARN_TIMEOUT_MS = "button_learn_timeout_ms"

    const val NOTIFICATION_CHANNEL_ID = "volume_monitor_channel"
    const val NOTIFICATION_ID = 1001

    const val BAUD_RATE = 115200
    const val DATA_BITS = 8
    const val MAX_VOLUME_TARGET = 255
    const val BASS_MAX_POSITION = 14          // 15 положений (0..14), как у громкости
    const val BASS_DEFAULT_POSITION = 7       // середина 0..14

    // ── Управление с экрана ──
    const val SCREEN_MAX_POSITION = 14          // 15 положений (0..14)
    const val KEY_SCREEN_CURRENT_VOLUME = "screen_current_volume"

    // ── SetVolumeMemo ──
    const val KEY_LAST_MEMO_VOLUME = "last_memo_volume"   // Int: последнее записанное в память значение (0..255), -1 если ещё нет
    const val MEMO_DEBOUNCE_MS = 20_000L                   // задержка перед отправкой SetVolumeMemo

    // ── Значения по умолчанию для кнопок ──
    const val DEFAULT_MAX_VOLUME_VALUE = 15
    const val DEFAULT_LONG_PRESS_DELAY_MS = 500L
    const val DEFAULT_BUTTON_LEARN_TIMEOUT_MS = 2000L
    const val LONG_PRESS_REPEAT_INTERVAL_MS = 200L

    // ── Teyes ──
    const val PREFS_NAME_TEYES = "TeyesPrefs"
    const val KEY_TEYES_MAX_VOLUME = "teyes_max_volume"
    const val KEY_TEYES_CURRENT_VOLUME = "teyes_current_volume"
    const val DEFAULT_TEYES_MAX_VOLUME = 36
    const val TEYES_MAX_VOLUME_LIMIT = 100

    // ── Матрица кнопок ──
    const val PREFS_NAME_MATRIX = "MatrixButtonPrefs"
    const val KEY_MATRIX_BUTTON_1 = "matrix_button_1"
    const val KEY_MATRIX_BUTTON_2 = "matrix_button_2"
    const val KEY_MATRIX_BUTTON_3 = "matrix_button_3"
    const val KEY_MATRIX_BUTTON_4 = "matrix_button_4"
    const val KEY_MATRIX_BUTTON_5 = "matrix_button_5"
    const val KEY_MATRIX_BUTTON_6 = "matrix_button_6"
    const val MATRIX_BUTTON_COUNT = 6
}
