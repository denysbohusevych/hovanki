# Полевой тест: план реализации

План для сессий, которые будут делать [ADR 0018](adr/0018-field-test-build.md): полевая сборка (`preview`) на staging-сервере, журнал лабы в настоящей игре, тени, отчёт и выгрузки для AI, Sentry, раздача. Зачем — в ADR, здесь — «как». Шаги 4 и 5 — это шаги 3 и 4 [radar-run.md](radar-run.md): там их содержание, здесь только то, что добавляет поле.

Статус: план, ни один шаг не сделан. Сделанный шаг отмечается здесь ✅, а в ADR 0018 появляется раздел «Отличия реализации».

## 0. Правила для каждой сессии

**Прочитать сначала:** `CLAUDE.md`, ADR 0018 целиком, ADR 0017 §4 и §7, [radar-run.md](radar-run.md) §0 и §1 («Как сделано»), [radio-lab.md](radio-lab.md) §4 (схема журнала). Код: `clientCore/.../lab/` (`LabLog`, `LabUploader`, `LabApi`), `server/.../lab/`, `shared/.../lab/` (`LabMerge`, `LabReportBuilder`, `LabSchema`), `server/.../game/Game.kt` (`Pokes`), `server/.../history/HistoryWriter.kt`.

**Как работать:**

- Один шаг — один PR в `main` (шаг можно делить, не склеивать).
- Поведение игры не меняется. Всё новое работает только при `FIELD_LOG` на сервере и в сборке `preview`, а тени только пишут в журнал.
- Перед PR: `./gradlew spotlessApply check`. Если менялись протокол или сервер, ещё `./gradlew :e2e:test`. В PR написать, что запускал.
- iOS пишется вслепую. В конце PR — список того, что владелец должен собрать и проверить на Маке.
- После шага обновить: таблицу API в `architecture.md`, `CLAUDE.md` → «Layout», этот документ (✅), ADR 0018 («Отличия реализации»).

**Правила, которые легко нарушить:**

- **Координаты** — только в полевом прогоне (`kind = GAME`) и только после согласия. В лабе их по-прежнему нет. В логах сервера, Sentry, ответах игры и `digest.jsonl` координат нет.
- **Release не меняется:** ни журнала, ни согласия, ни Sentry, ни экрана лабы. Проверка — `BuildInfo.channel`, а не `isDebug`.
- **Горячий путь `sync`** базу не трогает: события сервера пишутся после лока, отдельным потоком.
- **Админка:** `Staff`, роль admin, причина, `AuditLog` в той же транзакции, `textContent`.
- **Миграции** только добавляются, время — из `Clock`. Личные данные — `ON DELETE CASCADE` от `users`.

## 1. Шаг 1 — staging, лимит игроков, метрики

- **`deploy/`:**
  - второй набор: `.env.staging.example`, `deploy/staging.md` или раздел в [deploy.md](deploy.md) (EC2 t3.small, DuckDNS-имя, база `hovanki_staging` и своя роль на том же RDS, как выключить автообновление на день теста);
  - `compose.yaml` тот же, Spring-профиль `staging` через `.env`.
- **Сервер:**
  - `Game.MAX_PLAYERS` → свойство `hovanki.game.max-players` (по умолчанию 30), `Game` получает его параметром. Тест в `GameTest`: 31-й игрок входит при 60 и не входит при 30;
  - Micrometer: `/actuator/prometheus` на порту управления, закрыт от Caddy;
  - таймеры `sync` и остальных маршрутов, счётчики 5xx/429, gauge игр, игроков и сокетов из `GameRegistry` и `GameSockets`;
  - JSON-логи (Spring structured logging `logging.structured.format.console: ecs` или logstash).
- **`preview.yml`:** `-Phovanki.serverUrl=${{ vars.STAGING_SERVER_URL }}` для Android и iOS. Если переменной нет — прод и предупреждение в сводке.
- **`BuildConstants.CHANNEL`** (`debug` / `preview` / `release`) и `BuildInfo.channel`. Метка сборки показывает `β staging`.
- **Тесты:** `GameTest` (лимит), `DebugEndpointAbsentTest` остаётся зелёным, `/actuator/prometheus` не отдаётся наружу (тест конфигурации).

**Готово, когда:** staging поднят по инструкции, `preview` на телефоне ходит туда, в игру входят 31+ бот (`:e2e` с `HOVANKI_E2E_SERVER_URL`).

## 2. Шаг 2 — журнал лабы в игре

- **`:shared`:**
  - `protocol/Lab.kt`: `FieldJoinRequest/Response` (модель, ОС, сборка, commit, возможности, `consentAt`), `ApiRoutes.GAME_FIELD_JOIN`;
  - `LabSchema`: `gps` с `lat`/`lon` (необязательные), новые виды `sync`, `ui`, `perm`, `err`, `mark`, `survey`, `srv`;
  - `ServerFeature.FIELD_LOG` (строка в `admin.js` → `FEATURES`).
