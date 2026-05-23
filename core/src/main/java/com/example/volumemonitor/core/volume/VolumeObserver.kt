package com.example.volumemonitor.core.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.example.volumemonitor.core.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

// ── Иммутабельное состояние громкости ──

data class VolumeData(
    val current: Int,
    val max: Int,
    val target: Int  // значение для Arduino (0..255)
)

class VolumeObserver(
    private val context: Context,
    private val audioManager: AudioManager
) {
    private val TAG = "VolumeObserver"

    // ── Реактивное состояние (ФП) ──
    private val _volume = MutableStateFlow(currentVolumeData)
    val volume: StateFlow<VolumeData> = _volume.asStateFlow()

    val currentVolume: Int get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val maxVolume: Int get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    internal val currentVolumeData: VolumeData
        get() {
            val current = currentVolume
            val max = maxVolume
            val target = if (current == 0) 0
            else (current * Constants.MAX_VOLUME_TARGET.toDouble() / max)
                .roundToInt()
                .coerceIn(0, Constants.MAX_VOLUME_TARGET)
            return VolumeData(current, max, target)
        }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val data = currentVolumeData
                    if (_volume.value.current != data.current) {
                        Log.d(TAG, "Громкость изменилась: ${data.current}")
                        _volume.value = data
                    }
                }
            }
        }
    }

    fun register() {
        _volume.value = currentVolumeData
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(volumeReceiver, filter)
        }
    }

    fun unregister() {
        try { context.unregisterReceiver(volumeReceiver) } catch (_: Exception) {}
    }
}
