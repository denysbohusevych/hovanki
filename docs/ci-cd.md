# CI/CD

| Файл | Когда запускается | Что делает |
|---|---|---|
| `.github/workflows/ci.yml` | push в любую ветку (кроме изменений только в `*.md` и `docs/`), PR из форков, вручную | Проверки: форматирование, тесты, сборка Android и iOS |
| `.github/workflows/e2e-devices.yml` | каждую ночь, вручную | Медленный e2e-слой: приложение на двух Android-эмуляторах и iOS-симуляторе вместе с ботами ([e2e.md](e2e.md)) |
| `.github/workflows/preview.yml` | push в `main` (кроме изменений только в `*.md` и `docs/`), вручную | Тестовые сборки для телефонов: подписанный APK на pre-release `preview` и сборка iOS в TestFlight ([как поставить](#как-поставить-сборку-на-телефон)) |
| `.github/workflows/release.yml` | push тега `v*`, вручную | Подписанный Android-релиз (APK + AAB), Docker-образ сервера, GitHub Release |
| `.github/dependabot.yml` | раз в неделю | PR с обновлениями Gradle-зависимостей и GitHub Actions |

## Как поставить сборку на телефон

Путь для игры с друзьями, пока у сервера нет хостинга: сервер работает на вашем компьютере и открыт наружу через HTTPS-туннель, Android-сборка ставится из GitHub, iPhone — через TestFlight или из Xcode по кабелю.

| Что | Откуда | Обновления |
|---|---|---|
| Сервер | `./gradlew :server:bootRun` на компьютере + [Cloudflare Quick Tunnel](#сервер-через-туннель) | новый запуск туннеля — новый адрес |
| Android | pre-release [`preview`](https://github.com/denysbohusevych/hovanki/releases/tag/preview), файл `hovanki-preview.apk` | каждый push в `main`; ставить вручную или через Obtainium |
| iPhone | TestFlight, когда подтверждён Apple Developer Program | каждый push в `main`; TestFlight предлагает обновить |
| iPhone до подтверждения | Xcode по кабелю, бесплатный Apple ID | сборка работает 7 дней |

Версия, номер сборки и commit видны мелко внизу главного экрана, например `0.1.0-preview (42) · 1a2b3c4`: по ним отзыв привязывается к сборке. Номер сборки — номер запуска `preview.yml`, у Android и iOS из одного запуска он общий.

### Сервер через туннель

[Cloudflare Quick Tunnel](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/) выдаёт случайный адрес `https://<слова>.trycloudflare.com` и пробрасывает его на порт компьютера. Без аккаунта, домена и денег, с настоящим HTTPS-сертификатом — поэтому с ним работают тестовые сборки, которые ходят только по HTTPS. Телефоны подключаются откуда угодно, в том числе через мобильный интернет.

1. Установить `cloudflared` (один раз):
   - macOS: `brew install cloudflared`;
   - Windows: `winget install --id Cloudflare.cloudflared`;
   - Linux: `.deb` или бинарник со страницы [релизов cloudflared](https://github.com/cloudflare/cloudflared/releases/latest).
2. Запустить сервер и туннель в двух терминалах:

   ```bash
   ./gradlew :server:bootRun                        # сервер на :8080 (без профиля e2e)
   cloudflared tunnel --url http://localhost:8080   # печатает https://<слова>.trycloudflare.com
   ```

   На macOS, чтобы компьютер не уснул посреди игры: `caffeinate -i cloudflared tunnel --url http://localhost:8080`.
3. Подождать полминуты: DNS-запись нового адреса появляется не сразу. Потом проверить с телефона: открыть в браузере `https://<слова>.trycloudflare.com/actuator/health` — ответ `{"status":"UP",...}`. Если открыть адрес слишком рано, устройство может ещё несколько минут «помнить», что такого адреса нет (так было в проверке на macOS); помогает подождать или включить и выключить авиарежим.
4. Разослать адрес игрокам. В приложении: главный экран → внизу поле «Адрес сервера» → вставить адрес. `https://` можно не писать, приложение добавит его само.

Что важно знать:

- **Адрес меняется при каждом запуске `cloudflared`.** Перезапустили туннель — все вводят новый адрес. Приложение пока не запоминает адрес между запусками ([roadmap](roadmap.md)).
- **Компьютер нужен всю игру**: сон, закрытая крышка ноутбука или обрыв сети — и телефоны видят «Нет связи с сервером». Перезапуск сервера обрывает все игры, они хранятся в памяти.
- **Не открывайте туннелем сервер с профилем `e2e`**: его отладочный эндпоинт отдаёт позиции всех игроков любому, кто знает адрес. `./gradlew :server:bootRun` запускает сервер без этого профиля.
- Трафик идёт через Cloudflare (TLS заканчивается у них), то есть координаты игроков проходят через их серверы. Для теста с друзьями это приемлемо, для публичного запуска нужен свой хостинг.
- Quick Tunnel — для тестов: без гарантий доступности, до 200 одновременных запросов (игре с опросом раз в 3 секунды хватает с запасом).
- Quick Tunnel не запускается, если есть `~/.cloudflared/config.yml` (или `.yaml`) от настроенного именованного туннеля: временно переименуйте файл.
- Debug-сборки по-прежнему ходят на компьютер напрямую по HTTP в той же Wi-Fi сети ([README](../README.md#быстрый-старт)). Тестовым (preview, TestFlight) нужен HTTPS, то есть туннель или хостинг.

### Адрес сервера по умолчанию

Тестовые и релизные сборки стартуют с адресом из Gradle-свойства `hovanki.serverUrl`. Пока его нет, поле пустое и адрес вводят руками. Когда появится хостинг — одна строка в `gradle.properties`:

```properties
hovanki.serverUrl=https://hovanki.example.org
```

- Действует на Android и iOS, локально и в CI: при сборке значение попадает в сгенерированный `BuildConstants` модуля `:composeApp` (задача `generateBuildConstants`).
- Только `https://`, иначе сборка падает с понятной ошибкой: вне debug Android не пускает HTTP, а iOS закрывает его App Transport Security.
- Debug-сборки это свойство не используют: они стартуют с компьютера разработчика (`http://10.0.2.2:8080` в эмуляторе, `http://localhost:8080` в симуляторе).
- Ручной ввод на главном экране остаётся и заменяет адрес по умолчанию до перезапуска приложения.
- Для одной сборки: `./gradlew :androidApp:assemblePreview -Phovanki.serverUrl=https://…`.

### Android: pre-release `preview`

Каждый push в `main` собирает сборку `preview` и заменяет ею pre-release [`preview`](https://github.com/denysbohusevych/hovanki/releases/tag/preview). Ссылка на свежий APK постоянная:
<https://github.com/denysbohusevych/hovanki/releases/download/preview/hovanki-preview.apk>

`preview` — это release-сборка (R8, без debug-хуков `LaunchOptions` и e2e, только HTTPS) с отдельным application id `app.hovanki.preview` и названием «Hovanki β», см. [Тестовые сборки](#тестовые-сборки-previewyml).

**Первая установка** (Android 8+):

1. Репозиторий приватный, поэтому файл скачивается только из браузера, где вы вошли в GitHub. Открыть ссылку выше на телефоне (или страницу релиза → Assets → `hovanki-preview.apk`).
2. Открыть скачанный файл. Android попросит разрешить установку из этого браузера: «Настройки» → «Разрешить установку из этого источника» → назад → «Установить».
3. Google Play Защита может предупредить о неизвестном приложении: «Подробнее» → «Всё равно установить» (отправить на проверку — тоже можно).
4. При создании игры или входе в неё разрешить точное местоположение («При использовании приложения») и уведомления: уведомление держит отправку координат при выключенном экране.

**Обновление**: скачать и открыть свежий APK — он встаёт поверх, данные сохраняются, потому что ключ подписи тот же. Если Android пишет, что приложение не установлено из-за конфликта пакетов, сборка подписана другим ключом (например, сменился keystore): удалить старую и поставить заново.

**Автообновление через [Obtainium](https://github.com/ImranR98/Obtainium)** — он сам проверяет GitHub и ставит новые сборки:

1. Установить Obtainium (APK со страницы его релизов, F-Droid или IzzyOnDroid).
2. Репозиторий приватный, поэтому Obtainium'у нужен токен: GitHub → Settings → Developer settings → Fine-grained personal access tokens → Generate new token, доступ только к репозиторию `hovanki`, Permissions → Contents: Read-only. В Obtainium: Настройки → GitHub → Personal Access Token. Для публичного репозитория токен не нужен.
3. «Добавить приложение» → URL `https://github.com/denysbohusevych/hovanki`, до добавления включить:
   - **Include prereleases** — `preview` помечен как pre-release;
   - **Filter release titles by regular expression**: `^Preview` — чтобы не взять APK из релизов `v*` (там другое приложение, `app.hovanki`);
   - версию **по дате релиза** («Use release date as version» / pseudo-version; название пункта зависит от версии Obtainium): тег у всех сборок один (`preview`), а дата меняется с каждой.
4. Obtainium покажет обновление после следующего push в `main`.

**Друзьям**: ссылки на приватный репозиторий работают только у тех, у кого есть к нему доступ (collaborator личного репозитория получает право записи). Варианты: переслать APK файлом — ставится так же, обновления тем же путём; или сделать репозиторий публичным — тогда подойдут и ссылка, и Obtainium без токена.

### iPhone: TestFlight

После настройки из раздела [iOS: TestFlight](#ios-testflight) каждый push в `main` загружает сборку в TestFlight.

1. Поставить на iPhone приложение [TestFlight](https://apps.apple.com/app/testflight/id899247664) из App Store.
2. **Себе** (внутреннее тестирование, без проверки Apple): App Store Connect → приложение → TestFlight → Internal Testing → «+» → группа → добавить себя. Приглашение придёт на почту Apple ID, дальше — «Установить» в TestFlight. Новые сборки группа получает сама, когда App Store Connect их обработает (5–30 минут после job'а).
3. **Друзьям** (внешнее тестирование, до 10 000 человек, добавлять их в команду App Store Connect не нужно): TestFlight → External Testing → группа → Public Link или приглашения по e-mail. Первая сборка каждой версии (`MARKETING_VERSION`) проходит Beta App Review, обычно до суток. Заполнить Test Information: что тестировать и e-mail для отзывов; в заметках для ревьюера — зачем фоновая геолокация и какой адрес сервера ввести (туннель должен работать, пока идёт проверка). Следующие сборки той же версии обычно приходят без ревью.
4. Отзыв со скриншотом отправляется прямо из TestFlight; версия и commit — внизу главного экрана.

Сборка в TestFlight доступна 90 дней.

### iPhone по кабелю из Xcode (пока Apple Developer Program не подтверждён)

Нужны Mac с Xcode 26.4+ и JDK 21. Хватает бесплатного Apple ID (Personal Team), но такая сборка работает 7 дней, потом её ставят заново.

1. Xcode → Settings → Accounts → «+» → Apple ID. Появится команда «<Имя> (Personal Team)».
2. Узнать её Team ID: открыть `iosApp/iosApp.xcodeproj` → target `iosApp` → Signing & Capabilities → Team → выбрать Personal Team. Xcode запишет ID в проект: `git diff iosApp/iosApp.xcodeproj` покажет `DEVELOPMENT_TEAM = ABCDE12345;`. Скопировать ID, а изменение проекта откатить: `git checkout -- iosApp/iosApp.xcodeproj`.
3. Создать `iosApp/Configuration/Local.xcconfig` (он в `.gitignore`):

   ```
   TEAM_ID = ABCDE12345
   BUNDLE_ID = app.hovanki.ios.<ваше-имя>
   ```

   Свой `BUNDLE_ID` нужен, потому что бесплатная команда закрепляет bundle id за собой, а `app.hovanki.ios` должен остаться свободным для платного аккаунта и TestFlight.
4. Подключить iPhone кабелем, «Доверять этому компьютеру». Включить режим разработчика: Настройки → Конфиденциальность и безопасность → Режим разработчика (iOS 16+, с перезагрузкой). Если пункта нет, он появится после первой попытки запуска из Xcode.
5. Схема `iosApp` → выбрать свой iPhone → Run. Первая сборка долгая: Gradle собирает Kotlin-фреймворк.
6. При первом запуске iOS не откроет приложение от неизвестного разработчика: Настройки → Основные → VPN и управление устройством → «Apple Development: …» → Доверять.
7. Адрес сервера — туннель (`https://…trycloudflare.com`) или в той же Wi-Fi `http://<имя-мака>.local:8080`: Run собирает Debug, а Debug пускает HTTP в локальную сеть.

Когда платный аккаунт подтвердят, уберите `BUNDLE_ID` из `Local.xcconfig` и поставьте `TEAM_ID` платной команды — или пользуйтесь TestFlight.

## Тестовые сборки (`preview.yml`)

Push в `main` (кроме правок только в `*.md` и `docs/`) и ручной запуск (Actions → Preview → Run workflow). Новый запуск отменяет предыдущий. Номер сборки — `github.run_number` этого workflow: `versionCode` на Android и `CFBundleVersion` на iOS, у сборок из одного запуска он общий. Если переименовать файл workflow, счётчик начнётся заново, и новые сборки не встанут поверх старых — тогда нужно смещение в номере.

| Job | Раннер | Что делает |
|---|---|---|
| `Android preview APK` | `ubuntu-latest` | `./gradlew :androidApp:assemblePreview -Phovanki.versionCode=<номер>` с keystore из [секретов релиза](#секреты-для-подписи-android); проверка APK (`aapt2`): пакет и `versionCode`, не debuggable, без cleartext и debug deep link `hovanki://`; APK — артефакт `android-preview` (14 дней) |
| `Android pre-release` | `ubuntu-latest` | Только из `main` и только подписанный APK: удаляет и заново создаёт pre-release с тегом `preview` на текущем коммите; в описании — версия, commit, ссылка на запуск |
| `iOS TestFlight` | `macos-26` | Release-архив, проверка бандла, загрузка в TestFlight (только из `main`), см. [iOS: TestFlight](#ios-testflight) |

Сборка `preview` на Android:

- `initWith(release)`: R8, те же правила, тот же ключ. Debug-хуков нет: source set `preview` берёт no-op-двойники из `src/release`, deep link и cleartext-трафик объявлены только в debug-манифесте.
- `applicationId` `app.hovanki.preview`, `versionName` `<версия>-preview`, название «Hovanki β». Отдельный id нужен, чтобы тестовая сборка стояла рядом с будущим релизом из Google Play (Play переподписывает приложение своим ключом, и sideload-сборка поверх него не встанет) и чтобы номера сборок preview и релизов не пересекались.
- Без секретов подписи APK собирается неподписанным (на телефон такой не поставить), pre-release не трогается, а в логе и сводке запуска — список недостающих секретов. Job при этом зелёный.

Ручной запуск с другой ветки собирает всё, но ничего не публикует: pre-release и TestFlight обновляются только из `main`.

iOS job идёт около 12 минут на каждый push в `main` (без подписи; с подписью и загрузкой — немного дольше), а macOS-минуты приватного репозитория тарифицируются с множителем. Если это дорого — оставить ему только ручной запуск: `if: github.event_name == 'workflow_dispatch'` у job'а `ios`.

## CI (`ci.yml`)

Два параллельных job'а; новый push в ту же ветку отменяет предыдущий запуск.

**Lint, tests, Android** (`ubuntu-latest`, JDK 21):

1. `./gradlew spotlessCheck` — ktlint через Spotless. Локально исправляется `./gradlew spotlessApply`.
2. `./gradlew check --continue` — тесты и проверки всех модулей: `:shared` (JVM, Android), `:clientCore` (JVM), `:server`, Android-модули.
3. `./gradlew :e2e:test` — end-to-end сценарии: headless-боты играют партии против сервера (отдельный шаг; `check` запускается с `-x :e2e:test`). Отчёты сценариев всегда прикладываются артефактом `e2e-reports`.
4. Сборка debug-версии Android-приложения.
5. При падении отчёты `**/build/reports/` прикладываются к запуску как артефакт `test-reports` (7 дней).

**iOS** (`macos-26`, Xcode из образа раннера):

1. `./gradlew :shared:iosSimulatorArm64Test :clientCore:iosSimulatorArm64Test` — тесты общего кода и клиентской логики на iOS-симуляторе (Kotlin/Native).
2. `xcodebuild` приложения `iosApp` для симулятора, без подписи.

Кэш Kotlin/Native (`~/.konan`) сохраняется между запусками, ключ — хэш `gradle/libs.versions.toml`.

PR из той же репы проверяются push-запуском на тот же коммит (статусы привязаны к коммиту и видны в PR), поэтому отдельный `pull_request`-запуск делается только для форков.

## E2E на устройствах (`e2e-devices.yml`)

Каждую ночь в 02:17 UTC и вручную (Actions → E2E devices → Run workflow: сценарий `all` / `full-round` / `restart` и число ботов). На PR не запускается: прогон идёт 15–20 минут, а быстрый слой (`:e2e:test`) уже есть в `ci.yml`.

| Job | Раннер | Что делает |
|---|---|---|
| `Android emulators` | `ubuntu-latest` + KVM | `e2e/run-devices.sh --android 2`: два эмулятора, системный образ в кэше |
| `iOS simulator` | `macos-26` | `e2e/run-devices.sh --ios 1`: один симулятор, второй на раннере грузится слишком долго |

Отчёт со скриншотами и логами — артефакты `e2e-devices-android` и `e2e-devices-ios` (14 дней). Новый запуск на той же ветке отменяет предыдущий. Как читать отчёт — [e2e.md](e2e.md#отчёт).

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

Теми же секретами подписываются тестовые сборки `preview.yml`, поэтому каждая новая встаёт поверх предыдущей. Без секретов preview собирается неподписанным и не публикуется, а в логе запуска перечислены недостающие секреты.

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

Как поднять сервер из этого образа на машине в AWS с HTTPS — [deploy.md](deploy.md).

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

## iOS: TestFlight

Job `iOS TestFlight` в `preview.yml`: Release-архив → проверка бандла → `xcodebuild -exportArchive` с `destination: upload` → App Store Connect. Без `altool` и fastlane, только Xcode из образа раннера.

- **Bundle id постоянный**: `BUNDLE_ID = app.hovanki.ios` в `iosApp/Configuration/Config.xcconfig`, без `$(TEAM_ID)` в конце. Team ID CI берёт из provisioning profile; для локальной сборки — `TEAM_ID` в `Config.xcconfig` (его можно закоммитить, он не секрет) или в `Local.xcconfig`.
- **Версия** — `MARKETING_VERSION` в `Config.xcconfig` (`0.1.0`), **build number** — номер запуска `preview.yml`. Перед выпуском новой версии в App Store поднять `MARKETING_VERSION`. Релиз в App Store — это отправка на ревью уже загруженной сборки TestFlight, отдельный workflow не нужен.
- **Release без dev-исключений ATS**: в `Info.plist` нет `NSAllowsLocalNetworking` и `NSLocalNetworkUsageDescription`. Их добавляет только в Debug build phase «Debug: local network» (`iosApp/Configuration/debug-info-plist.sh`), а job проверяет, что в Release-бандле их нет.
- **Экспортное шифрование**: `ITSAppUsesNonExemptEncryption = NO` (только HTTPS) — App Store Connect не спрашивает про шифрование у каждой сборки.
- **Приватность**: `PrivacyInfo.xcprivacy` — точная геолокация и игровые данные (имя, заявки, голоса) собираются для работы приложения, не связаны с личностью, без трекинга; причины для required-reason API из Compose и Kotlin/Native. Тексты разрешений на en/uk/ru: английские — в `Info.plist`, переводы — `InfoPlist.xcstrings`, список языков — `CFBundleLocalizations` (без него iOS показала бы русские тексты телефону с языками «английский, русский»: Xcode не создаёт `en.lproj` для текстов из `Info.plist`). Анкету App Privacy в App Store Connect заполнить так же.
- **Иконка** `AppIcon-1024.png` — без неё App Store Connect сборку не примет.

### Подпись: ручные сертификаты, а не fastlane match

Сертификат Apple Distribution (`.p12`) и App Store provisioning profile лежат в секретах, job импортирует их во временный keychain и удаляет в конце.

- Разработчик один, приложение одно, подписывает только CI. Задача match — раздавать одни сертификаты многим разработчикам и машинам — здесь не возникает.
- Не нужны отдельный приватный репозиторий для сертификатов, доступ к нему, `MATCH_PASSWORD`, Ruby и fastlane на раннере: меньше движущихся частей и секретов, всё видно в одном шаге workflow.
- Цена — раз в год пересоздать сертификат и профиль и обновить три секрета. С match продление тоже ручное.
- Если появятся другие разработчики, перейти на match просто: поменяется только шаг «Install certificate and provisioning profile».

### Что сделать в Apple Developer и App Store Connect

1. **Apple Developer Program** (Individual): [developer.apple.com/programs/enroll](https://developer.apple.com/programs/enroll/), 99 $ в год, дождаться подтверждения. Team ID — developer.apple.com → Account → Membership details.
2. **App ID**: developer.apple.com → Certificates, IDs & Profiles → Identifiers → «+» → App IDs → App → Description `Hovanki`, Bundle ID Explicit `app.hovanki.ios`. Capabilities не нужны: фоновая геолокация — это `UIBackgroundModes` в `Info.plist`. Если id занят, выбрать другой и поменять `BUNDLE_ID` в `Config.xcconfig` (и `app.hovanki.ios` в `e2e/run-devices.sh`).
3. **Сертификат Apple Distribution** (на Mac): Xcode → Settings → Accounts → команда → Manage Certificates → «+» → Apple Distribution. Экспорт: «Связка ключей» → login → «Мои сертификаты» → «Apple Distribution: <имя> (<Team ID>)» → Экспортировать → формат `.p12`, задать пароль.
4. **Provisioning profile**: Certificates, IDs & Profiles → Profiles → «+» → Distribution → App Store Connect → App ID `app.hovanki.ios` → сертификат из п. 3 → имя, например `Hovanki App Store` → Generate → Download (`.mobileprovision`).
5. **Приложение в App Store Connect**: [appstoreconnect.apple.com](https://appstoreconnect.apple.com) → Apps → «+» → New App: iOS, имя (уникальное в App Store; если «Hovanki» занято — например «Hovanki: street hide & seek»), основной язык, Bundle ID `app.hovanki.ios`, SKU `hovanki`.
6. **API key**: App Store Connect → Users and Access → Integrations → App Store Connect API → Team Keys → «+» (в первый раз — Request Access, подтверждает Account Holder, то есть вы) → имя `GitHub Actions`, доступ `App Manager` → Generate. Скачать `.p8` — его дают скачать только один раз. Записать Key ID (в строке ключа) и Issuer ID (над таблицей).
7. **Секреты** (Settings → Secrets and variables → Actions → New repository secret) — таблица ниже. С Mac через GitHub CLI:

   ```bash
   base64 -i Hovanki.p12 | tr -d '\n' | gh secret set IOS_DISTRIBUTION_CERTIFICATE_BASE64
   gh secret set IOS_DISTRIBUTION_CERTIFICATE_PASSWORD        # пароль из п. 3
   base64 -i Hovanki_App_Store.mobileprovision | tr -d '\n' | gh secret set IOS_PROVISIONING_PROFILE_BASE64
   gh secret set APP_STORE_CONNECT_ISSUER_ID --body <Issuer ID>
   gh secret set APP_STORE_CONNECT_KEY_ID --body <Key ID>
   base64 -i AuthKey_<Key ID>.p8 | tr -d '\n' | gh secret set APP_STORE_CONNECT_KEY_BASE64
   ```

   Через веб-интерфейс: `base64 -i <файл> | tr -d '\n' | pbcopy` и вставить значение.
8. **Запуск**: Actions → Preview → Run workflow на `main` (или любой push в `main`). В сводке запуска появится «build N uploaded to TestFlight», через 5–30 минут сборка — в App Store Connect → TestFlight. Дальше — [iPhone: TestFlight](#iphone-testflight).
9. **Раз в год** истекают сертификат и профиль (и членство в программе): повторить п. 3–4 и обновить `IOS_DISTRIBUTION_CERTIFICATE_BASE64`, `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`, `IOS_PROVISIONING_PROFILE_BASE64`.

### Секреты iOS

| Секрет | Что это |
|---|---|
| `IOS_DISTRIBUTION_CERTIFICATE_BASE64` | Сертификат Apple Distribution с закрытым ключом, `.p12` в base64 одной строкой |
| `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD` | Пароль `.p12` |
| `IOS_PROVISIONING_PROFILE_BASE64` | App Store provisioning profile для `app.hovanki.ios`, `.mobileprovision` в base64 |
| `APP_STORE_CONNECT_ISSUER_ID` | Issuer ID ключей App Store Connect API |
| `APP_STORE_CONNECT_KEY_ID` | Key ID ключа |
| `APP_STORE_CONNECT_KEY_BASE64` | Файл ключа `AuthKey_<Key ID>.p8` в base64 |

Job проверяет профиль до сборки: тип App Store (без списка устройств) и bundle id приложения — при несовпадении он падает с понятным сообщением. Team ID берётся из профиля, отдельный секрет не нужен.

### Что проверяется без секретов

Пока секретов нет, job зелёный, а загрузку пропускает с сообщением в сводке запуска. Он при этом:

- собирает Release-архив для устройства без подписи: release-фреймворк Kotlin (без `LaunchOptions`), Swift, ресурсы;
- проверяет бандл: `CFBundleVersion` равен номеру запуска, в `Info.plist` нет `NSAppTransportSecurity` и `NSLocalNetworkUsageDescription`, есть `PrivacyInfo.xcprivacy`, `CFBundleLocalizations` и переводы текстов разрешений (`ru.lproj`, `uk.lproj`).

Подпись, экспорт и загрузка впервые выполнятся, когда появятся секреты; без них их не проверить.

### Google Play (позже)

Аккаунт разработчика, первая загрузка AAB вручную, затем service account JSON в секретах и загрузка из CI (fastlane supply или `r0adkll/upload-google-play`). В Play Console нужно заполнить декларации для фоновой геолокации и foreground service с типом `location`.

## Защита веток

Рекомендуемые настройки для `main` (Settings → Rules → Rulesets, или Branch protection rules):

- запрет прямого push: изменения только через PR;
- обязательные проверки: `Lint, tests, Android` и `iOS` (имена job'ов из `ci.yml`);
- запрет force push и удаления ветки;
- по желанию: требовать актуальность ветки перед merge и одно одобрение ревьюера.

Отдельным ruleset'ом для тегов `v*` стоит ограничить, кто может их создавать: тег запускает релиз.


## Dependabot

- **Gradle** (`/`, раз в неделю): группы `kotlin` (Kotlin, его плагины и KSP), `compose-android` (Compose Multiplatform, JetBrains androidx, Android Gradle plugins) и `minor-and-patch` (всё остальное, только minor/patch). Мажорные обновления вне первых двух групп приходят отдельными PR.
- **GitHub Actions** (раз в неделю): все обновления одним PR.
- Не больше 5 открытых PR на экосистему.

Обновляя Kotlin, сверяйтесь с [таблицей совместимости](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) Kotlin ↔ Gradle ↔ AGP ↔ Xcode (ссылка также в `gradle/libs.versions.toml`).
