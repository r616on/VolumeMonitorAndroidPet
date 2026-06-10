package com.example.volumemonitor.core

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.notification.NotificationController
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl
import com.example.volumemonitor.core.serialization.JsonCommandSerializer
import com.example.volumemonitor.core.usb.UsbPortState
import com.example.volumemonitor.core.usb.UsbSerialPortManager
import com.example.volumemonitor.core.volume.mode.ButtonsMode
import com.example.volumemonitor.core.volume.mode.CommandSender
import com.example.volumemonitor.core.volume.mode.ObserverMode
import com.example.volumemonitor.core.volume.mode.VolumeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VolumeMonitorService : Service() {
    private val TAG = "VolumeMonitor"

    private val usbManager: UsbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }

    private lateinit var portManager: UsbSerialPortManager
    private lateinit var notificationController: NotificationController
    private lateinit var settingsRepository: SettingsRepository

    private val commandSerializer = JsonCommandSerializer()
    private var selectedDevice: UsbDevice? = null

    // ── Активный режим управления громкостью ──
    private var activeMode: VolumeMode? = null

    private val binder: IBinder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── CommandSender: мост между режимом и сериал портом ──
    private val commandSender: CommandSender by lazy {
        CommandSender { target ->
            if (!::portManager.isInitialized) {
                Log.w(TAG, "commandSender: portManager не инициализирован, пропускаем отправку target=$target")
                return@CommandSender
            }
            val cmd = DeviceCommand.SetVolume(target)
            val json = commandSerializer.serialize(cmd)
            val framed = commandSerializer.frame(json)
            portManager.send(framed)
            AppEventBus.tryEmit(AppEvent.SerialDataSent(json))
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "USB Broadcast: $action")
            when (action) {
                Constants.ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = portManager.getDeviceFromIntent(intent)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                Log.i(TAG, "USB разрешение получено для: ${it.deviceName}")
                                if (selectedDevice?.deviceId == it.deviceId || selectedDevice == null) {
                                    portManager.connect(it)
                                }
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = portManager.getDeviceFromIntent(intent)
                    device?.let {
                        Log.i(TAG, "USB устройство подключено: ${it.deviceName}")
                        val saved = settingsRepository.getSavedDevice()
                        if (saved != null && it.vendorId == saved.first && it.productId == saved.second) {
                            selectedDevice = it
                            if (usbManager.hasPermission(it)) {
                                portManager.connect(it)
                            } else {
                                portManager.requestPermission(it)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = portManager.getDeviceFromIntent(intent)
                    if (device?.deviceId == selectedDevice?.deviceId) {
                        Log.i(TAG, "Выбранное USB устройство отключено")
                        portManager.disconnect()
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): VolumeMonitorService = this@VolumeMonitorService
    }

    fun setNotificationPendingIntent(pi: PendingIntent) {
        startForeground(Constants.NOTIFICATION_ID, notificationController.build(pi))
    }

    fun setSelectedUsbDevice(device: UsbDevice?) {
        selectedDevice = device
        Log.d(TAG, "Выбрано устройство: ${device?.deviceName ?: "null"}")
        portManager.disconnect()
        if (device != null) {
            if (usbManager.hasPermission(device)) {
                portManager.connect(device)
            } else {
                portManager.requestPermission(device)
            }
        }
    }

    fun sendCommand(commandJson: String) {
        val framed = commandSerializer.frame(commandJson)
        portManager.send(framed)
        AppEventBus.tryEmit(AppEvent.SerialDataSent(commandJson))
    }

    // ── Управление режимами ──

    private fun activateMode(modeId: VolumeControlMode) {
        Log.d(TAG, "Активация режима: $modeId")
        activeMode?.stop()
        activeMode = when (modeId) {
            VolumeControlMode.OBSERVER -> ObserverMode(
                context = this,
                commandSender = commandSender,
                settingsRepository = settingsRepository,
                appEvents = AppEventBus.events
            )
            VolumeControlMode.BUTTONS -> ButtonsMode(
                context = this,
                commandSender = commandSender,
                settingsRepository = settingsRepository,
                appEvents = AppEventBus.events
            )
        }
        activeMode?.start()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== Создание сервиса ===")

        portManager = UsbSerialPortManager(this, usbManager)
        notificationController = NotificationController(this)
        settingsRepository = SettingsRepositoryImpl(this)

        // Запуск сохранённого режима
        val savedModeId = settingsRepository.getVolumeControlMode()
        Log.d(TAG, "Режим управления: $savedModeId")
        activateMode(savedModeId)

        // ── Реактивные подписки ──

        // Данные от Arduino
        serviceScope.launch {
            portManager.dataFlow.collect { line ->
                AppEventBus.tryEmit(AppEvent.ArduinoResponse(line))
            }
        }

        // Статус USB-порта
        serviceScope.launch {
            portManager.state.collect { state ->
                AppEventBus.tryEmit(AppEvent.UsbStatusChanged(state))
                if (state is UsbPortState.Connected) {
                    activeMode?.onUsbConnected()
                }
            }
        }

        // События приложения
        serviceScope.launch {
            AppEventBus.events.collect { event ->
                when (event) {
                    is AppEvent.VolumeControlModeChanged -> {
                        Log.d(TAG, "Режим управления изменён на: ${event.mode}")
                        activateMode(event.mode)
                    }
                    else -> { /* остальные события обрабатываются внутри режимов */ }
                }
            }
        }

        // USB-фильтр
        val usbFilter = IntentFilter().apply {
            addAction(Constants.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, usbFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, usbFilter)
        }

        startForeground(Constants.NOTIFICATION_ID, notificationController.build())
        autoConnectSavedDevice()
    }

    private fun autoConnectSavedDevice() {
        val saved = settingsRepository.getSavedDevice() ?: return
        val (vid, pid) = saved
        val matchingDevice = usbManager.deviceList.values
            .firstOrNull { it.vendorId == vid && it.productId == pid }
        if (matchingDevice != null) {
            selectedDevice = matchingDevice
            if (usbManager.hasPermission(matchingDevice)) {
                portManager.connect(matchingDevice)
            } else {
                portManager.requestPermission(matchingDevice)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== Уничтожение сервиса ===")
        activeMode?.stop()
        serviceScope.cancel()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        portManager.disconnect()
    }

    override fun onBind(intent: Intent): IBinder = binder
}
