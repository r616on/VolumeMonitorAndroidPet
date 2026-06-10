package com.example.volumemonitor.core.volume

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeMathTest {

    @Test
    fun buttonToPort_zeroVolume_returnsZero() {
        assertEquals(0, VolumeMath.buttonToPort(0, 15))
    }

    @Test
    fun buttonToPort_maxVolume_returnsMaxTarget() {
        assertEquals(255, VolumeMath.buttonToPort(15, 15))
    }

    @Test
    fun buttonToPort_halfVolume_returnsCorrectPortValue() {
        assertEquals(119, VolumeMath.buttonToPort(7, 15))
    }

    @Test
    fun buttonToPort_minNonZero() {
        assertEquals(17, VolumeMath.buttonToPort(1, 15))
    }

    @Test
    fun buttonToPort_maxVolumeOne_returnsMaxTarget() {
        assertEquals(255, VolumeMath.buttonToPort(1, 1))
    }

    @Test
    fun buttonToPort_customMaxTarget() {
        assertEquals(50, VolumeMath.buttonToPort(5, 10, 100))
    }

    @Test
    fun buttonToPort_volumeExceedsMax_clampsToMaxTarget() {
        assertEquals(255, VolumeMath.buttonToPort(20, 15))
    }

    @Test
    fun buttonToPort_negativeVolume_clampsToZero() {
        assertEquals(0, VolumeMath.buttonToPort(-5, 15))
    }

    @Test
    fun buttonToPort_maxVolumeZero_coercesToAtLeastOne() {
        assertEquals(255, VolumeMath.buttonToPort(5, 0))
    }

    @Test
    fun buttonToPort_negativeMaxVolume_coercesToAtLeastOne() {
        assertEquals(255, VolumeMath.buttonToPort(5, -10))
    }

    @Test
    fun observerToTarget_zeroVolume_returnsZero() {
        assertEquals(0, VolumeMath.observerToTarget(0, 15))
    }

    @Test
    fun observerToTarget_maxVolume_returnsMaxTarget() {
        assertEquals(255, VolumeMath.observerToTarget(15, 15))
    }

    @Test
    fun observerToTarget_halfVolume_returnsCorrectTarget() {
        assertEquals(119, VolumeMath.observerToTarget(7, 15))
    }

    @Test
    fun observerToTarget_minNonZero() {
        assertEquals(17, VolumeMath.observerToTarget(1, 15))
    }

    @Test
    fun observerToTarget_maxOne() {
        assertEquals(255, VolumeMath.observerToTarget(1, 1))
    }

    @Test
    fun observerToTarget_customMaxTarget() {
        assertEquals(50, VolumeMath.observerToTarget(5, 10, 100))
    }

    @Test
    fun observerToTarget_largeVolume_coercesIn() {
        assertEquals(255, VolumeMath.observerToTarget(100, 15))
    }

    @Test
    fun observerToTarget_negativeCurrent_returnsZeroBecauseCoerceIn() {
        assertEquals(0, VolumeMath.observerToTarget(-5, 15))
    }

    @Test
    fun observerToTarget_maxVolumeZero_coercesToAtLeastOne() {
        assertEquals(255, VolumeMath.observerToTarget(5, 0))
    }
}
