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

## Радиолаба: Live Activity и фоновые режимы

Шаг 5 [docs/radar-run.md](../docs/radar-run.md) (§5.1, §5.3): режимы `mode.audio`, `mode.notification_wake`,
`mode.live_activity` и `uwb.ni` для двух iPhone в карманах. Только Debug-сборка, только лаборатория: игра их не
включает.

Что уже сделано без Xcode:

- `Configuration/debug-info-plist.sh` добавляет в Info.plist Debug-сборки фоновые режимы `audio` и
  `nearby-interaction` (к `bluetooth-*` и `location`, без повторов) и `NSSupportsLiveActivities = YES`. В Release
  их нет.
- `NSNearbyInteractionUsageDescription` — в `Info.plist` и `InfoPlist.xcstrings` (en/uk/ru).
- Код Live Activity на Swift: `iosApp/LiveActivity/HovankiLiveAttributes.swift` (оба таргета),
  `iosApp/LiveActivity/HovankiLiveActivityHost.swift` (приложение), `HovankiLive/HovankiLiveBundle.swift` и
  `HovankiLive/HovankiLiveWidget.swift` (расширение). `ContentView.swift` ищет класс `HovankiLiveActivityHost` по
  имени и отдаёт его в Kotlin (`LiveActivityBridgeKt.installLiveActivityHost`), поэтому приложение собирается и без
  этих файлов — тогда лаборатория пишет в журнал, что `mode.live_activity` недоступен.

Что делает владелец (один раз, на Маке):

1. Убрать наши файлы расширения в сторону, чтобы шаблон Xcode их не задел:
   `mv iosApp/HovankiLive /tmp/HovankiLive-ours`.
2. Xcode → File → New → Target… → iOS → Widget Extension. Product Name `HovankiLive`, галочка «Include Live
   Activity» (остальные — «Include Control», «Include Configuration App Intent» — снять), Team — тот же, что у
   приложения, Embed in Application — `iosApp`. На вопрос «Activate "HovankiLive" scheme?» — Activate или Cancel,
   всё равно: запускаем схему `iosApp`.
3. У нового таргета `HovankiLive`, General и Build Settings:
   - Bundle Identifier — bundle id приложения плюс `.live`: `app.hovanki.ios.live`, а с бесплатным Apple ID —
     `<BUNDLE_ID из Local.xcconfig>.live`. Вписать руками: `$(BUNDLE_ID)` у этого таргета не определён,
     `Config.xcconfig` подключён только к приложению (и подключать его сюда нельзя: он задаёт имя и bundle id
     приложения).
   - Team — тот же, что у приложения (`TEAM_ID` из `Local.xcconfig`).
   - Minimum Deployments / iOS Deployment Target — `16.2` (Xcode ставит самую новую iOS — тогда на iPhone со
     старой iOS расширение молча не установится).
   - Marketing Version `0.1.0` и Current Project Version `1` — как у приложения (иначе Xcode предупреждает о
     разных версиях).
   - Swift Language Version — `Swift 5`; Default Actor Isolation — `nonisolated`, если Xcode поставил `MainActor`
     (иначе `HovankiLiveAttributes` может не собраться в расширении).
4. Удалить Swift-файлы шаблона в папке `iosApp/HovankiLive/` (Move to Trash): `HovankiLive.swift`,
   `HovankiLiveBundle.swift`, `HovankiLiveLiveActivity.swift` и, если есть, `HovankiLiveControl.swift`,
   `AppIntent.swift`. `Info.plist` и `Assets.xcassets` шаблона оставить.
5. Вернуть наши файлы: `cp /tmp/HovankiLive-ours/*.swift iosApp/HovankiLive/`. Если группа расширения в Xcode —
   синхронизированная папка (синяя), они войдут в таргет сами; если обычная (жёлтая) — перетащить их в группу
   `HovankiLive` с галочкой только у таргета `HovankiLive`. `git status` после этого не должен показывать изменений
   в них.
6. Добавить в проект папку `iosApp/iosApp/LiveActivity/` (File → Add Files to "iosApp"…, «Create groups»):
   `HovankiLiveAttributes.swift` — галочки у обоих таргетов (`iosApp` и `HovankiLive`),
   `HovankiLiveActivityHost.swift` — только у `iosApp`.
7. Signing & Capabilities приложения: Background Modes менять не нужно — `audio` и `nearby-interaction` добавляет
   скрипт. Только если в журнале лаборатории нет ни звука, ни UWB в фоне, а в собранном
   `Hovanki.app/Info.plist` нет этих режимов, — включить «Audio, AirPlay, and Picture in Picture» и «Nearby
   Interaction» здесь (тогда они попадут и в Release — не коммитить).
8. Собрать схему `iosApp` на оба iPhone; ошибки компиляции прислать в сессию целиком.

**Коммитить `project.pbxproj` с расширением — только вместе с его профилем.** `ios-testflight.yml` (его зовут
`preview.yml` и `release.yml`) находит таргет `HovankiLive` в проекте сам и подписывает его вторым профилем из
секрета `IOS_LIVE_PROVISIONING_PROFILE_BASE64` (каждый таргет берёт свой профиль по bundle id). Таргета нет — сборка
прежняя, в сводке строка «no Live Activity extension». Таргет есть, а секрета нет — архив собирается без подписи и
проверяется, в TestFlight ничего не уходит, в сводке сказано почему. Порядок (docs/ci-cd.md, «iOS: TestFlight»):

1. developer.apple.com → Identifiers → «+» → App ID `app.hovanki.ios.live` (Explicit, capabilities не нужны).
2. Profiles → «+» → Distribution → App Store Connect → App ID `app.hovanki.ios.live` → тот же сертификат Apple
   Distribution → имя, например `Hovanki Live App Store` → скачать и положить в секрет
   `IOS_LIVE_PROVISIONING_PROFILE_BASE64` (`base64 -i … | tr -d '\n' | gh secret set …`).
3. В `iosApp/Info.plist` — `NSSupportsLiveActivities` = `YES` (Release-сборки тоже: полевая сборка `preview` — это
   Release; без ключа iOS отказывает в карточке, и пульс заблокированного остаётся уведомлением — job предупредит).
4. Закоммитить таргет из Xcode (`project.pbxproj`, `iosApp/HovankiLive/Info.plist` и `Assets.xcassets`
   расширения), Embed в приложении — `PlugIns/HovankiLive.appex` (job проверяет, что он там есть).

Полевая сборка (ADR 0018, четвёртая волна) запускает ту же Live Activity в раунде игры с радаром: роль и полоса
радара на экране блокировки, а пульс прячущегося на заблокированном iPhone — два alert'а карточки через 300 мс с
беззвучным звуком. Без расширения в сборке всё как раньше: беззвучное уведомление.

Проверка: в лаборатории шаг с `mode.live_activity` — заблокировать телефон, на экране блокировки карточка «Hovanki
lab / the run is on», раз в минуту меняется время. Если её нет: Настройки → Hovanki → Live Activities включены? В
Console.app по процессу Hovanki — строки `HovankiLive:`.

## Позже (после MVP): BLE

Когда появится поиск рядом по Bluetooth, добавить в `Info.plist`:

- `NSBluetoothAlwaysUsageDescription` — зачем приложению Bluetooth;
- в `UIBackgroundModes` — `bluetooth-central` (сканирование в фоне) и, если телефон будет сам
  вещать, `bluetooth-peripheral`.
