package com.example.volumemonitor.ui

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.volumemonitor.R
import com.example.volumemonitor.core.button.ButtonLearnManager
import com.example.volumemonitor.core.model.ButtonAction
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Диалог обучения кнопки.
 *
 * Показывает прогресс-бар, заполняющийся в течение 3 секунд удержания.
 * При успехе: «Кнопка назначена: KeyCode XXX», кнопка «OK».
 * При неудаче: «Удерживайте кнопку дольше», кнопка «Повторить».
 */
class ButtonLearnDialog : DialogFragment() {

    companion object {
        private const val TAG = "ButtonLearnDialog"
        private const val ARG_ACTION = "action"

        fun newInstance(action: ButtonAction): ButtonLearnDialog {
            return ButtonLearnDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_ACTION, action.name)
                }
            }
        }
    }

    private var learnListener: ((Int) -> Unit)? = null
    private lateinit var learnManager: ButtonLearnManager
    private val action: ButtonAction by lazy {
        ButtonAction.valueOf(requireArguments().getString(ARG_ACTION)!!)
    }

    private lateinit var instructionTextView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var resultTextView: TextView
    private lateinit var cancelButton: Button
    private lateinit var okButton: Button
    private lateinit var retryButton: Button

    fun setOnLearnListener(listener: (Int) -> Unit) {
        learnListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.Theme_VolumeMonitor)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_button_learn, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        instructionTextView = view.findViewById(R.id.learnInstructionTextView)
        progressBar = view.findViewById(R.id.learnProgressBar)
        resultTextView = view.findViewById(R.id.learnResultTextView)
        cancelButton = view.findViewById(R.id.learnCancelButton)
        okButton = view.findViewById(R.id.learnOkButton)
        retryButton = view.findViewById(R.id.learnRetryButton)

        learnManager = ButtonLearnManager()

        cancelButton.setOnClickListener {
            learnManager.cancelLearning()
            dismiss()
        }

        okButton.setOnClickListener {
            val keyCode = learnManager.learnedKeyCode
            learnManager.dismiss()
            if (keyCode != null) {
                learnListener?.invoke(keyCode)
            }
            dismiss()
        }

        retryButton.setOnClickListener {
            // Перезапуск обучения — возвращаемся в WAITING_FOR_PRESS
            resultTextView.text = ""
            resultTextView.visibility = View.GONE
            okButton.visibility = View.GONE
            retryButton.visibility = View.GONE
            cancelButton.visibility = View.VISIBLE
            progressBar.progress = 0
            progressBar.visibility = View.VISIBLE
            instructionTextView.text = "Нажмите и удерживайте кнопку в течение 2 секунд"
            learnManager.startLearning()
        }

        // ── Подписка на состояние обучения ──

        viewLifecycleOwner.lifecycleScope.launch {
            learnManager.state.collectLatest { state ->
                Log.d(TAG, "Состояние обучения: $state")
                when (state) {
                    ButtonLearnManager.State.IDLE -> {
                        // Ничего не делаем
                    }
                    ButtonLearnManager.State.WAITING_FOR_PRESS -> {
                        instructionTextView.text = "Нажмите и удерживайте кнопку в течение 2 секунд"
                        progressBar.progress = 0
                        progressBar.visibility = View.VISIBLE
                        resultTextView.visibility = View.GONE
                        okButton.visibility = View.GONE
                        retryButton.visibility = View.GONE
                        cancelButton.visibility = View.VISIBLE
                    }
                    ButtonLearnManager.State.LEARNING -> {
                        instructionTextView.text = "Удерживайте кнопку..."
                    }
                    ButtonLearnManager.State.LEARNED -> {
                        val keyCode = learnManager.learnedKeyCode ?: return@collectLatest
                        instructionTextView.text = "Обучение завершено!"
                        resultTextView.text = "Кнопка назначена: KeyCode $keyCode"
                        resultTextView.visibility = View.VISIBLE
                        progressBar.visibility = View.GONE
                        okButton.visibility = View.VISIBLE
                        retryButton.visibility = View.GONE
                        cancelButton.visibility = View.GONE
                    }
                }
            }
        }

        // ── Подписка на прогресс ──

        viewLifecycleOwner.lifecycleScope.launch {
            learnManager.progress.collectLatest { progress ->
                progressBar.progress = (progress * 100).toInt()
                // Если прогресс дошёл до 100%, но кнопку ещё не отпустили —
                // показываем подсказку
                if (progress >= 1f && learnManager.state.value == ButtonLearnManager.State.LEARNING) {
                    instructionTextView.text = "Можно отпустить кнопку"
                }
            }
        }

        // ── Запуск обучения ──
        learnManager.startLearning()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::learnManager.isInitialized) {
            learnManager.cancelLearning()
        }
    }
}
