package com.example.volumemonitor
import android.Manifest
import android.app.PendingIntent
import android.os.Build
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.volumemonitor.core.VolumeMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.SeekBar
import kotlin.math.roundToInt
import org.json.JSONObject
import android.os.Handler
import android.os.Looper

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var changePresetButton: Button
    private lateinit var presetTextView: TextView
    private var currentPreset: Int? = 1

    private lateinit var requestPresetButton: Button
    private lateinit var volumeTextView: TextView
    private lateinit var jsonTextView: TextView
    private lateinit var usbStatusTextView: TextView
    private lateinit var arduinoResponseTextView: TextView
    private lateinit var settingsButton: ImageButton

    private var volumeService: VolumeMonitorService? = null
    private var isBound = false
    private lateinit var audioManager: AudioManager
    private var presetButtonsRunnable: Runnable? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val responseHistory = StringBuilder() // Для накопления истории
    private lateinit var bassSeekBar: SeekBar
    private lateinit var bassValueTextView: TextView
    private var lastSentBassLevel: Int? = null
    private val PREFS_NAME = "BassPrefs"
    private val KEY_BASS_LEVEL = "bass_level"

    private fun loadBassLevel(): Int {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_BASS_LEVEL, 0) // По умолчанию 0 дБ
    }

    private fun saveBassLevel(level: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BASS_LEVEL, level)
            .apply()
    }

    private fun updateBassText(level: Int) {
        val sign = if (level > 0) "+$level" else level.toString()
        bassValueTextView.text = "$sign dB"
    }

    private fun sendBassCommand(level: Int) {
        // Кусочно-линейное преобразование -6..6 → 0..255 с точкой 0 дБ → 130
        val value = if (level <= 0) {
            ((level + 6) * 130f / 6f).roundToInt()
        } else {
            (130f + (level * 125f / 6f)).roundToInt()
        }.coerceIn(0, 255)

        volumeService?.sendCommand("{\"command\":\"set_bass_level\",\"value\":$value}")
        Log.d(TAG, "Bass level: $level dB -> value: $value")
    }

    // Receiver для обновления громкости
    private val volumeUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("VOLUME_UPDATED" == intent.action) {
                updateVolumeDisplay()
            }
        }
    }

    // Receiver для статуса USB
    private val usbStatusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("USB_STATUS_UPDATED" == intent.action) {
                updateUsbStatus()
            }
        }
    }

    // Receiver для ответов от Arduino
    private val arduinoResponseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ARDUINO_RESPONSE") {
                var response = intent.getStringExtra("response") ?: ""

                // Очищаем от лишних символов
                response = response.trim()

                // Убираем обрамление [ и ], если оно есть
                if (response.startsWith("[") && response.endsWith("]")) {
                    response = response.substring(1, response.length - 1)
                }

                // Добавляем в историю с временной меткой
                val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val formattedResponse = "[$timestamp] $response"

                // Добавляем в начало истории (чтобы новые сообщения были сверху)
                responseHistory.insert(0, "$formattedResponse\n")

                // Ограничиваем историю (например, последние 10 сообщений)
                val lines = responseHistory.toString().split("\n")
                if (lines.size > 10) {
                    responseHistory.clear()
                    responseHistory.append(lines.take(10).joinToString("\n"))
                }

                // Обновляем TextView
                arduinoResponseTextView.text = responseHistory.toString()
                // Пытаемся распарсить как JSON команду preset_changed
                try {
                    val jsonObject = JSONObject(response)
                    val command = jsonObject.optString("command")
                    if (command == "preset_changed") {
                        val value = jsonObject.optInt("value", -1)
                        if (value != -1) {
                            currentPreset = value
                            runOnUiThread {
                                presetTextView.text = "$value"
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Это не JSON или другая команда — просто игнорируем
                    Log.d(TAG, "Не удалось распарсить JSON: ${e.message}")
                }
                Log.d("ARDUINO_DEBUG", "Получено: $response")
            }
        }
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Сервис подключен")
            val binder = service as VolumeMonitorService.LocalBinder
            volumeService = binder.getService()
            isBound = true

            // Создаём PendingIntent для открытия MainActivity из уведомления
            val intent = Intent(this@MainActivity, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this@MainActivity,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            volumeService?.setNotificationPendingIntent(pendingIntent)

            updateVolumeDisplay()
            updateUsbStatus()
            Toast.makeText(this@MainActivity, "Сервис запущен", Toast.LENGTH_SHORT).show()
            val currentBass = bassSeekBar.progress - 6
            sendBassCommand(currentBass)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Сервис отключен")
            isBound = false
            volumeService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d(TAG, "=== MainActivity onCreate ===")

        initUIElements()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setupButtons()
        val savedBass = loadBassLevel()
        bassSeekBar.progress = savedBass + 6          // перевод -6..6 → 0..12
        updateBassText(savedBass)

        bassSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val level = progress - 6
                    updateBassText(level)
                    // Отправляем команду, только если значение действительно изменилось
                    if (lastSentBassLevel != level) {
                        sendBassCommand(level)
                        lastSentBassLevel = level
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val level = (seekBar?.progress ?: 6) - 6
                saveBassLevel(level)   // сохраняем финальное значение
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        registerReceivers()
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
        try {
            unregisterReceiver(volumeUpdateReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
        try {
            unregisterReceiver(usbStatusReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
        try {
            unregisterReceiver(arduinoResponseReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        presetButtonsRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        try {
            unregisterReceiver(volumeUpdateReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
        try {
            unregisterReceiver(usbStatusReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
        try {
            unregisterReceiver(arduinoResponseReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun initUIElements() {
        volumeTextView = findViewById(R.id.volumeTextView)
        jsonTextView = findViewById(R.id.jsonTextView)
        usbStatusTextView = findViewById(R.id.usbStatusTextView)
        arduinoResponseTextView = findViewById(R.id.arduinoResponseTextView) // добавить в XML
        settingsButton = findViewById(R.id.settingsButton)
        bassSeekBar = findViewById(R.id.bassSeekBar)
        bassValueTextView = findViewById(R.id.bassValueTextView)
        presetTextView = findViewById(R.id.presetTextView)
        changePresetButton = findViewById(R.id.changePresetButton)
        requestPresetButton = findViewById(R.id.requestPresetButton)
    }

    private fun setupButtons() {
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        changePresetButton.setOnClickListener {
            // Отправляем команду смены пресета (без параметров)
            volumeService?.sendCommand("{\"command\":\"change_preset\"}")

            requestPresetButton.isEnabled = false
            changePresetButton.isEnabled = false

            // Через 3 секунды разблокируем
            presetButtonsRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            presetButtonsRunnable = Runnable {
                requestPresetButton.isEnabled = true
                changePresetButton.isEnabled = true
            }
            Handler(Looper.getMainLooper()).postDelayed(presetButtonsRunnable!!, 3000)
        }
        requestPresetButton.setOnClickListener {
            volumeService?.sendCommand("{\"command\":\"get_preset\"}")

            requestPresetButton.isEnabled = false
            changePresetButton.isEnabled = false

            // Через 3 секунды разблокируем
            presetButtonsRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
            presetButtonsRunnable = Runnable {
                requestPresetButton.isEnabled = true
                changePresetButton.isEnabled = true
            }
            Handler(Looper.getMainLooper()).postDelayed(presetButtonsRunnable!!, 3000)
            Toast.makeText(this, "Запрос пресета отправлен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerReceivers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeUpdateReceiver, IntentFilter("VOLUME_UPDATED"), RECEIVER_NOT_EXPORTED)
            registerReceiver(usbStatusReceiver, IntentFilter("USB_STATUS_UPDATED"), RECEIVER_NOT_EXPORTED)
            registerReceiver(arduinoResponseReceiver, IntentFilter("ARDUINO_RESPONSE"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(volumeUpdateReceiver, IntentFilter("VOLUME_UPDATED"))
            registerReceiver(usbStatusReceiver, IntentFilter("USB_STATUS_UPDATED"))
            registerReceiver(arduinoResponseReceiver, IntentFilter("ARDUINO_RESPONSE"))
        }
        Log.d(TAG, "Broadcast Receiver'ы зарегистрированы")
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, VolumeMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent) // для Android 8+
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        Log.d(TAG, "Сервис запущен и привязан")
    }

    private fun updateVolumeDisplay() {
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeTextView.text = "Громкость: $currentVolume / $maxVolume"

        val targetVolume = if (currentVolume == 0) {
            0
        } else {
            Math.round(currentVolume * 255.0 / 30.0).coerceIn(0, 255)
        }

        jsonTextView.text = "JSON: {\"value\":$currentVolume,\"set_volume\":$targetVolume}"

    }

    private fun updateUsbStatus() {
        val status = volumeService?.usbStatus ?: "Сервис не доступен"
        usbStatusTextView.text = "Статус USB: $status"
    }
}