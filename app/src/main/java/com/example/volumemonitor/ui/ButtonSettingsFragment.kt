package com.example.volumemonitor.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.volumemonitor.R
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.button.ButtonPressService
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.ButtonAction
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl

/**
 * Фрагмент настроек кнопок.
 * Позволяет назначать кнопки для Vol+ и Vol- (поддерживает несколько keyCode на действие —
 * например, кнопки на руле, магнитоле и виртуальные на экране).
 * Также настраивает максимальное значение громкости и задержку долгого нажатия.
 */
class ButtonSettingsFragment : Fragment() {

    private val TAG = "ButtonSettingsFrag"

    private lateinit var volUpKeyCodesContainer: LinearLayout
    private lateinit var learnVolUpButton: Button
    private lateinit var volDownKeyCodesContainer: LinearLayout
    private lateinit var learnVolDownButton: Button
    private lateinit var matrixButtonsContainer: LinearLayout
    private lateinit var longPressDelayEditText: EditText
    private lateinit var resetAllButton: Button

    private lateinit var settingsRepository: SettingsRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_button_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settingsRepository = SettingsRepositoryImpl(requireContext())

        volUpKeyCodesContainer = view.findViewById(R.id.volUpKeyCodesContainer)
        learnVolUpButton = view.findViewById(R.id.learnVolUpButton)
        volDownKeyCodesContainer = view.findViewById(R.id.volDownKeyCodesContainer)
        learnVolDownButton = view.findViewById(R.id.learnVolDownButton)
        matrixButtonsContainer = view.findViewById(R.id.matrixButtonsContainer)
        longPressDelayEditText = view.findViewById(R.id.longPressDelayEditText)
        resetAllButton = view.findViewById(R.id.resetAllButton)

        // Восстанавливаем значения
        refreshButtonStatuses()
        longPressDelayEditText.setText(settingsRepository.getLongPressDelayMs().toString())

        // ── Обучение кнопок ──

        learnVolUpButton.setOnClickListener {
            showLearnDialog(ButtonAction.VOLUME_UP)
        }

        learnVolDownButton.setOnClickListener {
            showLearnDialog(ButtonAction.VOLUME_DOWN)
        }

        // ── Сохранение при потере фокуса ──

        longPressDelayEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveLongPressDelay()
        }

        // ── Сброс всех назначений ──

        // ── Заполнение матрицы кнопок ──

        populateMatrixButtons()

        resetAllButton.setOnClickListener {
            settingsRepository.removeAllButtonKeyCodes(ButtonAction.VOLUME_UP)
            settingsRepository.removeAllButtonKeyCodes(ButtonAction.VOLUME_DOWN)
            for (i in 1..6) {
                settingsRepository.removeAllMatrixButtonKeyCodes(i)
            }
            refreshButtonStatuses()
            populateMatrixButtons()
            notifyServiceSettingsChanged()
            Toast.makeText(requireContext(), "Назначения сброшены", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Показать диалог обучения для указанного действия.
     * Предварительно проверяет, включена ли AccessibilityService.
     */
    private fun showLearnDialog(action: ButtonAction) {
        if (!ButtonPressService.isRunning) {
            Log.w(TAG, "AccessibilityService не запущен, показываем диалог с предложением включить")
            AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_VolumeMonitor_Dialog)
                .setTitle("Служба специальных возможностей не включена")
                .setMessage(
                    "Для обучения и работы кнопок необходимо включить службу " +
                    "\"Монитор громкости\" в разделе \"Специальные возможности\" " +
                    "системных настроек.\n\nПерейти к настройкам сейчас?"
                )
                .setPositiveButton("Перейти") { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
                .show()
            return
        }
        val dialog = ButtonLearnDialog.newInstance(action)
        dialog.setOnLearnListener { keyCode ->
            Log.i(TAG, "Кнопка для $action выучена: keyCode=$keyCode")
            settingsRepository.addButtonKeyCode(action, keyCode)
            refreshButtonStatuses()
            notifyServiceSettingsChanged()
        }
        dialog.show(parentFragmentManager, "learnDialog")
    }

    /**
     * Обновить отображение списков назначенных keyCode.
     * Динамически наполняет контейнеры строками «KeyCode: 24 (KEYCODE_VOLUME_UP) [✕]».
     */
    private fun refreshButtonStatuses() {
        populateKeyCodeContainer(
            volUpKeyCodesContainer,
            settingsRepository.getButtonKeyCodes(ButtonAction.VOLUME_UP),
            ButtonAction.VOLUME_UP
        )
        populateKeyCodeContainer(
            volDownKeyCodesContainer,
            settingsRepository.getButtonKeyCodes(ButtonAction.VOLUME_DOWN),
            ButtonAction.VOLUME_DOWN
        )
    }

    /**
     * Наполняет контейнер строками keyCode.
     * Каждая строка: LinearLayout (горизонтальный) с TextView (keyCode + имя) и ImageButton [✕].
     * Если множество пустое — показывает «Не назначено».
     */
    private fun populateKeyCodeContainer(
        container: LinearLayout,
        keyCodes: Set<Int>,
        action: ButtonAction
    ) {
        container.removeAllViews()

        if (keyCodes.isEmpty()) {
            val emptyView = TextView(requireContext()).apply {
                text = "Не назначено"
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(emptyView)
            return
        }

        val sorted = keyCodes.sorted()
        for (keyCode in sorted) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 2, 0, 2)
            }

            val name = keyCodeToReadableName(keyCode)
            val label = TextView(requireContext()).apply {
                text = "KeyCode: $keyCode ($name)"
                textSize = 13f
                setTextColor(0xFF333333.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val deleteButton = ImageButton(requireContext()).apply {
                // Используем символ ✕ как текст на кнопке
                setImageResource(android.R.drawable.ic_delete)
                contentDescription = "Удалить keyCode $keyCode"
                layoutParams = LinearLayout.LayoutParams(
                    64,
                    64
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    settingsRepository.removeButtonKeyCode(action, keyCode)
                    refreshButtonStatuses()
                    notifyServiceSettingsChanged()
                    Log.d(TAG, "KeyCode $keyCode удалён для $action")
                }
            }

            row.addView(label)
            row.addView(deleteButton)
            container.addView(row)
        }
    }

    /**
     * Преобразует keyCode в читаемое имя, используя KeyEvent.keyCodeToString.
     */
    private fun keyCodeToReadableName(keyCode: Int): String {
        return try {
            KeyEvent.keyCodeToString(keyCode)
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }

    private fun saveLongPressDelay() {
        val text = longPressDelayEditText.text.toString()
        val value = text.toLongOrNull()
        if (value != null && value > 0) {
            settingsRepository.saveLongPressDelayMs(value)
            Log.d(TAG, "Задержка долгого нажатия сохранена: $value")
            notifyServiceSettingsChanged()
        } else {
            longPressDelayEditText.setText(settingsRepository.getLongPressDelayMs().toString())
        }
    }

    /**
     * Уведомить ButtonPressService о необходимости перезагрузить настройки.
     */
    private fun notifyServiceSettingsChanged() {
        AppEventBus.tryEmit(AppEvent.ButtonSettingsChanged)
        Log.d(TAG, "Событие ButtonSettingsChanged отправлено")
    }

    // ── Матрица кнопок ──

    /** Динамически наполняет контейнер 6 строками для кнопок матрицы. */
    private fun populateMatrixButtons() {
        matrixButtonsContainer.removeAllViews()

        for (buttonNumber in 1..6) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 4, 0, 4)
            }

            // Метка «Кнопка N»
            val label = TextView(requireContext()).apply {
                text = "Кнопка $buttonNumber"
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val keyCodes = settingsRepository.getMatrixButtonKeyCodes(buttonNumber)

            // Список keyCode
            val keyCodeContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    2f
                )
            }

            if (keyCodes.isEmpty()) {
                val emptyView = TextView(requireContext()).apply {
                    text = "Не назначено"
                    textSize = 12f
                    setTextColor(0xFF666666.toInt())
                }
                keyCodeContainer.addView(emptyView)
            } else {
                for (kc in keyCodes.sorted()) {
                    val name = keyCodeToReadableName(kc)
                    val kcLabel = TextView(requireContext()).apply {
                        text = "$kc ($name)"
                        textSize = 12f
                        setTextColor(0xFF333333.toInt())
                    }
                    keyCodeContainer.addView(kcLabel)
                }
            }

            // Кнопка «Обучить»
            val learnButton = Button(requireContext()).apply {
                text = "Обучить"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    showMatrixLearnDialog(buttonNumber)
                }
            }

            // Кнопка удаления
            if (keyCodes.isNotEmpty()) {
                val deleteButton = ImageButton(requireContext()).apply {
                    setImageResource(android.R.drawable.ic_delete)
                    contentDescription = "Сбросить кнопку $buttonNumber"
                    layoutParams = LinearLayout.LayoutParams(64, 64)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnClickListener {
                        settingsRepository.removeAllMatrixButtonKeyCodes(buttonNumber)
                        populateMatrixButtons()
                        notifyServiceSettingsChanged()
                        Log.d(TAG, "Сброшены назначения для кнопки $buttonNumber")
                    }
                }
                row.addView(label)
                row.addView(keyCodeContainer)
                row.addView(learnButton)
                row.addView(deleteButton)
            } else {
                row.addView(label)
                row.addView(keyCodeContainer)
                row.addView(learnButton)
            }

            matrixButtonsContainer.addView(row)
        }
    }

    /** Показать диалог обучения для кнопки матрицы. */
    private fun showMatrixLearnDialog(buttonNumber: Int) {
        if (!ButtonPressService.isRunning) {
            AlertDialog.Builder(requireContext(), R.style.ThemeOverlay_VolumeMonitor_Dialog)
                .setTitle("Служба специальных возможностей не включена")
                .setMessage(
                    "Для обучения и работы кнопок необходимо включить службу " +
                    "\"Монитор громкости\" в разделе \"Специальные возможности\" " +
                    "системных настроек.\n\nПерейти к настройкам сейчас?"
                )
                .setPositiveButton("Перейти") { _, _ ->
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("Отмена") { dialog, _ -> dialog.dismiss() }
                .show()
            return
        }
        val dialog = ButtonLearnDialog.newInstance(buttonNumber)
        dialog.setOnLearnListener { keyCode ->
            Log.i(TAG, "Кнопка матрицы $buttonNumber выучена: keyCode=$keyCode")
            settingsRepository.addMatrixButtonKeyCode(buttonNumber, keyCode)
            populateMatrixButtons()
            notifyServiceSettingsChanged()
        }
        dialog.show(parentFragmentManager, "matrixLearnDialog_$buttonNumber")
    }
}
