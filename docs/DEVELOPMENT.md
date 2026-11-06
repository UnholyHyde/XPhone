# Разработка XPhone

README ориентирован на пользователя; здесь собраны технические сведения о локальном проекте.
Проверяйте наличие файлов в выбранной ветке: опубликованные бинарные сборки и основная ветка могут различаться.

## Windows

C# + WPF, `net8.0-windows`, `RollForward=Major`. Зависимость NuGet — QRCoder.

```powershell
dotnet build src/XPhone
dotnet run --project src/XPhone
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File build-setup.ps1
```

`build.ps1` создаёт `dist/XPhone`. `build-setup.ps1` собирает автономную поставку с .NET
и установщик Inno Setup в `dist/Setup`. На машине сборки нужен Inno Setup 6.7+;
путь к компилятору можно передать через `-IsccPath`. Скрипты PowerShell сохраняются в UTF-8 с BOM.

| Каталог | Назначение |
|---|---|
| `src/XPhone/Adb` | ADB, обнаружение, сопряжение, подключение |
| `src/XPhone/Scrcpy` | Параметры, запуск и остановка движка, обработка ошибок |
| `src/XPhone/Settings` | Профили устройств и сохранение настроек |
| `src/XPhone/Audio` | Проверка уровня аудио средствами Windows |
| `src/XPhone/Diagnostics` | Проверка телефона |
| `src/XPhone/UI` | Окна WPF, тема и иконки |
| `src/XPhone/Core` | Процессы, WinAPI, сетевые проверки |
| `tools` | Оригинальная поставка scrcpy/ADB |
| `licenses` | Сторонние лицензии и NOTICE |
| `installer` | Сценарий Inno Setup |

Настройки пользователя находятся в `%APPDATA%/XPhone/settings.json`.
Служебные компоненты поставки лежат в подпапке `tools`; код выбирает брендированную копию
`xphone-screen.exe`, если она есть, либо исходный scrcpy для отладки.

## Android

Java, пакет `ru.xphone.booster`, minSdk 26, compile/target SDK 36. Интерфейс строится кодом;
дополнительная библиотека — Shizuku API. Для сборки нужны JDK 17+ и Android SDK.

```powershell
android/gradlew.bat -p android assembleRelease lintDebug
```

Путь SDK задаётся локально; ключ подписи и пароли должны храниться вне репозитория.
Не публикуйте `.jks`, `.keystore`, локальные свойства Gradle или секреты.
Подписанные обновления устанавливаются поверх существующего приложения при совпадении ключа.

Важные классы: `MainActivity` — вкладки; `Overlay` — игровая панель; `MiniHud`/`Telemetry` —
показатели; `PanelFeatures`/`PanelLayout` — выбор кнопок и компоновка; `PcLink` — связь с ПК;
`TouchTrace`/`TouchGlow` — касания; `GamePower` — режимы; `NativeRecorder` — штатная запись Samsung.

Сборка и программные проверки не заменяют проверку на реальном телефоне. Основная тестовая
конфигурация — одно устройство Samsung с One UI; Ethernet-переходник и другие модели требуют реального теста.

## Сторонние компоненты

Происхождение, лицензии и изменения ресурсов scrcpy описаны в [NOTICE](../licenses/NOTICE.txt).
При обновлении scrcpy сначала проверьте официальные параметры запуска и контрольные суммы;
не переносите параметры новой версии без проверки. Сохраняйте лицензии в конечной поставке.

## Иллюстрации инструкции Windows

Исходные снимки окна: `docs/images/desktop/*.jpg`, без изменения содержимого интерфейса.
Разметка (рамки, стрелки, номера, подписи) задаётся в `docs/tools/annotate_screenshots.py`.
Для пересборки нужны Pillow и resvg-py только на машине документации:

```powershell
python -m pip install Pillow resvg-py
python docs/tools/annotate_screenshots.py --png
```

SVG остаются редактируемыми исходниками, README использует PNG для надёжного отображения.
Не публикуйте действующие QR-коды/коды сопряжения и личное содержимое телефона.
