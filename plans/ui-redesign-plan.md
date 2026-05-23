# План редизайна VolumeMonitor

## Анализ текущего UI

| Проблема | Описание |
|----------|----------|
| Плоский дизайн | Прямоугольные элементы без скруглений и теней |
| Жёсткие цвета | `#2196F3`, `#4CAF50`, `#F0F0F0` в layout — не подстраиваются под тему |
| Нет карточек | Вся информация в сплошном LinearLayout без группировки |
| Нет Material 3 | Старая `MaterialComponents` тема без M3-атрибутов |
| Нет тёмной темы | `values-night/themes.xml` есть, но layout не использует `?attr/` |
| Технический вид | `monospace` в JSON/Arduino выглядит недружелюбно |
| Скудная типографика | Нет иерархии заголовков |

---

## Что делаем

### 1. Переход на Material 3
- **Тема:** `Theme.Material3.DayNight.NoActionBar`
- **Цвета:** глубокая сине-фиолетовая палитра с бирюзовым акцентом
- **Dynamic Colors:** поддержка Android 12+ (`?attr/colorPrimary` и т.д.)
- **Тёмная тема:** полная поддержка через M3-атрибуты

### 2. MainActivity — карточная структура

Каждый логический блок оборачивается в [`MaterialCardView`] со скруглением `12dp` и elevation `2dp`:

```
┌──────────────────────────────────────┐
│  🎛️  Монитор громкости       ⚙️    │
├──────────────────────────────────────┤
│  🔌 Статус USB                      │  ← Card: статус с иконкой
│  ┌────────────────────────────────┐  │
│  │  Подключено: Arduino Nano      │  │
│  └────────────────────────────────┘  │
│  🔊 Громкость                       │  ← Card: громкость
│  ┌────────────────────────────────┐  │
│  │  15 / 25  ████████░░░░░░░░░░  │  │
│  │  JSON: {"set_volume":127}      │  │
│  └────────────────────────────────┘  │
│  🎵 Bass (бас)                      │  ← Card: бас
│  ┌────────────────────────────────┐  │
│  │  50%  ━━━━━━━━━━━━○━━━━━━━━━  │  │
│  └────────────────────────────────┘  │
│  🎚️  Пресет                         │  ← Card: пресет
│  ┌────────────────────────────────┐  │
│  │            **1**               │  │     Крупная цифра по центру
│  │  [Сменить]    [Запросить]      │  │
│  └────────────────────────────────┘  │
│  📟 Ответ Arduino                    │  ← Card: лог
│  ┌────────────────────────────────┐  │
│  │ [14:30:01] preset_changed=2    │  │
│  │ [14:30:05] volume=128          │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
```

### 3. SettingsActivity — карточная структура

```
┌──────────────────────────────────────┐
│  ← Настройки USB                     │
├──────────────────────────────────────┤
│  🔌 Статус USB                      │  ← Card: статус
│  Устройство: Arduino Nano            │  ← Card: инфо об устройстве
│  VID: 0x2341  PID: 0x0043  ✅       │
│  ┌────────────────────────────────┐  │
│  │ [Spinner: USB устройства]      │  │  ← Card: выбор
│  │ [🔍 Сканировать]               │  │
│  └────────────────────────────────┘  │
│  [✅ Выбрать это устройство]        │  ← Filled button
│  [🔑 Запросить разрешения]          │  ← Outlined button
└──────────────────────────────────────┘
```

### 4. Новая палитра Material 3

```xml
<!-- colors.xml -->
<color name="seed">#FF1B5E96</color>
<color name="md_theme_light_primary">#FF1B5E96</color>
<color name="md_theme_light_secondary">#FF00897B</color>
<color name="md_theme_light_tertiary">#FF7C4DFF</color>
<color name="md_theme_light_surface">#FFF8F9FF</color>
<color name="md_theme_dark_primary">#FF82B1FF</color>
<color name="md_theme_dark_secondary">#FF64FFDA</color>
<color name="md_theme_dark_tertiary">#FFB388FF</color>
<color name="md_theme_dark_surface">#FF1A1C1E</color>
```

### 5. Иконки для карточек (векторные)
- [`ic_usb.xml`] — USB-штекера
- [`ic_volume.xml`] — динамик
- [`ic_bass.xml`] — эквалайзер
- [`ic_preset.xml`] — слайдер/пресет
- [`ic_terminal.xml`] — терминал/консоль
- [`ic_settings.xml`] — шестерёнка (уже есть)

### 6. Что обновить в `build.gradle`
```groovy
implementation 'com.google.android.material:material:1.11.0'  // с 1.9.0
```

---

## План реализации (8 шагов)

| # | Задача | Файлы |
|---|--------|-------|
| 1 | Обновить `material` до `1.11.0` | [`app/build.gradle`](app/build.gradle) |
| 2 | Переписать `colors.xml` | [`colors.xml`](app/src/main/res/values/colors.xml) |
| 3 | Переписать `themes.xml` (светлая + тёмная) на M3 | [`themes.xml`](app/src/main/res/values/themes.xml), [`themes.xml`](app/src/main/res/values-night/themes.xml) |
| 4 | Создать 5 векторных иконок | `res/drawable/ic_*.xml` |
| 5 | Переписать `activity_main.xml` — карточки + M3-компоненты | [`activity_main.xml`](app/src/main/res/layout/activity_main.xml) |
| 6 | Переписать `activity_settings.xml` — карточки | [`activity_settings.xml`](app/src/main/res/layout/activity_settings.xml) |
| 7 | Обновить `strings.xml` | [`strings.xml`](app/src/main/res/values/strings.xml) |
| 8 | Проверить компиляцию и внешний вид | `:app:compileDebugKotlin` |
