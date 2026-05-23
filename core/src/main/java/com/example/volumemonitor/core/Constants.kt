package com.example.volumemonitor.core

object Constants {
    const val ACTION_USB_PERMISSION = "com.example.volumemonitor.USB_PERMISSION"

    const val PREFS_NAME_USB = "UsbDevicePrefs"
    const val PREFS_NAME_BASS = "BassPrefs"
    const val KEY_VENDOR_ID = "vendorId"
    const val KEY_PRODUCT_ID = "productId"
    const val KEY_BASS_POSITION = "bass_position"

    const val NOTIFICATION_CHANNEL_ID = "volume_monitor_channel"
    const val NOTIFICATION_ID = 1001

    const val BAUD_RATE = 115200
    const val DATA_BITS = 8
    const val MAX_VOLUME_TARGET = 255
    const val BASS_MAX_POSITION = 8
    const val BASS_DEFAULT_POSITION = 4
}
