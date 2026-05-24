# План: Поддержка нескольких наборов кнопок (multi-keyCode)

## Цель
Разрешить обучение нескольких keyCode для каждого действия (VOLUME_UP / VOLUME_DOWN). Это нужно для целевых систем, где есть кнопки на руле, на магнитоле и виртуальные на экране — каждая с разным keyCode.

## Текущая архитектура
- `SettingsRepository.getButtonKeyCode(action)` → `Int?` (один keyCode)
- `SettingsRepository.saveButtonKeyCode(action, keyCode)` → перезаписывает
- `ButtonPressService` проверяет `keyCode == volumeUpKeyCode`
- `ButtonSettingsFragment` показывает статус `"Не назначено"` или `"KeyCode: 24"`

## Целевая архитектура
- `SettingsRepository.getButtonKeyCodes(action)` → `Set<Int>` (множество keyCode)
- `SettingsRepository.addButtonKeyCode(action, keyCode)` → добавляет в множество
- `SettingsRepository.removeButtonKeyCode(action, keyCode)` → удаляет из множества
- `ButtonPressService` проверяет `keyCode in volumeUpKeyCodes`
- `ButtonSettingsFragment` показывает список выученных keyCode с кнопками удаления

---

## Шаги реализации

### 1. `Constants.kt` — ключи не меняются
Добавить комментарий, что значения хранятся как `Set<String>` через `putStringSet`/`getStringSet`.

### 2. `SettingsRepository.kt` — новые методы
```kotlin
fun getButtonKeyCodes(action: ButtonAction): Set<Int>
fun addButtonKeyCode(action: ButtonAction, keyCode: Int)
fun removeButtonKeyCode(action: ButtonAction, keyCode: Int)
fun removeAllButtonKeyCodes(action: ButtonAction)
```

### 3. `SettingsRepositoryImpl.kt` — реализация
Хранить множество как `Set<String>` в SharedPreferences. При записи преобразовывать `Set<Int>` → `Set<String>`, при чтении — обратно.

Удалить старые методы `getButtonKeyCode` / `saveButtonKeyCode`, заменить на новые.

### 4. `ButtonPressService.kt` — проверка множества
```kotlin
// Было:
private var volumeUpKeyCode: Int? = null
// Стало:
private var volumeUpKeyCodes: Set<Int> = emptySet()
```
В `reloadSettings()` загружать множество.
В `handleKeyDown()` проверять `keyCode in volumeUpKeyCodes` / `keyCode in volumeDownKeyCodes`.

### 5. `ButtonSettingsFragment.kt` — новый UI
- Вместо `"Не назначено"` / `"KeyCode: 24"` показывать список выученных keyCode
- Каждый элемент списка: `"KeyCode: 24 (KEYCODE_VOLUME_UP)"` с кнопкой `[✕]` для удаления
- Кнопка «Обучить» остаётся, но теперь добавляет keyCode в множество (не перезаписывает)
- Обновить метод `refreshButtonStatuses()` для отображения списка
- Обновить `showLearnDialog()` — передавать `Set<Int>` существующих keyCode в диалог (опционально, для информации)

### 6. `ButtonLearnDialog.kt` — возврат keyCode
Текущий код уже возвращает выученный keyCode через `setOnLearnListener`. Нужно изменить только вызывающий код в `ButtonSettingsFragment.showLearnDialog()` — вызывать `addButtonKeyCode` вместо `saveButtonKeyCode`.

### 7. `fragment_button_settings.xml` — новый макет
- Убрать `TextView` для статуса (одиночная строка)
- Добавить `LinearLayout` (вертикальный) как контейнер для списка keyCode
- Каждая строка списка: `LinearLayout` (горизонтальный) с `TextView` (keyCode) + `ImageButton` (удалить)
- Контейнер наполняется динамически из кода

### 8. Миграция данных
При первом запуске после обновления: преобразовать старый одиночный keyCode (`Int`) в множество из одного элемента. Сделать в `SettingsRepositoryImpl` прозрачно.

---

### 9. Сокращение времени обучения (бонус)
- В `Constants.kt`: `DEFAULT_BUTTON_LEARN_TIMEOUT_MS = 2000L` (было 3000L)
- В `ButtonLearnDialog.kt`: текст инструкции «2 секунд» вместо «3 секунд»

## Затрагиваемые файлы
| Файл | Изменение |
|------|-----------|
| `core/.../Constants.kt` | `DEFAULT_BUTTON_LEARN_TIMEOUT_MS` = 2000L + комментарий к ключам |
| `core/.../SettingsRepository.kt` | Новые методы |
| `core/.../SettingsRepositoryImpl.kt` | Реализация + миграция |
| `core/.../ButtonPressService.kt` | `Set<Int>` вместо `Int?` |
| `app/.../ButtonSettingsFragment.kt` | UI списка, динамическое добавление строк |
| `app/.../ButtonLearnDialog.kt` | Текст «2 секунд» (было «3 секунд») |
| `app/.../fragment_button_settings.xml` | Новый макет со списком |
