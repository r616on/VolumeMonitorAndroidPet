# План исправления проблем VolumeMonitor

> **Ограничение:** версии SDK не повышаются (`compileSdk 33`, `targetSdk 33`, `minSdk 18`).

---

## Приоритет 1 — Критические (crash / безопасность)

### 1.1 `startService()` → `startForegroundService()` в SettingsActivity

**Файл:** `app/src/main/java/com/example/volumemonitor/SettingsActivity.kt`, строка 232

**Проблема:** На Android 8+ вызов `startService()` для foreground-сервиса, который уже был запущен как foreground, может вызвать `IllegalStateException`. В `MainActivity` этот код корректен, в `SettingsActivity` — нет.

**Исправление:** Заменить `startService(serviceIntent)` на условный вызов с проверкой `Build.VERSION.SDK_INT`, аналогично `MainActivity`:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    startForegroundService(serviceIntent)
} else {
    startService(serviceIntent)
}
```

---

### 1.2 `BootReceiver` — сузить `exported` и intent-filter

**Файл:** `app/src/main/AndroidManifest.xml`, строки 22-30

**Проблема:** `BootReceiver` имеет `exported="true"` и принимает `USB_DEVICE_ATTACHED`, что позволяет сторонним приложениям слать поддельные интенты.

**Исправление:** Убрать `USB_DEVICE_ATTACHED` из intent-filter `BootReceiver` (этот intent уже обрабатывается в `VolumeMonitorService`). Оставить только `BOOT_COMPLETED` и `QUICKBOOT_POWERON`.

```xml
<receiver
    android:name=".BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

---

### 1.3 `registerReceiver()` без флагов экспорта

**Файл:** `app/src/main/java/com/example/volumemonitor/MainActivity.kt`, строки 285-287

**Проблема:** На compileSdk 33 это пока предупреждение, но на compileSdk 34+ станет ошибкой. Лучше исправить сейчас.

**Исправление:** Добавить `RECEIVER_NOT_EXPORTED` (все три receiver'а — внутренние):

```kotlin
private fun registerReceivers() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(volumeUpdateReceiver, IntentFilter("VOLUME_UPDATED"), RECEIVER_NOT_EXPORTED)
        registerReceiver(usbStatusReceiver, IntentFilter("USB_STATUS_UPDATED"), RECEIVER_NOT_EXPORTED)
        registerReceiver(arduinoResponseReceiver, IntentFilter("ARDUINO_RESPONSE"), RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(volumeUpdateReceiver, IntentFilter("VOLUME_UPDATED"))
        registerReceiver(usbStatusReceiver, IntentFilter("USB_STATUS_UPDATED"))
        registerReceiver(arduinoResponseReceiver, IntentFilter("ARDUINO_RESPONSE"))
    }
    Log.d(TAG, "Broadcast Receiver'ы зарегистрированы")
}
```

Аналогично — для `SettingsActivity.registerReceivers()` (строка 223) и `VolumeMonitorService.onCreate()` (строки 212, 214-215).

---

## Приоритет 2 — Функциональные

### 2.1 Одинаковый `requestCode=0` для PendingIntent в SettingsActivity

**Файл:** `app/src/main/java/com/example/volumemonitor/SettingsActivity.kt`, строка 287

**Проблема:** При запросе разрешений для нескольких устройств все `PendingIntent` имеют `requestCode=0` и перезаписывают друг друга.

**Исправление:** Использовать `device.deviceId` как `requestCode`:

```kotlin
val permissionIntent = PendingIntent.getBroadcast(
    this,
    it.deviceId,  // уникальный requestCode для каждого устройства
    Intent(USB_PERMISSION_ACTION),
    flags
)
```

Аналогично в `VolumeMonitorService.requestUsbPermission()` (строка 294).

---

### 2.2 `getParcelableExtra` deprecated на API 33+

**Файл:** `core/src/main/java/com/example/volumemonitor/core/VolumeMonitorService.kt`, строки 91, 108, 128
**Файл:** `app/src/main/java/com/example/volumemonitor/SettingsActivity.kt`, строка 56

**Проблема:** `intent.getParcelableExtra<T>(String)` deprecated на API 33+. Без повышения compileSdk это работает, но генерирует предупреждения.

**Исправление:** Добавить `@Suppress("DEPRECATION")` и условную ветку:

```kotlin
@Suppress("DEPRECATION")
val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
```

---

### 2.3 Отсутствует `POST_NOTIFICATIONS` для Android 13+

**Файл:** `app/src/main/AndroidManifest.xml`

**Проблема:** На Android 13+ для показа уведомлений требуется runtime-разрешение `POST_NOTIFICATIONS`. Без него `startForeground()` может молча не показать уведомление.

**Исправление:** Добавить в манифест:
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

И запросить разрешение в `MainActivity.onCreate()` (или при первом запуске):
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
}
```

---

### 2.4 `VOLUME_CHANGED_ACTION` устарел на Android 15+

**Файл:** `core/src/main/java/com/example/volumemonitor/core/VolumeMonitorService.kt`, строка 214

**Проблема:** На Android 15+ `VOLUME_CHANGED_ACTION` не доставляется фоновым приложениям. На текущем `targetSdk 33` это не критично, но при повышении targetSdk в будущем станет проблемой.

**Исправление (на будущее):** Добавить комментарий-напоминание и альтернативную реализацию через `OnAudioVolumeChangedListener` при повышении targetSdk. Сейчас достаточно оставить TODO-комментарий.

---

## Приоритет 3 — Потокобезопасность и утечки

### 3.1 `serialBuffer` без синхронизации

**Файл:** `core/src/main/java/com/example/volumemonitor/core/VolumeMonitorService.kt`, строки 31, 53-59

**Проблема:** `StringBuilder` используется из IO-потока (чтение от Arduino) без синхронизации.

**Исправление:** Оборачивать доступ к `serialBuffer` в `synchronized` блок:

```kotlin
private val serialBuffer = StringBuilder()
private val bufferLock = Any()

