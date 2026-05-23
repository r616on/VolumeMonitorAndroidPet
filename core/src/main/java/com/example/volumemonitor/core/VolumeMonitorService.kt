package com.example.volumemonitor.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

class VolumeMonitorService : Service() {
    private val TAG = "VolumeMonitor"
    private val ACTION_USB_PERMISSION = "com.example.volumemonitor.USB_PERMISSION"
    private val NOTIFICATION_ID = 1001
    private val serialBuffer = StringBuilder()
    private val bufferLock = Any()
    private var audioManager: AudioManager? = null
    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null
    private var previousVolume = -1
    private val binder: IBinder = LocalBinder()

    // Для уведомления
    private var notificationPendingIntent: PendingIntent? = null

    var isUsbConnected = false
        private set
    var usbStatus = "Инициализация..."
        private set

    private var selectedDevice: UsbDevice? = null

    // Коллбэк для чтения данных от Arduino
    private val serialListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            val chunk = String(data, Charsets.UTF_8)
            synchronized(bufferLock) {
                serialBuffer.append(chunk)

                // Обрабатываем все полные строки в буфере
                var index: Int
                while (serialBuffer.indexOf("\n").also { index = it } >= 0) {
                    val line = serialBuffer.substring(0, index).trim()
                    serialBuffer.delete(0, index + 1)

                    if (line.isNotEmpty()) {
                        Log.d(TAG, "Полная строка от Arduino: $line")
                        val intent = Intent("ARDUINO_RESPONSE")
                        intent.putExtra("response", line)
                        sendBroadcast(intent)
                    }
                }
            }
        }

        override fun onRunError(e: Exception) {
            Log.e(TAG, "Ошибка чтения: ${e.message}")
            runOnUiThread {
                usbStatus = "Ошибка чтения"
                sendUsbStatusUpdate("Ошибка чтения")
            }
        }

        private fun runOnUiThread(action: () -> Unit) {
            Handler(mainLooper).post(action)
        }
    }

    // USB Receiver
    @Suppress("DEPRECATION")
    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "USB Broadcast: $action")
            when (action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = getUsbDeviceExtra(intent)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                Log.i(TAG, "USB разрешение получено для: ${it.deviceName}")
                                usbStatus = "Разрешение получено: ${it.deviceName}"
                                if (selectedDevice?.deviceId == it.deviceId || selectedDevice == null) {
                                    openSerialConnection(it)
                                }
                            }
                        } else {
                            Log.e(TAG, "USB разрешение отклонено")
                            usbStatus = "Разрешение отклонено"
                            sendUsbStatusUpdate("Разрешение отклонено")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getUsbDeviceExtra(intent)
                    device?.let {
                        Log.i(TAG, "USB устройство подключено: ${it.deviceName}")
                        val saved = loadSavedDevice()
                        if (saved != null && it.vendorId == saved.first && it.productId == saved.second) {
                            selectedDevice = it
                            if (usbManager?.hasPermission(it) == true) {
                                openSerialConnection(it)
                            } else {
                                usbStatus = "Устройство подключено, но нет разрешения"
                                sendUsbStatusUpdate(usbStatus)
                                requestUsbPermission(it)
                            }
                        } else {
                            usbStatus = "Подключено неизвестное устройство: ${it.deviceName}"
                            sendUsbStatusUpdate(usbStatus)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDeviceExtra(intent)
                    if (device?.deviceId == selectedDevice?.deviceId) {
                        Log.i(TAG, "Выбранное USB устройство отключено")
                        usbStatus = "Устройство отключено"
                        isUsbConnected = false
                        closeSerialConnection()
                        sendUsbStatusUpdate("Устройство отключено")
                    }
                }
            }
        }
    }

    // Volume Receiver
    private val volumeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    if (previousVolume != currentVolume) {
                        val targetVolume = if (currentVolume == 0) {
                            0
                        } else {
                            Math.round(currentVolume * 255.0 / 30.0).coerceIn(0, 255)
                        }
                        sendVolumeData(targetVolume)
                        Log.d(TAG, "Громкость изменилась: $currentVolume")
                        val volumeUpdateIntent = Intent("VOLUME_UPDATED")
                        volumeUpdateIntent.putExtra("volume", currentVolume)
                        sendBroadcast(volumeUpdateIntent)
                        previousVolume = currentVolume
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VolumeMonitorService = this@VolumeMonitorService
    }

    // Загрузка сохранённого устройства
    private fun loadSavedDevice(): Pair<Int, Int>? {
        val prefs = getSharedPreferences("UsbDevicePrefs", Context.MODE_PRIVATE)
        val vid = prefs.getInt("vendorId", -1)
        val pid = prefs.getInt("productId", -1)
        return if (vid != -1 && pid != -1) Pair(vid, pid) else null
    }

    // Метод для установки выбранного устройства из SettingsActivity
    fun setSelectedUsbDevice(device: UsbDevice?) {
        selectedDevice = device
        Log.d(TAG, "Выбрано устройство: ${device?.deviceName ?: "null"}")
        closeSerialConnection()
        if (device != null) {
            if (usbManager?.hasPermission(device) == true) {
                openSerialConnection(device)
            } else {
                usbStatus = "Нет разрешения для: ${device.deviceName}"
                requestUsbPermission(device)
            }
        }
    }

    // Метод для передачи PendingIntent из MainActivity (для уведомления)
    fun setNotificationPendingIntent(pendingIntent: PendingIntent) {
        notificationPendingIntent = pendingIntent
        // Обновляем уведомление с новым Intent
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== Создание сервиса ===")

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        usbManager = getSystemService(USB_SERVICE) as UsbManager

        val usbFilter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, usbFilter)
        }

        // TODO: VOLUME_CHANGED_ACTION не доставляется фоновым приложениям на Android 15+ (API 35).
        // При повышении targetSdk до 35 перейти на OnAudioVolumeChangedListener.
        val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, volumeFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, volumeFilter)
        }

        previousVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        Log.d(TAG, "Сервис создан успешно")

        // Запуск foreground (пока без PendingIntent, установится позже из MainActivity)
        startForeground(NOTIFICATION_ID, createNotification())

        // Попытка подключиться к сохранённому устройству
        val saved = loadSavedDevice()
        if (saved != null) {
            val (vid, pid) = saved
            val matchingDevice = usbManager?.deviceList?.values?.find {
                it.vendorId == vid && it.productId == pid
            }
            if (matchingDevice != null) {
                selectedDevice = matchingDevice
                if (usbManager?.hasPermission(matchingDevice) == true) {
                    openSerialConnection(matchingDevice)
                } else {
                    usbStatus = "Нет разрешения для сохранённого устройства"
                    sendUsbStatusUpdate(usbStatus)
                    // АВТОМАТИЧЕСКИЙ ЗАПРОС
                    requestUsbPermission(matchingDevice)
                }
            } else {
                Log.d(TAG, "Сохранённое устройство не подключено")
            }
        }
    }

    private fun createNotification(): Notification {
        val channelId = "volume_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Монитор громкости",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сервис отслеживает громкость и отправляет данные на Arduino"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Монитор громкости")
            .setContentText("Отслеживание громкости активно")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // замените на свою иконку
            .setOngoing(true)

        val pendingIntent = notificationPendingIntent ?: run {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                PendingIntent.getActivity(this, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } else null
        }
        pendingIntent?.let { builder.setContentIntent(it) }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== Уничтожение сервиса ===")
        try {
            unregisterReceiver(usbReceiver)
            unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при отмене регистрации ресиверов: ${e.message}")
        }
        closeSerialConnection()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun requestUsbPermission(device: UsbDevice) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION),
                flags
            )
            usbManager?.requestPermission(device, permissionIntent)
            Log.d(TAG, "Запрос разрешения отправлен для: ${device.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запроса разрешения: ${e.message}")
        }
    }

    private fun openSerialConnection(device: UsbDevice) {
        Log.i(TAG, "Открытие последовательного порта для: ${device.deviceName}")
        closeSerialConnection()

        val manager = usbManager ?: run {
            Log.e(TAG, "UsbManager равен null")
            usbStatus = "Ошибка: USB сервис недоступен"
            sendUsbStatusUpdate("Ошибка: USB сервис недоступен")
            return
        }

        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                Log.e(TAG, "Нет драйвера для устройства")
                usbStatus = "Неподдерживаемое устройство"
                sendUsbStatusUpdate("Неподдерживаемое устройство")
                return
            }

            val port = driver.ports[0]
            val connection: UsbDeviceConnection = manager.openDevice(device) ?: run {
                Log.e(TAG, "Не удалось открыть соединение с устройством")
                usbStatus = "Ошибка открытия соединения"
                sendUsbStatusUpdate("Ошибка открытия соединения")
                return
            }

            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            Log.i(TAG, "Порт открыт, скорость 115200")

            serialPort = port
            isUsbConnected = true
            usbStatus = "Подключено: ${device.deviceName}"
            sendUsbStatusUpdate("USB подключено: ${device.deviceName}")

            serialIoManager = SerialInputOutputManager(port, serialListener)
            serialIoManager?.start()

            // Отправляем текущую громкость при подключении
            sendCurrentVolume()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка открытия последовательного порта: ${e.message}")
            usbStatus = "Ошибка: ${e.message}"
            sendUsbStatusUpdate("Ошибка подключения")
            closeSerialConnection()
        }
    }

    private fun closeSerialConnection() {
        serialIoManager?.stop()
        serialIoManager = null

        try {
            serialPort?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка при закрытии порта: ${e.message}")
        }
        serialPort = null
        isUsbConnected = false
    }

    // Отправка команды в формате JUDI
    fun sendCommand(commandJson: String) {
        if (!isUsbConnected || serialPort == null) {
            Log.w(TAG, "USB не подключено, команда не отправлена")
            return
        }
        val framedMessage = "[$commandJson]\n"
        try {
            serialPort?.write(framedMessage.toByteArray(Charsets.UTF_8), 1000)
            Log.d(TAG, "Отправлено: $framedMessage")
        } catch (e: IOException) {
            Log.e(TAG, "Ошибка отправки: ${e.message}")
            selectedDevice?.let { openSerialConnection(it) }
        }
    }

    // Отправка данных громкости
    fun sendVolumeData(volumeLevel: Long) {
        val json = "{\"command\":\"set_volume\",\"value\":$volumeLevel}"
        sendCommand(json)
    }

    // Отправка текущей громкости
    fun sendCurrentVolume() {
        val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val target = if (current == 0) 0 else Math.round(current * 255.0 / 30.0).coerceIn(0, 255)
        sendVolumeData(target)
    }

    private fun sendUsbStatusUpdate(status: String) {
        val intent = Intent("USB_STATUS_UPDATED")
        intent.putExtra("status", status)
        sendBroadcast(intent)
    }
}