- **Сервер:**
  - миграция `V12__field_log.sql`: `lab_runs.kind` (`LAB` по умолчанию / `GAME`), `lab_runs.game_id`, `lab_devices.user_id` (`REFERENCES users ON DELETE CASCADE`), `lab_devices.consent_at`;
  - `LabRunService`: открыть полевой прогон при старте игры (флаг включён), вход по токену игры, пределы `kind = GAME` (`hovanki.field.max-devices: 100`, `max-run-bytes: 2GB`), закрыть при удалении игры;
  - `DataRetention`: 90 дней, как у лабы;
  - маршрут `POST /api/v1/games/{gameId}/field/join` в `GameController` (bearer игрока).
- **Телефон:**
  - `LabLog(isEnabled = …)` включается и в `preview`, когда игрок согласился и сервер ответил на `field/join`;
  - `FieldSession` в `clientCore/lab/` входит при старте игры, выходит в конце, прореживает (`rx` раз в секунду на пира, `frame`/`air` раз в 10 с);
  - точки `LocationProvider` → `gps` с координатами, `GameConnection` → `sync`, `GameSessionManager` → `ui`/`err`, разрешения → `perm`;
  - `LabUploader` тот же, интервал 10 с.
- **Экран согласия** (`ui/field/`) при первом запуске `preview`, текст из ADR 0018 §3.4 (en/uk/ru, типографские кавычки); `ClientStorage.fieldConsentAt`.
- **«Что-то не так»:** встряхивание (акселерометр из `:device`) и пункт в меню игры → `mark` и поле для пары слов.
- **Три вопроса после игры** на экране итогов → `survey`.
- **Экран лабы у сотрудников:** `AccountProfile.labAccess` (умолчание `false`), `DiagnosticsPanel`/Lab показывается при `isDebug || labAccess`; `LabController.join` в `preview` требует токен аккаунта сотрудника.
- **Тесты:** `LabApiTest` (вход по токену игры, флаг выключен → 404, чужой токен, пределы `GAME`), `commonTest` (прореживание, схема с координатами, согласие), `DataRetention` и `ON DELETE CASCADE` аккаунта.
- **e2e:** `FieldLogTest` — 6 ботов играют партию с журналом, пачки на сервере, координаты есть только в полевом прогоне, `SnapshotAudit` зелёный.

**Готово, когда:** бот-партия на staging оставляет журналы всех игроков, а «Что-то не так» с телефона видно в сырых пачках.

## 3. Шаг 3 — события сервера и правила в тени

- **`Game` копит `FieldEvent`**, как `Pokes`:
  - фазы;
  - заявки и исход, отказы `TOO_FAR` / `NO_LOCATION` / `NOT_NEARBY` с расстоянием;
  - раскрытия с причиной;
  - отброшенные точки;
  - свечения;
  - смены полос по парам.
- **`GameService.update`** после лока отдаёт их в `FieldEventWriter` (очередь и один поток, как `HistoryWriter`), он пишет пачками от устройства `server`.
- **Раз в 10 с** `srv` в каждую полевую игру: метрики шага 1.
- **Правила в тени:**
  - при каждой заявке — что сказала бы `ProximityCatch` (`wouldAccept`, пара, секунд «горит» за окно);
  - при каждой полосе — полоса с поправкой `POCKET_STEALTH`.

  Функции уже есть в `Game`/`:shared`, их нужно вызвать без применения.
- **Тесты:** `GameTest` (события копятся и не мешают игре; тень не меняет исход заявки), тест писателя (очередь переполнена — событие теряется со счётчиком, игра не ждёт).

## 4. Шаг 4 — каналы (radar-run.md, шаг 3)