// В serialListener.onNewData:
synchronized(bufferLock) {
    serialBuffer.append(chunk)
    var index: Int
    while (serialBuffer.indexOf("\n").also { index = it } >= 0) {
        val line = serialBuffer.substring(0, index).trim()
        serialBuffer.delete(0, index + 1)
        // ...
    }
}
```

---

### 3.2 `Handler.postDelayed` без возможности отмены

**Файл:** `app/src/main/java/com/example/volumemonitor/MainActivity.kt`, строки 264-267, 276-279

**Проблема:** Runnable не сохраняется — невозможно отменить при `onDestroy()`.

**Исправление:** Сохранять Runnable в переменные и отменять в `onDestroy()`:

```kotlin
private var presetButtonsRunnable: Runnable? = null

// В setupButtons():
presetButtonsRunnable = Runnable {
    requestPresetButton.isEnabled = true
    changePresetButton.isEnabled = true
}
Handler(Looper.getMainLooper()).postDelayed(presetButtonsRunnable!!, 3000)

// В onDestroy():
override fun onDestroy() {
    super.onDestroy()
    presetButtonsRunnable?.let { 
        Handler(Looper.getMainLooper()).removeCallbacks(it) 
    }
    // ... дерегистрация receiver'ов, unbind service
}
```

---

### 3.3 Потенциальная утечка BroadcastReceiver

**Файл:** `app/src/main/java/com/example/volumemonitor/MainActivity.kt`, строки 217-236

**Проблема:** Receiver'ы регистрируются в `onResume()`, дерегистрируются в `onPause()`. Если `onDestroy()` вызывается без `onPause()`, receiver'ы утекают.

**Исправление:** Добавить безопасную дерегистрацию в `onDestroy()`:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    try {
        unregisterReceiver(volumeUpdateReceiver)
    } catch (e: Exception) { /* уже дерегистрирован */ }
    try {
        unregisterReceiver(usbStatusReceiver)
    } catch (e: Exception) { /* уже дерегистрирован */ }
    try {
        unregisterReceiver(arduinoResponseReceiver)
    } catch (e: Exception) { /* уже дерегистрирован */ }
    if (isBound) {
        unbindService(serviceConnection)
        isBound = false
    }
}
```

Аналогично для `SettingsActivity`.

---

## Приоритет 4 — Качество кода

### 4.1 Hardcoded строки → `strings.xml`

**Файлы:** `MainActivity.kt`, `SettingsActivity.kt`, `VolumeMonitorService.kt`, layout-файлы

**Проблема:** Все строки захардкожены, локализация невозможна.

**Исправление:** Вынести все пользовательские строки в `app/src/main/res/values/strings.xml` и использовать `getString(R.string....)` в коде и `@string/...` в XML.

### 4.2 `!!` оператор в `updateUsbStatus()`

**Файл:** `app/src/main/java/com/example/volumemonitor/MainActivity.kt`, строка 319

**Проблема:** `volumeService!!.usbStatus` — форсированное разыменование nullable переменной.

**Исправление:** Использовать безопасный вызов:

```kotlin
private fun updateUsbStatus() {
    val status = volumeService?.usbStatus ?: "Сервис не доступен"
    usbStatusTextView.text = "Статус USB: $status"
}
```

