package com.example.volumemonitor.core.serialization

import com.example.volumemonitor.core.model.DeviceCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonCommandSerializerTest {

    @Test
    fun serialize_setVolume() {
        val result = DeviceCommand.SetVolume(128).toJson()
        assertEquals("""{"command":"set_volume","value":128}""", result)
    }

    @Test
    fun serialize_setBassLevel() {
        val result = DeviceCommand.SetBassLevel(4).toJson()
        assertEquals("""{"command":"set_bass_level","value":4}""", result)
    }

    @Test
    fun serialize_changePreset() {
        val result = DeviceCommand.ChangePreset.toJson()
        assertEquals("""{"command":"change_preset"}""", result)
    }

    @Test
    fun serialize_getPreset() {
        val result = DeviceCommand.GetPreset.toJson()
        assertEquals("""{"command":"get_preset"}""", result)
    }

    @Test
    fun frame_wrapsInBracketsAndNewline() {
        val result = DeviceCommand.frame("hello")
        val expected = "[hello]\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }

    @Test
    fun frame_emptyString() {
        val result = DeviceCommand.frame("")
        val expected = "[]\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }

    @Test
    fun serialize_setVolume_zero() {
        val result = DeviceCommand.SetVolume(0).toJson()
        assertEquals("""{"command":"set_volume","value":0}""", result)
    }

    @Test
    fun serialize_setVolume_boundary() {
        val result = DeviceCommand.SetVolume(255).toJson()
        assertEquals("""{"command":"set_volume","value":255}""", result)
    }

    @Test
    fun frame_nonAscii() {
        val result = DeviceCommand.frame("привет")
        val expected = "[привет]\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }
}
