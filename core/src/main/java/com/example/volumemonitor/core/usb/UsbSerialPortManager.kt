package com.example.volumemonitor.core.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.example.volumemonitor.core.Constants
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

// ── Иммутабельное состояние USB-порта ──

sealed class UsbPortState {
    object Disconnected : UsbPortState()
    object Initializing : UsbPortState()
    data class Connected(val deviceName: String) : UsbPortState()
    data class Error(val message: String) : UsbPortState()
}

val UsbPortState.displayText: String
    get() = when (this) {
        is UsbPortState.Initializing -> "Инициализация..."
        is UsbPortState.Connected -> "Подключено: $deviceName"
        is UsbPortState.Error -> "Ошибка: $message"
        is UsbPortState.Disconnected -> "Отключено"
    }

class UsbSerialPortManager(
    private val context: Context,
    private val usbManager: UsbManager
) {
    private val TAG = "UsbSerialPortManager"
    private val serialBuffer = StringBuilder()
    private val bufferLock = Any()

    private var serialPort: UsbSerialPort? = null
    private var serialIoManager: SerialInputOutputManager? = null

    // ── Реактивное состояние (ФП) ──
    private val _state = MutableStateFlow<UsbPortState>(UsbPortState.Disconnected)
    val state: StateFlow<UsbPortState> = _state.asStateFlow()

    private val _dataFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val dataFlow: SharedFlow<String> = _dataFlow.asSharedFlow()

    val isConnected: Boolean get() = _state.value is UsbPortState.Connected

    private val serialListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            val chunk = String(data, Charsets.UTF_8)
            synchronized(bufferLock) {
                serialBuffer.append(chunk)
                var index: Int
                while (serialBuffer.indexOf("\n").also { index = it } >= 0) {
                    val line = serialBuffer.substring(0, index).trim()
                    serialBuffer.delete(0, index + 1)
                    if (line.isNotEmpty()) {
                        Log.d(TAG, "Полная строка от Arduino: $line")
                        _dataFlow.tryEmit(line)
                    }
                }
            }
        }

        override fun onRunError(e: Exception) {
            Log.e(TAG, "Ошибка чтения: ${e.message}")
            _state.value = UsbPortState.Error("Ошибка чтения")
        }
    }

    @Suppress("DEPRECATION")
    fun getDeviceFromIntent(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    fun connect(device: UsbDevice) {
        Log.i(TAG, "Открытие последовательного порта для: ${device.deviceName}")
        disconnect()

        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                Log.e(TAG, "Нет драйвера для устройства")
                _state.value = UsbPortState.Error("Неподдерживаемое устройство")
                return
            }

            val port = driver.ports[0]
            val connection: UsbDeviceConnection = usbManager.openDevice(device) ?: run {
                Log.e(TAG, "Не удалось открыть соединение с устройством")
                _state.value = UsbPortState.Error("Ошибка открытия соединения")
                return
            }

            port.open(connection)
            port.setParameters(Constants.BAUD_RATE, Constants.DATA_BITS, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            Log.i(TAG, "Порт открыт, скорость ${Constants.BAUD_RATE}")

            serialPort = port
            _state.value = UsbPortState.Connected(device.deviceName)

            serialIoManager = SerialInputOutputManager(port, serialListener)
            serialIoManager?.start()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка открытия последовательного порта: ${e.message}")
            _state.value = UsbPortState.Error("Ошибка: ${e.message}")
            disconnect()
        }
    }

    fun disconnect() {
        synchronized(bufferLock) {
            serialIoManager?.stop()
            serialIoManager = null
            try { serialPort?.close() } catch (e: IOException) { Log.e(TAG, "Ошибка при закрытии порта: ${e.message}") }
            serialPort = null
        }
        _state.value = UsbPortState.Disconnected
    }

    fun send(bytes: ByteArray) {
        // Логируем всегда — для отладки, даже если USB не подключено
        Log.d(TAG, "→ send: ${String(bytes, Charsets.UTF_8)} (connected=$isConnected)")
        synchronized(bufferLock) {
            if (!isConnected || serialPort == null) {
                Log.w(TAG, "USB не подключено, данные не отправлены")
                return
            }
            try {
                serialPort?.write(bytes, 1000)
                Log.d(TAG, "Отправлено: ${String(bytes, Charsets.UTF_8)}")
            } catch (e: IOException) {
                Log.e(TAG, "Ошибка отправки: ${e.message}")
            }
        }
    }

    fun requestPermission(device: UsbDevice) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, device.deviceId,
                Intent(Constants.ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
            Log.d(TAG, "Запрос разрешения отправлен для: ${device.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запроса разрешения: ${e.message}")
        }
    }
}
