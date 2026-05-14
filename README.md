# RadioNameHook

LSPosed модуль для Belgee X50 (платформа Ecarx). Подменяет пустое название радиостанции на имя из локальной базы.

## Как это работает

1. Модуль внедряется в процесс `com.ecarx.multimedia` через LSPosed framework
2. Перехватывает вызовы `setRadioStationName` / `getRadioStationName` / `RadioInfo.getName()`
3. Когда значение пустое - подставляет имя из `/sdcard/RadioNames/stations.json`

## База станций

Файл: `/sdcard/RadioNames/stations.json`

```json
{
  "fm": {
    "103.4": "Русское Радио",
    "104.2": "Авторадио",
    "92.0": "Эхо Москвы"
  },
  "am": {
    "675": "Маяк"
  }
}
```

## Установка

1. Залить проект на GitHub
2. GitHub Actions соберёт APK автоматически
3. Скачать `RadioNameHook-debug` artifact
4. Установить APK на ГУ через `adb install app-debug.apk`
5. Открыть LSPosed Manager → Modules → включить "Radio Name Hook"
6. Выбрать scope: `com.ecarx.multimedia`
7. Перезагрузить ГУ

## Отладка

```bash
adb logcat | grep RadioNameHook
```

## Требования

- Magisk 24+ с Zygisk
- LSPosed v1.9+ (zygisk версия)
