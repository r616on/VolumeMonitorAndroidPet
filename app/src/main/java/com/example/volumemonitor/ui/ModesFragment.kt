package com.example.volumemonitor.ui

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import com.example.volumemonitor.R
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.MaxVolumeSource
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl

/**
 * Фрагмент настройки режимов управления громкостью.
 * Позволяет выбрать один режим и настроить его параметры.
 * Доступные режимы:
 * - OBSERVER — отслеживание системной громкости
 * - BUTTONS — управление через назначенные кнопки
 */
class ModesFragment : Fragment() {

    private val TAG = "ModesFragment"

    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var modeDescriptionTextView: TextView
    private lateinit var applyButton: Button
    private lateinit var observerMaxSettingsLayout: View
    private lateinit var useSystemMaxCheckBox: CheckBox
    private lateinit var observerMaxVolumeEditText: EditText
    private lateinit var buttonMaxVolumeSettingsLayout: View
    private lateinit var buttonMaxVolumeEditText: EditText
    private var currentMode: VolumeControlMode = VolumeControlMode.OBSERVER
    private var pendingMode: VolumeControlMode? = null
    private var currentMaxSource: MaxVolumeSource = MaxVolumeSource.SYSTEM
    private var savedCustomMaxValue: Int = 0
    private var savedButtonMaxValue: Int = 15
    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(requireContext())
    }
    private val audioManager: AudioManager by lazy {
        requireActivity().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_modes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modeRadioGroup = view.findViewById(R.id.modeRadioGroup)
        modeDescriptionTextView = view.findViewById(R.id.modeDescriptionTextView)
        applyButton = view.findViewById(R.id.applyButton)
        observerMaxSettingsLayout = view.findViewById(R.id.observerMaxSettingsLayout)
        useSystemMaxCheckBox = view.findViewById(R.id.useSystemMaxCheckBox)
        observerMaxVolumeEditText = view.findViewById(R.id.observerMaxVolumeEditText)
        buttonMaxVolumeSettingsLayout = view.findViewById(R.id.buttonMaxVolumeSettingsLayout)
        buttonMaxVolumeEditText = view.findViewById(R.id.buttonMaxVolumeEditText)

        // Восстанавливаем текущий режим
        currentMode = settingsRepository.getVolumeControlMode()
        pendingMode = currentMode
        when (currentMode) {
            VolumeControlMode.OBSERVER -> modeRadioGroup.check(R.id.radioObserver)
            VolumeControlMode.BUTTONS -> modeRadioGroup.check(R.id.radioButtons)
            VolumeControlMode.SCREEN -> modeRadioGroup.check(R.id.radioScreen)
        }
        updateModeDescription(currentMode)

        // Показать/скрыть настройки макс. громкости в зависимости от режима
        val systemMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        observerMaxSettingsLayout.visibility = if (currentMode == VolumeControlMode.OBSERVER) View.VISIBLE else View.GONE
        buttonMaxVolumeSettingsLayout.visibility = if (currentMode == VolumeControlMode.BUTTONS) View.VISIBLE else View.GONE

        // Восстанавливаем настройки макс. громкости OBSERVER
        currentMaxSource = settingsRepository.getObserverMaxVolumeSource()
        useSystemMaxCheckBox.isChecked = (currentMaxSource == MaxVolumeSource.SYSTEM)
        val customMax = settingsRepository.getObserverCustomMaxVolume()
        savedCustomMaxValue = if (customMax > 0) customMax else systemMaxVolume
        observerMaxVolumeEditText.setText(savedCustomMaxValue.toString())
        observerMaxVolumeEditText.visibility = if (currentMaxSource == MaxVolumeSource.CUSTOM) View.VISIBLE else View.GONE

        // Восстанавливаем настройки макс. громкости BUTTONS
        savedButtonMaxValue = settingsRepository.getMaxVolumeValue()
        buttonMaxVolumeEditText.setText(savedButtonMaxValue.toString())

        useSystemMaxCheckBox.setOnCheckedChangeListener { _, isChecked ->
            currentMaxSource = if (isChecked) MaxVolumeSource.SYSTEM else MaxVolumeSource.CUSTOM
            observerMaxVolumeEditText.visibility = if (isChecked) View.GONE else View.VISIBLE
            updateApplyButtonState()
        }

        observerMaxVolumeEditText.addTextChangedListener {
            updateApplyButtonState()
        }

        buttonMaxVolumeEditText.addTextChangedListener {
            updateApplyButtonState()
        }

        updateApplyButtonState()

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                R.id.radioObserver -> VolumeControlMode.OBSERVER
                R.id.radioButtons -> VolumeControlMode.BUTTONS
                R.id.radioScreen -> VolumeControlMode.SCREEN
                else -> return@setOnCheckedChangeListener
            }
            Log.d(TAG, "Выбран режим: $selectedMode (текущий: $currentMode)")
            pendingMode = selectedMode
            observerMaxSettingsLayout.visibility = if (selectedMode == VolumeControlMode.OBSERVER) View.VISIBLE else View.GONE
            buttonMaxVolumeSettingsLayout.visibility = if (selectedMode == VolumeControlMode.BUTTONS) View.VISIBLE else View.GONE
            updateModeDescription(selectedMode)
            updateApplyButtonState()
        }

        applyButton.setOnClickListener {
            val modeToApply = pendingMode ?: return@setOnClickListener
            Log.d(TAG, "Применение режима: $modeToApply")
            settingsRepository.saveVolumeControlMode(modeToApply)
            AppEventBus.tryEmit(AppEvent.VolumeControlModeChanged(modeToApply))
            currentMode = modeToApply

            // Сохраняем настройки макс. громкости OBSERVER
            settingsRepository.saveObserverMaxVolumeSource(currentMaxSource)
            var shouldEmitObserverEvent = false
            if (currentMaxSource == MaxVolumeSource.CUSTOM) {
                val text = observerMaxVolumeEditText.text.toString()
                val value = text.toIntOrNull()
                if (value != null && value > 0) {
                    val systemMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val safeValue = value.coerceIn(1, systemMax)
                    settingsRepository.saveObserverCustomMaxVolume(safeValue)
                    savedCustomMaxValue = safeValue
                    if (safeValue != value) {
                        observerMaxVolumeEditText.setText(safeValue.toString())
                    }
                    shouldEmitObserverEvent = true
                } else {
                    observerMaxVolumeEditText.setText(savedCustomMaxValue.toString())
                }
            } else {
                shouldEmitObserverEvent = true
            }
            if (shouldEmitObserverEvent) {
                AppEventBus.tryEmit(AppEvent.ObserverSettingsChanged)
            }

            // Сохраняем настройки макс. громкости BUTTONS
            val btnText = buttonMaxVolumeEditText.text.toString()
            val btnValue = btnText.toIntOrNull()
            if (btnValue != null && btnValue > 0) {
                settingsRepository.saveMaxVolumeValue(btnValue)
                savedButtonMaxValue = btnValue
            } else {
                buttonMaxVolumeEditText.setText(savedButtonMaxValue.toString())
            }
            AppEventBus.tryEmit(AppEvent.ButtonSettingsChanged)

            updateApplyButtonState()
            Toast.makeText(requireContext(), "Настройки применены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateApplyButtonState() {
        val modeChanged = pendingMode != null && pendingMode != currentMode
        val sourceChanged = currentMaxSource != settingsRepository.getObserverMaxVolumeSource()
        val editTextValue = observerMaxVolumeEditText.text.toString().toIntOrNull() ?: 0
        val valueChanged = currentMaxSource == MaxVolumeSource.CUSTOM && editTextValue != savedCustomMaxValue
        val btnMaxValue = buttonMaxVolumeEditText.text.toString().toIntOrNull() ?: 0
        val buttonMaxChanged = btnMaxValue > 0 && btnMaxValue != savedButtonMaxValue
        applyButton.isEnabled = modeChanged || sourceChanged || valueChanged || buttonMaxChanged
    }

    private fun updateModeDescription(mode: VolumeControlMode) {
        modeDescriptionTextView.text = when (mode) {
            VolumeControlMode.OBSERVER ->
                "Отслеживание: громкость считывается из системы Android и отправляется на Arduino при каждом изменении."
            VolumeControlMode.BUTTONS ->
                "Кнопки: громкость изменяется на ±1 при каждом нажатии назначенной кнопки. Удерживайте кнопку для непрерывного изменения."
            VolumeControlMode.SCREEN ->
                "Экран: громкость регулируется ползунком на главном экране (15 положений)."
        }
    }
}
