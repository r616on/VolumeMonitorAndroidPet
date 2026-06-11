package com.example.volumemonitor.ui

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.volumemonitor.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.volumemonitor.R
import com.example.volumemonitor.core.VolumeMonitorService
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.model.MaxVolumeSource
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl
import com.example.volumemonitor.core.usb.UsbPortState
import com.example.volumemonitor.core.usb.displayText
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

class MainFragment : Fragment() {

    private val TAG = "MainFragment"

    private lateinit var changePresetButton: Button
    private lateinit var presetTextView: TextView
    private lateinit var requestPresetButton: Button
    private lateinit var volumeTextView: TextView
    private lateinit var usbStatusTextView: TextView
    private lateinit var bassSeekBar: SeekBar
    private lateinit var bassValueTextView: TextView
    private lateinit var bassMinusButton: Button
    private lateinit var bassPlusButton: Button

    private lateinit var screenVolumeLayout: View
    private lateinit var screenVolumeSeekBar: SeekBar
    private lateinit var screenVolumeValueTextView: TextView
    private lateinit var screenVolumeMinusButton: Button
    private lateinit var screenVolumePlusButton: Button

    private val audioManager: AudioManager by lazy { requireActivity().getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(requireContext()) }

    private var lastSentBassLevel: Int? = null
    private var currentPreset: Int = 0
    private var lastUsbStatus: UsbPortState = UsbPortState.Initializing

    // ── Состояние загрузки пресета ──
    private lateinit var presetProgressBar: ProgressBar
    private val presetHandler = Handler(Looper.getMainLooper())
    private var isWaitingForPreset = false

    companion object {
        private const val CHANGE_PRESET_DELAY_MS = 10_000L
        private const val PRESET_RESPONSE_TIMEOUT_MS = 5_000L
    }

    private fun getService(): VolumeMonitorService? =
        (requireActivity() as? MainActivity)?.volumeService

    private fun loadBassLevel(): Int = settingsRepository.getBassLevel()
    private fun saveBassLevel(level: Int) = settingsRepository.saveBassLevel(level)

    private fun bassPositionToPercent(position: Int): Int =
        (position * 100f / Constants.BASS_MAX_POSITION.toFloat()).roundToInt()

    private fun bassPositionToValue(position: Int): Int {
        val percent = bassPositionToPercent(position)
        return (percent * 255f / 100f).roundToInt().coerceIn(0, 255)
    }

    private fun updateBassText(level: Int) {
        bassValueTextView.text = "${bassPositionToPercent(level)}%"
    }

    private fun screenPositionToPercent(position: Int): Int =
        (position * 100f / Constants.SCREEN_MAX_POSITION.toFloat()).roundToInt()

    private fun updateScreenText(position: Int) {
        screenVolumeValueTextView.text = "${screenPositionToPercent(position)}%"
    }

