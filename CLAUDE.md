# Проект «XPhone» — инструкции для Claude

Оболочка вокруг scrcpy: экран и звук Android-телефона на ПК для стримов. Описание и устройство кода — `README.md`.

## Как общаться с пользователем

- Пиши **по-русски, коротко и простыми словами** — пользователь не программист.
- Не вываливай код в чат: меняй файлы сам, в ответе — что сделано и что проверить.

## Правила проекта

- C# + WPF, `net8.0-windows` (`RollForward=Major` — запускается и на .NET 10). Без лишних NuGet-пакетов (сейчас только QRCoder — QR для сопряжения).
- Звук «Только на ПК» = `--audio-source=playback` без `--audio-dup` (Android 13+): `output` даёт тишину в играх с голосовым чатом (режим звонка), проверено на S25 Ultra + Mobile Legends.
- У пользователя на ПК бывает включён AmneziaVPN — он перехватывает трафик к домашней сети, и Wi-Fi/mDNS не работают (`Core\NetCheck.cs`).
- Лёгкость: никаких фоновых процессов, кроме adb-сервера; scrcpy — только после «Подключить».
- Весь интерфейс, сообщения и ошибки — на русском. Ошибки — человеческим языком (`Scrcpy\HumanErrors.cs`), сырые логи только в «Подробностях».
- Вид — как у программы пульта (`C:\Projects\StreamDeck\pult-app\ui\style.css`): только оттенки серого, иконки Lucide (`UI\Icon.cs`), шрифты Manrope/Unbounded. Стили — в `UI\Theme.xaml`.
- scrcpy/adb лежат в `tools\` без изменений. Обновление: скачать `scrcpy-win64-vX.zip` с GitHub, сверить SHA256, заменить файлы, поправить версию в `licenses\NOTICE.txt` и README. Перед этим проверить `scrcpy --help`: параметры могли поменяться.
- Сохраняй `.ps1` в UTF-8 **с BOM** (иначе Windows PowerShell ломает русский текст).

## Android-приложение (android/)

- Java, `ru.xphone.booster`, minSdk 26, target 36. Зависимости — только Shizuku API. UI собирается кодом (`Ui.java`), стиль как у ПК-версии.
- Инструменты сборки вне репозитория: `%LOCALAPPDATA%\XPhone-build` (jdk, android-sdk, gradle). `JAVA_HOME` → `...\XPhone-build\jdk`.
- Ключ подписи: `Документы\XPhone-signing-key\xphone-release.jks` (не в git!). Пароли — `%USERPROFILE%\.gradle\gradle.properties`. Не печатать их в вывод.
- Проверено на S25 Ultra: `cmd display set-user-preferred-display-mode` не работает (SecurityException) — разрешение только через настройки. `cmd notification set_dnd none` глушит и игру — используем `priority`.
- После переустановки с другой подписью Shizuku-разрешение выдаётся заново (только руками пользователя).
- Две темы (`Ui.java`: «Графит» и «Стекло»), цвета — поля `Ui`, `Ui.load()` перед построением экрана/панели.
- В панели показывать только доступное: нет функции на телефоне или нет прав — плитки нет (+ строка «Ещё N — с Shizuku»).
- Микрофон: `cmd sensor_privacy enable|disable <user> microphone` (Android 12+, через Shizuku) — проверено.
- «Не беспокоить» на S25 Ultra с Galaxy Watch сразу выключает служба часов (watchuniteplugin) — показываем подсказку.
- В игре включено «Без анимаций» (animator scale 0): таймеры показа окон — только `Handler.postDelayed`, не `animate().withEndAction`.
- Оверлей-окна не видны в `uiautomator dump` — при проверке нажимать по координатам со скриншота.

## Сборка и запуск

```
dotnet build src\XPhone                                   # проверка
dotnet run --project src\XPhone                           # запуск
powershell -ExecutionPolicy Bypass -File build.ps1        # готовая папка dist\XPhone
```

## Проверка на телефоне

Проверить работу с телефоном может только пользователь (Samsung Galaxy S25 Ultra): попроси подключить телефон и сказать, что видно.
