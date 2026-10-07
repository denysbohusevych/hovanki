# Hovanki

[![CI](https://github.com/denysbohusevych/hovanki/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/denysbohusevych/hovanki/actions/workflows/ci.yml?query=branch%3Amain)
[![Nightly](https://github.com/denysbohusevych/hovanki/actions/workflows/nightly.yml/badge.svg?event=schedule)](https://github.com/denysbohusevych/hovanki/actions/workflows/nightly.yml?query=event%3Aschedule)

Уличные прятки для Android и iOS: сужающаяся зона, геолокация игроков через сервер, находка подтверждается одноразовым кодом с телефона прячущегося (QR или 4 цифры). Сервер решает, кто кого видит, и раскрывает подозрительных игроков вместо наказаний. Аккаунты по нику, email и паролю (email можно подтвердить позже), друзья, группы-«компании» с приглашением в игру и чат внутри игры — всем или своей команде. В профиле — статистика (игры, победы, находки, пройденные километры, средняя скорость) и история игр; маршрут игры сохраняется на 90 дней, только если игрок сам это включил, и виден только ему; запись каждой игры — пути всех — хранится 90 дней для её игроков с аккаунтом. Вид карты игрок выбирает сам: светлая, тёмная, минимал или «Авто» (тёмная после заката), с 3D-домами и рельефом ([ADR 0025](docs/adr/0025-map-styles-and-height.md)). Хост может открыть игру для зрителей: её смотрят по коду, с задержкой, которую он выбрал, а админы — вживую, с причиной в журнале. Лобби говорит, сколько игроков помещается в зону по её местности (дворы, лес, парк, поле). Большие игры на сотни человек назначает админ: зона карандашом на карте, запись заранее, старт по времени. За выключателями на сервере (оператор включает в админке, хост — в лобби) — радар по Bluetooth «тепло / горячо / горит» с пульсом из кармана, задания, искры и перки на доске хоста ([ADR 0012](docs/adr/0012-nearby-radar.md), [ADR 0013](docs/adr/0013-quests-sparks-and-sensors.md)); на настоящих телефонах это ещё не проверено.

## Стек

- **Клиент:** Kotlin Multiplatform + Compose Multiplatform (Android и iOS из одного кода), Koin, Ktor Client, kotlinx.coroutines/serialization.
- **Сервер:** Spring Boot на Kotlin, REST (`/api/v1`), состояние игр в памяти, аккаунты, друзья и история законченных игр — в PostgreSQL (Flyway).
- **Общий модуль:** протокол, TOTP-коды находки, гео-математика и правила игры — один код на клиенте и сервере.
- **Версии:** Kotlin 2.4.20, Gradle 9.7.1, AGP 9.3.3, Compose Multiplatform 1.12.1, Ktor 3.6.0, Koin 4.2.2, Spring Boot 4.1.1. Android: minSdk 26, targetSdk 36, compileSdk 37. iOS 16+. Все версии — в `gradle/libs.versions.toml`.

Почему так — [ADR 0001](docs/adr/0001-stack.md).

## Структура

| Путь | Что это |
|---|---|
| `shared/` | KMP (JVM, Android, iOS): DTO протокола, `ApiRoutes`, TOTP, гео, расписание зоны, правила GPS |
| `server/` | Spring Boot сервер: игры в памяти, аккаунты, друзья, группы, жалобы, история игр, расписание больших игр и флаги возможностей в PostgreSQL; админка |
| `clientCore/` | KMP (JVM, Android, iOS): клиентская логика без UI — API сервера, синхронизация, `ServerClock`, игровая сессия с чатом, аккаунт, друзья и группы |
| `radar/` | KMP (JVM, Android, iOS): радар между телефонами — каналы (форматы жетона в эфире), хосты платформ (Bluetooth LE на Android и iOS, симулятор эфира с правилами ОС для ботов), свой эфир радиолаборатории |
| `device/` | KMP (JVM, Android, iOS): сам телефон — карман, движение, вибрация пульса, `DeviceInfo`, пробы радиолаборатории |
| `composeApp/` | KMP-библиотека клиента: Compose UI, DI, платформенные сервисы (геолокация, фон, Keystore/Keychain; радио, пульс и датчики — в `radar/` и `device/`) |
| `androidApp/` | Android-приложение: точка входа (`Application`, `MainActivity`) |
| `e2e/` | End-to-end тесты: headless-боты на клиентском коде играют партии против настоящего сервера; оркестратор приложения на эмуляторах и симуляторах (Maestro) |
| `iosApp/` | Xcode-проект: SwiftUI-оболочка вокруг Compose UI |
| `docs/` | [архитектура](docs/architecture.md), [e2e-тесты](docs/e2e.md) и [их локальный запуск](docs/e2e-local.md), [CI/CD](docs/ci-cd.md), [деплой сервера](docs/deploy.md), [метрики и аналитика](docs/metrics.md), [roadmap](docs/roadmap.md), [ADR](docs/adr/) |
| `gradle/libs.versions.toml` | версии зависимостей и плагинов |

## Что нужно

- **JDK 21** (сервер собирается toolchain'ом 21; недостающий JDK Gradle скачает сам через foojay).
- **PostgreSQL** для локального сервера: проще всего Docker (`deploy/compose.dev.yaml`), см. [Быстрый старт](#быстрый-старт). Тестам он не нужен.
- **Android Studio** с плагином Kotlin Multiplatform или **IntelliJ IDEA** с Android-плагином; Android SDK с платформой 37.
- Для iOS: **Mac на Apple Silicon** и **Xcode 26.4+** (собираются только таргеты `iosArm64` и `iosSimulatorArm64`).

## Быстрый старт

**1. Сервер**

Аккаунты, друзья и группы сервер хранит в PostgreSQL: база `hovanki` на `localhost:5432`, пользователь и пароль `hovanki`.

```bash
docker compose -f deploy/compose.dev.yaml up -d   # PostgreSQL 17 для разработки; остановить — down, стереть данные — down -v
./gradlew :server:bootRun
curl http://localhost:8080/actuator/health   # {"status":"UP",...}
```

Подойдёт и любой свой PostgreSQL с такими базой, пользователем и паролем. Письма с кодами (подтверждение email, сброс пароля) локальный сервер не отправляет, а пишет в свой лог.

Админка ([ADR 0008](docs/adr/0008-admin.md)) локально: запустить сервер с ключом — `HOVANKI_ADMIN_SECRETKEY=$(openssl rand -base64 32) ./gradlew :server:bootRun`, сделать свой аккаунт админом — `psql -h localhost -U hovanki -c "UPDATE users SET role = 'ADMIN' WHERE email_key = lower('you@example.com')"` — и открыть <http://localhost:8080/admin> в Chrome или Firefox (cookie сессии `Secure`: без HTTPS браузеры принимают её только на `localhost`). Коды из писем — в логе сервера. На сервере в AWS — [deploy.md](docs/deploy.md#админка).

Тестам (`:server:test`, `:e2e:test`) своя база не нужна: они сами поднимают встроенный PostgreSQL 17 (без Docker, бинарники из Maven Central). Уже запущенный сервер PostgreSQL можно подставить через `HOVANKI_TEST_DATABASE_URL='jdbc:postgresql://localhost:5432/hovanki?user=hovanki&password=hovanki'` (пользователю нужно право `CREATEDB`: тесты создают отдельную базу на каждый запуск и потом удаляют). В облачных сессиях Claude Code переменную выставляет SessionStart-хук [`.claude/hooks/session-start.sh`](.claude/hooks/session-start.sh), если в контейнере есть свой PostgreSQL.

**2. Android**

```bash
./gradlew :androidApp:installDebug   # на запущенный эмулятор или устройство
```

Или конфигурация `androidApp` в Android Studio. Поля адреса в приложении нет, debug-сборка выбирает сервер сама:

- эмулятор: `http://10.0.2.2:8080` (так эмулятор видит компьютер; debug-сборка разрешает HTTP);
- реальный телефон: общий сервер из `hovanki.serverUrl`, как тестовые сборки, с панелью диагностики «DBG» ([architecture.md](docs/architecture.md#диагностика-debug-сборки)); на свой компьютер — параметром запуска: `adb shell am start -n app.hovanki/app.hovanki.android.MainActivity --es hovanki.server http://<IP компьютера в LAN>:8080`, телефон в той же Wi-Fi сети.

**3. iOS**

```bash
open iosApp/iosApp.xcodeproj
```

Схема `iosApp`, симулятор, Run. Первая сборка долгая: Xcode вызывает Gradle и собирает Kotlin-фреймворк.

- симулятор: `http://localhost:8080`;
- реальный iPhone: общий сервер, как на Android; на свой Mac — `-hovanki.server http://<имя-мака>.local:8080` в аргументах схемы (Debug-сборка пускает HTTP только к localhost и `*.local`), Team ID — в `iosApp/Configuration/Local.xcconfig`. Подробности — [iosApp/README.md](iosApp/README.md).

**4. Движение без прогулки**

- Android-эмулятор: Extended controls (`…`) → Location — точка или маршрут.
- iOS-симулятор: Features → Location — точка, City Run, Freeway Drive.

Для игры нужно минимум два клиента: например, эмулятор и симулятор, или два эмулятора.

## Сборки на реальные телефоны

Играть на улице с друзьями: основной сервер работает в AWS (`https://hovanki.duckdns.org`, [docs/deploy.md](docs/deploy.md)), а тестовые сборки (`preview`) ходят на второй, [staging](docs/deploy.md#staging) (ADR 0018); свою версию сервера можно открыть наружу через HTTPS-туннель (`cloudflared tunnel --url http://localhost:8080`, без аккаунта) и собрать под него тестовую сборку. Android-сборка ставится из Google Play (внутреннее тестирование) или с pre-release [`preview`](https://github.com/denysbohusevych/hovanki/releases/tag/preview) (каждый push в `main`, обновления через Obtainium), iPhone — через TestFlight или из Xcode по кабелю. Пошагово — [docs/ci-cd.md, «Как поставить сборку на телефон»](docs/ci-cd.md#как-поставить-сборку-на-телефон).

Сборки ходят только по HTTPS и только на один сервер: релизные — на адрес из Gradle-свойства `hovanki.serverUrl` (`gradle.properties`), тестовые из CI — на staging (переменная репозитория `STAGING_SERVER_URL`, [docs/ci-cd.md](docs/ci-cd.md#адрес-сервера)). Аккаунты живут на сервере сборки, поля адреса в приложении нет ([ADR 0004](docs/adr/0004-accounts-friends-chat.md)). Debug-сборки в эмуляторе и симуляторе ходят на компьютер разработчика, на телефоне — на основной сервер (с панелью диагностики «DBG»: GPS, dBm Bluetooth, синхронизации, см. [architecture.md](docs/architecture.md#диагностика-debug-сборки)); параметр запуска `server` ведёт куда угодно. Версия и commit сборки — мелко внизу экрана входа и профиля.

## Команды

| Команда | Что делает |
|---|---|
| `docker compose -f deploy/compose.dev.yaml up -d` | PostgreSQL для локального сервера |
| `./gradlew :server:bootRun` | Сервер на `:8080` (коды из писем — в его логе) |
| `./gradlew :androidApp:installDebug` | Собрать и поставить debug-сборку Android |
| `./gradlew :androidApp:assemblePreview` | Тестовая Android-сборка (release-код, `app.hovanki.preview`); подписывается, если заданы переменные `ANDROID_KEYSTORE_*` ([CI/CD](docs/ci-cd.md#секреты-для-подписи-android)) |
| `./gradlew check` | Все тесты и проверки, кроме e2e-сценариев (то же, что в CI на Linux) |
| `./gradlew :shared:jvmTest` | Быстрые тесты общего кода |
| `./gradlew :clientCore:jvmTest` | Тесты клиентской логики (API, синхронизация, `ServerClock`) на JVM |
| `./gradlew :radar:jvmTest :device:jvmTest` | Тесты радара и телефона (кодеки каналов, бюджет рекламы, правила ОС и симулятор эфира, классификатор движения, признаки кармана, границы модулей) на JVM |
| `./gradlew :server:test` | Тесты сервера |
| `./gradlew :e2e:test` | End-to-end сценарии: боты играют целые партии против сервера (~6 мин), отчёты — `e2e/build/reports/e2e/`. Только явно: в `check` и CI на push не входят, идут ночью |
| `./gradlew :e2e:test -Pe2e.slow=true` | То же вместе с долгими сценариями (тег `slow`: партия с правилами по умолчанию, большая игра на 300 ботов), как ночью; `--tests '*BigGameLoadTest' -Pe2e.bigGamePlayers=1600` — нагрузочный тест большой игры на 1 600 ботов |
| `./gradlew :e2e:devices` | Приложение на уже запущенных эмуляторах (из Android Studio) вместе с ботами: ставит debug-сборку, поднимает сервер, UI через Maestro; `-Pe2e.scenario=restart\|all`. То же — конфигурации «E2E emulators» в Android Studio. Пошагово — [docs/e2e-local.md](docs/e2e-local.md) |
| `e2e/run-devices.sh --android 2 --bots 3` | Приложение на двух эмуляторах вместе с ботами, UI через Maestro (`--ios 1` — симулятор на Mac); отчёт — `e2e/build/reports/devices/`. Подробности — [docs/e2e.md](docs/e2e.md) |
| `./gradlew :e2e:route --args="--to 50.4481,30.5402 --adb emulator-5554"` | Провести эмулятор (`--simctl <udid>` — симулятор) по маршруту пешком |
| `./gradlew :e2e:lab --args="merge A.jsonl mac.jsonl --out e2e/build/lab/merged"` | Свести журналы лаборатории радио с нескольких устройств на одну шкалу (`docs/radio-lab.md`) |
| `./gradlew :shared:iosSimulatorArm64Test` | Тесты общего кода на iOS-симуляторе (только macOS); то же для `:device` и `:clientCore` |
| `./gradlew spotlessApply` | Отформатировать код (ktlint); `spotlessCheck` — проверка в CI |
| `./gradlew :server:bootJar` | Jar сервера для Docker: `server/build/libs/hovanki-server.jar` |

## Документация

- [Архитектура](docs/architecture.md): модули, поток данных раунда, видимость, время, фазы, находка, API, рецепты.
- [E2E-тесты](docs/e2e.md): боты, приложение на эмуляторах и симуляторах, как написать сценарий и читать отчёт.
- [Локальный запуск e2e](docs/e2e-local.md): пошагово из Android Studio и терминала — подготовка эмуляторов и Maestro, параметры, отчёты, частые проблемы.
- [CI/CD](docs/ci-cd.md): как поставить сборку на телефон (Android pre-release, TestFlight, Xcode по кабелю, туннель к своему серверу), быстрые проверки на push, ночные e2e, что запускать перед PR, тестовые сборки, релиз по тегу, секреты подписи, образ сервера, защита веток.
- [Деплой сервера](docs/deploy.md): одна машина в AWS (EC2, Франкфурт), Docker Compose, Caddy с Let's Encrypt, база в Amazon RDS, почта, обновление и откат, бэкапы и восстановление базы.
- [Roadmap](docs/roadmap.md): что уже сделано и что дальше.
- [Дневник разработки](docs/devlog.md): что и когда сделано, простыми словами — для маркетинга, с подсказками, что и как заснять.
- [ADR 0001: выбор стека](docs/adr/0001-stack.md).
- [ADR 0002: сессия на устройстве, возврат в игру после перезапуска](docs/adr/0002-session-storage.md).
- [ADR 0003: карта и здания как запретная зона](docs/adr/0003-map-and-buildings.md).
- [ADR 0004: аккаунты, друзья, группы и чат](docs/adr/0004-accounts-friends-chat.md).
- [ADR 0012: радар по Bluetooth и UWB, возможности телефонов, флаги сервера](docs/adr/0012-nearby-radar.md) и [ADR 0013: задания, искры, перки и датчики](docs/adr/0013-quests-sparks-and-sensors.md) — за флагами, спайк на телефонах впереди.
- [CLAUDE.md](CLAUDE.md): правила для AI-агентов (и людей) при работе с кодом.
