package com.example.volumemonitor.core.serialization

import com.example.volumemonitor.core.model.DeviceCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonCommandSerializerTest {

    private val serializer = JsonCommandSerializer()

    @Test
    fun serialize_setVolume() {
        val result = serializer.serialize(DeviceCommand.SetVolume(128))
        assertEquals("""{"command":"set_volume","value":128}""", result)
    }

    @Test
    fun serialize_setBassLevel() {
        val result = serializer.serialize(DeviceCommand.SetBassLevel(4))
        assertEquals("""{"command":"set_bass_level","value":4}""", result)
    }

    @Test
    fun serialize_changePreset() {
        val result = serializer.serialize(DeviceCommand.ChangePreset)
        assertEquals("""{"command":"change_preset"}""", result)
    }

    @Test
    fun serialize_getPreset() {
        val result = serializer.serialize(DeviceCommand.GetPreset)
        assertEquals("""{"command":"get_preset"}""", result)
    }

    @Test
    fun frame_wrapsInBracketsAndNewline() {
        val result = serializer.frame("hello")
        val expected = "[hello]\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }

    @Test
    fun frame_emptyString() {
        val result = serializer.frame("")
        val expected = "[]\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }

    @Test
    fun serialize_setVolume_zero() {
        val result = serializer.serialize(DeviceCommand.SetVolume(0))
        assertEquals("""{"command":"set_volume","value":0}""", result)
    }

    @Test
    fun serialize_setVolume_boundary() {
        val result = serializer.serialize(DeviceCommand.SetVolume(255))
        assertEquals("""{"command":"set_volume","value":255}""", result)
    }

    @Test
    fun frame_nonAscii() {
        val result = serializer.frame("привет")
        val expected = "[привет]\n".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, result)
    }
}
