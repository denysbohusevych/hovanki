# CI/CD

| Файл | Когда запускается | Что делает |
|---|---|---|
| `.github/workflows/ci.yml` | push в любую ветку, PR из форков, вручную | Быстрые проверки (~3 мин, iOS-job самый долгий): форматирование, тесты без e2e-сценариев, сборка Android и iOS |
| `.github/workflows/nightly.yml` | каждую ночь, вручную на любой ветке | Долгие проверки: e2e-боты (`./gradlew :e2e:test`) и приложение на двух Android-эмуляторах и iOS-симуляторе вместе с ботами ([e2e.md](e2e.md)) |
| `.github/workflows/release.yml` | push тега `v*`, вручную | Подписанный Android-релиз (APK + AAB), Docker-образ сервера, GitHub Release |
| `.github/dependabot.yml` | раз в неделю | PR с обновлениями Gradle-зависимостей и GitHub Actions |

Правило: push и PR проверяются быстро, всё долгое идёт ночью и по кнопке. Что запускать самому перед PR — [ниже](#что-запускать-перед-pr).

## CI (`ci.yml`)

Два параллельных job'а; новый push в ту же ветку отменяет предыдущий запуск.

**Lint, tests, Android** (`ubuntu-latest`, JDK 21):

1. `./gradlew spotlessCheck` — ktlint через Spotless. Локально исправляется `./gradlew spotlessApply`.
2. `./gradlew check --continue` — тесты и проверки всех модулей: `:shared` (JVM, Android), `:clientCore` (JVM), `:server`, Android-модули, юнит-тесты инструментов `:e2e` (`:e2e:unitTest`: маршруты, шум GPS, разбор дерева UI). E2e-сценарии (`:e2e:test`) в `check` не входят: они запускаются только явно и ночью.
3. Сборка debug- и release-версии Android-приложения, debug APK — артефакт `hovanki-debug-apk`.
4. При падении отчёты `**/build/reports/` прикладываются к запуску как артефакт `test-reports` (7 дней).

**iOS** (`macos-26`, Xcode из образа раннера):

1. `./gradlew :shared:iosSimulatorArm64Test :clientCore:iosSimulatorArm64Test` — тесты общего кода и клиентской логики на iOS-симуляторе (Kotlin/Native).
2. `xcodebuild` приложения `iosApp` для симулятора, без подписи.

Кэш Kotlin/Native (`~/.konan`) сохраняется между запусками, ключ — хэш `gradle/libs.versions.toml`.

PR из той же репы проверяются push-запуском на тот же коммит (статусы привязаны к коммиту и видны в PR), поэтому отдельный `pull_request`-запуск делается только для форков.

Фильтра по путям нет: CI идёт и на изменения только в `docs/`. Иначе обязательные проверки у такого PR навсегда остались бы в ожидании.

## Nightly (`nightly.yml`)

Долгие проверки: каждую ночь в 02:17 UTC и вручную на любой ветке.

| Job | Раннер | Время | Что делает |
|---|---|---|---|
| `E2E bots` | `ubuntu-latest` | ~5 мин | `./gradlew :e2e:test`: headless-боты играют партии против сервера ([e2e.md](e2e.md#быстрый-слой-боты)) |
| `Android emulators` | `ubuntu-latest` + KVM | ~15 мин | `e2e/run-devices.sh --android 2`: два эмулятора, системный образ в кэше |
| `iOS simulator` | `macos-26` | ~20 мин | `e2e/run-devices.sh --ios 1`: один симулятор, второй на раннере грузится слишком долго |
| `Nightly issue` | `ubuntu-latest` | секунды | только по расписанию: issue при падении, закрытие при зелёном прогоне (ниже) |

**Запуск вручную** — Actions → Nightly → Run workflow, выбрать ветку и:
- `suite`: `all` (по умолчанию), `bots` (только боты) или `devices` (только эмуляторы и симулятор);
- `scenario`: сценарий устройств — `all`, `full-round` или `restart`;
- `bots`: число ботов в партии на устройствах.

То же из командной строки: `gh workflow run nightly.yml --ref <ветка> -f suite=devices -f scenario=restart`. Workflow берётся из выбранной ветки, так что изменения в нём самом проверяются тем же запуском.

**Отчёты** (14 дней):
- `e2e-reports` — отчёты сценариев ботов (`e2e/build/reports/e2e/`), при падении ещё `e2e-test-reports` — HTML-отчёт JUnit;
- `e2e-devices-android`, `e2e-devices-ios` — отчёт устройств со скриншотами и логами, как читать — [e2e.md](e2e.md#отчёт).

Таймлайн упавшего сценария печатается и в лог job'а, так что причину видно без скачивания артефактов.

Новый ручной запуск на той же ветке отменяет предыдущий ручной; ночной запуск ручные не отменяют.

### Если ночной прогон упал

Job `Nightly issue` работает только для запусков по расписанию и обходится `GITHUB_TOKEN` (`issues: write`), без секретов:
- упал или был прерван (в том числе по таймауту) любой job — открывается issue «Nightly e2e failed» с меткой `nightly-failure`, ссылкой на запуск и таблицей результатов job'ов. Если такой issue уже открыт, в него добавляется комментарий, новых issue не плодим;
- следующий зелёный ночной прогон закрывает issue с комментарием.

В issue упоминается владелец репозитория (`@owner`), поэтому уведомление приходит ему при любых настройках Watch; остальным — если они следят за репозиторием (Watch → All Activity). Кроме того, о падении workflow по расписанию GitHub пишет тому, кто последним менял `cron` (Settings → Notifications → Actions).

Где смотреть вручную: Actions → Nightly, фильтр `event:schedule`; открытые issue с меткой `nightly-failure`.

Для публичного репозитория GitHub отключает workflow по расписанию после 60 дней без активности в репозитории — тогда Actions → Nightly → Enable workflow.

## Что запускать перед PR

CI на push не играет партии, поэтому перед PR их запускает автор изменения:

| Что меняется | Что запустить | Где |
|---|---|---|
| Любой код | `./gradlew spotlessApply` и `./gradlew check` (или быстрый цикл `./gradlew :shared:jvmTest :clientCore:jvmTest :server:test`) | локально |
| Правила игры, протокол, поведение клиент–сервер (`:shared`, `:server`, `:clientCore`, `:e2e`) | `./gradlew :e2e:test` (~3 мин, работает и в облачном контейнере без KVM) | локально |
| UI, платформенный код (`:composeApp`, `androidApp`, `iosApp`), Maestro-флоу, `run-devices.sh` | `./gradlew :e2e:devices` на своих эмуляторах ([e2e.md](e2e.md#из-android-studio-на-своих-эмуляторах)) или ночной workflow вручную на своей ветке: `suite=devices`, нужный сценарий | локально с Android Studio / GitHub Actions, 15–20 мин |

В описании PR — что из этого запускалось (чеклист в шаблоне PR).

## Релиз (`release.yml`)

### Как выпустить версию

Релизим с `main`, на коммите с зелёным CI (сам релизный workflow тесты не гоняет):

```bash
git checkout main && git pull
git tag v0.1.0
git push origin v0.1.0
```

- `versionName` = тег без `v` (`0.1.0`), `versionCode` = номер запуска workflow (`github.run_number`), он растёт с каждым запуском. Если переименовать файл workflow, счётчик начнётся заново — тогда в Gradle-сборке нужно добавить смещение, иначе Google Play не примет сборку с меньшим `versionCode`.
- Тег с дефисом (`v0.2.0-rc.1`) создаёт pre-release, образ получает только тег `0.2.0-rc.1`, `latest` не двигается.
- Ручной запуск (Actions → Release → Run workflow) собирает APK/AAB и образ (с тегом `sha-<коммит>`), но GitHub Release не создаёт — удобно проверить пайплайн.

### Job'ы

| Job | Что делает | Результат |
|---|---|---|
| `android` | Раскодирует keystore из секретов (если есть), `./gradlew :androidApp:assembleRelease :androidApp:bundleRelease -Phovanki.versionName=… -Phovanki.versionCode=…` | Артефакт `android-release`: `hovanki-<версия>-release.apk` и `.aab` |
| `server-image` | `./gradlew :server:bootJar` → `server/build/libs/hovanki-server.jar`, сборка `server/Dockerfile` и push в GHCR | `ghcr.io/denysbohusevych/hovanki-server` |
| `github-release` | Только для тега: GitHub Release с APK/AAB, автоматическими release notes и ссылкой на образ | Страница релиза |

Права у каждого job'а минимальные: `contents: read`, `packages: write` только у `server-image`, `contents: write` только у `github-release`. Параллельные запуски для одного тега не отменяются, а ждут.

### Секреты для подписи Android

Settings → Secrets and variables → Actions → New repository secret:

| Секрет | Что это |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Файл keystore в base64 одной строкой |
| `ANDROID_KEYSTORE_PASSWORD` | Пароль keystore |
| `ANDROID_KEY_ALIAS` | Алиас ключа |
| `ANDROID_KEY_PASSWORD` | Пароль ключа (для PKCS12 совпадает с паролем keystore) |

Workflow кладёт keystore в `$RUNNER_TEMP/release.jks` и выставляет переменные окружения `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Gradle-сборка `androidApp` подписывает release только если они заданы. Без секретов релиз соберётся неподписанным (`…-release-unsigned.apk`), в логе будет предупреждение.

Создать keystore (один раз, JDK `keytool`):

```bash
keytool -genkeypair -v \
  -keystore hovanki-release.jks -storetype PKCS12 \
  -alias hovanki -keyalg RSA -keysize 4096 -validity 10000

# Linux
base64 -w0 hovanki-release.jks > hovanki-release.jks.b64
# macOS
base64 -i hovanki-release.jks | tr -d '\n' > hovanki-release.jks.b64

# Через GitHub CLI вместо веб-интерфейса
gh secret set ANDROID_KEYSTORE_BASE64 < hovanki-release.jks.b64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body hovanki
gh secret set ANDROID_KEY_PASSWORD
```

- Keystore и пароли **никогда не коммитим** (`*.jks`, `*.keystore` уже в `.gitignore`). Храним копию в менеджере паролей команды: без ключа нельзя выпустить обновление.
- Для Google Play включаем Play App Signing: наш ключ становится ключом загрузки (upload key), при утере его можно перевыпустить через поддержку Google.
- Локальная подписанная сборка: выставить те же четыре переменные окружения и запустить `./gradlew :androidApp:assembleRelease`.

### Образ сервера

Образ публикуется в GitHub Container Registry: `ghcr.io/denysbohusevych/hovanki-server`.

| Тег образа | Откуда |
|---|---|
| `0.1.0`, `0.1` | тег `v0.1.0` |
| `latest` | последний релизный тег без суффикса pre-release |
| `sha-<коммит>` | каждая сборка, включая ручные |

```bash
docker run --rm -p 8080:8080 ghcr.io/denysbohusevych/hovanki-server:0.1.0
curl http://localhost:8080/actuator/health
```

- Проверьте видимость пакета после первой публикации: чтобы тянуть образ без логина, он должен быть публичным (Package settings → Change visibility). Для приватного — `docker login ghcr.io` с PAT со scope `read:packages`.
- Настройки — через переменные окружения: `PORT`, `HOVANKI_GAMES_FINISHED_RETENTION=15m`, `HOVANKI_GAMES_IDLE_RETENTION=2h` и т.п. (relaxed binding Spring Boot для `hovanki.games.*`).
- Пробы для оркестратора: `/actuator/health/liveness` и `/actuator/health/readiness`.
- Игры хранятся в памяти: запускаем **одну реплику**, рестарт/деплой обрывает идущие игры.
- Release-сборки приложений должны ходить по HTTPS (на Android cleartext разрешён только в debug, на iOS действует App Transport Security), поэтому в проде сервер ставится за reverse proxy с TLS (Caddy, Traefik, nginx) или за managed-балансировщик.
- Локальная сборка образа: `./gradlew :server:bootJar && docker build -t hovanki-server server`.

## iOS: TestFlight (следующий шаг)

Сейчас CI только собирает iOS-приложение без подписи. Для выкладки в TestFlight понадобится:

1. **Apple Developer Program** (платный аккаунт организации или личный), App ID и запись приложения в App Store Connect. Сейчас bundle id зависит от `TEAM_ID` разработчика (`app.hovanki.ios<TEAM_ID>` в `iosApp/Configuration/Config.xcconfig`) — для релиза нужен один постоянный bundle id и team.
2. **App Store Connect API key** (Users and Access → Integrations): Issuer ID, Key ID и файл `.p8` — в секреты (`APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_KEY_BASE64`). Ключ нужен для загрузки сборок без логина под Apple ID и 2FA.
3. **Подпись**, один из вариантов:
   - **fastlane match** — сертификаты и профили лежат зашифрованными в отдельном приватном репозитории; в секретах `MATCH_PASSWORD` и доступ к репозиторию (deploy key или токен). Удобно, когда разработчиков несколько.
   - **Вручную** — сертификат Apple Distribution (`.p12` + пароль) и provisioning profile в base64-секретах; в job'е они импортируются во временный keychain.
4. **Job на `macos-26`** в `release.yml`: `xcodebuild archive` → `xcodebuild -exportArchive` с `ExportOptions.plist` (method `app-store-connect`) → загрузка через `fastlane pilot` или `xcrun altool`. Build number — `github.run_number`, версия — из тега.
5. **Приватность**: `NSLocationWhenInUseUsageDescription`, `NSCameraUsageDescription` и `UIBackgroundModes: location` уже есть в `Info.plist`; нужны privacy manifest (`PrivacyInfo.xcprivacy`) и анкета App Privacy в App Store Connect (сбор точной геолокации). Ревью Apple проверяет обоснование фоновой геолокации. `NSLocalNetworkUsageDescription` и `NSAllowsLocalNetworking` нужны только для разработки — в релизной конфигурации их лучше убрать.

Аналогично для **Google Play**: аккаунт разработчика, первая загрузка AAB вручную, затем service account JSON в секретах и загрузка из CI (fastlane supply или `r0adkll/upload-google-play`). В Play Console нужно заполнить декларации для фоновой геолокации и foreground service с типом `location`.

## Защита веток

Рекомендуемые настройки для `main` (Settings → Rules → Rulesets, или Branch protection rules):

- запрет прямого push: изменения только через PR;
- обязательные проверки: `Lint, tests, Android` и `iOS` (имена job'ов из `ci.yml`). Job'ы `nightly.yml` обязательными не делаем: на PR они не запускаются;
- запрет force push и удаления ветки;
- по желанию: требовать актуальность ветки перед merge и одно одобрение ревьюера.

Отдельным ruleset'ом для тегов `v*` стоит ограничить, кто может их создавать: тег запускает релиз.


## Dependabot

- **Gradle** (`/`, раз в неделю): группы `kotlin` (Kotlin, его плагины и KSP), `compose-android` (Compose Multiplatform, JetBrains androidx, Android Gradle plugins) и `minor-and-patch` (всё остальное, только minor/patch). Мажорные обновления вне первых двух групп приходят отдельными PR.
- **GitHub Actions** (раз в неделю): все обновления одним PR.
- Не больше 5 открытых PR на экосистему.

Обновляя Kotlin, сверяйтесь с [таблицей совместимости](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) Kotlin ↔ Gradle ↔ AGP ↔ Xcode (ссылка также в `gradle/libs.versions.toml`).
