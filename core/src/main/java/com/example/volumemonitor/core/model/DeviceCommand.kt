package com.example.volumemonitor.core.model

sealed class DeviceCommand {
    data class SetVolume(val value: Int) : DeviceCommand()
    data class SetBassLevel(val value: Int) : DeviceCommand()
    object ChangePreset : DeviceCommand()
    object GetPreset : DeviceCommand()
}
