package com.example.volumemonitor.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.volumemonitor.R
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.VolumeControlMode
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl

/**
 * Фрагмент общих настроек.
 * Позволяет выбрать режим управления громкостью:
 * - OBSERVER — отслеживание системной громкости
 * - BUTTONS — управление через назначенные кнопки
 */
class GeneralSettingsFragment : Fragment() {

    private val TAG = "GeneralSettingsFrag"

    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var modeDescriptionTextView: TextView
    private lateinit var applyButton: Button
    private var currentMode: VolumeControlMode = VolumeControlMode.OBSERVER
    private var pendingMode: VolumeControlMode? = null
    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_general_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        modeRadioGroup = view.findViewById(R.id.modeRadioGroup)
        modeDescriptionTextView = view.findViewById(R.id.modeDescriptionTextView)
        applyButton = view.findViewById(R.id.applyButton)

        // Восстанавливаем текущий режим
        currentMode = settingsRepository.getVolumeControlMode()
        pendingMode = currentMode
        when (currentMode) {
            VolumeControlMode.OBSERVER -> modeRadioGroup.check(R.id.radioObserver)
            VolumeControlMode.BUTTONS -> modeRadioGroup.check(R.id.radioButtons)
        }
        updateModeDescription(currentMode)
        updateApplyButtonState()

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                R.id.radioObserver -> VolumeControlMode.OBSERVER
                R.id.radioButtons -> VolumeControlMode.BUTTONS
                else -> return@setOnCheckedChangeListener
            }
            Log.d(TAG, "Выбран режим: $selectedMode (текущий: $currentMode)")
            pendingMode = selectedMode
            updateModeDescription(selectedMode)
            updateApplyButtonState()
        }

        applyButton.setOnClickListener {
            val modeToApply = pendingMode ?: return@setOnClickListener
            Log.d(TAG, "Применение режима: $modeToApply")
            settingsRepository.saveVolumeControlMode(modeToApply)
            AppEventBus.tryEmit(AppEvent.VolumeControlModeChanged(modeToApply))
            currentMode = modeToApply
            updateApplyButtonState()
            Toast.makeText(requireContext(), "Настройки применены", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateApplyButtonState() {
        val hasChanges = pendingMode != null && pendingMode != currentMode
        applyButton.isEnabled = hasChanges
    }

    private fun updateModeDescription(mode: VolumeControlMode) {
        modeDescriptionTextView.text = when (mode) {
            VolumeControlMode.OBSERVER ->
                "Отслеживание: громкость считывается из системы Android и отправляется на Arduino при каждом изменении."
            VolumeControlMode.BUTTONS ->
                "Кнопки: громкость изменяется на ±1 при каждом нажатии назначенной кнопки. Удерживайте кнопку для непрерывного изменения."
        }
    }
}
