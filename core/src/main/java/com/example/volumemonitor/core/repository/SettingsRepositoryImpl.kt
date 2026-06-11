package com.example.volumemonitor.core.repository

import android.content.Context
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.model.ButtonAction
import com.example.volumemonitor.core.model.MaxVolumeSource
import com.example.volumemonitor.core.model.VolumeControlMode

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val usbPrefs = context.getSharedPreferences(Constants.PREFS_NAME_USB, Context.MODE_PRIVATE)
    private val bassPrefs = context.getSharedPreferences(Constants.PREFS_NAME_BASS, Context.MODE_PRIVATE)
    private val generalPrefs = context.getSharedPreferences(Constants.PREFS_NAME_GENERAL, Context.MODE_PRIVATE)
    private val buttonPrefs = context.getSharedPreferences(Constants.PREFS_NAME_BUTTONS, Context.MODE_PRIVATE)
    private val matrixPrefs = context.getSharedPreferences(Constants.PREFS_NAME_MATRIX, Context.MODE_PRIVATE)

    override fun getSavedDevice(): Pair<Int, Int>? {
        val vid = usbPrefs.getInt(Constants.KEY_VENDOR_ID, -1)
        val pid = usbPrefs.getInt(Constants.KEY_PRODUCT_ID, -1)
        return if (vid != -1 && pid != -1) Pair(vid, pid) else null
    }

    override fun saveDevice(vendorId: Int, productId: Int) {
        usbPrefs.edit().putInt(Constants.KEY_VENDOR_ID, vendorId)
            .putInt(Constants.KEY_PRODUCT_ID, productId).apply()
    }

    override fun getBassLevel(): Int = bassPrefs.getInt(Constants.KEY_BASS_POSITION, Constants.BASS_DEFAULT_POSITION)

    override fun saveBassLevel(level: Int) {
        bassPrefs.edit().putInt(Constants.KEY_BASS_POSITION, level).apply()
    }

    // ── Режим управления ──

    override fun getVolumeControlMode(): VolumeControlMode {
        val name = generalPrefs.getString(Constants.KEY_VOLUME_CONTROL_MODE, VolumeControlMode.OBSERVER.name)
        return try {
            VolumeControlMode.valueOf(name ?: VolumeControlMode.OBSERVER.name)
        } catch (_: Exception) {
            VolumeControlMode.OBSERVER
        }
    }

    override fun saveVolumeControlMode(mode: VolumeControlMode) {
        generalPrefs.edit().putString(Constants.KEY_VOLUME_CONTROL_MODE, mode.name).apply()
    }

    // ── Макс. громкость для OBSERVER ──

    override fun getObserverMaxVolumeSource(): MaxVolumeSource {
        val name = generalPrefs.getString(Constants.KEY_OBSERVER_MAX_VOLUME_SOURCE, MaxVolumeSource.SYSTEM.name)
        return try {
            MaxVolumeSource.valueOf(name ?: MaxVolumeSource.SYSTEM.name)
        } catch (_: Exception) {
            MaxVolumeSource.SYSTEM
        }
    }

    override fun saveObserverMaxVolumeSource(source: MaxVolumeSource) {
        generalPrefs.edit().putString(Constants.KEY_OBSERVER_MAX_VOLUME_SOURCE, source.name).apply()
    }

    override fun getObserverCustomMaxVolume(): Int =
        generalPrefs.getInt(Constants.KEY_OBSERVER_CUSTOM_MAX_VOLUME, 0)

    override fun saveObserverCustomMaxVolume(value: Int) {
        generalPrefs.edit().putInt(Constants.KEY_OBSERVER_CUSTOM_MAX_VOLUME, value).apply()
    }

    // ── Кнопки ──

    override fun getButtonKeyCodes(action: ButtonAction): Set<Int> {
        val key = prefKeyForAction(action)
        // Пробуем новый формат: Set<String>
        val stringSet = buttonPrefs.getStringSet(key, null)
        if (stringSet != null) {
            return stringSet.mapNotNull { it.toIntOrNull() }.toSet()
        }
        // Миграция из старого формата: одиночный Int
        val oldValue = buttonPrefs.getInt(key, -1)
        if (oldValue != -1) {
            // Конвертируем в новый формат
            val newSet = setOf(oldValue.toString())
            buttonPrefs.edit().putStringSet(key, newSet).apply()
            return setOf(oldValue)
        }
        return emptySet()
    }

    override fun addButtonKeyCode(action: ButtonAction, keyCode: Int) {
        val key = prefKeyForAction(action)
        val current = getButtonKeyCodes(action).toMutableSet()
        current.add(keyCode)
        val stringSet = current.map { it.toString() }.toSet()
        buttonPrefs.edit().putStringSet(key, stringSet).apply()
    }

    override fun removeButtonKeyCode(action: ButtonAction, keyCode: Int) {
        val key = prefKeyForAction(action)
        val current = getButtonKeyCodes(action).toMutableSet()
        current.remove(keyCode)
        val stringSet = current.map { it.toString() }.toSet()
        buttonPrefs.edit().putStringSet(key, stringSet).apply()
    }

    override fun removeAllButtonKeyCodes(action: ButtonAction) {
        val key = prefKeyForAction(action)
        buttonPrefs.edit().putStringSet(key, emptySet()).apply()
    }

    private fun prefKeyForAction(action: ButtonAction): String = when (action) {
        ButtonAction.VOLUME_UP -> Constants.KEY_BUTTON_VOLUME_UP
        ButtonAction.VOLUME_DOWN -> Constants.KEY_BUTTON_VOLUME_DOWN
        ButtonAction.PRESET_NEXT -> Constants.KEY_BUTTON_PRESET_NEXT
    }

    override fun getMaxVolumeValue(): Int =
        buttonPrefs.getInt(Constants.KEY_MAX_VOLUME_VALUE, Constants.DEFAULT_MAX_VOLUME_VALUE)

    override fun saveMaxVolumeValue(value: Int) {
        buttonPrefs.edit().putInt(Constants.KEY_MAX_VOLUME_VALUE, value).apply()
    }

    override fun getButtonCurrentVolume(): Int =
        buttonPrefs.getInt(Constants.KEY_BUTTON_CURRENT_VOLUME, 0)

    override fun saveButtonCurrentVolume(volume: Int) {
        buttonPrefs.edit().putInt(Constants.KEY_BUTTON_CURRENT_VOLUME, volume).apply()
    }

    override fun getLongPressDelayMs(): Long =
        buttonPrefs.getLong(Constants.KEY_LONG_PRESS_DELAY_MS, Constants.DEFAULT_LONG_PRESS_DELAY_MS)

    override fun saveLongPressDelayMs(delayMs: Long) {
        buttonPrefs.edit().putLong(Constants.KEY_LONG_PRESS_DELAY_MS, delayMs).apply()
    }

    // ── Матрица кнопок ──

    private fun prefKeyForMatrixButton(buttonNumber: Int): String = when (buttonNumber) {
        1 -> Constants.KEY_MATRIX_BUTTON_1
        2 -> Constants.KEY_MATRIX_BUTTON_2
        3 -> Constants.KEY_MATRIX_BUTTON_3
        4 -> Constants.KEY_MATRIX_BUTTON_4
        5 -> Constants.KEY_MATRIX_BUTTON_5
        6 -> Constants.KEY_MATRIX_BUTTON_6
        else -> throw IllegalArgumentException("Matrix button must be 1..6, got $buttonNumber")
    }

    override fun getMatrixButtonKeyCodes(buttonNumber: Int): Set<Int> {
        val key = prefKeyForMatrixButton(buttonNumber)
        val stringSet = matrixPrefs.getStringSet(key, null)
        if (stringSet != null) {
            return stringSet.mapNotNull { it.toIntOrNull() }.toSet()
        }
        return emptySet()
    }

    override fun addMatrixButtonKeyCode(buttonNumber: Int, keyCode: Int) {
        val key = prefKeyForMatrixButton(buttonNumber)
        val current = getMatrixButtonKeyCodes(buttonNumber).toMutableSet()
        current.add(keyCode)
        val stringSet = current.map { it.toString() }.toSet()
        matrixPrefs.edit().putStringSet(key, stringSet).apply()
    }

    override fun removeMatrixButtonKeyCode(buttonNumber: Int, keyCode: Int) {
        val key = prefKeyForMatrixButton(buttonNumber)
        val current = getMatrixButtonKeyCodes(buttonNumber).toMutableSet()
        current.remove(keyCode)
        val stringSet = current.map { it.toString() }.toSet()
        matrixPrefs.edit().putStringSet(key, stringSet).apply()
    }

    override fun removeAllMatrixButtonKeyCodes(buttonNumber: Int) {
        val key = prefKeyForMatrixButton(buttonNumber)
        matrixPrefs.edit().putStringSet(key, emptySet()).apply()
    }

    // ── Управление с экрана ──

    override fun getScreenCurrentVolume(): Int =
        generalPrefs.getInt(Constants.KEY_SCREEN_CURRENT_VOLUME, 0)

    override fun saveScreenCurrentVolume(volume: Int) {
        generalPrefs.edit().putInt(Constants.KEY_SCREEN_CURRENT_VOLUME, volume).apply()
    }

    // ── SetVolumeMemo ──

    override fun getLastMemoVolume(): Int =
        generalPrefs.getInt(Constants.KEY_LAST_MEMO_VOLUME, -1)

    override fun saveLastMemoVolume(volume: Int) {
        generalPrefs.edit().putInt(Constants.KEY_LAST_MEMO_VOLUME, volume).apply()
    }
}
