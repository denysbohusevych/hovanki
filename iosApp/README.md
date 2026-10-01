# iosApp

Тонкая iOS-оболочка: SwiftUI-приложение, которое показывает Compose Multiplatform UI из Kotlin-фреймворка
`ComposeApp` (модуль `:composeApp`). Вся логика и экраны живут в Kotlin; здесь только `Info.plist`,
тексты разрешений (`InfoPlist.xcstrings`, en/uk/ru), privacy manifest (`PrivacyInfo.xcprivacy`), иконка
и точка входа (`ContentView.swift` вызывает `MainViewControllerKt.mainViewController()`).

## Запуск

1. Нужны Mac на Apple Silicon, Xcode 26.4+ и JDK 21 (Gradle запускается из Xcode).
2. Откройте `iosApp/iosApp.xcodeproj`, схема `iosApp`.
3. Для симулятора ничего настраивать не нужно. Для реального устройства нужен Team ID: создайте
   `iosApp/Configuration/Local.xcconfig` (в `.gitignore`, подключается из `Config.xcconfig`):

   ```
   TEAM_ID = ABCDE12345
   // Только для бесплатного Apple ID (Personal Team): свой bundle id, app.hovanki.ios — для App Store.
   BUNDLE_ID = app.hovanki.ios.<ваше-имя>
   ```

   Bundle ID по умолчанию постоянный — `app.hovanki.ios` (`BUNDLE_ID` в `Config.xcconfig`), с ним собираются
   TestFlight и e2e на симуляторе. Пошагово, включая режим разработчика на iPhone, —
   [docs/ci-cd.md, «iPhone по кабелю из Xcode»](../docs/ci-cd.md#iphone-по-кабелю-из-xcode-пока-apple-developer-program-не-подтверждён).
4. Первая сборка долгая: фаза «Compile Kotlin Framework» вызывает
   `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`, который скачивает Kotlin/Native toolchain
   (`~/.konan`) и компилирует фреймворк. Следующие сборки инкрементальные.

Версия (`MARKETING_VERSION`) — в `Config.xcconfig`, build number в CI — номер запуска `preview.yml` (сборка для App Store из `release.yml` берёт версию из тега, [docs/ci-cd.md, «Номера сборок»](../docs/ci-cd.md#номера-сборок)). Внизу
главного экрана приложение показывает версию, build number и commit.

Сборка из терминала (как в CI):

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Такая сборка без подписи годится только чтобы проверить компиляцию. Приложение хранит сессию игры в Keychain
([ADR 0002](../docs/adr/0002-session-storage.md)), а у неподписанного приложения нет entitlements, и Keychain
отказывает (`errSecMissingEntitlement`): после перезапуска оно не вернётся в игру. Для запуска на симуляторе
подписывайте «to run locally», как это делает Xcode (так собирает `e2e/run-devices.sh`):

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= build
```

Kotlin-фреймворк собирается только для `iosArm64` и `iosSimulatorArm64`, поэтому x86_64 для симулятора
исключён (`EXCLUDED_ARCHS`). Если IDE уже собрала фреймворк сама, она выставляет
`OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES`, и скрипт пропускает Gradle.

## Сервер

- Симулятор ходит на Mac по `http://localhost:8080`.
- Реальное устройство в той же Wi-Fi сети ходит на `http://<имя-мака>.local:8080`
  (имя: `scutil --get LocalHostName` или Системные настройки > Основные > Общий доступ). Поля адреса в приложении
  нет: адрес передаётся параметром запуска Debug-сборки. Xcode → Product → Scheme → Edit Scheme… → Run →
  Arguments → Arguments Passed On Launch: `-hovanki.server http://<имя-мака>.local:8080` (две строки или одна
  через пробел). Схема с этим параметром — локальная настройка, не коммитьте её. При первом подключении iOS
  спросит доступ к локальной сети.
- Обычный HTTP к localhost и `*.local` разрешён только в Debug: build phase «Debug: local network»
  (`Configuration/debug-info-plist.sh`) добавляет `NSAllowsLocalNetworking` и `NSLocalNetworkUsageDescription`
  в Info.plist Debug-сборки. Release (TestFlight, App Store) ходит только по HTTPS — на хостинг или через туннель
  ([docs/ci-cd.md](../docs/ci-cd.md#сервер-через-туннель)).
- Адрес по умолчанию: Release и Debug на iPhone — Gradle-свойство `hovanki.serverUrl` (сейчас
  `https://hovanki.duckdns.org`, [docs/deploy.md](../docs/deploy.md)), Debug в симуляторе — `http://localhost:8080`.
  Release ходит только туда: аккаунты живут на одном сервере ([ADR 0004](../docs/adr/0004-accounts-friends-chat.md)).
  Debug-сборка на iPhone играет с остальными и показывает панель диагностики «DBG»: GPS, dBm Bluetooth,
  синхронизации ([docs/architecture.md](../docs/architecture.md#диагностика-debug-сборки)).

## Геолокация в фоне

- В `UIBackgroundModes` включён `location`: раунд продолжает отслеживаться при заблокированном экране.
- Достаточно разрешения «При использовании» (`NSLocationWhenInUseUsageDescription`): раунд всегда
  начинается из открытого приложения, а начатые на переднем плане обновления продолжаются в фоне.
  Для этого Kotlin-код должен выставить у `CLLocationManager` `allowsBackgroundLocationUpdates = true`
  и `showsBackgroundLocationIndicator = true` (синий индикатор в статус-баре) и запускать обновления,
  пока приложение на экране.
- `NSLocationAlwaysAndWhenInUseUsageDescription` тоже есть: приложение не просит «Всегда», но с этим ключом
  пользователь может выбрать «Всегда» в настройках, если iOS останавливает раунд в фоне.
- В симуляторе движение имитируется через Features > Location или файлом GPX в схеме.

## Позже (после MVP): BLE

Когда появится поиск рядом по Bluetooth, добавить в `Info.plist`:

- `NSBluetoothAlwaysUsageDescription` — зачем приложению Bluetooth;
- в `UIBackgroundModes` — `bluetooth-central` (сканирование в фоне) и, если телефон будет сам
  вещать, `bluetooth-peripheral`.
