package com.example.volumemonitor.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.volumemonitor.MainActivity
import com.example.volumemonitor.R
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.VolumeMonitorService
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl
import com.example.volumemonitor.core.usb.UsbPortState
import com.example.volumemonitor.core.usb.displayText
import kotlinx.coroutines.launch

class UsbSettingsFragment : Fragment() {

    private val TAG = "UsbSettingsFragment"

    // View
    private lateinit var usbStatusTextView: TextView
    private lateinit var selectedDeviceTextView: TextView
    private lateinit var usbDevicesSpinner: Spinner
    private lateinit var usbScanButton: Button
    private lateinit var selectDeviceButton: Button
    private lateinit var usbPermissionButton: Button

    // Состояние
    private val usbManager: UsbManager by lazy { requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager }
    private val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(requireContext()) }

    private val connectedUsbDevices = mutableListOf<UsbDevice>()
    private var selectedUsbDevice: UsbDevice? = null
    private var lastUsbStatus: UsbPortState = UsbPortState.Initializing

    private fun getService(): VolumeMonitorService? =
        (requireActivity() as? MainActivity)?.volumeService

    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_USB_PERMISSION -> {
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
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    scanUsbDevices()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_usb_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usbStatusTextView = view.findViewById(R.id.usbStatusTextView)
        selectedDeviceTextView = view.findViewById(R.id.selectedDeviceTextView)
        usbDevicesSpinner = view.findViewById(R.id.usbDevicesSpinner)
        usbScanButton = view.findViewById(R.id.usbScanButton)
        selectDeviceButton = view.findViewById(R.id.selectDeviceButton)
        usbPermissionButton = view.findViewById(R.id.usbPermissionButton)

        setupSpinner()
        setupButtons()

        // Подписка на события USB-статуса (viewLifecycleOwner — отменяется при onDestroyView)
        viewLifecycleOwner.lifecycleScope.launch {
            AppEventBus.events.collect { event ->
                if (event is AppEvent.UsbStatusChanged) {
                    lastUsbStatus = event.status
                    updateUsbStatus()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceivers()
        scanUsbDevices()
    }

    override fun onPause() {
        super.onPause()
        try {
            requireActivity().unregisterReceiver(usbReceiver)
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "Receiver already unregistered")
        }
    }

    private fun setupButtons() {
        usbScanButton.setOnClickListener { scanUsbDevices() }
        selectDeviceButton.setOnClickListener { selectCurrentSpinnerDevice() }
        usbPermissionButton.setOnClickListener { requestUsbPermissionForAllDevices() }
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, mutableListOf("-- Выберите устройство --"))
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
        settingsRepository.saveDevice(device.vendorId, device.productId)
        Log.d(TAG, "Сохранено устройство: VID=${device.vendorId}, PID=${device.productId}")
    }

    private fun loadAndAutoselectDevice() {
        val saved = settingsRepository.getSavedDevice() ?: run {
            Log.d(TAG, "Нет сохраненных устройств для авто-выбора.")
            updateSelectedDeviceInfo()
            return
        }
        val (vendorId, productId) = saved

        val deviceIndex = connectedUsbDevices.indexOfFirst { it.vendorId == vendorId && it.productId == productId }
        if (deviceIndex != -1) {
            if (usbDevicesSpinner.selectedItemPosition != deviceIndex + 1) {
                val device = connectedUsbDevices[deviceIndex]
                Log.d(TAG, "Найдено сохраненное устройство: ${device.deviceName}")
                usbDevicesSpinner.setSelection(deviceIndex + 1, true)
                selectedUsbDevice = device
                getService()?.setSelectedUsbDevice(selectedUsbDevice)
                Toast.makeText(requireContext(), "Авто-выбор: ${device.deviceName}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.d(TAG, "Сохраненное устройство (VID=$vendorId, PID=$productId) не найдено.")
        }
        updateSelectedDeviceInfo()
    }

    private fun selectCurrentSpinnerDevice() {
        val device = selectedUsbDevice
        if (device != null) {
            saveSelectedUsbDevice(device)
            getService()?.setSelectedUsbDevice(device)
            Toast.makeText(requireContext(), "Устройство выбрано: ${device.deviceName}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Выберите устройство из списка", Toast.LENGTH_SHORT).show()
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
            addAction(Constants.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireActivity().registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireActivity().registerReceiver(usbReceiver, filter)
        }
        Log.d(TAG, "Broadcast Receiver зарегистрирован")
    }

    private fun updateUsbStatus() {
        usbStatusTextView.text = "Статус USB: ${lastUsbStatus.displayText}"
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
            val deviceNames = connectedUsbDevices.mapIndexed { index, device ->
                val hasPermission = usbManager.hasPermission(device)
                val permissionStatus = if (hasPermission) "[✅]" else "[❌]"
                val isArduino = if (device.vendorId == 0x2341 && device.productId == 0x0043) " [Arduino Nano]" else ""
                "${index + 1}. ${device.deviceName} $permissionStatus$isArduino"
            }
            adapter.addAll(deviceNames)
        }
        adapter.notifyDataSetChanged()

        loadAndAutoselectDevice()
        updateUsbStatus()
    }

    private fun requestUsbPermissionForAllDevices() {
        if (connectedUsbDevices.isEmpty()) {
            Toast.makeText(requireContext(), "Нет USB устройств для запроса разрешений", Toast.LENGTH_SHORT).show()
            return
        }

        val requested = connectedUsbDevices.filter { !usbManager.hasPermission(it) }.onEach { device ->
            try {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    requireContext(), device.deviceId, Intent(Constants.ACTION_USB_PERMISSION), flags
                )
                usbManager.requestPermission(device, permissionIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запроса разрешения для ${device.deviceName}: ${e.message}")
            }
        }.size

        if (requested > 0) {
            Toast.makeText(requireContext(), "Отправлены запросы на разрешение для $requested устройств(а)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Все подключенные устройства уже имеют разрешение", Toast.LENGTH_SHORT).show()
        }
    }
}
