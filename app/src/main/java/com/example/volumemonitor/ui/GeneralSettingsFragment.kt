package com.example.volumemonitor.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
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

        // Восстанавливаем текущий режим
        val currentMode = settingsRepository.getVolumeControlMode()
        when (currentMode) {
            VolumeControlMode.OBSERVER -> modeRadioGroup.check(R.id.radioObserver)
            VolumeControlMode.BUTTONS -> modeRadioGroup.check(R.id.radioButtons)
        }
        updateModeDescription(currentMode)

        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                R.id.radioObserver -> VolumeControlMode.OBSERVER
                R.id.radioButtons -> VolumeControlMode.BUTTONS
                else -> return@setOnCheckedChangeListener
            }
            Log.d(TAG, "Выбран режим: $selectedMode")
            settingsRepository.saveVolumeControlMode(selectedMode)
            AppEventBus.tryEmit(AppEvent.VolumeControlModeChanged(selectedMode))
            updateModeDescription(selectedMode)
        }
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
