package com.example.volumemonitor.core.button

import android.util.Log
import com.example.volumemonitor.core.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Общий модуль обучения кнопок.
 * Используется как для Volume Up, так и для Volume Down.
 *
 * Логика:
 * 1. startLearning() → WAITING_FOR_PRESS
 * 2. Пользователь нажимает кнопку → onKeyDown() → LEARNING, запускается таймер
 * 3. Таймер отсчитывает learnTimeoutMs, в это время прогресс обновляется
 * 4. Если пользователь удерживает дольше learnTimeoutMs → LEARNED (успех)
 * 5. Если пользователь отпускает раньше → WAITING_FOR_PRESS (нужно повторить)
 * 6. cancelLearning() → IDLE
 */
class ButtonLearnManager(
    private val learnTimeoutMs: Long = Constants.DEFAULT_BUTTON_LEARN_TIMEOUT_MS
) {
    companion object {
        private const val TAG = "ButtonLearnManager"
        const val PROGRESS_UPDATE_INTERVAL_MS = 50L

        /** Активный экземпляр менеджера обучения (для доступа из ButtonPressService). */
        @Volatile
        var activeInstance: ButtonLearnManager? = null
            private set
    }

    /** Состояние обучения. */
    enum class State {
        /** Ожидание начала обучения. */
        IDLE,
        /** Ожидание нажатия кнопки. */
        WAITING_FOR_PRESS,
        /** Идёт обучение — кнопка нажата, идёт отсчёт времени удержания. */
        LEARNING,
        /** Обучение завершено успешно — кнопка выучена. */
        LEARNED
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Прогресс удержания: 0f..1f. */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** Выученный keyCode (заполняется при успешном обучении). */
    private var _learnedKeyCode: Int? = null
    val learnedKeyCode: Int? get() = _learnedKeyCode

    /** Код клавиши, которую ожидаем отпустить (защита от чужих нажатий). */
    private var expectedKeyCode: Int? = null

    private var learnStartTimeMs: Long = 0L
    private var progressJob: Job? = null
    private var completionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Запустить обучение. Переход в WAITING_FOR_PRESS.
     */
    fun startLearning() {
        Log.d(TAG, "startLearning()")
        cancelInternalJobs()
        _learnedKeyCode = null
        expectedKeyCode = null
        _progress.value = 0f
        _state.value = State.WAITING_FOR_PRESS
        activeInstance = this
    }

    /**
     * Вызывается при нажатии кнопки (из ButtonPressService).
     * Если состояние WAITING_FOR_PRESS — начинаем отсчёт.
     */
    fun onKeyDown(keyCode: Int) {
        if (_state.value != State.WAITING_FOR_PRESS) {
            Log.d(TAG, "onKeyDown($keyCode) проигнорирован, состояние=${_state.value}")
            return
        }
        Log.d(TAG, "onKeyDown($keyCode) — начинаем обучение")
        expectedKeyCode = keyCode
        learnStartTimeMs = System.currentTimeMillis()
        _progress.value = 0f
        _state.value = State.LEARNING
        startProgressTracking()
    }

    /**
     * Вызывается при отпускании кнопки (из ButtonPressService).
     * Проверяет, прошло ли learnTimeoutMs с момента нажатия.
     */
    fun onKeyUp(keyCode: Int) {
        if (_state.value != State.LEARNING) {
            Log.d(TAG, "onKeyUp($keyCode) проигнорирован, состояние=${_state.value}")
            return
        }
        // Защита от чужих нажатий: проверяем, что отпустили ту же клавишу
        if (keyCode != expectedKeyCode) {
            Log.d(TAG, "onKeyUp($keyCode) проигнорирован — ожидался $expectedKeyCode")
            return
        }
        val elapsed = System.currentTimeMillis() - learnStartTimeMs
        Log.d(TAG, "onKeyUp($keyCode) — прошло $elapsed мс из $learnTimeoutMs мс")
        if (elapsed >= learnTimeoutMs) {
            // Успех — кнопка выучена
            _learnedKeyCode = keyCode
            _progress.value = 1f
            _state.value = State.LEARNED
            Log.i(TAG, "Кнопка выучена: keyCode=$keyCode")
        } else {
            // Слишком рано отпустили — возвращаемся в ожидание нажатия
            _progress.value = 0f
            _state.value = State.WAITING_FOR_PRESS
            Log.d(TAG, "Кнопка отпущена слишком рано, ожидаем повторного нажатия")
        }
        cancelInternalJobs()
    }

    /**
     * Ручной сброс обучения.
     */
    fun cancelLearning() {
        Log.d(TAG, "cancelLearning()")
        cancelInternalJobs()
        _learnedKeyCode = null
        expectedKeyCode = null
        _progress.value = 0f
        _state.value = State.IDLE
        if (activeInstance == this) {
            activeInstance = null
        }
    }

    /**
     * Сбросить после успешного обучения (сохраняет выученный keyCode).
     */
    fun dismiss() {
        Log.d(TAG, "dismiss()")
        cancelInternalJobs()
        expectedKeyCode = null
        _state.value = State.IDLE
        if (activeInstance == this) {
            activeInstance = null
        }
    }

    // ── Приватные методы ──

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(PROGRESS_UPDATE_INTERVAL_MS)
                val elapsed = System.currentTimeMillis() - learnStartTimeMs
                val p = (elapsed.toFloat() / learnTimeoutMs.toFloat()).coerceIn(0f, 1f)
                _progress.value = p
                if (p >= 1f) {
                    // Таймер истёк — автозавершение
                    completionJob?.cancel()
                    completionJob = scope.launch {
                        // Небольшая задержка для отображения полного прогресса
                        delay(100L)
                        if (_state.value == State.LEARNING) {
                            Log.i(TAG, "Таймер обучения истёк, ожидаем отпускания кнопки")
                            // Не переключаем состояние — ждём onKeyUp для фиксации
                        }
                    }
                    break
                }
            }
        }
    }

    private fun cancelInternalJobs() {
        progressJob?.cancel()
        progressJob = null
        completionJob?.cancel()
        completionJob = null
    }
}
