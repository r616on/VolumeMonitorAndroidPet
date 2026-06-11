package com.example.volumemonitor.core.button

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

        /** Минимальный интервал между вызовами [findAccessibilityNodeInfosByViewId] для Teyes (мс). */
        private const val TEYES_READ_THROTTLE_MS = 200L
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(this) }

    private var volumeUpKeyCodes: Set<Int> = emptySet()
    private var volumeDownKeyCodes: Set<Int> = emptySet()
    private var presetNextKeyCodes: Set<Int> = emptySet()
    private var longPressDelayMs: Long = Constants.DEFAULT_LONG_PRESS_DELAY_MS

    // ── Матрица кнопок ──
    /** keyCode → номер кнопки (1..6). */
    private var matrixKeyCodeToButton: Map<Int, Int> = emptyMap()

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

        // ═══════════════════════════════════════════════════════════════
        // [КРИТИЧНО] Программная установка FLAG_REQUEST_FILTER_KEY_EVENTS
        //
        // На Android 12+ (API 31+) система ИГНОРИРУЕТ XML-декларацию
        // флага в accessibility_service_config.xml. Флаг ОБЯЗАТЕЛЬНО
        // нужно установить программно, иначе onKeyEvent() никогда
        // не будет вызываться.
        //
        // Документация:
        // "If your service targets Android 12 (API level 31) or higher,
        //  you must also set this flag in your service's onServiceConnected()
        //  method. Otherwise, the system ignores the XML declaration."
        // ═══════════════════════════════════════════════════════════════
        serviceInfo?.let { info ->
            val updatedFlags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            if (info.flags != updatedFlags) {
                info.flags = updatedFlags
                serviceInfo = info
                Log.i(TAG, "FLAG_REQUEST_FILTER_KEY_EVENTS установлен программно (flags=0x${info.flags.toString(16)})")
            } else {
                Log.d(TAG, "FLAG_REQUEST_FILTER_KEY_EVENTS уже был установлен (flags=0x${info.flags.toString(16)})")
            }
        }

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
        if (event == null) return

        val source = event.source ?: return
        try {
            // ── Чтение громкости Teyes из SystemUI ──
            // Teyes SystemUI показывает громкость в TextView с id=vol_text
            // (view ID: com.android.systemui:id/vol_text)

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                // Фильтрация: реагируем только на изменение текста, игнорируем
                // структурные изменения (CONTENT_CHANGE_TYPE_SUBTREE и др.) —
                // это предотвращает вызов findAccessibilityNodeInfosByViewId
                // при КАЖДОМ изменении контента SystemUI (время, иконки и т.д.)
                val changeTypes = event.contentChangeTypes
                if (changeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT == 0) return

                val packageName = event.packageName?.toString() ?: ""
                if (packageName != "com.android.systemui") return

                tryReadTeyesVolume(source)
                return
            }

            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val packageName = event.packageName?.toString() ?: ""
                if (packageName != "com.android.systemui") return

                tryReadTeyesVolume(source)
            }
        } finally {
            source.recycle()
        }
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
            KeyEvent.ACTION_DOWN -> {
                // Проверяем кнопку «Пресет +»
                if (event.keyCode in presetNextKeyCodes) {
                    cancelLongPress()
                    handlePresetNextKeyDown(event.keyCode, event.repeatCount)
                    return false
                }
                // Проверяем кнопки матрицы
                val matrixButton = matrixKeyCodeToButton[event.keyCode]
                if (matrixButton != null) {
                    cancelLongPress()
                    handleMatrixKeyDown(event.keyCode, matrixButton, event.repeatCount)
                    return false
                }
                handleKeyDown(event.keyCode, event.repeatCount)
            }
            KeyEvent.ACTION_UP -> {
                // Проверяем кнопки матрицы
                val matrixButton = matrixKeyCodeToButton[event.keyCode]
                if (matrixButton != null) {
                    handleMatrixKeyUp(event.keyCode, matrixButton)
                    return false
                }
                handleKeyUp(event.keyCode)
            }
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
        presetNextKeyCodes = settingsRepository.getButtonKeyCodes(ButtonAction.PRESET_NEXT)
        longPressDelayMs = settingsRepository.getLongPressDelayMs()

        // Загружаем keyCode для матрицы кнопок
        val map = mutableMapOf<Int, Int>()
        for (buttonNumber in 1..Constants.MATRIX_BUTTON_COUNT) {
            val keyCodes = settingsRepository.getMatrixButtonKeyCodes(buttonNumber)
            for (kc in keyCodes) {
                map[kc] = buttonNumber
            }
        }
        matrixKeyCodeToButton = map

        // Предупреждение о пересечении keyCode между матрицей и Vol+/Vol-/PresetNext
        val intersection = matrixKeyCodeToButton.keys.intersect(volumeUpKeyCodes + volumeDownKeyCodes + presetNextKeyCodes)
        if (intersection.isNotEmpty()) {
            Log.w(TAG, "Обнаружено пересечение keyCode матрицы и Vol+/Vol-/PresetNext: $intersection")
        }

        Log.d(TAG, "Настройки загружены: volUp=$volumeUpKeyCodes, volDown=$volumeDownKeyCodes, presetNext=$presetNextKeyCodes, longPressDelay=$longPressDelayMs, matrix=$matrixKeyCodeToButton")
    }

    // ── Отмена long-press (вызывается при переключении на матрицу/пресет) ──

    private fun cancelLongPress() {
        activeKeyCode = null
        isLongPressActive = false
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    // ── Обработка кнопок матрицы ──

    /** Нажатие физической кнопки, привязанной к матрице. */
    private fun handleMatrixKeyDown(keyCode: Int, buttonNumber: Int, repeatCount: Int) {
        if (repeatCount > 0) return // игнорируем автоповтор
        Log.d(TAG, "Матрица: кнопка $buttonNumber нажата (keyCode=$keyCode)")
        AppEventBus.tryEmit(AppEvent.MatrixButtonDown(buttonNumber))
    }

    /** Отпускание физической кнопки, привязанной к матрице. */
    private fun handleMatrixKeyUp(keyCode: Int, buttonNumber: Int) {
        Log.d(TAG, "Матрица: кнопка $buttonNumber отпущена (keyCode=$keyCode)")
        AppEventBus.tryEmit(AppEvent.MatrixButtonUp(buttonNumber))
    }
    // ── Обработка кнопки «Пресет +» ──

    /** Нажатие физической кнопки, привязанной к «Пресет +». */
    private fun handlePresetNextKeyDown(keyCode: Int, repeatCount: Int) {
        if (repeatCount > 0) return // игнорируем автоповтор
        Log.d(TAG, "Пресет +: нажатие (keyCode=$keyCode)")
        AppEventBus.tryEmit(AppEvent.PresetNextPressed)
    }

    // ── Чтение громкости Teyes из SystemUI (Accessibility) ──

    /** Системный идентификатор волюм-текста Teyes SystemUI: com.android.systemui:id/vol_text. */
    private val teyesVolumeViewId: String = "com.android.systemui:id/vol_text"

    /** Последнее прочитанное значение громкости Teyes (для избежания дублирования). */
    private var lastReadTeyesVolume: Int = -1

    /** Время последнего вызова [tryReadTeyesVolume] (мс) — для throttle. */
    private var lastTeyesReadTime: Long = 0L

    /**
     * Throttled-обёртка над [readTeyesVolumeInternal].
     *
     * Быстрый путь: если source-узел сам является vol_text — читаем напрямую
     * (без throttle, без обхода дерева). Медленный путь — полный обход
     * через [android.view.accessibility.AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId] —
     * выполняется не чаще [TEYES_READ_THROTTLE_MS].
     */
    private fun tryReadTeyesVolume(source: android.view.accessibility.AccessibilityNodeInfo?) {
        if (source == null) return

        // Быстрый путь: source-узел сам является vol_text (не нужен обход дерева)
        if (source.viewIdResourceName == teyesVolumeViewId) {
            tryReadVolumeFromNode(source)
            return
        }

        // Медленный путь: полный обход дерева с throttle
        val now = System.currentTimeMillis()
        if (now - lastTeyesReadTime < TEYES_READ_THROTTLE_MS) return
        lastTeyesReadTime = now
        readTeyesVolumeInternal(source)
    }

    /**
     * Пытается прочитать текущую громкость Teyes из SystemUI через AccessibilityNodeInfo.
     *
     * Ищет TextView с id=vol_text в дереве accessibility-узлов пакета SystemUI.
     * При успешном чтении эмитит [AppEvent.TeyesVolumeRead] с найденным значением.
     */
    private fun readTeyesVolumeInternal(source: android.view.accessibility.AccessibilityNodeInfo) {
        try {
            val volNodes = source.findAccessibilityNodeInfosByViewId(teyesVolumeViewId)
            if (volNodes.isNullOrEmpty()) return

            for (node in volNodes) {
                try {
                    if (tryReadVolumeFromNode(node)) break // читаем только первый подходящий узел
                } finally {
                    node.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка при чтении громкости Teyes через Accessibility: ${e.message}")
        }
    }

    /** Читает текст из конкретного accessibility-узла и эмитит событие.
     *  @return true если громкость успешно прочитана, false если текст пустой или не parsable. */
    private fun tryReadVolumeFromNode(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        if (text.isEmpty()) return false

        val volume = text.toIntOrNull()
        if (volume != null && volume in 1..100) {
            if (volume != lastReadTeyesVolume) {
                lastReadTeyesVolume = volume
                Log.d(TAG, "Прочитана громкость Teyes из SystemUI (vol_text): $volume")
                AppEventBus.tryEmit(AppEvent.TeyesVolumeRead(volume))
            }
            return true
        }
        return false
    }
}
