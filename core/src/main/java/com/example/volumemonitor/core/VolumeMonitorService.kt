package com.example.volumemonitor.core

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.notification.NotificationController
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl
import com.example.volumemonitor.core.serialization.JsonCommandSerializer
import com.example.volumemonitor.core.usb.UsbPortState
import com.example.volumemonitor.core.usb.UsbSerialPortManager
import com.example.volumemonitor.core.volume.VolumeObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VolumeMonitorService : Service() {
    private val TAG = "VolumeMonitor"

    private val audioManager: AudioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val usbManager: UsbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }

    private lateinit var portManager: UsbSerialPortManager
    private lateinit var volumeObserver: VolumeObserver
    private lateinit var notificationController: NotificationController
    private lateinit var settingsRepository: SettingsRepository

    private val commandSerializer = JsonCommandSerializer()
    private var selectedDevice: UsbDevice? = null
    private val binder: IBinder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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

    private fun sendVolumeData(targetVolume: Int) {
        val cmd = DeviceCommand.SetVolume(targetVolume)
        val json = commandSerializer.serialize(cmd)
        val framed = commandSerializer.frame(json)
        portManager.send(framed)
        AppEventBus.tryEmit(AppEvent.SerialDataSent(json))
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== Создание сервиса ===")

        portManager = UsbSerialPortManager(this, usbManager)
        volumeObserver = VolumeObserver(this, audioManager)
        notificationController = NotificationController(this)
        settingsRepository = SettingsRepositoryImpl(this)

        // Реактивное связывание через StateFlow
        serviceScope.launch {
            volumeObserver.volume.collect { data ->
                AppEventBus.tryEmit(AppEvent.VolumeChanged(data.current, data.target))
                sendVolumeData(data.target)
            }
        }
        serviceScope.launch {
            portManager.dataFlow.collect { line ->
                AppEventBus.tryEmit(AppEvent.ArduinoResponse(line))
            }
        }
        serviceScope.launch {
            portManager.state.collect { state ->
                AppEventBus.tryEmit(AppEvent.UsbStatusChanged(state))
                if (state is UsbPortState.Connected) {
                    val data = volumeObserver.currentVolumeData
                    AppEventBus.tryEmit(AppEvent.VolumeChanged(data.current, data.target))
                    sendVolumeData(data.target)
                }
            }
        }

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

        volumeObserver.register()
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
        serviceScope.cancel()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        volumeObserver.unregister()
        portManager.disconnect()
    }

    override fun onBind(intent: Intent): IBinder = binder
}
