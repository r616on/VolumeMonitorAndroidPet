package com.example.volumemonitor.core.repository

interface SettingsRepository {
    fun getSavedDevice(): Pair<Int, Int>?
    fun saveDevice(vendorId: Int, productId: Int)
    fun getBassLevel(): Int
    fun saveBassLevel(level: Int)
}
