package com.example.volumemonitor.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.volumemonitor.R
import com.example.volumemonitor.core.event.AppEvent
import com.example.volumemonitor.core.event.AppEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Направление записи лога.
 */
enum class LogDirection { SENT, RECEIVED }

/**
 * Фрагмент, отображающий полный лог всего, что отправлено в serial port и получено из него.
 *
 * Архитектура:
 * - Сбор событий SharedFlow — на [Dispatchers.Default] (не блокирует главный поток)
 * - Хранение строк — thread-safe через synchronized([LinkedList])
 * - UI-обновление — через [Handler] на главном потоке с троттлингом 80ms
 */
class LogFragment : Fragment() {

    private val TAG = "LogFragment"
    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
    private val maxLines = 200

    private lateinit var logTextView: TextView
    private lateinit var counterTextView: TextView

    /** Thread-safe очередь строк (новые — в начало). */
    private val lines = Collections.synchronizedList(LinkedList<String>())
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Диагностика ──
    private val eventCount = AtomicInteger(0)
    private var lastLogTime = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logTextView = view.findViewById(R.id.logTextView)
        logTextView.movementMethod = ScrollingMovementMethod()
        counterTextView = view.findViewById(R.id.logCounterTextView)

        // Кнопка очистки логов
        val clearLogButton = view.findViewById<android.widget.Button>(R.id.clearLogButton)
        clearLogButton.setOnClickListener { clearLogs() }

        // Сбор событий на фоновом диспетчере — не блокирует главный поток
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            AppEventBus.events.collect { event ->
                when (event) {
                    is AppEvent.SerialDataSent -> appendLine(LogDirection.SENT, event.rawLine)
                    is AppEvent.ArduinoResponse -> appendLine(LogDirection.RECEIVED, event.rawLine)
                    is AppEvent.VolumeChanged -> {}        // не логируется
                    is AppEvent.UsbStatusChanged -> {}      // не логируется
                    is AppEvent.ButtonPressed -> {}
                    is AppEvent.VolumeControlModeChanged -> {}
                    AppEvent.ButtonSettingsChanged -> {}
                    AppEvent.ObserverSettingsChanged -> {
                        addEvent("Настройки максимальной громкости OBSERVER изменены")
                    }
                    is AppEvent.ModeStateChanged -> {}      // не логируется
                    is AppEvent.ScreenVolumeChanged -> {}  // не логируется
                    is AppEvent.MatrixButtonDown -> {}     // не логируется
                    is AppEvent.MatrixButtonUp -> {}       // не логируется
                    AppEvent.PresetNextPressed -> {}     // не логируется
                }
            }
        }
    }

    /**
     * Добавить информационную строку (без направления SENT/RECEIVED) в лог.
     * Используется для внутренних событий, таких как изменение настроек.
     */
    private fun addEvent(message: String) {
        val timeStr = timeFormat.get().format(Date())
        val line = "[$timeStr] ● $message"

        synchronized(lines) {
            lines.add(0, line)
            while (lines.size > maxLines) {
                lines.removeAt(lines.size - 1)
            }
        }

        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.postDelayed(updateRunnable, 80)
    }

    private fun appendLine(direction: LogDirection, data: String) {
        // Диагностический счётчик событий в секунду
        val now = System.currentTimeMillis()
        if (now - lastLogTime >= 1000) {
            Log.d(TAG, "DIAG: events/sec=${eventCount.getAndSet(0)}, queueSize=${lines.size}")
            lastLogTime = now
        }
        eventCount.incrementAndGet()

        val timeStr = timeFormat.get().format(Date())
        val arrow = if (direction == LogDirection.SENT) "\u2192" else "\u2190"
        val line = "[$timeStr] $arrow $data"

        // Потокобезопасная вставка + обрезка под одной блокировкой
        synchronized(lines) {
            lines.add(0, line)           // вставка в начало (новые сверху)
            while (lines.size > maxLines) {
                lines.removeAt(lines.size - 1)  // удаление самой старой строки
            }
        }

        // Планируем UI-обновление с троттлингом (главный поток)
        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.postDelayed(updateRunnable, 80)
    }

    /** Собирает текст из [lines] и обновляет UI. Выполняется на главном потоке. */
    private val updateRunnable = Runnable {
        val sb = StringBuilder()
        // Снимок под блокировкой для потокобезопасности
        synchronized(lines) {
            for (line in lines) {
                sb.append(line).append('\n')
            }
        }
        logTextView.text = sb.toString()
        counterTextView.text = "Строк: ${lines.size}"
    }

    /** Очистить все логи и обновить UI. */
    private fun clearLogs() {
        synchronized(lines) {
            lines.clear()
        }
        logTextView.text = ""
        counterTextView.text = "Строк: 0"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainHandler.removeCallbacks(updateRunnable)
    }
}
