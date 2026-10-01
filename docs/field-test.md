# Полевой тест: план реализации

План для сессий, которые будут делать [ADR 0018](adr/0018-field-test-build.md): полевая сборка (`preview`) на staging-сервере, журнал лабы в настоящей игре, тени, отчёт и выгрузки для AI, Sentry, раздача. Зачем — в ADR, здесь — «как». Шаги 4 и 5 — это шаги 3 и 4 [radar-run.md](radar-run.md): там их содержание, здесь только то, что добавляет поле.

Статус: шаги 1, 2, 4, 7 и 8 сделаны (2026-10-01, одной волной из пяти веток), шаги 3 и 5 и дыры журнала шага 2 — второй волной (2026-10-01), шаги 6 и 9 впереди. Ничего из этого ещё не пробовали на настоящих телефонах и на поднятом staging. Сделанный шаг отмечается здесь ✅ со списками «Как сделано» и «Не проверено», а полный список отличий — в разделе «Отличия реализации» [ADR 0018](adr/0018-field-test-build.md#отличия-реализации).

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

## 1. Шаг 1 — staging, лимит игроков, метрики ✅ (2026-10-01)

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

**Как сделано:**

- **Лимит:** `hovanki.game.max-players` (`GameLimitsProperties`, допустимо 2–1 600, проверяется при старте), по умолчанию `Game.MAX_PLAYERS` = 30, на staging 60. Большие игры берут свой лимит.
- **Метрики:** `micrometer-registry-prometheus`. Gauge `hovanki.games`, `hovanki.players` (токены игроков в `GameRegistry`, без лока игры: это игроки игр в памяти, а не «онлайн»), `hovanki.sockets`. Таймер `hovanki.game.sync{transport=poll|socket}` вокруг `GameService.sync` (с ожиданием лока, без сети). У `http.server.requests` — p50/p95/p99. Отдельных счётчиков 5xx и 429 нет: это теги `outcome` и `status` того же таймера.
- **Профиль `staging`** (`application-staging.yaml`): 60 игроков, JSON-логи ECS, весь `/actuator` (health, restarthold, prometheus) на порту управления 8081, вход в прогон лабы только сотрудникам, полевой журнал разрешён (`hovanki.field.allowed`; без него, как на проде, `FIELD_LOG` не включается и не действует), лимиты по IP на 60 человек за одним адресом (часы сервера — 3 000 в минуту, вход, регистрация, сброс пароля). Caddyfile в репозитории нет (Caddy запускается командой `reverse-proxy --to server:8080`), поэтому метрики закрыты отдельным портом, а не путём: на порту игры `/actuator/*` отвечает 404. Прод не меняется.
- **`hovanki-update.sh`** спрашивает `restarthold` сначала на 8081, потом на 8080: один скрипт на обе машины.
- **deploy:** `compose.yaml` читает `DATABASE_NAME`, `DATABASE_USER`, `SPRING_PROFILES_ACTIVE`, `SENTRY_DSN`, `SERVER_MEM_LIMIT`, все с умолчаниями прода; `deploy/.env.staging.example`; раздел [«Staging»](deploy.md#staging) в deploy.md.
- **`preview.yml`:** `STAGING_SERVER_URL` (обрезается, только `https://`; без неё сборки только проверяются и никуда не публикуются), `-Phovanki.channel=preview`, DSN Sentry. В Kotlin-фреймворк iOS свойства попадают через `gradle.properties` домашней папки Gradle: Xcode сам запускает `./gradlew`, и `-P` до него не доходит. После сборки проверяется, что в приложении адрес staging и нет адреса прода (APK — в dex, iOS — в исполняемом файле, UTF-8 и UTF-16).
- **Канал сборки:** `BuildConstants.CHANNEL` из `hovanki.channel` (`release` по умолчанию или `preview`, иное — ошибка сборки), `BuildInfo.channel` (`debug` / `preview` / `release`, `isFieldBuild`), в подписи — «β staging».
- **Тесты:** `GameTest` (лимит), `PlayerLimitTest`, `StagingProfileTest` (настоящие порты, профиль `staging`: 61-й не входит, prometheus на порту управления, на порту игры 404, `DebugController` нет), `ProfileConfigTest` (yaml без контекста: у прода нет prometheus, staging не задаёт ничего из профиля `e2e`), `MetricsDefaultsTest`, `GameSocketTest` (тег транспорта), `BuildInfoTest`.

**Не проверено:** staging не поднят (здесь нет AWS и Docker), `compose`, Caddy и `hovanki-update.sh` на настоящей машине не запускались, SQL роли staging с `REVOKE CONNECT … FROM PUBLIC` на RDS не выполнялся, `preview.yml` на GitHub Actions не запускался. «Готово, когда» — после того как владелец поднимет staging.

## 2. Шаг 2 — журнал лабы в игре ✅ (2026-10-01)

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

**Как сделано:**

- **Протокол:** `ServerFeature.FIELD_LOG`, `LabRunKind` (`LAB` / `GAME`), `FieldJoinRequest`/`FieldJoinResponse` (с темпом прореживания), `ApiRoutes.GAME_FIELD_JOIN`, `UserProfile.labAccess`. Виды и поля журнала — `lab/FieldSchema.kt`; схема по-прежнему версии 2.
- **Сервер:** `FieldController` → `FieldRunService`, миграция `V12__field_log.sql`, `hovanki.field.*` (100 устройств с повторными входами, 2 ГБ, окно входа, 30 минут досылки, 90 дней).
  - Прогон игры открывает не старт игры, а **первый вход телефона**: игра, которую никто не пишет, ничего не стоит, а `GameService` базу не трогает. Закрывает его `GameJanitor` раз в минуту, когда игры нет в памяти, — только прогоны, в которые телефоны входили через этот процесс, и при первой чистке после старта прогоны, открытые до него (игры, потерянные при перезапуске). Другой процесс на той же базе чужие живые прогоны не закрывает.
  - Все журналы игр вместе — не больше `hovanki.field.max-total-bytes` (5 ГБ): диск RDS общий с продом; дальше вход и пачки — `LIMIT_REACHED`. Вход ограничен и по IP (`field-join-per-ip`, 300 в час). Время согласия из будущего сервер записывает как «сейчас», телефон перештамповывает его по часам сервера с первым снимком игры.
  - Живой вид прогона игры — только устройства: отправители `rx` — меняющиеся жетоны игры, пар «кто кого слышит» в нём нет (отчёт, шаг 6); сводка `rx` с `n` считается за `n` показаний.
  - Хранение пачек лабы (`hovanki.lab.chunk-retention`) прогоны игр не трогает: они уходят целиком по `hovanki.field.retention`.
  - Выгрузка — тем же маршрутом лабы; он открыт, если включён `RADIO_LAB` или `FIELD_LOG`, дальше смотрится флаг прогона этого устройства. Координаты сервер оставляет только в `gps` прогона игры, из остальных строк вырезает.
  - Вход в прогон лабы смотрит токен аккаунта, только если включён `hovanki.lab.join-staff-only` (staging).
  - В админке прогоны игр видны во вкладке «Радиолаба» с пометкой «игра»: без кода, QR и плана, «Завершить» — 409, сырые журналы и удаление работают. Вкладка «Полевые тесты» — шаг 6.
- **Телефон:** `GameTrace` — что игра сообщает журналу; `FieldSession` входит со стартом раунда (HIDING или SEEKING), только в `preview` и после согласия; 404 — больше не спрашивает в этой игре, без сети — повтор через 15 с; полевой режим `LabLog` и `FieldThinning` (`rx` раз в секунду на пира и канал, `frame`/`air` раз в 10 с, `gps` раз в секунду); выгрузка раз в 10 с; ушёл из игры — остаток выгружен; согласие отозвано — невыгруженное стирается, и досылка прошлой игры останавливается вместе с её загрузчиком. Часы сервера телефон меряет не при входе, а в своё случайное мгновение в первые 20 с и потом раз в 5 минут с разбросом, пять вопросов с паузой 250 мс: телефоны одной игры не бьют по `/time` разом. Ошибки радио и геолокации уходят ещё и в Sentry — только пока есть согласие, `err` несёт id события.
- **Экраны** (`ui/field/`): согласие закрывает всё приложение, «Нет, спасибо» — экран «без этого не поиграть»; отзыв — в профиле и на стартовом экране (его видит и гость). В тексте согласия — сбои и ошибки приложения, и что журнал гостя уходит через 90 дней или раньше по просьбе к организаторам. «Что-то не так» — встряхивание (`ShakeDetector` в `:device`, только пока приложение на экране) и кнопка в диалоге «Ещё» игры. Три вопроса — карточка на экране итогов. Вкладка «LAB» у сотрудников — только лаборатория, без «Export».
- **Тесты:** `FieldApiTest` (и чужие прогоны на общей базе, согласие из будущего), `FieldLimitsApiTest` (и общий потолок журналов), `FieldProductionApiTest` (сервер без `hovanki.field.allowed`), `RateLimitApiTest` (вход по IP), `DatabaseTest` (каскад от аккаунта, удаление через 90 дней, своё хранение у прогонов игр), `LabApiTest`, `LabLiveTest`, `ProfileConfigTest`, `FieldSchemaTest`, `FieldSessionTest` (перештамповка согласия, Sentry без согласия, досылка после отзыва, разброс часов), `FieldThinningTest`, `ShakeDetectorTest`, `BuildInfoTest`, `CrashReportingTest`; e2e `FieldLogTest` — шесть ботов с журналом и отдельный сервер с выключенным флагом.

**Дыры журнала закрыты (вторая волна, 2026-10-01):**

- `gps` несёт `speed` (м/с) и `bearing` (градусы), где их говорит ОС. `LocationSample` получил `@Transient`-поля `speedMetersPerSecond`/`bearingDegrees`: в протокол они не попадают, их читает только полевой `gps`. Android — `hasSpeed()`/`hasBearing()`, iOS — `speed`/`course` (минус — «неизвестно»).
- `sync.bytes` — размер ответа: у HTTP — длина тела в байтах (`GameApi.syncMeasured`, `HttpGameApi` читает текст и сам разбирает его `protocolJson`), у сокета — длина текстового кадра `snapshot`. Путь: `ConnectionEvent.Snapshot.bytes` → `GameTrace.onSyncBytes` → `FieldSession`. В протоколе ничего нового.
- Датчики в поле, с раунда, прорежены (`FieldProbeThinning`):
  - `carry` и `motion` (activity) — по смене, из `CarryMonitor`/`ActivityMonitor` независимо от фич игры;
  - `prox` — по смене близко/далеко;
  - `light` — при смене в 2 раза или раз в минуту (не чаще раза в 5 с);
  - `battery` — по смене состояния или экономии, иначе раз в минуту;
  - `thermal` — по смене (новый `FieldKinds.THERMAL`, `LabProbes.thermal()`: Android `PowerManager.addThermalStatusListener` с API 29, iOS `NSProcessInfo.thermalState`).

  Сырые отсчёты движения не пишутся. Один слушатель датчиков и один `CarryMonitor` кормят и эти события, и тень кармана шага 5 (`CarryShadow`). На iOS в поле нет близости (включённый датчик гасит экран) и света.
- `perm`: интерфейс `AppPermissions` (clientCore), реализации `AndroidAppPermissions`/`IosAppPermissions` в composeApp — `location` (always / when_in_use / denied), `precise`, `notifications`, `camera`, `power_saver`, на Android `battery_opt` (`on` — система может усыпить приложение). `FieldSession` смотрит раз в 30 с и пишет только изменения; при входе — первое состояние. Bluetooth — из радио в Koin.
- `ui`: `Panel(screen = …)` и `TrackScreen`/`FieldUi`/`LocalFieldUi` пишут открытие и закрытие панелей (chat, invite, lobby_map, board, settings, buildings, quests, perks); `App` — верхние экраны теми же словами, что хлебные крошки сбоев. Открытые до старта журнала экраны пишутся при старте. Ключевые действия — `GameTrace.onAction` из `GameSessionManager` как `ui` `tap` (`FieldActions`: start_game, settings_saved, catch_claim, catch_scan, catch_confirm, catch_dispute, vote, chat_send, checkpoint_scan, perk_use, quest_done, leave). Ни текста, ни id.
- «Принята ли точка» — событие сервера `fixes` (шаг 3).

**Не проверено:** ничего на телефонах (согласие, встряхивание, вопросы, вкладка «LAB», отзыв на стартовом экране; датчики, `thermal`, `perm`, `speed`/`bearing`), сборка iOS-приложения (код только скомпилирован), бот-партия на staging. Не мерили: хватает ли лимитов по IP на staging и разброса часов на 60 телефонов за одним Wi-Fi, сколько на самом деле занимают журналы (потолок 5 ГБ — оценка, его поднимают переменной), сколько батареи берут лишние слушатели датчиков в поле (рядом с мониторами игры с радаром слушателей акселерометра два). Права на уведомления на iOS приходят асинхронно: первый просмотр без `notifications`. `FakeGps` ботов не несёт скорости и курса — их проверяет только юнит-тест. Пока нет: `ui` экранов, которые не `Panel` (камеры сканера, диалог «Ещё»), пар в живом виде прогона игры (шаг 6), лимита на создание игр (только вход в журнал по IP и общий потолок). Каждый опрос во всех сборках теперь идёт через `syncMeasured` (тело читается текстом, чтобы знать размер): немного лишнего CPU и в release. Журнал одного гостя по его просьбе удалить нельзя: админ удаляет прогон игры целиком (или ждёт 90 дней). Тревога RDS на свободное место — настройка владельца ([deploy.md](deploy.md#staging)).

## 3. Шаг 3 — события сервера и правила в тени ✅ (2026-10-01)

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

**Как сделано:**

- **`Game` копит события поля**, как толчки: `FieldEvents` (до 5 000 между двумя взятиями, лишнее — счётчик). Только когда `Game.fieldLog` включён: его ставит `GameService.locked` по `FeatureFlags.isEnabled(FIELD_LOG)` (из памяти); без флага игра не копит ничего. Виды — `ServerKinds`/`ServerFields` в `FieldSchema.kt`:
  - `phase` (`phase`, `from`);
  - `claim`: `seeker`, `hider`, `catch` у открытой, `outcome` = `open` или причина/код отказа (`TOO_FAR`, `NO_LOCATION`, `NOT_NEARBY`, `WRONG_STATE`…), `dist_m` (ближе всего по GPS) и `est_m` (вероятнее всего);
  - `catch`: `confirmed`/`rejected` и `reason` — `code`, `timeout`, `attempts`, `dispute`, `left`, `end`;
  - `dispute`: `open` и каждый голос;
  - `reveal`: `start`/`change`/`end`, причина (`VisibilityReason`), `seconds` прошлого раскрытия; смотрится не чаще раза в секунду, конец раунда закрывает все;
  - `glow`: `start`/`end` в своё время;
  - `fixes`: на каждый `sync` — `accepted`, `refused_<Result>` (`MOCK`, `OUT_OF_ORDER`, `IMPLAUSIBLE`), `fix_from`/`fix_to` — время самих точек, чтобы сопоставить с `gps` телефона; в лобби не пишется;
  - `band`: направление чтения (`observer` → `heard`, id игроков), `band`/`from` — полоса пары, `shadow_band`, `stealth`.

  Игроки — по id, без ников; координат нет, только метры. Чужой id из запроса заявки пишется как `?`.
- **Правила в тени:**
  - при каждой заявке, даже с выключенным `PROXIMITY_CATCH`: `proximity` (правило включено), `radar` (радар у обоих), `shadow_accept`, `burning_s` (сколько пара горит без перерыва), `burning_ago_s` (сколько секунд назад горела выдержку);
  - `Radar` ведёт второй набор сглаживателей с поправкой `POCKET_STEALTH` (в игре с «карман прячет» он совпадает с настоящим), его полоса — `shadow_band`; событие `band` пишется, когда сдвинулась любая из двух полос.

  Исход игры тень не меняет — это проверяет тест.
- **`FieldEventWriter`** (`server/.../lab/`): ограниченная очередь (`hovanki.field.server-queue`, 20 000) и один поток. Игра никогда не ждёт: переполнение теряет события со счётчиком Micrometer `hovanki.field.events.dropped` и полем `dropped` в следующем `srv`. События берутся после лока в `GameService.locked` (и при отказах, например заявка, отклонённая GPS) и в чистке `GameJanitor`. Две заявки одной игры могут отдать события писателю в обратном порядке: перед нумерацией он сортирует их по времени (устойчиво). Раз в `hovanki.field.server-flush` (5 с) поток пишет их через `FieldLogStore` (`JdbcFieldLogStore`) устройством `server`: одна строка `lab_devices` на прогон, без `user_id` и согласия, токен никому не выдаётся. Строки — формат телефонов (схема 2): заголовок `session` + `clock` со смещением 0, `t` = `dt` = `mono` по часам сервера, `dev: server`, `seq` без дыр, `run`; `LabMerge` читает их как ещё одно устройство. Пределы те же, что у выгрузки телефона: байты прогона, общий потолок журналов игр, досылка 30 минут после конца прогона. Живой вид обновляется.
- **Прогон по-прежнему открывает первый телефон** (с шага 5 — уже в лобби). До его входа события игры ждут в памяти — первые 2 000, остальное только считается в `hovanki.field.events.unlogged`: игра, которую никто не пишет, базу не трогает. Сервер ищет прогон игры сам (`LabRunRepository.findGameRun`) при сбросе и при `srv`. Игры, которых больше нет в памяти, писатель дописывает и забывает по сигналу чистки.
- **`srv` раз в `hovanki.field.srv-every` (10 с; в профиле e2e 5 с)** — `FieldServerSampler` (`@Scheduled`); при выключенном `FIELD_LOG` не делает ничего и не копит времена `sync` (окна и базы счётчиков ошибок обнуляются, чтобы первый `srv` после включения не нёс старого). Пишет в каждый прогон игры в памяти, у которой он есть:
  - `window` (мс), `syncs`, `sync_p50`/`sync_p95` (все), `poll_p50`/`poll_p95`, `socket_p50`/`socket_p95` — точные перцентили `GameService.sync` за окно в мс (`ServerMetrics.takeSyncWindow`, с ожиданием лока, без сети);
  - `e5xx`, `e429` с прошлого `srv` (из `http.server.requests`; в первом окне после старта их нет);
  - `sockets`, `games`, `players` (gauge шага 1), `heap_mb`, `heap_max_mb`, `cpu` (доля процесса 0..1), `dropped`.
- **Счётчик закрытий живого канала** `hovanki.socket.close{code}`: в `GameSocketHandler.afterConnectionClosed`, кто бы ни закрыл. Коды 1000–1015 и четыре кода `SocketClose` — как есть, остальные — `other`.
- **Тесты:** `GameFieldLogTest` (обычная игра не копит ничего; фазы, точки, отказ `TOO_FAR` с расстоянием, заявка → находка по коду по порядку; тень «вплотную» при выключенном и включённом правиле, с журналом и без — исходы одинаковы; полоса и `shadow_band` у карманного прячущегося, радар у телефонов одинаков с журналом и без; раскрытия, свечения, конец раунда); `FieldEventWriterTest` (события ждут прогон, порядок по времени, `seq` без дыр, одно устройство; переполнение теряет со счётчиком и не ждёт; неудачная запись сохраняет события и номера; закрытый прогон, забытая игра); `ServerMetricsTest`; `FieldApiTest.theServerWritesItsEventsIntoTheGamesRun` (MockMvc: устройство `server` с фазами, точками, отказом `NO_LOCATION` и `srv`); e2e `FieldLogTest`: в сыром журнале игры — журнал сервера с заголовком, HIDING/SEEKING, заявкой Сэма на Анну с ответом тени, `fixes` и `srv`; телефоны считаются без устройства `server`.

**Не проверено:** на staging и с живыми игроками не запускалось: цифры `srv` (CPU через `com.sun.management`, heap) на t3.small и цена писателя при 60 игроках не мерены. Отчёт пока не читает события сервера (шаг 6); вид `band` сервера зовётся так же, как `band` лабы телефона, — шагу 6 различать их по устройству. «Толчки и сокеты» из ADR 0018 §3.3 в журнал игры не пишутся, есть только счётчик закрытий в Micrometer. События сервера называют только игроков с телефоном в прогоне и согласием (остальные — `other`, без расстояний и полос); выход телефона без сети сервер не узнаёт до конца игры, а записанное до отзыва согласия остаётся в устройстве `server` 90 дней и с аккаунтом не удаляется. Что `srv` раз в 10 с, лимит памяти писателя (50 000 событий) и отбрасывание поздних событий забытой игры выдерживают staging — не мерили. Если `FIELD_LOG` выключить и включить посреди раунда, первые `reveal` после этого могут нести старую длительность. Живой вид админки с устройством `server` глазами не смотрели.

## 4. Шаг 4 — каналы (radar-run.md, шаг 3) ✅ (2026-10-01)

Всё из [radar-run.md §3](radar-run.md#3-шаг-3--каналы-и-хосты--2026-10-01), плюс для поля:

- **Починка рекламы Android-прячущегося** (не больше 31 байта) — до выхода на улицу, это баг игры.
- **Раскладка на Android в игре** — от номера игрока по кругу (`.scan_response` / `.bare` / `.mfr`). Слушатели разбирают все три. Раскладка пишется в `adv`.
- **`ble.overflow` в тени:** разбор масок у Android и iPhone на экране → `shadow` с жетоном и RSSI; в `ProximityRadio` игры не идёт.
- **`ble.ibeacon.region`:** вход и выход → журнал.

**Как сделано:**

- **Реклама Android-прячущегося починена во всех сборках:** `.scan_response` — UUID игры в пакете (18 байт), жетон в ответе на скан (22 байта). Было 40 байт в одном пакете, Android отвечал `ADVERTISE_FAILED_DATA_TOO_LARGE`.
- **Раскладки по кругу — только при журнале** (полевая сборка с согласием, лаба в debug): номер игрока (индекс в `snapshot.players`, `RadioOptions`) mod 3 → `.scan_response` / `.bare` / `.mfr`. Без журнала, то есть в release, у всех `.scan_response`: её пропускает скан iPhone по сервису.
- **Маски в тени:** Android разбирает сырые кадры Apple, iPhone на экране — вторым сканом по 128 UUID таблицы (только при журнале). Карманный iPhone с журналом при `willResignActive` меняет рекламу на «сервис + UUID таблицы его жетона», при возвращении — обратно. Ответы — только `shadow`, в игру не идут.
- **Журнал:** `adv` с раскладкой и байтами, `frame`, `air`, `shadow`, `region`; `rx` несёт канал (`tech`). Прореживание `frame`/`air` — забота `FieldSession`.
- **Тесты:** `AdBudgetTest`, `ChannelCodecsTest`, `RadarCatalogTest`, `AirDecoderTest`, `AirRulesTest`, `JvmAirHostTest`, `ModuleBoundariesTest`, `LabRadarEventsTest`; e2e `RadarTest`, `ProximityCatchTest`, `LabRunTest`, `BeaconTest` на симуляторе эфира.

**Не проверено:** эфир на телефонах — уходит ли теперь реклама Android, какие раскладки слышат iPhone и другие Android, ответ на скан у iPhone в фоне. iOS только скомпилирован (`compileKotlinIosArm64` на Linux), не слинкован и не запускался; Swift-помощник Мака (`.mfr`) не собирался. Вероятно, iPhone не слышит `.bare` и `.mfr`: CoreBluetooth в игре сканирует по UUID сервиса.

## 5. Шаг 5 — чоканье и тени (radar-run.md, шаг 4) ✅ (2026-10-01)

Всё из [radar-run.md §4](radar-run.md#4-шаг-4--чоканье-тени-карточки--2026-10-01), плюс для поля:

- **Карточка в лобби** игры с радаром (только `preview` с журналом): «Чокнись с соседом» — выбрать игрока, коснуться, «Чокнулись» у обоих → `touch` с истиной. После игры на экране итогов — ещё раз.
- **`carry.v2` пишет `shadow`** в игре. Вопрос «где был телефон» — в `survey`.

**Как сделано** (общее с лабой — в [radar-run.md §4](radar-run.md#4-шаг-4--чоканье-тени-карточки--2026-10-01)):

- **Телефон входит в прогон игры уже в лобби** (`FieldSession.JOIN_PHASES`: LOBBY, HIDING, SEEKING; не на итогах). Тики, датчики и тень кармана — с раунда. Тихое лобби ничего не выгружает: таймер `LabUploader` не шлёт пачку из одних своих `net`, а опросы вне раунда, которые прошли и не сменили фазу, не пишутся (первый, смена фазы и отказы — пишутся).
- **Карточка «Чокнись с соседом»** (`ui/field/TouchCard.kt`, `TouchCard(neighbours, again)`):
  - показывается, пока `FieldSession.touchCard`: журнал идёт, игра с радаром, лобби или итоги; только в `preview`;
  - игрок выбирает соседа чипами (все игроки, кроме себя), жмёт «Чокнулись» — `FieldSession.touched(id)`: истина `touch src=button` с `partner` = id игрока уходит в журнал и сразу на сервер;
  - под кнопкой — число чоканий (`FieldSession.touchCount`, обнуляется при выходе из прогона), рядом «Не сейчас» (убирает карточку до ухода с экрана);
  - в лобби — под строкой радара, на итогах — над тремя вопросами, с заголовком «Ещё раз — для дрейфа»;
  - имена видны только на экране, в журнал уходит PlayerId; строки `field_touch_*` — en/ru/uk.
- **Радио касания:** пока карточка на экране, радио работает вне раунда со случайным жетоном журнала (`GameTrace.touchRadioToken`, новый на каждый прогон, не жетон радара игрока). Услышанное идёт только в журнал, в `sync` — никогда. Раунд заменяет его своим радио. В release `GameTrace.None`: радио вне раунда нет.
- **Толчки акселерометра** (`ImpactMonitor` из `:device`, 100 Гц) пишутся, пока карточка на экране, как кандидаты: `touch src=impact` с `g` и `ago`.
- **Тень кармана в игре:** `CarryShadow` пишет `shadow` `carry.v1` (монитор игры) и `carry.v2` (`CarryClassifier`) при смене состояния, с раунда.
- **Экран лабы** (debug и `preview` для персонала): секция «Touches» при включённой лабе — метки партнёра (выбранного прогона или A/B/droid, без своей), «We touched <метка>» пишет тот же `touch src=button`.
- **Тесты:** `FieldSessionTest` (вход в лобби, не на итогах; карточка, жетон, `touched`, толчки, счётчик; тихое лобби — 30 опросов без выгрузки; в release карточки нет), `CarryShadowTest`, `LabUploaderTest`, `RadioSessionTest` (радио касания не шлёт ничего на сервер, раунд его заменяет); e2e `FieldLogTest` — телефоны в журнале с лобби, Сэм и Анна чокаются там, у обоих в журнале толчок и кнопка, Сэм слышал Анну до раунда.

**Не проверено:** ничего на телефонах — частота и время акселерометра, хватает ли 0,8 g и ±150 мс на настоящем касании, пороги пика RSSI, `carry.v2` на настоящих датчиках, батарея (датчики в раунде, акселерометр 100 Гц и радио в лобби). Радио в лобби и на итогах: окно Bluetooth на iOS, переключение рекламы Android между радио касания и радио раунда. Закрытие сокета, открывшегося после таймаута (`KtorGameSocketOpener`), проверено только чтением кода Ktor: теста на медленный апгрейд нет. Внешний вид карточки и секции «Touches» (отступы, длинные ники в чипах); ни Maestro-потока, ни UI-теста на карточку нет (в debug нет полевой сессии). iOS только скомпилирован. Пока карточка на экране в лобби игры с радаром, телефон выгружает раз в 10 с (радио касания пишет `rx`) — так задумано. Соседи — все игроки без фильтра по расстоянию: в большой игре чипов много. Отчёт полевого прогона новых разделов не показывает (шаг 6).

## 6. Шаг 6 — отчёт, админка, выгрузки

- **`shared/.../lab/FieldReportBuilder`** (рядом с `LabReportBuilder`, тот же `LabMerge`): разделы ADR 0018 §6; детекторы аномалий — отдельные чистые функции с тестами на придуманных журналах.
- **`LabReportWriter`** считает и `GAME`: понемногу во время игры для живого вида, целиком после конца. Большие игры — потоково, по окнам в 10 минут, чтобы не держать всё в heap.
- **Маршруты админа:** `GET /api/v1/admin/field/games`, `/{runId}`, `/{runId}/report.md`, `/{runId}/digest.jsonl`, `/{runId}/raw.zip?device=&from=&to=` (с причиной), `DELETE` (с причиной). В `digest.jsonl` ники заменены на `P1…Pn`, координат нет.
- **Вкладка «Полевые тесты»** в `static/admin/`: список, живой вид (устройства: последняя пачка, батарея, фон, `sync`; кнопка «Отметка» → `mark` устройства `staff`), отчёт, выгрузки.
- **Тесты:** `FieldReportBuilderTest`, тест маршрутов админа (moderator → отказ, причина обязательна, `AuditLog`), `FieldLogTest` проверяет отчёт и выгрузки.

## 7. Шаг 7 — Sentry ✅ (2026-10-01, Android и сервер; iOS — владелец)

- **Где:** `sentry-kotlin-multiplatform` в `libs.versions.toml` и `:composeApp`. Инициализация — только при `BuildConstants.CHANNEL == preview` и непустом `hovanki.sentryDsn`.
- **Фильтр:** `beforeSend`/`beforeBreadcrumb` вырезают координаты, токены, email, ники и текст чата. Тест фильтра — в `commonTest`, на придуманных событиях.
- **Связь с журналом:** id события → `err` в журнал.
- **iOS:** Sentry Cocoa через SPM в `iosApp` (владелец), вызов из Kotlin — как у остальных мостов.
- **Сервер:** `sentry-spring-boot` только с профилем `staging`.
- **`preview.yml`:** DSN из секрета, загрузка mapping (R8) и dSYM.

**Как сделано:**

- **Не `sentry-kotlin-multiplatform`, а нативный SDK.** Android: `sentry-android` только в типе сборки `preview` (`previewImplementation`), клей — `androidApp/src/preview` (`installCrashReporting`, первой строкой `HovankiApplication.onCreate`, при непустом DSN), в debug и release — no-op двойник `src/withoutSentry`, SDK там нет вообще. Авто-старт через манифест выключен.
- **Настройки SDK:** `environment = staging`, `release = <версия>+<номер>`, `sendDefaultPii = false`; сессии, трассировка, скриншоты и свои хлебные крошки SDK выключены — только ошибки. `beforeBreadcrumb` оставляет только имена экранов (`loading`, `welcome`, `main`, `spectator`, `resuming`, `lobby`, `game`, `results`), `beforeSend` убирает user, request, extras, tags, имя и id устройства.
- **Фильтр** `SentryScrubber` — в `:shared`, общий для телефона и сервера: bearer и `token=`/`password=`/`Authorization:`, hex от 16 символов, base64-подобные от 24, email, `lat=`/`lon=`, десятичные с 4+ знаками; обрезка до 300 символов после очистки. Ники и чат шаблоном не найти: от них защищает то, что из события убрано всё, кроме исключения и экранов.
- **Связь с журналом:** `ErrorReporter.capture(t)` в `:clientCore` возвращает id события; `FieldSession` отдаёт туда ошибки радио и геолокации (только пока есть согласие), `err` пишет id. Ошибку команды (сеть или ответ сервера, в тексте может быть JSON с никами) — только в журнал.
- **Только с согласием:** `CrashReporting.isAllowed` ставится при старте Koin по сохранённому согласию и следует за `FieldSession.consentAt`; `beforeSend` Android и `CurrentCrashReporter` без него ничего не отправляют. SDK по-прежнему стартует первым в `onCreate`, но крэш до старта Koin, до согласия и после отзыва не уходит.
- **Сервер:** `sentry-spring-boot-4-starter` и `sentry-logback`; стартер включается только при свойстве `sentry.dsn` (`SENTRY_DSN` в `.env` staging), ключа `dsn:` в `application.yaml` нет намеренно. Пустой `SENTRY_DSN`, который `compose.yaml` передаёт и на проде, SDK выключает (`SentryEmptyDsnTest`). `ServerEventScrubber` строже: без хлебных крошек, текст исключений базы и почты выбрасывается целиком. Ошибки уровня ERROR в логе становятся событиями.
- **R8:** `-Phovanki.sentryProguardUuid=<uuid>` кладёт id маппинга в манифест preview; сам маппинг — `androidApp/build/outputs/mapping/preview/mapping.txt`.
- **Тесты:** `SentryScrubberTest`, `CrashReportingTest`, `SentryEventScrubberTest` (`:androidApp:testPreviewUnitTest`, входит в `check`), `ServerEventScrubberTest`, `SentryAbsentTest`, `SentryEmptyDsnTest`, `SentryEnabledTest` (поддельный DSN и записывающий транспорт), `FieldSessionTest` (id в `err`).

**Не проверено:** настоящая доставка в Sentry (нет DSN и телефона), что крэш без согласия действительно не уходит (в том числе из кэша SDK при следующем запуске), символизация по маппингу, память staging с потоками SDK. Не сделано: загрузка маппинга R8 и dSYM из CI (нужен секрет `SENTRY_AUTH_TOKEN`, его нет), iOS целиком — ниже.

**iOS — сделать на Маке (владелец).** В Kotlin есть `CrashReporter` и `CrashReporting.install(...)`; пока ничего не установлено, это no-op. Код ниже не компилировался: имена опций сверить с установленной версией Sentry Cocoa.

1. Xcode → File → Add Package Dependencies → `https://github.com/getsentry/sentry-cocoa` → продукт `Sentry` в таргет `iosApp`. SDK окажется и в сборке App Store, но без DSN не запускается; если это не годится — отдельная конфигурация для TestFlight.
2. DSN: ключ `SentryDsn` в `Info.plist` со значением `$(SENTRY_DSN)` и строка `SENTRY_DSN =` в `Configuration/Config.xcconfig`. В xcconfig `//` — комментарий, поэтому адрес пишут как `https:/$()/ключ@o1.ingest.de.sentry.io/42`. Запасной путь — `CrashReporting.shared.dsn` (из `hovanki.sentryDsn`).
3. Файл `iosApp/iosApp/SentryBootstrap.swift`:

```swift
import ComposeApp
import Foundation

#if canImport(Sentry)
import Sentry

/// Crash reports of the field test build (docs/adr/0018-field-test-build.md §7). Starts Sentry only when the build has
/// a DSN (TestFlight builds; App Store builds have none) and hands the Kotlin side a reporter.
enum SentryBootstrap {
    static func startIfConfigured() {
        let dsn = configuredDsn()
        if dsn.isEmpty { return }
        SentrySDK.start { options in
            options.dsn = dsn
            options.environment = "staging"
            options.releaseName = releaseName()
            options.sendDefaultPii = false
            options.attachScreenshot = false
            options.attachViewHierarchy = false
            options.enableAutoSessionTracking = false
            options.enableAutoBreadcrumbTracking = false
            options.enableNetworkBreadcrumbs = false
            options.enableNetworkTracking = false
            options.enableCaptureFailedRequests = false
            options.enableUserInteractionTracing = false
            options.enableAutoPerformanceTracing = false
            options.tracesSampleRate = nil
            options.maxBreadcrumbs = 30
            // Nothing without the tester's consent: set when Koin starts and whenever it changes.
            options.beforeSend = { event in CrashReporting.shared.isAllowed ? scrubEvent(event) : nil }
            options.beforeBreadcrumb = { crumb in scrubBreadcrumb(crumb) }
        }
        CrashReporting.shared.install(reporter: SentryCrashReporter())
    }

    private static func configuredDsn() -> String {
        if let fromPlist = Bundle.main.object(forInfoDictionaryKey: "SentryDsn") as? String,
           !fromPlist.isEmpty, !fromPlist.hasPrefix("$(") {
            return fromPlist
        }
        return CrashReporting.shared.dsn
    }

    private static func releaseName() -> String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? ""
        let build = info?["CFBundleVersion"] as? String ?? ""
        return "\(version)+\(build)"
    }

    private static let keptContexts: Set<String> = ["app", "device", "os", "runtime", "culture", "trace"]

    private static func scrubEvent(_ event: Event) -> Event? {
        let kotlin = CrashReporting.shared
        event.user = nil
        event.request = nil
        event.extra = nil
        event.tags = nil
        event.serverName = nil
        event.transaction = event.transaction.map { kotlin.scrub(text: $0) }
        event.breadcrumbs = event.breadcrumbs?.compactMap { scrubBreadcrumb($0) }
        if let message = event.message {
            event.message = SentryMessage(formatted: kotlin.scrub(text: message.formatted))
        }
        event.exceptions?.forEach { $0.value = kotlin.scrub(text: $0.value) }
        event.context = event.context?.filter { keptContexts.contains($0.key) }
        event.context?["device"]?["id"] = nil
        return event
    }

    private static func scrubBreadcrumb(_ crumb: Breadcrumb) -> Breadcrumb? {
        let kotlin = CrashReporting.shared
        guard crumb.category == kotlin.screenCategory,
              let message = crumb.message,
              let name = kotlin.screenName(raw: message) else { return nil }
        crumb.message = name
        crumb.data = nil
        return crumb
    }
}

/// What the Kotlin side calls: the screens' trail and the errors it catches.
final class SentryCrashReporter: NSObject, CrashReporter {
    func capture(t: KotlinThrowable) -> String? {
        let name = String(describing: type(of: t))
        let error = NSError(
            domain: "kotlin",
            code: 0,
            userInfo: [NSLocalizedDescriptionKey: "\(name): \(t.message ?? "")"]
        )
        let id = SentrySDK.capture(error: error)
        return id == SentryId.empty ? nil : id.sentryIdString
    }

    func breadcrumb(screen: String) {
        guard let name = CrashReporting.shared.screenName(raw: screen) else { return }
        let crumb = Breadcrumb(level: .info, category: CrashReporting.shared.screenCategory)
        crumb.type = "navigation"
        crumb.message = name
        SentrySDK.addBreadcrumb(crumb)
    }
}
#else
// The Sentry package is not added yet: nothing starts.
enum SentryBootstrap {
    static func startIfConfigured() {}
}
#endif
```

4. В `iOSApp.swift` вызвать до первого экрана: `init() { SentryBootstrap.startIfConfigured() }` в `struct iOSApp: App`.
5. dSYM загружать в Sentry шагом TestFlight-сборки (`sentry-cli debug-files upload`), иначе нативные стеки не читаются. Kotlin-стек в событие iOS не попадает: `capture(t:)` отдаёт только класс и сообщение.
6. App Store Connect → App Privacy и Play Console → Data safety: «Crash data» — собирается, не связано с пользователем, для диагностики.

## 8. Шаг 8 — раздача ✅ (2026-10-01, часть сессии; Play Console и App Store Connect — владелец)

- **Android:**
  - владелец: аккаунт Play Console, приложение `app.hovanki.preview`, первая загрузка AAB руками, декларации геолокации и foreground service, service account;
  - сессия: job в `preview.yml` — `bundlePreview` и загрузка в `internal` (`r0adkll/upload-google-play`), без секрета — пропуск с сообщением.
- **iOS:**
  - владелец: внешняя группа TestFlight с публичной ссылкой, демо-аккаунт на staging для Beta App Review, «What to Test»;
  - `release.yml` — iOS-архив с адресом прода для App Store.
- **[ci-cd.md](ci-cd.md):** инструкция тестеру — как поставить на iPhone и Android, как дать разрешения, что делать, если не пускает.

**Как сделано:**

- **Google Play:** job `Google Play internal` в `preview.yml` — AAB из того же запуска Gradle, что и APK (`bundlePreview`), загрузка в трек `internal` приложения `app.hovanki.preview` (`r0adkll/upload-google-play`, закреплён на SHA коммита v1.1.5: действие получает ключ сервисного аккаунта; секрет `PLAY_SERVICE_ACCOUNT_JSON`), только из `main` и только сборка на staging (без `STAGING_SERVER_URL` ни pre-release, ни Play, ни TestFlight). Нет секрета — уведомление, job зелёный; секрет не похож на ключ service account — ошибка. Статус выпуска — переменная `PLAY_RELEASE_STATUS` (`completed` по умолчанию, `draft` — если Play ещё считает приложение черновиком).
- **iOS:** шаги TestFlight вынесены в общий `ios-testflight.yml` (`workflow_call`). Его зовут `preview.yml` (staging, DSN Sentry) и `release.yml` (сборка для App Store по тегу: адрес прода, версия из тега, номер сборки `100000 + номер запуска`, адрес staging внутри — ошибка). Загрузка — только при пуше тега.
- **Номера сборок:** у `preview` и `release` разные счётчики запусков, а приложение одно (`app.hovanki.ios`), поэтому у сборки App Store смещение 100 000. После тега владелец поднимает `MARKETING_VERSION` в `Config.xcconfig`; job предупреждает, если версия тега равна ей.
- **[ci-cd.md](ci-cd.md):** «Номера сборок», [«TestFlight: внешняя группа и Beta App Review»](ci-cd.md#testflight-внешняя-группа-и-beta-app-review) (публичная ссылка, ручная раздача внешней группе, «What to Test» начинается со «staging», демо-аккаунт, шаблон заметок для ревью), [«Google Play: внутреннее тестирование»](ci-cd.md#google-play-внутреннее-тестирование), [«Переменные и секреты»](ci-cd.md#переменные-и-секреты), [«Инструкция тестеру»](ci-cd.md#инструкция-тестеру).

**Не проверено:** ничего не запускалось на GitHub Actions: ни вызов общего workflow, ни загрузка в Play и TestFlight, ни подпись, ни пропуск публикации без `STAGING_SERVER_URL`. Проверены `actionlint` с shellcheck и шаги-скрипты локально на подставных данных. Не проверено, что фаза Xcode «Compile Kotlin Framework» на `macos-26` читает `gradle.properties` домашней папки Gradle (на Linux — да) и в какой кодировке Kotlin/Native хранит строки (проверка ищет UTF-8 и UTF-16 и только предупреждает, если не нашла ни одного адреса). `status: completed` для нового приложения в Play — тоже не проверено.

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
- Сборки iOS после шагов 2, 4, 5 и 7; Sentry Cocoa через SPM и `SentryBootstrap.swift` (шаг 7).
- После шагов 1, 2, 4, 7, 8 — проверки на телефонах и на staging: список «Что сделать владельцу» в «Отличиях реализации» [ADR 0018](adr/0018-field-test-build.md#что-сделать-владельцу).
