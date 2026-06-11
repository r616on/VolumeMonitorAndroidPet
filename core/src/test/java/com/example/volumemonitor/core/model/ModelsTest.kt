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
            is DeviceCommand.SetVolumeMemo -> "memo:${cmd.value}"
            is DeviceCommand.ButtonDown -> "down:${cmd.value}"
            is DeviceCommand.ButtonUp -> "up:${cmd.value}"
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
        assertEquals(4, modes.size)
        assertTrue(modes.contains(VolumeControlMode.OBSERVER))
        assertTrue(modes.contains(VolumeControlMode.BUTTONS))
        assertTrue(modes.contains(VolumeControlMode.SCREEN))
        assertTrue(modes.contains(VolumeControlMode.BUTTON_MATRIX))
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

    // ── DeviceCommand toJson и commandName ──

    @Test
    fun deviceCommand_setVolume_toJson() {
        val json = DeviceCommand.SetVolume(100).toJson()
        assertEquals("""{"command":"set_volume","value":100}""", json)
    }

    @Test
    fun deviceCommand_setBassLevel_toJson() {
        val json = DeviceCommand.SetBassLevel(200).toJson()
        assertEquals("""{"command":"set_bass_level","value":200}""", json)
    }

    @Test
    fun deviceCommand_changePreset_toJson() {
        val json = DeviceCommand.ChangePreset.toJson()
        assertEquals("""{"command":"change_preset"}""", json)
    }

    @Test
    fun deviceCommand_getPreset_toJson() {
        val json = DeviceCommand.GetPreset.toJson()
        assertEquals("""{"command":"get_preset"}""", json)
    }

    @Test
    fun deviceCommand_commandName_setVolume() {
        assertEquals("set_volume", DeviceCommand.SetVolume(5).commandName)
    }

    @Test
    fun deviceCommand_commandName_setBassLevel() {
        assertEquals("set_bass_level", DeviceCommand.SetBassLevel(5).commandName)
    }

    @Test
    fun deviceCommand_commandName_changePreset() {
        assertEquals("change_preset", DeviceCommand.ChangePreset.commandName)
    }

    @Test
    fun deviceCommand_commandName_getPreset() {
        assertEquals("get_preset", DeviceCommand.GetPreset.commandName)
    }

    // ── Валидация параметров ──

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_setVolume_rejectsNegative() {
        DeviceCommand.SetVolume(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_setVolume_rejectsOverflow() {
        DeviceCommand.SetVolume(256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_setBassLevel_rejectsNegative() {
        DeviceCommand.SetBassLevel(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_setBassLevel_rejectsOverflow() {
        DeviceCommand.SetBassLevel(256)
    }

    // ── ButtonDown / ButtonUp ──

    @Test
    fun deviceCommand_buttonDown_toJson() {
        val json = DeviceCommand.ButtonDown(3).toJson()
        assertEquals("""{"command":"button_down","value":3}""", json)
    }

    @Test
    fun deviceCommand_buttonUp_toJson() {
        val json = DeviceCommand.ButtonUp(6).toJson()
        assertEquals("""{"command":"button_up","value":6}""", json)
    }

    @Test
    fun deviceCommand_commandName_buttonDown() {
        assertEquals("button_down", DeviceCommand.ButtonDown(1).commandName)
    }

    @Test
    fun deviceCommand_commandName_buttonUp() {
        assertEquals("button_up", DeviceCommand.ButtonUp(1).commandName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_buttonDown_rejectsZero() {
        DeviceCommand.ButtonDown(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_buttonDown_rejectsSeven() {
        DeviceCommand.ButtonDown(7)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_buttonUp_rejectsZero() {
        DeviceCommand.ButtonUp(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun deviceCommand_buttonUp_rejectsSeven() {
        DeviceCommand.ButtonUp(7)
    }

    // ── Фреймирование ──

    @Test
    fun deviceCommand_frame_wrapsJson() {
        val result = DeviceCommand.frame("""{"command":"get_preset"}""")
        val expected = "[{\"command\":\"get_preset\"}]\n".toByteArray(Charsets.UTF_8)
        org.junit.Assert.assertArrayEquals(expected, result)
    }
}