    private fun sendBassCommand(level: Int) {
        val value = bassPositionToValue(level)
        getService()?.sendCommand(DeviceCommand.SetBassLevel(value))
        Log.d(TAG, "Bass level: ${bassPositionToPercent(level)}% (pos=$level) -> value: $value")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        volumeTextView = view.findViewById(R.id.volumeTextView)
        usbStatusTextView = view.findViewById(R.id.usbStatusTextView)
        bassSeekBar = view.findViewById(R.id.bassSeekBar)
        bassValueTextView = view.findViewById(R.id.bassValueTextView)
        presetTextView = view.findViewById(R.id.presetTextView)

        screenVolumeLayout = view.findViewById(R.id.screenVolumeLayout)
        screenVolumeSeekBar = view.findViewById(R.id.screenVolumeSeekBar)
        screenVolumeValueTextView = view.findViewById(R.id.screenVolumeValueTextView)
        screenVolumeMinusButton = view.findViewById(R.id.screenVolumeMinusButton)
        screenVolumePlusButton = view.findViewById(R.id.screenVolumePlusButton)
        bassMinusButton = view.findViewById(R.id.bassMinusButton)
        bassPlusButton = view.findViewById(R.id.bassPlusButton)
        changePresetButton = view.findViewById(R.id.changePresetButton)
        requestPresetButton = view.findViewById(R.id.requestPresetButton)
        presetProgressBar = view.findViewById(R.id.presetProgressBar)

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
                val level = seekBar?.progress ?: 7
                saveBassLevel(level)
            }
        })

        bassMinusButton.setOnClickListener {
            val newVal = (bassSeekBar.progress - 1).coerceAtLeast(0)
            bassSeekBar.progress = newVal
            updateBassText(newVal)
            sendBassCommand(newVal)
            lastSentBassLevel = newVal
            saveBassLevel(newVal)
        }

        bassPlusButton.setOnClickListener {
            val newVal = (bassSeekBar.progress + 1).coerceAtMost(Constants.BASS_MAX_POSITION)
            bassSeekBar.progress = newVal
            updateBassText(newVal)
            sendBassCommand(newVal)
            lastSentBassLevel = newVal
            saveBassLevel(newVal)
        }

        screenVolumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateScreenText(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                AppEventBus.tryEmit(AppEvent.ScreenVolumeChanged(seekBar.progress))
            }
        })

        screenVolumeMinusButton.setOnClickListener {
            val newVal = (screenVolumeSeekBar.progress - 1).coerceAtLeast(0)
            screenVolumeSeekBar.progress = newVal
            updateScreenText(newVal)
            AppEventBus.tryEmit(AppEvent.ScreenVolumeChanged(newVal))
        }

        screenVolumePlusButton.setOnClickListener {
            val newVal = (screenVolumeSeekBar.progress + 1).coerceAtMost(Constants.SCREEN_MAX_POSITION)
            screenVolumeSeekBar.progress = newVal
            updateScreenText(newVal)
            AppEventBus.tryEmit(AppEvent.ScreenVolumeChanged(newVal))
        }

        changePresetButton.setOnClickListener {
            enterPresetLoadingState()
            getService()?.sendCommand(DeviceCommand.ChangePreset)
            presetHandler.postDelayed({
                requestPresetWithTimeout()
            }, CHANGE_PRESET_DELAY_MS)
        }

        requestPresetButton.setOnClickListener {
            enterPresetLoadingState()
            requestPresetWithTimeout()
        }

        // Подписка на события через SharedFlow
        viewLifecycleOwner.lifecycleScope.launch {
            AppEventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ModeStateChanged -> {
                        volumeTextView.text = "Громкость: ${event.currentVolume} / ${event.maxVolume} (${event.displayLabel})"
                        val isScreenMode = event.modeId == VolumeControlMode.SCREEN
                        screenVolumeLayout.visibility = if (isScreenMode) View.VISIBLE else View.GONE
                        if (isScreenMode) {
                            if (screenVolumeSeekBar.progress != event.currentVolume) {
                                screenVolumeSeekBar.progress = event.currentVolume
                                updateScreenText(event.currentVolume)
                            }
                        }
                    }
                    is AppEvent.VolumeChanged -> {
                        // Fallback: если ModeStateChanged ещё не пришёл, используем прямое чтение
                        refreshVolumeDisplay()
                    }
                    is AppEvent.UsbStatusChanged -> {
                        lastUsbStatus = event.status
                        updateUsbStatus()
                        if (event.status is UsbPortState.Connected) {
                            // При USB-подключении синхронизировать пресет
                            enterPresetLoadingState()
                            requestPresetWithTimeout()
                        }
                    }
                    is AppEvent.ArduinoResponse -> {
                        val preset = parsePresetResponse(event.rawLine)
                        if (preset != null) {
                            onPresetResponseReceived(preset)
                        }
                    }
                    else -> {}
                }
            }
        }

        // Начальное отображение громкости
        refreshVolumeDisplay()
        updateUsbStatus()

        // Авто-запрос пресета при старте
        enterPresetLoadingState()
        requestPresetWithTimeout()
    }

    override fun onResume() {
        super.onResume()
        refreshVolumeDisplay()
        updateUsbStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        presetHandler.removeCallbacksAndMessages(null)
    }

    // ── Управление состоянием загрузки пресета ──

    /** Заблокировать кнопки, показать спиннер, скрыть текст пресета. */
    private fun enterPresetLoadingState() {
        isWaitingForPreset = true
        presetTextView.visibility = View.GONE
        presetProgressBar.visibility = View.VISIBLE
        changePresetButton.isEnabled = false
        requestPresetButton.isEnabled = false
    }

    /** Разблокировать кнопки, скрыть спиннер, показать текст пресета. */
    private fun exitPresetLoadingState(presetNumber: Int?) {
        isWaitingForPreset = false
        presetHandler.removeCallbacksAndMessages(null)
        presetProgressBar.visibility = View.GONE
        presetTextView.visibility = View.VISIBLE
        if (presetNumber != null) {
            currentPreset = presetNumber
            presetTextView.text = "$presetNumber"
        } else {
            presetTextView.text = "0"
        }
        changePresetButton.isEnabled = true
        requestPresetButton.isEnabled = true
    }

    /** Отправить GetPreset и установить таймаут. */
    private fun requestPresetWithTimeout() {
        getService()?.sendCommand(DeviceCommand.GetPreset)
        presetHandler.postDelayed({
            Log.w(TAG, "Таймаут ожидания ответа пресета")
            exitPresetLoadingState(null)
        }, PRESET_RESPONSE_TIMEOUT_MS)
    }

    /** Обработка пришедшего ответа preset_changed от Arduino. */
    private fun onPresetResponseReceived(preset: Int) {
        if (isWaitingForPreset) {
            exitPresetLoadingState(preset)
        } else {
            // Ответ пришёл вне активного ожидания — просто обновляем текст
            currentPreset = preset
            presetTextView.text = "$preset"
        }
    }

    private fun updateUsbStatus() {
        usbStatusTextView.text = "Статус USB: ${lastUsbStatus.displayText}"
    }

    /** Мгновенное отображение громкости на основе текущего режима (синхронное чтение). */
    private fun refreshVolumeDisplay() {
        val mode = settingsRepository.getVolumeControlMode()
        val text = when (mode) {
            VolumeControlMode.OBSERVER -> {
                val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val systemMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val source = settingsRepository.getObserverMaxVolumeSource()
                if (source == MaxVolumeSource.CUSTOM) {
                    val customMax = settingsRepository.getObserverCustomMaxVolume()
                    val displayMax = if (customMax > 0) customMax else systemMax
                    val displayCurrent = current.coerceAtMost(displayMax)
                    "Громкость: $displayCurrent / $displayMax (пользовательская)"
                } else {
                    "Громкость: $current / $systemMax (системная)"
                }
            }
            VolumeControlMode.BUTTONS -> {
                val current = settingsRepository.getButtonCurrentVolume()
                val max = settingsRepository.getMaxVolumeValue()
                "Громкость: $current / $max (кнопки)"
            }
            VolumeControlMode.SCREEN -> {
                val current = settingsRepository.getScreenCurrentVolume()
                val max = Constants.SCREEN_MAX_POSITION
                "Громкость: $current / $max (экран)"
            }
        }
        volumeTextView.text = text
    }

    /** Парсит ответ Arduino и возвращает номер пресета, либо null. */
    private fun parsePresetResponse(rawLine: String): Int? {
        var response = rawLine.trim()
        if (response.startsWith("[") && response.endsWith("]")) {
            response = response.substring(1, response.length - 1)
        }

        return try {
            val jsonObject = JSONObject(response)
            val command = jsonObject.optString("command")
            if (command == "preset_changed") {
                val value = jsonObject.optInt("value", -1)
                if (value != -1) value else null
            } else null
        } catch (e: JSONException) {
            Log.d(TAG, "Не удалось распарсить JSON: ${e.message}")
            null
        }
    }
}
