package com.example.volumemonitor.core.repository

import com.example.volumemonitor.core.model.ButtonAction
import com.example.volumemonitor.core.model.VolumeControlMode

interface SettingsRepository {
    fun getSavedDevice(): Pair<Int, Int>?
    fun saveDevice(vendorId: Int, productId: Int)
    fun getBassLevel(): Int
    fun saveBassLevel(level: Int)

    // ── Режим управления ──
    fun getVolumeControlMode(): VolumeControlMode
    fun saveVolumeControlMode(mode: VolumeControlMode)

    // ── Кнопки ──
    fun getButtonKeyCodes(action: ButtonAction): Set<Int>
    fun addButtonKeyCode(action: ButtonAction, keyCode: Int)
    fun removeButtonKeyCode(action: ButtonAction, keyCode: Int)
    fun removeAllButtonKeyCodes(action: ButtonAction)
    fun getMaxVolumeValue(): Int
    fun saveMaxVolumeValue(value: Int)
    fun getButtonCurrentVolume(): Int
    fun saveButtonCurrentVolume(volume: Int)
    fun getLongPressDelayMs(): Long
    fun saveLongPressDelayMs(delayMs: Long)
}