Всё из [radar-run.md §3](radar-run.md#3-шаг-3--каналы-и-хосты), плюс для поля:

- **Починка рекламы Android-прячущегося** (не больше 31 байта) — до выхода на улицу, это баг игры.
- **Раскладка на Android в игре** — от номера игрока по кругу (`.scan_response` / `.bare` / `.mfr`). Слушатели разбирают все три. Раскладка пишется в `adv`.
- **`ble.overflow` в тени:** разбор масок у Android и iPhone на экране → `shadow` с жетоном и RSSI; в `ProximityRadio` игры не идёт.
- **`ble.ibeacon.region`:** вход и выход → журнал.

## 5. Шаг 5 — чоканье и тени (radar-run.md, шаг 4)

Всё из [radar-run.md §4](radar-run.md#4-шаг-4--чоканье-тени-карточки), плюс для поля:

- **Карточка в лобби** игры с радаром (только `preview` с журналом): «Чокнись с соседом» — выбрать игрока, коснуться, «Чокнулись» у обоих → `touch` с истиной. После игры на экране итогов — ещё раз.
- **`carry.v2` пишет `shadow`** в игре. Вопрос «где был телефон» — в `survey`.

## 6. Шаг 6 — отчёт, админка, выгрузки

- **`shared/.../lab/FieldReportBuilder`** (рядом с `LabReportBuilder`, тот же `LabMerge`): разделы ADR 0018 §6; детекторы аномалий — отдельные чистые функции с тестами на придуманных журналах.
- **`LabReportWriter`** считает и `GAME`: понемногу во время игры для живого вида, целиком после конца. Большие игры — потоково, по окнам в 10 минут, чтобы не держать всё в heap.
- **Маршруты админа:** `GET /api/v1/admin/field/games`, `/{runId}`, `/{runId}/report.md`, `/{runId}/digest.jsonl`, `/{runId}/raw.zip?device=&from=&to=` (с причиной), `DELETE` (с причиной). В `digest.jsonl` ники заменены на `P1…Pn`, координат нет.
- **Вкладка «Полевые тесты»** в `static/admin/`: список, живой вид (устройства: последняя пачка, батарея, фон, `sync`; кнопка «Отметка» → `mark` устройства `staff`), отчёт, выгрузки.
- **Тесты:** `FieldReportBuilderTest`, тест маршрутов админа (moderator → отказ, причина обязательна, `AuditLog`), `FieldLogTest` проверяет отчёт и выгрузки.

## 7. Шаг 7 — Sentry

- **Где:** `sentry-kotlin-multiplatform` в `libs.versions.toml` и `:composeApp`. Инициализация — только при `BuildConstants.CHANNEL == preview` и непустом `hovanki.sentryDsn`.
- **Фильтр:** `beforeSend`/`beforeBreadcrumb` вырезают координаты, токены, email, ники и текст чата. Тест фильтра — в `commonTest`, на придуманных событиях.
- **Связь с журналом:** id события → `err` в журнал.
- **iOS:** Sentry Cocoa через SPM в `iosApp` (владелец), вызов из Kotlin — как у остальных мостов.
- **Сервер:** `sentry-spring-boot` только с профилем `staging`.
- **`preview.yml`:** DSN из секрета, загрузка mapping (R8) и dSYM.

## 8. Шаг 8 — раздача

- **Android:**
  - владелец: аккаунт Play Console, приложение `app.hovanki.preview`, первая загрузка AAB руками, декларации геолокации и foreground service, service account;
  - сессия: job в `preview.yml` — `bundlePreview` и загрузка в `internal` (`r0adkll/upload-google-play`), без секрета — пропуск с сообщением.
- **iOS:**
  - владелец: внешняя группа TestFlight с публичной ссылкой, демо-аккаунт на staging для Beta App Review, «What to Test»;
  - `release.yml` — iOS-архив с адресом прода для App Store.
- **[ci-cd.md](ci-cd.md):** инструкция тестеру — как поставить на iPhone и Android, как дать разрешения, что делать, если не пускает.

## 9. Шаг 9 — репетиция и день теста

- **Нагрузка:** `FieldLoadTest` (тег `slow`) — 50 ботов с журналом и радаром 30 минут на сервере с 1 ГБ heap → байт в час на игрока, CPU, heap отчёта. Цифры → ADR 0018 (объёмы) и пределы.
- **Устройства:** ночной `devices` на сборке `preview`.
- **Организаторы:** прогон лабы на трёх своих телефонах через `preview`, на staging.
- **Чек-лист дня** (в этот документ):
  - за 3 дня: сборка в TestFlight и Play Internal, приглашения;
  - накануне: staging на t3.medium, автообновление выключено, флаги (`FIELD_LOG`, `RADIO_LAB`, `LIVE_SOCKET`, `RADAR`, `ACTIVITY`);
  - на месте: согласие и разрешения у всех, заряд ≥ 60 %, энергосбережение выключено, чоканье в лобби;
  - после игры: вопросы, второе чоканье, отчёт, выгрузка для AI;
  - в конце дня: staging обратно на t3.small.

## Что нужно от владельца

- AWS: вторая EC2 и база на RDS (по инструкции шага 1), DuckDNS-имя.
- Play Console ($25), приложение `app.hovanki.preview`, service account.
- App Store Connect: внешняя группа TestFlight, демо-аккаунт, подача на Beta App Review за 2–3 дня до теста.
- Sentry: организация в регионе EU, два проекта (приложение и сервер), DSN в секреты.
- Сборки iOS после шагов 2, 4, 5 и 7; Sentry Cocoa через SPM.
