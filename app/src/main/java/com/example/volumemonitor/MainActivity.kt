package com.example.volumemonitor

import android.Manifest
import android.app.PendingIntent
import android.os.Build
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.volumemonitor.core.VolumeMonitorService
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.usb.UsbPortState
import com.example.volumemonitor.core.usb.displayText
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl
import com.example.volumemonitor.core.serialization.JsonCommandSerializer
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    // ── View (lateinit для findViewById — допустимо) ──
    private lateinit var changePresetButton: Button
    private lateinit var presetTextView: TextView
    private lateinit var requestPresetButton: Button
    private lateinit var volumeTextView: TextView
    private lateinit var jsonTextView: TextView
    private lateinit var usbStatusTextView: TextView
    private lateinit var arduinoResponseTextView: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var bassSeekBar: SeekBar
    private lateinit var bassValueTextView: TextView

    // ── Иммутабельное состояние ──
    private val audioManager: AudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(this) }
    private val commandSerializer = JsonCommandSerializer()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var volumeService: VolumeMonitorService? = null
    private var isBound = false
    private var lastSentBassLevel: Int? = null
    private var presetButtonsRunnable: Runnable? = null
    private var currentPreset: Int = 1
    private var lastUsbStatus: UsbPortState = UsbPortState.Initializing

    // ── История ответов Arduino ──
    private val responseHistory = mutableListOf<String>()

    private fun loadBassLevel(): Int = settingsRepository.getBassLevel()
    private fun saveBassLevel(level: Int) = settingsRepository.saveBassLevel(level)

    private fun bassPositionToPercent(position: Int): Int =
        (position * 100f / 8f).roundToInt()

    private fun bassPositionToValue(position: Int): Int {
        val percent = bassPositionToPercent(position)
        return (percent * 255f / 100f).roundToInt().coerceIn(0, 255)
    }

    private fun updateBassText(level: Int) {
        bassValueTextView.text = "${bassPositionToPercent(level)}%"
    }

    private fun sendBassCommand(level: Int) {
        val value = bassPositionToValue(level)
        val json = commandSerializer.serialize(DeviceCommand.SetBassLevel(value))
        volumeService?.sendCommand(json)
        Log.d(TAG, "Bass level: ${bassPositionToPercent(level)}% (pos=$level) -> value: $value")
    }

    // ── ServiceConnection ──
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Сервис подключен")
            val binder = service as VolumeMonitorService.LocalBinder
            volumeService = binder.getService()
            isBound = true

            val intent = Intent(this@MainActivity, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this@MainActivity, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            volumeService?.setNotificationPendingIntent(pendingIntent)

            updateVolumeDisplay()
            updateUsbStatus()
            Toast.makeText(this@MainActivity, "Сервис запущен", Toast.LENGTH_SHORT).show()
            sendBassCommand(bassSeekBar.progress)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Сервис отключен")
            isBound = false
            volumeService = null
        }
    }

    // ── Жизненный цикл ──

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "=== MainActivity onCreate ===")

        initUIElements()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setupButtons()

        val savedBass = loadBassLevel()
        bassSeekBar.progress = savedBass
        updateBassText(savedBass)

        bassSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateBassText(progress)
                    if (lastSentBassLevel != progress) {
                        sendBassCommand(progress)
                        lastSentBassLevel = progress
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val level = seekBar?.progress ?: 4
                saveBassLevel(level)
            }
        })

        // Единая подписка на AppEventBus вместо 3 BroadcastReceiver
        lifecycleScope.launch {
            AppEventBus.events.collect { event ->
                when (event) {
                    is AppEvent.VolumeChanged -> updateVolumeDisplay()
                    is AppEvent.UsbStatusChanged -> {
                        lastUsbStatus = event.status
                        updateUsbStatus()
                    }
                    is AppEvent.ArduinoResponse -> onArduinoResponse(event.rawLine)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        startAndBindService()
        updateVolumeDisplay()
        updateUsbStatus()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        presetButtonsRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    // ── Инициализация UI ──

    private fun initUIElements() {
        volumeTextView = findViewById(R.id.volumeTextView)
        jsonTextView = findViewById(R.id.jsonTextView)
        usbStatusTextView = findViewById(R.id.usbStatusTextView)
        arduinoResponseTextView = findViewById(R.id.arduinoResponseTextView)
        settingsButton = findViewById(R.id.settingsButton)
        bassSeekBar = findViewById(R.id.bassSeekBar)
        bassValueTextView = findViewById(R.id.bassValueTextView)
        presetTextView = findViewById(R.id.presetTextView)
        changePresetButton = findViewById(R.id.changePresetButton)
        requestPresetButton = findViewById(R.id.requestPresetButton)
    }

    // ── Кнопки ──

    private fun setupButtons() {
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        changePresetButton.setOnClickListener {
            volumeService?.sendCommand(commandSerializer.serialize(DeviceCommand.ChangePreset))
            disablePresetButtonsTemporarily()
        }

        requestPresetButton.setOnClickListener {
            volumeService?.sendCommand(commandSerializer.serialize(DeviceCommand.GetPreset))
            disablePresetButtonsTemporarily()
            Toast.makeText(this, "Запрос пресета отправлен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun disablePresetButtonsTemporarily() {
        requestPresetButton.isEnabled = false
        changePresetButton.isEnabled = false
        presetButtonsRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        val runnable = Runnable {
            requestPresetButton.isEnabled = true
            changePresetButton.isEnabled = true
        }
        presetButtonsRunnable = runnable
        Handler(Looper.getMainLooper()).postDelayed(runnable, 3000)
    }

    // ── Сервис ──

    private fun startAndBindService() {
        val serviceIntent = Intent(this, VolumeMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        Log.d(TAG, "Сервис запущен и привязан")
    }

    // ── Обновление UI (реактивное) ──

    private fun updateVolumeDisplay() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeTextView.text = "Громкость: $currentVolume / $maxVolume"

        val targetVolume = if (currentVolume == 0) 0
        else (currentVolume * 255.0 / 30.0).roundToInt().coerceIn(0, 255)

        jsonTextView.text = "JSON: {\"value\":$currentVolume,\"set_volume\":$targetVolume}"
    }

    private fun updateUsbStatus() {
        usbStatusTextView.text = "Статус USB: ${lastUsbStatus.displayText}"
    }

    // ── Обработка ответа Arduino ──

    private fun onArduinoResponse(rawLine: String) {
        var response = rawLine.trim()
        if (response.startsWith("[") && response.endsWith("]")) {
            response = response.substring(1, response.length - 1)
        }

        val timestamp = timeFormat.format(Date())
        val formattedResponse = "[$timestamp] $response"

        responseHistory.add(0, formattedResponse)
        if (responseHistory.size > 10) {
            responseHistory.removeAt(responseHistory.size - 1)
        }
        arduinoResponseTextView.text = responseHistory.joinToString("\n")

        try {
            val jsonObject = JSONObject(response)
            val command = jsonObject.optString("command")
            if (command == "preset_changed") {
                val value = jsonObject.optInt("value", -1)
                if (value != -1) {
                    currentPreset = value
                    presetTextView.text = "$value"
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Не удалось распарсить JSON: ${e.message}")
        }
        Log.d("ARDUINO_DEBUG", "Получено: $response")
    }
}