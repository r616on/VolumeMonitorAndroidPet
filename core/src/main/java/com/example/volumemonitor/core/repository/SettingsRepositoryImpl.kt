package com.example.volumemonitor.core.repository

import android.content.Context
import com.example.volumemonitor.core.Constants

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val usbPrefs = context.getSharedPreferences(Constants.PREFS_NAME_USB, Context.MODE_PRIVATE)
    private val bassPrefs = context.getSharedPreferences(Constants.PREFS_NAME_BASS, Context.MODE_PRIVATE)

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
}