### 4.3 `textSize` в `dp` вместо `sp`

**Файл:** `app/src/main/res/layout/activity_main.xml`, строки 90, 135, 143

**Проблема:** Размер текста задан в `dp` — не учитывает системные настройки размера шрифта.

**Исправление:** Заменить `dp` на `sp` для всех `textSize`:
```xml
android:textSize="22sp"  <!-- было 22dp -->
```

### 4.4 Вложенные `ScrollView`

**Файл:** `app/src/main/res/layout/activity_main.xml`, строки 2 и 155

**Проблема:** Внешний `ScrollView` содержит внутренний `ScrollView` для `arduinoResponseTextView` — проблемы с прокруткой.

**Исправление:** Убрать внутренний `ScrollView`, задать `maxHeight` для `arduinoResponseTextView` через код или заменить на `NestedScrollView`.

### 4.5 APK в репозитории

**Файл:** `app/release/app-release.apk`

**Исправление:** Добавить `app/release/` в `.gitignore` и удалить APK из репозитория.

### 4.6 Пустые тестовые файлы

**Файлы:** `app/src/androidTest/java/.../ExampleInstrumentedTest.java`, `app/src/test/java/.../ExampleUnitTest.java`

**Исправление:** Удалить пустые файлы (0 chars) или написать реальные тесты.

### 4.7 `notificationPendingIntent` может быть null при старте

**Файл:** `core/src/main/java/com/example/volumemonitor/core/VolumeMonitorService.kt`, строка 221

**Проблема:** `startForeground()` вызывается до установки `notificationPendingIntent`.

**Исправление:** Добавить fallback-PendingIntent в `createNotification()`, который открывает MainActivity:

```kotlin
private fun createNotification(): Notification {
    // ...
    val pendingIntent = notificationPendingIntent ?: PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    builder.setContentIntent(pendingIntent)
    // ...
}
```

### 4.8 Явный манифест для core-модуля

**Файл:** создать `core/src/main/AndroidManifest.xml`

**Проблема:** Отсутствует явный манифест для Android Library модуля.

**Исправление:** Создать минимальный манифест:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
</manifest>
```

---

## Порядок выполнения

| Шаг | Приоритет | Задача | Файлы |
|-----|-----------|--------|-------|
| 1 | 🔴 P1 | `startForegroundService` в SettingsActivity | `SettingsActivity.kt:232` |
| 2 | 🔴 P1 | Сузить `exported` BootReceiver | `AndroidManifest.xml:22-30` |
| 3 | 🔴 P1 | `RECEIVER_NOT_EXPORTED` для registerReceiver | `MainActivity.kt:285-287`, `SettingsActivity.kt:223`, `VolumeMonitorService.kt:212-215` |
| 4 | 🟠 P2 | Уникальный requestCode для PendingIntent | `SettingsActivity.kt:287`, `VolumeMonitorService.kt:294` |
| 5 | 🟠 P2 | `@Suppress("DEPRECATION")` для getParcelableExtra | `VolumeMonitorService.kt:91,108,128`, `SettingsActivity.kt:56` |
| 6 | 🟠 P2 | Разрешение POST_NOTIFICATIONS | `AndroidManifest.xml`, `MainActivity.kt` |
| 7 | 🟡 P3 | Синхронизация serialBuffer | `VolumeMonitorService.kt:31,53-59` |
| 8 | 🟡 P3 | Отмена Handler.postDelayed | `MainActivity.kt:264-279` |
| 9 | 🟡 P3 | Защита от утечки Receiver в onDestroy | `MainActivity.kt`, `SettingsActivity.kt` |
| 10 | 🟢 P4 | Hardcoded строки → strings.xml | все .kt и .xml |
| 11 | 🟢 P4 | Убрать `!!` в updateUsbStatus | `MainActivity.kt:319` |
| 12 | 🟢 P4 | dp → sp для textSize | `activity_main.xml:90,135,143` |
| 13 | 🟢 P4 | Убрать вложенный ScrollView | `activity_main.xml:155-170` |
| 14 | 🟢 P4 | APK из репозитория | `.gitignore`, `app/release/` |
| 15 | 🟢 P4 | Удалить пустые тесты | `ExampleInstrumentedTest.java`, `ExampleUnitTest.java` |
| 16 | 🟢 P4 | Fallback PendingIntent для уведомления | `VolumeMonitorService.kt:246-271` |
| 17 | 🟢 P4 | Явный манифест core-модуля | `core/src/main/AndroidManifest.xml` |
