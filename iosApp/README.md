# iosApp

Тонкая iOS-оболочка: SwiftUI-приложение, которое показывает Compose Multiplatform UI из Kotlin-фреймворка
`ComposeApp` (модуль `:composeApp`). Вся логика и экраны живут в Kotlin; здесь только `Info.plist`,
иконки и точка входа (`ContentView.swift` вызывает `MainViewControllerKt.mainViewController()`).

## Запуск

1. Нужны Mac на Apple Silicon, Xcode 26.4+ и JDK 21 (Gradle запускается из Xcode).
2. Откройте `iosApp/iosApp.xcodeproj`, схема `iosApp`.
3. Для симулятора ничего настраивать не нужно. Для реального устройства впишите свой Team ID в
   `iosApp/Configuration/Config.xcconfig` (`TEAM_ID=...`, найти его можно в Xcode > Settings > Accounts).
   Bundle ID получится `app.hovanki.ios<TEAM_ID>`, поэтому у каждого разработчика он свой.
4. Первая сборка долгая: фаза «Compile Kotlin Framework» вызывает
   `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, который скачивает Kotlin/Native toolchain
   (`~/.konan`) и компилирует фреймворк. Следующие сборки инкрементальные.

Сборка из терминала (как в CI):

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Kotlin-фреймворк собирается только для `iosArm64` и `iosSimulatorArm64`, поэтому x86_64 для симулятора
исключён (`EXCLUDED_ARCHS`). Если IDE уже собрала фреймворк сама, она выставляет
`OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES`, и скрипт пропускает Gradle.

## Сервер

- Симулятор ходит на Mac по `http://localhost:8080`.
- Реальное устройство должно быть в той же Wi-Fi сети и ходить на `http://<имя-мака>.local:8080`
  (имя: `scutil --get LocalHostName` или Системные настройки > Основные > Общий доступ). Адрес сервера
  задаётся на главном экране приложения. При первом подключении iOS спросит доступ к локальной сети.
- Обычный HTTP разрешён только для localhost и `*.local` (`NSAllowsLocalNetworking` в ATS);
  продовый сервер должен быть на HTTPS.

## Геолокация в фоне

- В `UIBackgroundModes` включён `location`: раунд продолжает отслеживаться при заблокированном экране.
- Достаточно разрешения «При использовании» (`NSLocationWhenInUseUsageDescription`): раунд всегда
  начинается из открытого приложения, а начатые на переднем плане обновления продолжаются в фоне.
  Для этого Kotlin-код должен выставить у `CLLocationManager` `allowsBackgroundLocationUpdates = true`
  и `showsBackgroundLocationIndicator = true` (синий индикатор в статус-баре) и запускать обновления,
  пока приложение на экране.
- Если понадобится `requestAlwaysAuthorization`, добавьте `NSLocationAlwaysAndWhenInUseUsageDescription`.
- В симуляторе движение имитируется через Features > Location или файлом GPX в схеме.

## Позже (после MVP): BLE

Когда появится поиск рядом по Bluetooth, добавить в `Info.plist`:

- `NSBluetoothAlwaysUsageDescription` — зачем приложению Bluetooth;
- в `UIBackgroundModes` — `bluetooth-central` (сканирование в фоне) и, если телефон будет сам
  вещать, `bluetooth-peripheral`.
