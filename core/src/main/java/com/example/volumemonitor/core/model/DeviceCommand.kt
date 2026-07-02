package com.example.volumemonitor.core.model

/**
 * Единый реестр команд, отправляемых в serial port.
 *
 * Каждый подкласс инкапсулирует свой wire-формат: имя команды, JSON-сериализацию,
 * валидацию параметров. Добавление новой команды — новый подкласс в этом файле.
 *
 * Фреймирование (обрамление "[...]\n") — общее для всех команд, вынесено в [frame].
 */
sealed class DeviceCommand {
    /** Имя команды в JSON-поле "command" (например "set_volume"). */
    abstract val commandName: String

    /** Сериализовать команду в JSON-строку для отправки в порт. */
    abstract fun toJson(): String

    // ── Команды ──────────────────────────────────────────────

    /** Установить громкость (0..255). */
    data class SetVolume(val value: Int) : DeviceCommand() {
        override val commandName = "set_volume"
        init { require(value in 0..255) { "Volume must be 0..255, got $value" } }
        override fun toJson() = """{"command":"$commandName","value":$value}"""
    }

    /** Сохранить громкость в энергонезависимую память адаптера (0..255). */
    data class SetVolumeMemo(val value: Int) : DeviceCommand() {
        override val commandName = "set_volume_memo"
        init { require(value in 0..255) { "Volume must be 0..255, got $value" } }
        override fun toJson() = """{"command":"$commandName","value":$value}"""
    }

    /** Установить уровень баса (0..255). */
    data class SetBassLevel(val value: Int) : DeviceCommand() {
        override val commandName = "set_bass_level"
        init { require(value in 0..255) { "Bass level must be 0..255, got $value" } }
        override fun toJson() = """{"command":"$commandName","value":$value}"""
    }

    /** Переключить на следующий пресет (без параметров). */
    object ChangePreset : DeviceCommand() {
        override val commandName = "change_preset"
        override fun toJson() = """{"command":"$commandName"}"""
    }

    /** Запросить текущий пресет (без параметров). */
    object GetPreset : DeviceCommand() {
        override val commandName = "get_preset"
        override fun toJson() = """{"command":"$commandName"}"""
    }

    /** Нажатие кнопки матрицы (1..6). */
    data class ButtonDown(val value: Int) : DeviceCommand() {
        override val commandName = "button_down"
        init { require(value in 1..6) { "Matrix button must be 1..6, got $value" } }
        override fun toJson() = """{"command":"$commandName","value":$value}"""
    }

    /** Отпускание кнопки матрицы (1..6). */
    data class ButtonUp(val value: Int) : DeviceCommand() {
        override val commandName = "button_up"
        init { require(value in 1..6) { "Matrix button must be 1..6, got $value" } }
        override fun toJson() = """{"command":"$commandName","value":$value}"""
    }

    /** Включить/выключить REM на устройстве. */
    data class ChangeRem(val enable: Boolean) : DeviceCommand() {
        override val commandName = "change_rem"
        override fun toJson(): String {
            val value = if (enable) "enable" else "disable"
            return """{"command":"$commandName","value":"$value"}"""
        }
    }

    // ── Фреймирование ────────────────────────────────────────

    companion object {
        /**
         * Обрамить JSON-строку для отправки в serial port.
         * Формат: "[json]\n" — требование прошивки Arduino.
         */
        fun frame(json: String): ByteArray = "[$json]\n".toByteArray(Charsets.UTF_8)
    }
}
