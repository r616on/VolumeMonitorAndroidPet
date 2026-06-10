package com.example.volumemonitor.core.event

import com.example.volumemonitor.core.model.ButtonAction
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.usb.UsbPortState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppEventBusTest {

    @Test
    fun tryEmit_doesNotThrowOnOverflow() {
        repeat(32) { i ->
            AppEventBus.tryEmit(AppEvent.VolumeChanged(i, i))
        }
    }

    @Test
    fun modeStateChanged_containsCorrectData() {
        val event = AppEvent.ModeStateChanged(
            modeId = VolumeControlMode.BUTTONS,
            currentVolume = 7,
            maxVolume = 20,
            displayLabel = "кнопки"
        )
        assertEquals(VolumeControlMode.BUTTONS, event.modeId)
        assertEquals(7, event.currentVolume)
        assertEquals(20, event.maxVolume)
        assertEquals("кнопки", event.displayLabel)
    }

    @Test
    fun allEventTypes_instantiateCorrectly() {
        val vc = AppEvent.VolumeChanged(5, 85)
        assertEquals(5, vc.current)
        assertEquals(85, vc.target)

        val usb = AppEvent.UsbStatusChanged(UsbPortState.Connected("Dev"))
        assertEquals("Dev", (usb.status as UsbPortState.Connected).deviceName)

        val ar = AppEvent.ArduinoResponse("OK")
        assertEquals("OK", ar.rawLine)

        val sd = AppEvent.SerialDataSent("{}")
        assertEquals("{}", sd.rawLine)

        val bp = AppEvent.ButtonPressed(ButtonAction.VOLUME_UP)
        assertEquals(ButtonAction.VOLUME_UP, bp.action)

        val mc = AppEvent.VolumeControlModeChanged(VolumeControlMode.OBSERVER)
        assertEquals(VolumeControlMode.OBSERVER, mc.mode)

        assertNotNull(AppEvent.ButtonSettingsChanged)
        assertNotNull(AppEvent.ObserverSettingsChanged)
    }
}
