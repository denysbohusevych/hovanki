# Hovanki

[![CI](https://github.com/denysbohusevych/hovanki/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/denysbohusevych/hovanki/actions/workflows/ci.yml?query=branch%3Amain)
[![Nightly](https://github.com/denysbohusevych/hovanki/actions/workflows/nightly.yml/badge.svg?event=schedule)](https://github.com/denysbohusevych/hovanki/actions/workflows/nightly.yml?query=event%3Aschedule)

Уличные прятки для Android и iOS: сужающаяся зона, геолокация игроков через сервер, находка подтверждается одноразовым кодом с телефона прячущегося (QR или 4 цифры). Сервер решает, кто кого видит, и раскрывает подозрительных игроков вместо наказаний.

## Стек

- **Клиент:** Kotlin Multiplatform + Compose Multiplatform (Android и iOS из одного кода), Koin, Ktor Client, kotlinx.coroutines/serialization.
- **Сервер:** Spring Boot на Kotlin, REST (`/api/v1`), состояние игр в памяти.
- **Общий модуль:** протокол, TOTP-коды находки, гео-математика и правила игры — один код на клиенте и сервере.
- **Версии:** Kotlin 2.4.20, Gradle 9.7.1, AGP 9.3.3, Compose Multiplatform 1.12.1, Ktor 3.6.0, Koin 4.2.2, Spring Boot 4.1.1. Android: minSdk 26, targetSdk 36, compileSdk 37. iOS 16+. Все версии — в `gradle/libs.versions.toml`.

Почему так — [ADR 0001](docs/adr/0001-stack.md).

## Структура

| Путь | Что это |
|---|---|
| `shared/` | KMP (JVM, Android, iOS): DTO протокола, `ApiRoutes`, TOTP, гео, расписание зоны, правила GPS |
| `server/` | Spring Boot сервер игры |
| `clientCore/` | KMP (JVM, Android, iOS): клиентская логика без UI — API сервера, синхронизация, `ServerClock`, игровая сессия |
| `composeApp/` | KMP-библиотека клиента: Compose UI, DI, платформенные сервисы (геолокация, фон) |
| `androidApp/` | Android-приложение: точка входа (`Application`, `MainActivity`) |
| `e2e/` | End-to-end тесты: headless-боты на клиентском коде играют партии против настоящего сервера; оркестратор приложения на эмуляторах и симуляторах (Maestro) |
| `iosApp/` | Xcode-проект: SwiftUI-оболочка вокруг Compose UI |
| `docs/` | [архитектура](docs/architecture.md), [e2e-тесты](docs/e2e.md), [CI/CD](docs/ci-cd.md), [деплой сервера](docs/deploy.md), [roadmap](docs/roadmap.md), [ADR](docs/adr/) |
| `gradle/libs.versions.toml` | версии зависимостей и плагинов |

## Что нужно

- **JDK 21** (сервер собирается toolchain'ом 21; недостающий JDK Gradle скачает сам через foojay).
- **Android Studio** с плагином Kotlin Multiplatform или **IntelliJ IDEA** с Android-плагином; Android SDK с платформой 37.
- Для iOS: **Mac на Apple Silicon** и **Xcode 26.4+** (собираются только таргеты `iosArm64` и `iosSimulatorArm64`).

## Быстрый старт

**1. Сервер**

```bash
./gradlew :server:bootRun
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
```

**2. Android**

```bash
./gradlew :androidApp:installDebug   # на запущенный эмулятор или устройство
```

Или конфигурация `androidApp` в Android Studio. Адрес сервера вводится на главном экране приложения (приложение запоминает его и имя игрока):

- эмулятор: `http://10.0.2.2:8080` (так эмулятор видит компьютер; debug-сборка разрешает HTTP);
- реальный телефон: `http://<IP компьютера в LAN>:8080`, телефон в той же Wi-Fi сети.

**3. iOS**

```bash
open iosApp/iosApp.xcodeproj
```

Схема `iosApp`, симулятор, Run. Первая сборка долгая: Xcode вызывает Gradle и собирает Kotlin-фреймворк.

- симулятор: `http://localhost:8080`;
- реальный iPhone: `http://<имя-мака>.local:8080` (Debug-сборка пускает HTTP только к localhost и `*.local`), Team ID — в `iosApp/Configuration/Local.xcconfig`. Подробности — [iosApp/README.md](iosApp/README.md).

**4. Движение без прогулки**

- Android-эмулятор: Extended controls (`…`) → Location — точка или маршрут.
- iOS-симулятор: Features → Location — точка, City Run, Freeway Drive.

Для игры нужно минимум два клиента: например, эмулятор и симулятор, или два эмулятора.

## Сборки на реальные телефоны

Играть на улице с друзьями: сервер работает в AWS (`https://hovanki.duckdns.org`, [docs/deploy.md](docs/deploy.md)), и тестовые сборки стартуют с этим адресом; свою версию сервера можно открыть наружу через HTTPS-туннель (`cloudflared tunnel --url http://localhost:8080`, без аккаунта). Android-сборка ставится с pre-release [`preview`](https://github.com/denysbohusevych/hovanki/releases/tag/preview) (каждый push в `main`, обновления через Obtainium), iPhone — через TestFlight или из Xcode по кабелю. Пошагово — [docs/ci-cd.md, «Как поставить сборку на телефон»](docs/ci-cd.md#как-поставить-сборку-на-телефон).

Тестовые сборки ходят только по HTTPS. Адрес сервера вводится на главном экране; адрес по умолчанию задаёт Gradle-свойство `hovanki.serverUrl` (`gradle.properties`). Версия и commit сборки — мелко внизу главного экрана.

## Команды

| Команда | Что делает |
|---|---|
| `./gradlew :server:bootRun` | Сервер на `:8080` |
| `./gradlew :androidApp:installDebug` | Собрать и поставить debug-сборку Android |
| `./gradlew :androidApp:assemblePreview` | Тестовая Android-сборка (release-код, `app.hovanki.preview`); подписывается, если заданы переменные `ANDROID_KEYSTORE_*` ([CI/CD](docs/ci-cd.md#секреты-для-подписи-android)) |
| `./gradlew check` | Все тесты и проверки, кроме e2e-сценариев (то же, что в CI на Linux) |
| `./gradlew :shared:jvmTest` | Быстрые тесты общего кода |
| `./gradlew :clientCore:jvmTest` | Тесты клиентской логики (API, синхронизация, `ServerClock`) на JVM |
| `./gradlew :server:test` | Тесты сервера |
| `./gradlew :e2e:test` | End-to-end сценарии: боты играют целые партии против сервера (~3 мин), отчёты — `e2e/build/reports/e2e/`. Только явно: в `check` и CI на push не входят, идут ночью |
| `e2e/run-devices.sh --android 2 --bots 3` | Приложение на двух эмуляторах вместе с ботами, UI через Maestro (`--ios 1` — симулятор на Mac); отчёт — `e2e/build/reports/devices/`. Подробности — [docs/e2e.md](docs/e2e.md) |
| `./gradlew :e2e:route --args="--to 50.4481,30.5402 --adb emulator-5554"` | Провести эмулятор (`--simctl <udid>` — симулятор) по маршруту пешком |
| `./gradlew :shared:iosSimulatorArm64Test` | Тесты общего кода на iOS-симуляторе (только macOS); то же для `:clientCore` |
| `./gradlew spotlessApply` | Отформатировать код (ktlint); `spotlessCheck` — проверка в CI |
| `./gradlew :server:bootJar` | Jar сервера для Docker: `server/build/libs/hovanki-server.jar` |

## Документация

- [Архитектура](docs/architecture.md): модули, поток данных раунда, видимость, время, фазы, находка, API, рецепты.
- [E2E-тесты](docs/e2e.md): боты, приложение на эмуляторах и симуляторах, как написать сценарий и читать отчёт.
- [CI/CD](docs/ci-cd.md): как поставить сборку на телефон (Android pre-release, TestFlight, Xcode по кабелю, туннель к своему серверу), быстрые проверки на push, ночные e2e, что запускать перед PR, тестовые сборки, релиз по тегу, секреты подписи, образ сервера, защита веток.
- [Деплой сервера](docs/deploy.md): одна машина в AWS (EC2, Франкфурт), Docker Compose, Caddy с Let's Encrypt, обновление и откат.
- [Roadmap](docs/roadmap.md): что уже сделано и что дальше.
- [ADR 0001: выбор стека](docs/adr/0001-stack.md).
- [ADR 0002: сессия на устройстве, возврат в игру после перезапуска](docs/adr/0002-session-storage.md).
- [ADR 0003: карта и здания как запретная зона](docs/adr/0003-map-and-buildings.md).
- [CLAUDE.md](CLAUDE.md): правила для AI-агентов (и людей) при работе с кодом.
