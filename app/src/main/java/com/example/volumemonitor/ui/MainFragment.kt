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
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.volumemonitor.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.volumemonitor.R
import com.example.volumemonitor.core.VolumeMonitorService
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.DeviceCommand
import com.example.volumemonitor.core.model.MaxVolumeSource
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl
import com.example.volumemonitor.core.serialization.JsonCommandSerializer
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

    private val audioManager: AudioManager by lazy { requireActivity().getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(requireContext()) }
    private val commandSerializer = JsonCommandSerializer()

    private var lastSentBassLevel: Int? = null
    private var presetButtonsRunnable: Runnable? = null
    private var currentPreset: Int = 1
    private var lastUsbStatus: UsbPortState = UsbPortState.Initializing

    private fun getService(): VolumeMonitorService? =
        (requireActivity() as? MainActivity)?.volumeService

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
        getService()?.sendCommand(json)
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
        changePresetButton = view.findViewById(R.id.changePresetButton)
        requestPresetButton = view.findViewById(R.id.requestPresetButton)

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

        changePresetButton.setOnClickListener {
            getService()?.sendCommand(commandSerializer.serialize(DeviceCommand.ChangePreset))
            disablePresetButtonsTemporarily()
        }

        requestPresetButton.setOnClickListener {
            getService()?.sendCommand(commandSerializer.serialize(DeviceCommand.GetPreset))
            disablePresetButtonsTemporarily()
        }

        // Подписка на события через SharedFlow
        viewLifecycleOwner.lifecycleScope.launch {
            AppEventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ModeStateChanged -> {
                        volumeTextView.text = "Громкость: ${event.currentVolume} / ${event.maxVolume} (${event.displayLabel})"
                    }
                    is AppEvent.VolumeChanged -> {
                        // Fallback: если ModeStateChanged ещё не пришёл, используем прямое чтение
                        refreshVolumeDisplay()
                    }
                    is AppEvent.UsbStatusChanged -> {
                        lastUsbStatus = event.status
                        updateUsbStatus()
                    }
                    is AppEvent.ArduinoResponse -> {
                        val preset = parsePresetResponse(event.rawLine)
                        if (preset != null) {
                            currentPreset = preset
                            presetTextView.text = "$preset"
                        }
                    }
                    else -> {}
                }
            }
        }

        // Начальное отображение громкости
        refreshVolumeDisplay()
        updateUsbStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshVolumeDisplay()
        updateUsbStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        presetButtonsRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
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
