package com.example.volumemonitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.volumemonitor.core.VolumeMonitorService

class SettingsActivity : AppCompatActivity() {

    private val TAG = "SettingsActivity"

    companion object {
        private const val PREFS_NAME = "UsbDevicePrefs"
        private const val KEY_VENDOR_ID = "vendorId"
        private const val KEY_PRODUCT_ID = "productId"
        private const val USB_PERMISSION_ACTION = "com.example.volumemonitor.USB_PERMISSION"
    }

    private lateinit var usbStatusTextView: TextView
    private lateinit var selectedDeviceTextView: TextView
    private lateinit var usbDevicesSpinner: Spinner
    private lateinit var usbScanButton: Button
    private lateinit var selectDeviceButton: Button
    private lateinit var usbPermissionButton: Button

    private var volumeService: VolumeMonitorService? = null
    private var isBound = false
    private lateinit var usbManager: UsbManager

    private val connectedUsbDevices: MutableList<UsbDevice> = ArrayList()
    private var selectedUsbDevice: UsbDevice? = null

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
            when (intent.action) {
                USB_PERMISSION_ACTION -> {
                    synchronized(this) {
                        val device: UsbDevice? = getUsbDeviceExtra(intent)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                Log.d(TAG, "Permission granted for device ${it.deviceName}")
                                scanUsbDevices()
                            }
                        } else {
                            Log.d(TAG, "Permission denied for device ${device?.deviceName}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    scanUsbDevices()
                }
            }
        }
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "Сервис подключен")
            val binder = service as VolumeMonitorService.LocalBinder
            volumeService = binder.getService()
            isBound = true
            updateUsbStatus()
            scanUsbDevices()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Сервис отключен")
            isBound = false
            volumeService = null
            updateUsbStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initUIElements()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setupButtons()
        setupSpinner()
    }

    override fun onResume() {
        super.onResume()
        registerReceivers()
        startAndBindService()
        scanUsbDevices()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) { /* уже дерегистрирован */ }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun initUIElements() {
        usbStatusTextView = findViewById(R.id.usbStatusTextView)
        selectedDeviceTextView = findViewById(R.id.selectedDeviceTextView)
        usbDevicesSpinner = findViewById(R.id.usbDevicesSpinner)
        usbScanButton = findViewById(R.id.usbScanButton)
        selectDeviceButton = findViewById(R.id.selectDeviceButton)
        usbPermissionButton = findViewById(R.id.usbPermissionButton)
    }

    private fun setupButtons() {
        usbScanButton.setOnClickListener { scanUsbDevices() }
        selectDeviceButton.setOnClickListener { selectCurrentSpinnerDevice() }
        usbPermissionButton.setOnClickListener { requestUsbPermissionForAllDevices() }
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("-- Выберите устройство --"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        usbDevicesSpinner.adapter = adapter

        usbDevicesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedUsbDevice = if (position > 0 && position <= connectedUsbDevices.size) {
                    connectedUsbDevices[position - 1]
                } else {
                    null
                }
                updateSelectedDeviceInfo()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedUsbDevice = null
                updateSelectedDeviceInfo()
            }
        }
    }

    private fun saveSelectedUsbDevice(device: UsbDevice) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_VENDOR_ID, device.vendorId)
            putInt(KEY_PRODUCT_ID, device.productId)
            apply()
        }
        Log.d(TAG, "Сохранено устройство: VID=${device.vendorId}, PID=${device.productId}")
    }

    private fun loadAndAutoselectDevice() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val vendorId = prefs.getInt(KEY_VENDOR_ID, -1)
        val productId = prefs.getInt(KEY_PRODUCT_ID, -1)

        if (vendorId != -1 && productId != -1) {
            val deviceIndex = connectedUsbDevices.indexOfFirst { it.vendorId == vendorId && it.productId == productId }
            if (deviceIndex != -1) {
                if (usbDevicesSpinner.selectedItemPosition != deviceIndex + 1) {
                    val device = connectedUsbDevices[deviceIndex]
                    Log.d(TAG, "Найдено сохраненное устройство: ${device.deviceName}")
                    usbDevicesSpinner.setSelection(deviceIndex + 1, true)
                    selectedUsbDevice = device
                    volumeService?.setSelectedUsbDevice(selectedUsbDevice)
                    Toast.makeText(this, "Авто-выбор: ${device.deviceName}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d(TAG, "Сохраненное устройство (VID=$vendorId, PID=$productId) не найдено.")
            }
        } else {
            Log.d(TAG, "Нет сохраненных устройств для авто-выбора.")
        }
        updateSelectedDeviceInfo()
    }

    private fun selectCurrentSpinnerDevice() {
        val device = selectedUsbDevice
        if (device != null) {
            saveSelectedUsbDevice(device)
            volumeService?.setSelectedUsbDevice(device)
            Toast.makeText(this, "Устройство выбрано: ${device.deviceName}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Выберите устройство из списка", Toast.LENGTH_SHORT).show()
        }
        updateSelectedDeviceInfo()
    }

    private fun updateSelectedDeviceInfo() {
        val device = selectedUsbDevice
        if (device != null) {
            val hasPermission = usbManager.hasPermission(device)
            val permissionText = if (hasPermission) "✅ Разрешение есть" else "❌ Нет разрешения"
            selectedDeviceTextView.text = """Выбрано: ${device.deviceName}
VID: 0x${Integer.toHexString(device.vendorId)}
PID: 0x${Integer.toHexString(device.productId)}
$permissionText"""
        } else {
            selectedDeviceTextView.text = "Устройство не выбрано"
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        Log.d(TAG, "Broadcast Receiver зарегистрирован")
    }

    private fun startAndBindService() {
        if (!isBound) {
            try {
                val serviceIntent = Intent(this, VolumeMonitorService::class.java)
                // Запускаем сервис, чтобы он оставался в памяти
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
                Log.d(TAG, "Сервис запущен и привязан")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка привязки сервиса: ${e.message}")
            }
        }
    }

    private fun updateUsbStatus() {
        val status = if (isBound && volumeService?.isUsbConnected == true) "ПОДКЛЮЧЕНО" else "НЕТ ПОДКЛЮЧЕНИЯ"
        usbStatusTextView.text = "Статус USB: $status"
    }

    private fun scanUsbDevices() {
        val adapter = usbDevicesSpinner.adapter as ArrayAdapter<String>

        connectedUsbDevices.clear()
        connectedUsbDevices.addAll(usbManager.deviceList.values)

        adapter.clear()
        adapter.add("-- Выберите устройство --")

        if (connectedUsbDevices.isEmpty()) {
            adapter.add("Нет USB устройств")
        } else {
            connectedUsbDevices.forEachIndexed { index, device ->
                val hasPermission = usbManager.hasPermission(device)
                val permissionStatus = if (hasPermission) "[✅]" else "[❌]"
                val isArduino = if (device.vendorId == 0x2341 && device.productId == 0x0043) " [Arduino Nano]" else ""
                val itemText = "${index + 1}. ${device.deviceName} $permissionStatus$isArduino"
                adapter.add(itemText)
            }
        }
        adapter.notifyDataSetChanged()

        loadAndAutoselectDevice()
        updateUsbStatus()
    }

    private fun requestUsbPermissionForAllDevices() {
        if (connectedUsbDevices.isEmpty()) {
            Toast.makeText(this, "Нет USB устройств для запроса разрешений", Toast.LENGTH_SHORT).show()
            return
        }

        var requested = 0
        connectedUsbDevices.forEach {
            if (!usbManager.hasPermission(it)) {
                try {
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val permissionIntent = PendingIntent.getBroadcast(this, it.deviceId, Intent(USB_PERMISSION_ACTION), flags)
                    usbManager.requestPermission(it, permissionIntent)
                    requested++
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка запроса разрешения для ${it.deviceName}: ${e.message}")
                }
            }
        }

        if (requested > 0) {
            Toast.makeText(this, "Отправлены запросы на разрешение для $requested устройств(а)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Все подключенные устройства уже имеют разрешение", Toast.LENGTH_SHORT).show()
        }
    }
}