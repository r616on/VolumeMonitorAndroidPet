package com.example.volumemonitor.core.button

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.volumemonitor.core.Constants
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import com.example.volumemonitor.core.model.ButtonAction
import com.example.volumemonitor.core.repository.SettingsRepository
import com.example.volumemonitor.core.repository.SettingsRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AccessibilityService для перехвата KeyEvent в фоновом режиме.
 *
 * Загружает назначенные keyCode из SettingsRepository.
 * При нажатии эмитит ButtonPressed (+1 к громкости).
 * Если кнопка удерживается дольше longPressDelayMs — запускает автоповтор
 * каждые 200 мс (непрерывное изменение). При отпускании — останавливает.
 *
 * Игнорирует системный repeatCount.
 * Возвращает false из onKeyEvent, чтобы не блокировать системную обработку кнопок.
 */
class ButtonPressService : AccessibilityService() {

    companion object {
        private const val TAG = "ButtonPressService"

        /** Флаг, что сервис активен и зарегистрирован в системе. */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(this) }

    private var volumeUpKeyCodes: Set<Int> = emptySet()
    private var volumeDownKeyCodes: Set<Int> = emptySet()
    private var longPressDelayMs: Long = Constants.DEFAULT_LONG_PRESS_DELAY_MS

    // ── Отслеживание долгого нажатия ──
    private var activeKeyCode: Int? = null
    private var pressStartTime: Long = 0L
    private var isLongPressActive: Boolean = false
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // ── Жизненный цикл AccessibilityService ──

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "=== AccessibilityService подключен === isRunning=$isRunning, готов принимать KeyEvent")
        isRunning = true
        reloadSettings()

        // Подписка на изменение настроек кнопок
        serviceScope.launch {
            AppEventBus.events.collect { event ->
                if (event is AppEvent.ButtonSettingsChanged) {
                    Log.d(TAG, "Получено событие ButtonSettingsChanged, перезагружаем настройки")
                    reloadSettings()
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Не используется — нам нужны только KeyEvent
    }

    override fun onInterrupt() {
        Log.w(TAG, "Сервис прерван")
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "=== AccessibilityService уничтожен ===")
        isRunning = false
        activeKeyCode = null
        isLongPressActive = false
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
        serviceScope.cancel()
    }

    // ── Перехват клавиш ──

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // ── ДИАГНОСТИКА: логируем каждое событие клавиш ──
        val actionName = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "OTHER(${event.action})"
        }
        Log.d(TAG, "onKeyEvent: action=$actionName, keyCode=${event.keyCode}, repeatCount=${event.repeatCount}, learnActive=${ButtonLearnManager.activeInstance != null}")

        // Если активен режим обучения — перенаправляем событие в ButtonLearnManager
        val learnManager = ButtonLearnManager.activeInstance
        if (learnManager != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Игнорируем автоповтор при обучении
                    if (event.repeatCount == 0) {
                        Log.d(TAG, "onKeyEvent: перенаправляем ACTION_DOWN в learnManager, keyCode=${event.keyCode}")
                        learnManager.onKeyDown(event.keyCode)
                    } else {
                        Log.d(TAG, "onKeyEvent: ACTION_DOWN проигнорирован (repeatCount=${event.repeatCount}) в режиме обучения")
                    }
                }
                KeyEvent.ACTION_UP -> {
                    Log.d(TAG, "onKeyEvent: перенаправляем ACTION_UP в learnManager, keyCode=${event.keyCode}")
                    learnManager.onKeyUp(event.keyCode)
                }
            }
            return false
        }

        // Обычный режим — обработка назначенных кнопок
        when (event.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(event.keyCode, event.repeatCount)
            KeyEvent.ACTION_UP -> handleKeyUp(event.keyCode)
        }
        // ВАЖНО: возвращаем false, чтобы не блокировать системную обработку кнопок
        return false
    }

    // ── Обработка нажатий ──

    private fun handleKeyDown(keyCode: Int, repeatCount: Int) {
        // Игнорируем системный автоповтор (repeatCount > 0) — используем свой механизм
        if (repeatCount > 0) return

        val action = when {
            keyCode in volumeUpKeyCodes -> ButtonAction.VOLUME_UP
            keyCode in volumeDownKeyCodes -> ButtonAction.VOLUME_DOWN
            else -> return
        }

        Log.d(TAG, "Кнопка нажата: keyCode=$keyCode, action=$action")
        activeKeyCode = keyCode
        pressStartTime = System.currentTimeMillis()
        isLongPressActive = false

        // Короткое нажатие — сразу эмитим ±1
        AppEventBus.tryEmit(AppEvent.ButtonPressed(action))

        // Планируем переход в режим долгого нажатия через longPressDelayMs
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        val longPressRunnable = object : Runnable {
            override fun run() {
                if (activeKeyCode == keyCode) {
                    Log.d(TAG, "Долгое нажатие активировано: keyCode=$keyCode")
                    isLongPressActive = true
                    startLongPressRepeat(action)
                }
            }
        }
        repeatRunnable = longPressRunnable
        repeatHandler.postDelayed(longPressRunnable, longPressDelayMs)
    }

    private fun startLongPressRepeat(action: ButtonAction) {
        val runnable = object : Runnable {
            override fun run() {
                if (activeKeyCode != null && isLongPressActive) {
                    AppEventBus.tryEmit(AppEvent.ButtonPressed(action))
                    repeatHandler.postDelayed(this, Constants.LONG_PRESS_REPEAT_INTERVAL_MS)
                }
            }
        }
        repeatRunnable = runnable
        repeatHandler.postDelayed(runnable, Constants.LONG_PRESS_REPEAT_INTERVAL_MS)
    }

    private fun handleKeyUp(keyCode: Int) {
        if (keyCode == activeKeyCode) {
            Log.d(TAG, "Кнопка отпущена: keyCode=$keyCode")
            activeKeyCode = null
            isLongPressActive = false
            repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
            repeatRunnable = null
        }
    }

    // ── Настройки ──

    /**
     * Перезагрузить настройки кнопок из SharedPreferences.
     * Вызывается при подключении сервиса и после изменения настроек.
     */
    fun reloadSettings() {
        volumeUpKeyCodes = settingsRepository.getButtonKeyCodes(ButtonAction.VOLUME_UP)
        volumeDownKeyCodes = settingsRepository.getButtonKeyCodes(ButtonAction.VOLUME_DOWN)
        longPressDelayMs = settingsRepository.getLongPressDelayMs()
        Log.d(TAG, "Настройки загружены: volUp=$volumeUpKeyCodes, volDown=$volumeDownKeyCodes, longPressDelay=$longPressDelayMs")
    }
}
