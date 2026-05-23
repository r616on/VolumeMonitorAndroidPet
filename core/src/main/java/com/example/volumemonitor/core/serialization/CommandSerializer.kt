package com.example.volumemonitor.core.serialization

import com.example.volumemonitor.core.model.DeviceCommand

interface CommandSerializer {
    fun serialize(command: DeviceCommand): String
    fun frame(raw: String): ByteArray
}

class JsonCommandSerializer : CommandSerializer {

    override fun serialize(command: DeviceCommand): String = when (command) {
        is DeviceCommand.SetVolume -> """{"command":"set_volume","value":${command.value}}"""
        is DeviceCommand.SetBassLevel -> """{"command":"set_bass_level","value":${command.value}}"""
        DeviceCommand.ChangePreset -> """{"command":"change_preset"}"""
        DeviceCommand.GetPreset -> """{"command":"get_preset"}"""
    }

    override fun frame(raw: String): ByteArray = "[$raw]\n".toByteArray(Charsets.UTF_8)
}
