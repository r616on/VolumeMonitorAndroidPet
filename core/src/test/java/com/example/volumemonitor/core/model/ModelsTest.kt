package com.example.volumemonitor.core.model

import com.example.volumemonitor.core.usb.UsbPortState
import com.example.volumemonitor.core.usb.displayText
import com.example.volumemonitor.core.volume.VolumeData
import com.example.volumemonitor.core.volume.mode.ModeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun volumeData_equality() {
        val a = VolumeData(5, 15, 85)
        val b = VolumeData(5, 15, 85)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun volumeData_inequality() {
        val a = VolumeData(5, 15, 85)
        val b = VolumeData(6, 15, 102)
        assertNotEquals(a, b)
    }

    @Test
    fun modeState_equality() {
        val a = ModeState(10, 20, "test")
        val b = ModeState(10, 20, "test")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun modeState_inequality() {
        val a = ModeState(10, 20, "test")
        val b = ModeState(11, 20, "test")
        assertNotEquals(a, b)
    }

    @Test
    fun deviceCommand_sealedHierarchy() {
        val cmd: DeviceCommand = DeviceCommand.SetVolume(100)
        val result = when (cmd) {
            is DeviceCommand.SetVolume -> "vol:${cmd.value}"
            is DeviceCommand.SetBassLevel -> "bass:${cmd.value}"
            DeviceCommand.ChangePreset -> "change"
            DeviceCommand.GetPreset -> "get"
        }
        assertEquals("vol:100", result)
    }

    @Test
    fun buttonAction_enumValues() {
        val actions = ButtonAction.values()
        assertEquals(2, actions.size)
        assertTrue(actions.contains(ButtonAction.VOLUME_UP))
        assertTrue(actions.contains(ButtonAction.VOLUME_DOWN))
    }

    @Test
    fun volumeControlMode_enumValues() {
        val modes = VolumeControlMode.values()
        assertEquals(2, modes.size)
        assertTrue(modes.contains(VolumeControlMode.OBSERVER))
        assertTrue(modes.contains(VolumeControlMode.BUTTONS))
    }

    @Test
    fun maxVolumeSource_enumValues() {
        val sources = MaxVolumeSource.values()
        assertEquals(2, sources.size)
        assertTrue(sources.contains(MaxVolumeSource.SYSTEM))
        assertTrue(sources.contains(MaxVolumeSource.CUSTOM))
    }

    @Test
    fun usbPortState_displayText_disconnected() {
        val state: UsbPortState = UsbPortState.Disconnected
        assertEquals("Отключено", state.displayText)
    }

    @Test
    fun usbPortState_displayText_initializing() {
        val state: UsbPortState = UsbPortState.Initializing
        assertEquals("Инициализация...", state.displayText)
    }

    @Test
    fun usbPortState_displayText_connected() {
        val state: UsbPortState = UsbPortState.Connected("Arduino Uno")
        assertEquals("Подключено: Arduino Uno", state.displayText)
    }

    @Test
    fun usbPortState_displayText_error() {
        val state: UsbPortState = UsbPortState.Error("Timeout")
        assertEquals("Ошибка: Timeout", state.displayText)
    }
}
