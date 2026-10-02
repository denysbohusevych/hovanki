# Большой прогон: план реализации

План для сессий, которые будут реализовывать [ADR 0017](adr/0017-radar-techniques-and-big-run.md): что делать по шагам, в каких файлах, чем проверять и какие правила легко нарушить. Зачем всё это и что именно проверяем — в самом ADR (§2.3 — каталог техник, §6 — программа прогона). Этот документ — «как».

Статус: шаги 1 (2026-09-29), 2, 3, 4, 5 и 7 (2026-09-30) сделаны (шаг 5 — вслепую, прогон на телефонах впереди), шаг 6 отложен. Полевая сборка ([ADR 0018](adr/0018-field-test-build.md), [field-test.md](field-test.md), шаги 4–5) берёт радар шагов 3–5 как есть и добавляет своё: `RadarCatalog.field` (раскладки Android по кругу от номера игрока, маска overflow в тени), чтения тени в журнале, касание в лобби тем же `impact` и отметкой касания. Каждый шаг ниже — отдельная сессия и отдельный PR. Сделанный шаг отмечается здесь ✅, а в ADR 0017 появляется раздел «Отличия реализации».

## 0. Правила для каждой сессии

**Прочитать сначала:** `CLAUDE.md`, ADR 0017 целиком, [radio-lab.md](radio-lab.md) §4 (схема журнала) и §12 (что уже измерено), [radio-lab-tests.md](radio-lab-tests.md) (как сейчас идёт автоматический прогон), ADR 0016 §2 (маска), ADR 0008 (админка). Код: `clientCore/.../lab/`, `composeApp/.../ui/debug/`, `radar/` и `device/` (с шага 2 там радио, пробы лаборатории и их Android- и iOS-реализации; `composeApp/src/{androidMain,iosMain}/.../lab/` — только «Поделиться»), `e2e/.../lab/LabMerge.kt`, `server/.../api/AdminController.kt`, `server/.../features/`.

**Как работать:**

- Один шаг — один PR в `main`. Шаг можно делить на несколько PR, но не склеивать два шага.
- Имена классов и маршрутов ниже — предложение. Если код подсказывает лучше, делай лучше и запиши разницу в «Отличия реализации» ADR 0017.
- Поведение игры не меняется, пока шаг прямо не говорит обратного. Всё новое — только в лаборатории, в прогонах и за флагом `RADIO_LAB`.
- Перед PR: `./gradlew spotlessApply check`; поменял протокол или сервер — `./gradlew :e2e:test`. В PR написать, что запускал.
- **iOS пишется вслепую** (в облаке Linux). Поэтому: маленькими частями; только API, у которых есть Objective-C-заголовки (Kotlin/Native видит только их); всё, что есть только в Swift (ActivityKit, Wi-Fi Aware), — через мост (§5.3). В конце PR — список того, что владелец должен собрать и проверить на Маке. Ошибки компиляции он присылает в ту же сессию.
- После шага — обновить: таблицу API в `architecture.md` (новые маршруты), `CLAUDE.md` → «Layout» (новые модули и пакеты), этот документ (✅), ADR 0017 («Отличия реализации»).

**Правила, которые легко нарушить:**

- **Координат нет нигде**: ни в журнале, ни в пачках, ни в отчёте. У `gps` — только точность и возраст.
- Экран лаборатории и вход в прогон — **только в debug-сборке** (`BuildInfo.isDebug`). В release и preview — ни экрана, ни выгрузки.
- RSSI, жетоны, сырые кадры — только в таблицах лаборатории и только в отчёте для админа. Никогда — в логах сервера, в ответах игры, игрокам.
- Каждый маршрут `/api/v1/admin/...` — с параметром `Staff`, проверкой роли admin в `AdminService`, причиной и `AuditLog` в той же транзакции. Страница админки — только `textContent`.
- Миграции только добавляются (`V<n>__*.sql`), время — из инжектированного `Clock`.
- Идентификаторы устройств (адрес Android, `CBPeripheral.identifier`) — только хешем с солью прогона.

## 1. Шаг 1 — прогон на сервере ✅ (2026-09-29)

Цель: админ создаёт прогон, телефоны входят по коду, идут по плану сервера и каждые 5 с шлют журнал; сервер хранит его и считает отчёт; админка показывает пульт, живой вид и отчёт. Техники — те, что лаборатория умеет уже сейчас. После этого шага можно выходить с тремя телефонами.

### 1.1 Протокол (`:shared`)

`protocol/Lab.kt`, маршруты в `ApiRoutes`:

| Маршрут | Кто | Что |
|---|---|---|
| `POST /api/v1/lab/runs/join` | телефон, без авторизации (код — пропуск) | код, метка, модель, ОС, сборка, commit, возможности → токен устройства, секрет радара на прогон, соль для хешей, план |
| `GET /api/v1/lab/runs/{runId}/state` | телефон, bearer токен устройства | номер шага, начало шага по часам сервера, пауза, конец |
| `POST /api/v1/lab/runs/{runId}/advance` | телефон | «Дальше» / «Повторить шаг» / «Пауза» |
| `POST /api/v1/lab/runs/{runId}/events` | телефон | пачка: `seqFrom`, `seqTo`, gzip JSONL → подтверждение последнего принятого `seq` |
| `GET/POST /api/v1/admin/lab/runs` | админ | список; создать (название, сценарий, метки, причина) → код |
| `GET /api/v1/admin/lab/runs/{runId}` | админ | прогон, устройства, живой вид |
| `POST /api/v1/admin/lab/runs/{runId}/advance`, `/finish` | админ | пульт |
| `GET /api/v1/admin/lab/runs/{runId}/report` | админ | отчёт (JSON) |
| `GET /api/v1/admin/lab/runs/{runId}/raw` | админ, причина | сырые журналы zip (для `:e2e:lab merge`) |
| `DELETE /api/v1/admin/lab/runs/{runId}` | админ, причина | удалить |

- Сценарий — данные в `:shared` (`lab/LabScenarios.kt`): шаги с длительностью или «до кнопки», для каждой метки — какие техники включены, текст для человека и истина (расстояния пар, место телефона, действие). Туда же переезжают `LabRunScript` с проверкой «заблокированный шаг не меняет рекламу» (сейчас в `clientCore/lab/LabRun.kt`) и нынешний `LabRunScripts.RADIO` как первый сценарий.
- Разбор журналов переезжает из `e2e/.../lab/LabMerge.kt` в `:shared/lab/` (чистый Kotlin, без `java.*`): чтение схем v1 и v2, сопоставление жетонов устройствам, сводка по отрезкам. `:e2e:lab merge` становится тонкой обёрткой над ним. Отчёт — `@Serializable` данные (`LabReport`), не markdown: его рисует админка.
- `ServerFeature.RADIO_LAB` (строка в `admin.js` → `FEATURES` с названием и описанием).

### 1.2 Сервер

- Миграция `V10__radio_lab.sql`: `lab_runs`, `lab_devices`, `lab_chunks`, `lab_reports` (ADR 0017 §7), всё `ON DELETE CASCADE` от прогона. Токены устройств — только хешем.
- Пакет `lab/`: `LabRunRepository` (JdbcClient), `LabRunService` (создание, вход, план по часам, пачки, пределы), `LabReportWriter` (считает отчёт вне потока запроса, как `HistoryWriter`: понемногу во время прогона, целиком после `finish`).
- Контроллеры: `LabController` в `api/` (маршруты телефона, bearer токен устройства → `LabDevice`, по образцу `PlayerRef`), маршруты админа — в `AdminController` или отдельном `LabAdminController` с тем же `Staff`.
- Флаг выключен — маршруты отвечают 404. Пределы: 8 устройств, 24 часа на вход и выгрузку, 300 МБ сырых данных на прогон (сверх — новый `ErrorReason` на существующем `ErrorCode`), `RateLimiter` на устройство. Пачка с уже принятыми `seq` — «ок», без записи.
- `DataRetention`: сырые пачки — 90 дней после конца прогона; отчёты не удаляются.
- Тесты: `LabApiTest` (MockMvc: вход по коду, неверный код, флаг выключен → 404, пачка и повтор пачки, пределы, чужой токен), тест репозитория, тест `DataRetention`, тест админ-маршрутов (роль moderator → отказ, причина обязательна, запись в `AuditLog`). `DebugEndpointAbsentTest` остаётся зелёным.

### 1.3 Телефон (`:clientCore/lab`, `:composeApp`)

- `LabApi`/`HttpLabApi` рядом с `GameApi`.
- `LabLog` → схема 2: в каждом событии `run`, `seq`; новые виды — `step`, `net`. `peer` хешируется солью прогона, пока телефон в прогоне.
- `LabUploader`: раз в 5 с отдаёт новые события пачкой; без сети — ждёт и повторяет; пишет `net`. Кольцевой буфер тот же, выгруженное из него не удаляется, пока не подтверждено.
- `LabRunFollower` — обобщение `LabRunner`: план приходит с сервера, шаг — по `state` (раз в 2 с) и часам сервера; на смене шага включает техники этого шага для своей метки, ставит отметку с истиной, пищит и вибрирует на экране, заблокированному — уведомление. Мак (`MacRunFollower`) переходит на тот же план: `run.sh --lab --run <код>`.
- Экран Lab: «Войти в прогон» (код руками или QR через существующий `CatchCodeScanner`), шаг, указание своей метке, обратный отсчёт, кнопки пульта, «сколько не выгружено».
- Тесты `commonTest`: схема v2, выгрузка с отказами сети и повтором, следование плану по придуманным часам.

### 1.4 Админка

Вкладка «Радиолаба» (только admin): список прогонов; создание (сценарий из списка, метки, причина) с кодом и QR (QR рисуется существующим кодировщиком `:shared/qr` на сервере или в JS — без чужих библиотек); пульт; живой вид (устройства и таблица «пара × канал: слышно за 10 с, медиана RSSI»), опрос раз в 2 с; отчёт — таблицы, покрытие раскрашено; скачать сырые журналы и удалить — с причиной.

### 1.5 e2e

`LabRunTest`: три бота (`BotPlayer` с `FakeRadio`) входят в прогон, проходят короткий сценарий, выгружают журналы; отчёт считается, покрытие совпадает с тем, кого `RadioWorld` сделал слышимым. Флаг включает `StaffConsole`.

**Готово, когда:** на своём сервере с включённым флагом Android из CI-сборки входит по коду, проходит `RADIO`, в админке видно его пачки и отчёт; всё выше зелёное.

**Как сделано** — главное, чем код отличается от плана выше (полный список — «Отличия реализации» в [ADR 0017](adr/0017-radar-techniques-and-big-run.md#отличия-реализации)):

- Миграция — `V11__radio_lab.sql` (V10 занята). В `lab_runs` только ник админа, без id; через год `DataRetention` его стирает.
- **Жетон радара выдаёт сервер** при входе (`radarToken`, один на устройство, без вращения). Повторный вход с той же меткой — новое устройство.
- Маршруты админа работают и с выключенным флагом; маршруты телефона при выключенном — 404.
- Выгрузка — строки журнала как есть (`application/x-ndjson`, можно `Content-Encoding: gzip`), границы пачки в запросе; `ackedSeq` — только то, что сервер хранит. Законченный прогон ещё 30 минут (`hovanki.lab.upload-grace`) принимает последние пачки.
- План считают одинаково сервер и телефон (`LabRunPlan`, `:shared`); конец прогона — только по ответу сервера. Отчёт версии 1 — покрытие, карман, вибрация, батарея, маски, тики; карточек техник и «без X» нет.
- Мак: `run.sh --lab --run <код>` (`MacServerRun`). Каталог планов: `radio` и короткий `e2e` для тестов; `MacSetup` убрана, шаг Мака — `PhoneSetup`.

**Не проверено:** Android и iOS сборки `:composeApp` (в облаке нет Android SDK), вход по QR камерой в карточке, обратный отсчёт, iOS-`gzip`; `:e2e:devices` не гонялся. Проверено: `:shared:jvmTest`, `:clientCore:jvmTest`, `:server:test`, `:e2e:test` (весь набор, включая `LabRunTest`). Чтобы закрыть «Готово, когда» целиком, нужен один выход с настоящим Android.

## 2. Шаг 2 — модули `:radar` и `:device` ✅ (2026-09-30)

Цель: перенос без изменения поведения.

- Новые KMP-модули по правилам AGP 9 из `CLAUDE.md` (`com.android.kotlin.multiplatform.library`, `kotlin { android { … } }`, цели jvm, android, iosArm64, iosSimulatorArm64), версии — только в `libs.versions.toml`.
- В `:radar`: `clientCore/radio/*` (`ProximityRadio`, `RadioSighting`, `RadioTrace`, `PrecisionRadio`), `AndroidProximityRadio`, `IosProximityRadio`, `AndroidLabAir`, `IosLabAir`, `LabAir`, `AirFrame`.
- В `:device`: `CarryMonitor`, `ActivityMonitor`, `ActivityClassifier`, `PocketPulse`, `DeviceInfo` с их Android- и iOS-реализациями; пробы лаборатории `LabProbes`, `LabScreen`, `LabHaptics` с реализациями.
- `:clientCore` зависит от обоих; Koin-привязки остаются в `composeApp/di`. Разрешения — по-прежнему в манифесте `androidApp`.
- Попутно: `haptic` пишет причину остановки Core Haptics именем (`audio_session_interrupt`, `application_suspended`, …), а не числом.
- Тест на границы: техники разных пакетов `:radar` не импортируют друг друга (простой тест, читающий исходники).

**Готово, когда:** `check` и `:e2e:test` зелёные, Android собирается в CI, владелец собрал iOS и нынешний прогон `RADIO` идёт как раньше.

**Как сделано** (полный список — «Отличия реализации» в [ADR 0017](adr/0017-radar-techniques-and-big-run.md#шаг-2-2026-09-30)):

- Пакеты: `app.hovanki.radar` (`ProximityRadio`, `PrecisionRadio`, `AndroidProximityRadio` с `RADAR_PERMISSIONS`, `IosProximityRadio`) и `app.hovanki.radar.lab` (`LabAir`, `AirFrame`, `ProbeEvent`, `AndroidLabAir`, `IosLabAir`); `app.hovanki.device` (`CarryMonitor`, `ActivityMonitor`, `ActivityClassifier`, `PocketPulse`, `DeviceInfo` с реализациями) и `app.hovanki.device.lab` (`LabProbes`, `LabScreen`, `LabHaptics`, `CarryFeatures`, `HapticStopReason` с реализациями). Файлы перенесены `git mv`.
- В `:clientCore` остались `tracking/BackgroundTracker` и `HiderAlerts` (слежение игры, а не датчики), вся `lab/`, `LabFiles` и `LabRadioTrace` (пишет в `LabLog`, которого `:radar` не знает). `:clientCore` берёт оба модуля через `api`; `:radar` и `:device` друг от друга не зависят. В `:composeApp` — Koin, `BluetoothPermission`, `AndroidLabFiles`/`IosLabFiles` (выделены в свои файлы).
- `IosPocketPulse` получает тексты уведомления параметром (`notificationText`): у `:device` нет ресурсов Compose, привязка в `PlatformModule.ios.kt` читает `Res.string`, как раньше.
- `haptic` пишет `engine_stopped: audio_session_interrupt (1)` (`HapticStopReason`, тест в `commonTest`). `LabController` кладёт `engine_stopped` в `result`, причину — в `reason`: раньше всё шло в `result`, и отчёт остановок не считал. Попутно в манифест `composeApp` добавлен `WAKE_LOCK`: без него `PROXIMITY_SCREEN_OFF_WAKE_LOCK` лаборатории на Android бросал бы `SecurityException` (на телефоне не проверялось).
- `ModuleBoundariesTest` (`jvmTest` обоих модулей) читает исходники: подпакеты не импортируют друг друга, `:radar` не видит `:device`, `:clientCore` и Compose, `:device` — `:radar`, `:clientCore`, Compose и его ресурсы.
- `ActivityClassifierTest` и `CarryFeaturesTest` (из `LabPartsTest`) — в `:device`; CI гоняет `:device:iosSimulatorArm64Test`. У `:device` свой манифест только с `VIBRATE` (для lint модуля; слитый манифест приложения не меняется).

**Не проверено:** сборка Android (в облаке нет SDK — CI), iOS-приложение и тесты на симуляторе (Мак), прогон `RADIO` на телефонах. Проверено: `spotlessCheck`, `:shared:jvmTest`, `:radar:jvmTest`, `:device:jvmTest`, `:clientCore:jvmTest`, `:server:test`, `:e2e:unitTest`, метаданные `commonMain`/`iosMain` у `:radar`, `:device`, `:clientCore`, `:composeApp`, `:e2e:test`.

## 3. Шаг 3 — каналы и хосты ✅ (2026-09-30)

Цель: каждый способ передать жетон — канал; реклама собирается хостом; все показания и кадры несут `tech`.

- Общий код (`:radar`, корневой пакет и `channel/<id>/`): `RadarChannel` (id, вклад в рекламу, что слушать, разбор кадра), `AirFrame` (общая модель кадра: имя, UUID сервисов, UUID маски, service data, данные производителя, iBeacon, RSSI, `peer`, API), `AdPart`, `ScanInterest`.
- `AdBudget` — чистая функция, считающая байты обычной рекламы Android так же, как `BluetoothLeAdvertiser.totalBytes`: 2 байта на поле, 128-битный UUID — 16, флаги только у connectable. Тест: нынешняя реклама прячущегося — 40 байт, не влезает; три раскладки — влезают.
- Каналы: `ble.service_data` в трёх раскладках (`.scan_response`, `.bare`, `.mfr`), `ble.name`, `ble.ibeacon` (ranging), `ble.ibeacon.region` (вход и выход из региона — сейчас мониторинг включён, но `didEnterRegion` никто не ловит), `ble.overflow` (кодирование, маска в рекламе iPhone, разбор у Android, iPhone на экране и Мака; «замёрзший» жетон).
- Хосты: `AndroidAirHost`, `IosAirHost` (одна реклама `CBPeripheralManager` из вкладов, в фоне не трогать; один скан с фильтром по всем интересам), `JvmAirHost` — симулятор эфира с правилами ОС (iPhone в фоне: имя и данные выброшены, UUID → биты маски, iBeacon не вещается; Android: 31 байт). `bot/Radio.kt` переходит на него.
- Журнал: `frame` (свои кадры целиком), `air` (чужие раз в секунду), `adv` с раскладкой байт.
- Игра по-прежнему видит только `ProximityRadio.run`, но внутри — хост с каналами из каталога. Раскладка Android в игре **меняется** на `scan_response` (в плане было «решит прогон»): прежняя не влезает в 31 байт, и честный симулятор её не пропустил бы; прогон сравнивает `scan_response` с `bare` и `mfr` (см. «Как сделано»).

**Готово, когда:** тесты кодеков, бюджета и симулятора зелёные; `RadarTest` и остальные e2e зелёные на симуляторе эфира; владелец собрал iOS.

**Как сделано** (решения и отличия — «Отличия реализации» в [ADR 0017](adr/0017-radar-techniques-and-big-run.md#шаг-3-2026-09-30)):

- Пакеты `:radar`: корень `app.hovanki.radar` (модель, `RadarCatalog`, `AdBudget` с `AdPlan`, `AirTally`, `OsRules`, `AirHost`, `RadarTrace`, `HostProximityRadio`, `ProximityRadio`); `channel.servicedata`, `channel.name`, `channel.ibeacon`, `channel.overflow`; `host` (`AndroidAirHost`, `IosAirHost`, `JvmAirHost` с `SimulatedAir`); `lab` (`LabAir`, `HostLabAir`). Удалены `AndroidProximityRadio`, `IosProximityRadio`, `AndroidLabAir`, `IosLabAir`, `RadioTrace` (вместо неё `RadarTrace`). `ModuleBoundariesTest`: пакет канала импортирует только корень и `:shared`, `host` и `lab` друг друга не знают.
- Семь каналов в `RadarCatalog`; статус `GAME` у `ble.service_data.scan_response`, `ble.name`, `ble.ibeacon` и `ble.ibeacon.region` (то, что игра вещала и слушала; регион iOS мониторил и раньше), остальные — `LAB`, включая `ble.overflow` (ADR 0016 за `OVERFLOW_RADAR` не реализован). Таблица каналов — в [architecture.md](architecture.md#радар-каналы-и-хосты).
- Игра: `HostProximityRadio(host)` берёт `RadarCatalog.game`; показание — `RadioSighting` с `tech`. Протокол игры не менялся: `tech` только в `RadioSighting` и в журнале лаборатории.
- Что сравнивает прогон: шаг плана может задать каналы (`PhoneSetup.techniques`, id из каталога; неизвестный id журнал отмечает и пропускает), так что `scan_response`, `bare` и `mfr` можно вещать по очереди и слушать «слушать всё» (`HostLabAir`: интересы всех каналов и сырые данные Apple). План `radio` не менялся; шаги, где раскладки идут друг против друга, пишет шаг 7.
- Журнал ([radio-lab.md §4.1](radio-lab.md)): `frame` — свои кадры целиком, `air` — чужие раз в секунду (счёт и биты, без содержимого), `adv` с `tech` и `layout` (байты по пакетам и что отброшено: `dropped`), `rx` с `tech`, в `scan` — `region_enter`/`region_exit`. Схема остаётся 2.
- Симулятор эфира (`SimulatedAir`, `JvmAirHost` на JVM, правила `OsRules` в `commonMain`) заменил ручные правила «кто кого слышит» в `bot/Radio.kt`; правила ОС перечислены в [e2e.md](e2e.md) («Радио») и в ADR.
- Проверено на JVM: кодек каждого канала (`commonTest`: круг «жетон → кадр → жетон» и отрицательный случай), `AdBudget` (40 байт прежней раскладки, 18 + 22 у `scan_response`, 22 у `bare`, 24 у `mfr`), `OsRules`, `AirTally`, `HostProximityRadio`, `RadarCatalog`; `SimulatedAir` с `JvmAirHost` (Android-прячущийся на прежней раскладке не слышен никому, на `scan_response` слышен всем; iPhone в фоне на `ble.name` не слышен никому, на `ble.overflow` слышен Android маской и iPhone на экране UUID; маяк ищущего заблокированный iPhone слышит только ranging'ом; регион входит и выходит); `LabLog` (`frame`/`air`/`adv`), `LabController` на поддельном хосте; `RadarTest`, `ProximityCatchTest`, `LabRunTest` и остальное на симуляторе.

**Не проверено:** `AndroidAirHost` (сборка Android — в облаке нет SDK, соберёт CI; на телефоне — ни `ADVERTISE_FAILED_*`, ни два фильтра скана, ни что iPhone склеивает ответ на скан с рекламой) и `IosAirHost` (написан вслепую, компилируются только метаданные `iosMain`; сборка, регион и ranging — на Маке). Ни одна цифра dBm и ни одно правило iOS из симулятора на настоящих телефонах не проверены: симулятор — модель того, что мы про них знаем. Проверено: `spotlessCheck`, `:shared:jvmTest`, `:radar:jvmTest`, `:device:jvmTest`, `:clientCore:jvmTest`, `:server:test`, `:e2e:unitTest`, `:e2e:test`, метаданные `iosMain`/`commonMain` у `:radar`, `:clientCore`, `:composeApp`.

**Владельцу:** собрать iOS на Маке и прислать ошибки `IosAirHost` в ту же сессию.

## 4. Шаг 4 — чоканье, тени, карточки ✅ (2026-09-30)

Цель: каждый конкурент из таблицы «Конкурируют» (ADR 0017 §2.3) получает ответ из журналов — чистыми функциями над событиями.

- `:shared`: `TouchDetector` (удар в акселерометре у двух телефонов в ±150 мс по часам сервера + пик RSSI), `Smoothing` в трёх вариантах (`ema`, `p80`, `rate`), `Calibration` в трёх (`none`, `model`, `touch`), `WitnessInference`, «без X». Всё — чистые функции над событиями журнала.
- Телефон: кнопка «Чокнулись с …» (истина для детектора: `mark` с `action = touch`), событие `impact` (сырьё для детектора), классификатор `carry.v2` в `:device` по radio-lab.md §7.3 в тени (`shadow`). Сглаживания и калибровки на телефоне не тенятся: отчёт пересчитывает их из `rx`.
- Отчёт: карточки техник против критериев ADR 0017 §2.3; «без X»; калибровки и чоканье; карман против разметки, `carry.v1` и `carry.v2` рядом.
- Тесты на придуманных журналах: детектор находит касание и не находит обычную ходьбу; «без X» на канале, который единственный слышал, даёт расхождение.

**Готово, когда:** тесты на придуманных журналах зелёные (`:shared`, `:device`, `:clientCore` на JVM), отчёт на журнале без новых событий не падает и даёт пустые списки и карточки «мало данных», Android и iOS собираются в CI и на Маке.

**Как сделано** (решения, пороги и отличия — «Отличия реализации» в [ADR 0017](adr/0017-radar-techniques-and-big-run.md#шаг-4-2026-09-30)):

- `:shared`, `rules/Smoothing.kt`: `SignalSmoother` (добавить показание, спросить полосу), `Smoothings` (`smooth.ema`, `smooth.p80`, `smooth.rate`), `EmaSmoother` (сам `RadarSmoother`), `PercentileSmoother` (80-й процентиль за 2,5 с), `RateSmoother` (уровень EMA плюс поправка за частоту показаний). Игра по-прежнему держит `RadarSmoother`.
- `:shared`, пакет `lab`: `LabRadar.kt` (`LabBandTruth` — полоса по расстоянию; `LabDistances` — расстояние пары по секундам из шагов сценария и отметок с `distance`; `Calibration`/`Calibrations`; `BandTrack` — полосы всех направлений и пар под одним сглаживанием и одной калибровкой; `BandErrors` — точно / мимо на одну / мимо / средняя ошибка для всех девяти сочетаний), `LabTouch.kt` (`Touch`, `TouchDetector`, `TouchSpread`: разброс и дрейф), `LabInference.kt` (`WitnessInference`), `LabWithout.kt` (`WithoutChannel`), `LabCards.kt` (`TechniqueCards`, `TechniqueCard`, `Verdict`, `CardFacts`). `LabReportBuilder` собирает всё в отчёт; `LabMerge` получил `carryMatricesByTech()` и `techOf(rx)`.
- Отчёт (`LabReport`, версия та же, новые поля с умолчаниями, старые отчёты читаются): `cards`, `touches`, `touchSpreads`, `missedTouches`, `calibrations`, `bands`, `without`, `witness`, `LabReportCarry.tech`, `LabReportDirection.tech`. На журнале без новых событий — пустые списки, карточки «мало данных», ничего не падает.
- Телефон: `:device` — `CarryClassifier` (`carry.v2`), `ImpactDetector` (`app.hovanki.device.lab`), `Gravity` и `Orientation` теперь в корне пакета (в `lab` — `typealias`); акселерометр 50 Гц (Android `SENSOR_DELAY_GAME`, iOS `deviceMotionUpdateInterval = 0,02`). `:clientCore` — `LabLog.impact` и `LabLog.shadow`; `LabController` кормит `ImpactDetector` показаниями датчиков, раз в секунду кормит `CarryClassifier` и пишет `shadow`, `touched(метка)` пишет отметку касания. `:composeApp` — в карточке «Marks» лаборатории строка «Touched with …»: в прогоне на сервере кнопка на каждую другую метку прогона, вне прогона — поле для метки и кнопка.
- Админка (`admin.js`, страница отчёта): сразу после «Проблем» — «Карточки техник» (вердикт: «оставить» / «выбросить» / «мало данных», условие, числа, чего не хватило); после «Кто кого слышал» (в таблице новая колонка «Техника») — «Чоканье» (найденные касания, разброс и дрейф по направлениям, пропущенные отметки), «Калибровки» (поправки в дБ), «Полосы против расстояний» (доля точных секунд: зелёный от 80 %, жёлтый от 60 %), «Без канала», «Свидетель»; «Карман» — по матрице на каждую пару (метка, `carry.v1`/`carry.v2`).
- Журнал ([radio-lab.md §4.1](radio-lab.md)): `impact` {`peak`, `ago`}, `shadow` {`tech`, `state`, `reason`}, `mark` с `action = touch`. Схема остаётся 2.

**Проверено:** тестами на придуманных журналах: `SmoothingTest`, `LabRadarTest`, `LabTouchTest`, `LabInferenceTest`, `LabWithoutTest`, `LabCardsTest`, `LabReportBuilderTest` (в `:shared`: сглаживания, калибровки, ошибки полос по расстояниям шагов и отметкам, касание находится и совпадает с отметкой, ходьба не находится, свидетель, «без X», карточки, отчёт целиком и на старом журнале), `CarryClassifierTest` и `ImpactDetectorTest` (`:device`), `LabControllerTest` (`:clientCore`: `impact`, `shadow`, отметка касания); `admin.js` — `node --check`. Прогнано: `spotlessCheck`, `:shared:jvmTest`, `:radar:jvmTest`, `:device:jvmTest`, `:clientCore:jvmTest`, `:server:test`, `:e2e:unitTest`, метаданные `commonMain`/`iosMain` у `:radar`, `:device`, `:clientCore`, `:composeApp`, весь `:e2e:test`.

**Не проверено:** ни одна цифра не с настоящих телефонов, все пороги (удар 0,6 g, пик касания +6 дБ, полосы 1,5 / 4 / 15 м, опорные −40 и −60 дБм, `carry.v2`, критерии карточек) — догадки плана до прогона. Телефонная часть собирается только в CI (Android; в облаке нет SDK) и на Маке (iOS: 50 Гц `deviceMotion` написан вслепую); отчёт в админке не открывался в браузере.

**Владельцу** (на телефонах, когда дойдёт до прогона): в лаборатории нажать «Touched with …» и метку другого телефона сразу после касания — телефоны стукаются друг о друга спина к спине одним ударом, три касания на пару в начале и одно в конце (§3 ADR: повторяемость и дрейф); кнопку можно нажать на одном из двух телефонов (отметки пары в пределах 2 с — одно касание). Расстояния между парами — как раньше: кнопки «Distance» в «Marks» или шаги сценария с `distances`. Отчёт откроется в админке после прогона, карточки — первым разделом.

## 5. Шаг 5 — два iPhone в карманах ✅ (2026-09-30, вслепую)

Самый вслепую написанный шаг: код есть весь, но iOS-часть ни разу не собиралась на Маке, а Android-часть — только в CI (в облаке нет Android SDK). Прогон на телефонах — впереди (§5.4). Решения и отличия от плана — «Отличия реализации», «Шаг 5» в [ADR 0017](adr/0017-radar-techniques-and-big-run.md#шаг-5-2026-09-30).

Как это включается: шаг сценария называет техники по id в `PhoneSetup.techniques` (сценарий `big_run` уже называет их, §7), а `LabController.setTechniques` раздаёт их: каналы — радио стенда (как раньше), `mode.*` — `BackgroundModes`, `gatt.link` — `GattLink`, `uwb.ni` — `PrecisionRadio` с остальными устройствами прогона, `pulse.*` — чем бьёт пульс лаборатории. Незнакомый id по-прежнему пишется в `note` и пропускается; то, чего у телефона нет (режим, UWB), пишется один раз и пропускается. В игре ничего не меняется: она не включает ни режимов, ни связи, а её `PrecisionRadio` остаётся `NoopPrecisionRadio`.

### 5.1 Вибрация и режимы ✅

- `mode.audio`: `AVAudioSession` категории `playback` с `mixWithOthers`, беззвучный WAV по кругу (`AVAudioPlayer`, громкость 0); выключение — `setActive(false)` с `notifyOthersOnDeactivation`. Прерывания (звонок, Siri, будильник; после конца плеер запускается снова), смены маршрута и сброс медиасервисов — события `mode`. `pulse.core_haptics.audio` — второй `CHHapticEngine`, созданный на этой общей сессии (`HapticKind.CORE_HAPTICS_AUDIO`); без `mode.audio` он играет на сессии по умолчанию.
- `mode.notification_wake`: локальное уведомление раз в 20 с («wake N», без звука), но не циклом в приложении — в фоне цикл спит вместе с ним, — а заранее поставленными в очередь триггерами на 10 минут вперёд (30 штук); цикл только доливает очередь и пишет `notification_sent`, когда уведомлению пора. Отчёт смотрит, появились ли маски и ranging в пределах 10 с после каждого.
- Тест вибрации: групп столько, сколько `LabHaptics.kinds`, на iPhone теперь пять (1 Core Haptics, 2 Core Haptics на аудиосессии, 3 impact, 4 уведомление с беззвучным звуком, 5 без звука).
- Пульс: `pulse.core_haptics`, `pulse.core_haptics.audio`, `pulse.live_activity` (Core Haptics при включённой Live Activity), `pulse.impact`, `pulse.notify_silent_sound`, `pulse.notify` выбирают, чем бьёт `PhoneSetup.pulse`; несколько сразу — цепочка «первое, что сработало» (ADR 0017 §2.3): бьёт первый, который есть у телефона, а после первой ошибки — следующий.

**Как сделано:** `:device` — `BackgroundModes` (`ModeIds`, `ModeResult`, `ModeEvent`, `NoopBackgroundModes`) и `LiveActivityHost` в корне пакета, `IosBackgroundModes` и общие для лаборатории куски (`IosLabSupport.kt`: запрос уведомлений, беззвучный WAV) в `iosMain`, `HapticKind.CORE_HAPTICS_AUDIO` в `IosLabHaptics`; Android — `NoopBackgroundModes` (приложение там и так держит foreground service). Фоновые режимы `audio` и `nearby-interaction` и `NSSupportsLiveActivities` добавляет в Info.plist только Debug-сборки `debug-info-plist.sh`. Журнал — событие `mode` {`mode`, `event`, `reason`}: `on`, `off`, `failed`, `unavailable` от лаборатории и события самих режимов; отдельного вида `audio` нет (§4 ADR 0017 его называл).

### 5.2 GATT (`gatt.link`) ✅

- Каждый телефон с `gatt.link` держит один сервис (`RadarService.LINK_UUID`) с одной характеристикой жетона (`LINK_TOKEN_UUID`: чтение, запись, notify; значение — 4 байта жетона, который вещает радио стенда), рекламирует сервис (заблокированный iPhone — только битом overflow: это и меряем), сканирует его и подключается к каждому найденному (не больше `MAX_LINKS` = 5; подключаются обе стороны пары), читает жетон, подписывается на notify, пишет свой жетон раз в 5 с (запись будит приложение партнёра), читает RSSI раз в 3 с, переподключается после разрыва. Без сопряжения и шифрования.
- Журнал `link`: каждый шаг связи (`connect`, `connected`, `services`, `subscribed`, `wrote`, `notified`, `disconnected` с ошибкой ОС, `reconnect`, `forget`, `identifier_changed`, `central_*` серверной стороны…) и `reading` — жетон или RSSI через связь (`peer` хешируется, как в `rx`).

**Как сделано:** `:radar`, пакет `link/` (импортирует только корень и `:shared`, `ModuleBoundariesTest`): `GattLink`, `LinkReading`, `LinkTrace`, `GattLinkRules`, `LinkTechnique`, `NoopGattLink`, чистые `LinkOperations`/`LinkPeers`; Android — `AndroidGattLink` (`BluetoothGattServer` + `BluetoothLeScanner` + `connectGatt(TRANSPORT_LE)`, свой `AdvertiseCallback` рядом с рекламой игры, операции по одной на соединение с тайм-аутом); iOS — `IosGattLink` (второй `CBPeripheralManager` и свой `CBCentralManager` рядом с менеджерами игры, делегат на каждого партнёра, повторный `connect` после разрыва: iOS держит запрос без тайм-аута — так и переподключаются в фоне). RSSI читает только подключившаяся сторона. `identifier_changed` — когда новый id прочитал жетон молчащего старого. Лаборатория пишет в связь тот же жетон, что вещает радио стенда (`DiagnosticsBench.advertisedToken`), и показывает в карточке прогона «link: N peers».

### 5.3 UWB (`uwb.ni`) — в два захода

**Заход 1 ✅** (без нового таргета). `IosPrecisionRadio` (`:radar`, пакет `uwb/`): Nearby Interaction, одна «домашняя» `NISession` на iPhone, её токен (`NIDiscoveryToken`, `NSKeyedArchiver` с secure coding, base64) телефон отдаёт в прогон (`POST /api/v1/lab/runs/{runId}/uwb`, V12: `lab_devices.uwb_token`), состояние прогона перечисляет токены всех устройств по меткам (`LabRunStateView.uwbTokens`). `LabRunFollower` при входе в прогон готовит радио (`prepare()`), отдаёт токен и каждый новый (после инвалидации сессии он меняется), каждый ответ сервера передаёт `LabController.setUwbPeers`; шаг с `uwb.ni` ранжирует со всеми метками, кроме своей. Сессия одна — значит, партнёр один: первый по метке, остальные пишутся в `range` как `config` с ошибкой (для двух iPhone шага 5 этого хватает). Журнал `range`: `reading` {`peer` — метка, `m`, `deg`} и шаги сессии (`session_start`, `config`, `running`, `suspended`, `suspension_ended`, `removed`, `invalidated`, `rerun`, `stop`); токены в журнал не пишутся. `LabCapabilities.uwb` при входе — есть ли чип (`NISession.deviceCapabilities`). Проверяем на экране и с `screenOff` (экран погашен датчиком, приложение активно).

**Заход 2 ✅ со стороны Kotlin, Swift-файлы готовы — таргет добавляет владелец.** `mode.live_activity` (и `pulse.live_activity`) запускает Live Activity, когда приложение уходит с экрана (`UIApplicationWillResignActiveNotification`: позже iOS её не даст), раз в минуту обновляет время, выключение — `end()`. ActivityKit есть только в Swift, поэтому: `LiveActivityHost` в `:device` → `BridgedLiveActivityHost` в `composeApp/iosMain` (`LiveActivityBridge.kt`) → Swift-класс `HovankiLiveActivityHost`, который `ContentView.swift` ищет по имени (`NSClassFromString`) и отдаёт в Kotlin. Без него приложение собирается и работает, а `mode.live_activity` пишется как `unavailable`. Файлы: `iosApp/iosApp/LiveActivity/` (атрибуты — в оба таргета, хост — в приложение), `iosApp/HovankiLive/` (виджет расширения).

**Не проверено (всё):** ни одна строка iOS-части не собиралась (`IosBackgroundModes`, аудиосессия у `CHHapticEngine`, `IosGattLink`, `IosPrecisionRadio`, Swift-файлы, `debug-info-plist.sh`), Android-часть `gatt.link` собирается только в CI и не запускалась. Живёт ли `mode.audio` на блокировке, будит ли запись GATT заблокированный iPhone, работает ли UWB с Live Activity, когда оба заблокированы, — это и есть вопросы прогона. Проверено на JVM: `LabTechniquesTest` (режим по id и его события, связь пишет `link`, UWB с партнёром пишет `range`, пульс выбирает свой способ и переходит к следующему), `LabRunFollowerTest` (токен UWB уходит на сервер, токены прогона — в контроллер), `GattLinkTest`/`LinkOperationsTest`/`LinkPeersTest`, `UwbTechniqueTest`, `BackgroundModesTest`, серверный `LabApiTest` (токен одного устройства виден другому по метке), весь `:e2e:test --tests '*LabRunTest*'`.

### 5.4 Что делает владелец в Xcode

Точные шаги — в [iosApp/README.md](../iosApp/README.md#радиолаба-live-activity-и-фоновые-режимы), раздел «Радиолаба: Live Activity и фоновые режимы». Коротко:

1. Убрать наши файлы расширения в сторону (`mv iosApp/HovankiLive /tmp/HovankiLive-ours`), File → New → Target → Widget Extension `HovankiLive` с «Include Live Activity», тот же Team, bundle id — id приложения плюс `.live` (вписать руками), iOS 16.2.
2. Удалить Swift-файлы шаблона, вернуть наши (`cp /tmp/HovankiLive-ours/*.swift iosApp/HovankiLive/`), добавить `iosApp/iosApp/LiveActivity/`: `HovankiLiveAttributes.swift` — в оба таргета, `HovankiLiveActivityHost.swift` — только в приложение.
3. Background Modes не трогать: `audio` и `nearby-interaction` добавляет скрипт Debug-сборки (проверить в собранном `Hovanki.app/Info.plist`).
4. Собрать схему `iosApp` на оба iPhone, ошибки компиляции прислать целиком. **`project.pbxproj` с расширением не коммитить** (TestFlight подписывает всё одним профилем).

**Что смотреть первым при ошибке:**

- Не собирается `:device` для iOS на `CHHapticEngine(audioSession = …)` — строка с приведением `AVAudioSession.sharedInstance() as objcnames.classes.AVAudioSession` в `device/src/iosMain/.../lab/IosLabPlatform.kt` (`newEngine`): инициализатор объявлен с forward declaration, приведение только переименовывает тип. Если компилятор не пускает — прислать ошибку; временно можно вернуть `CHHapticEngine(andReturnError = …)` для этого вида.
- Live Activity не появляется и в журнале `mode.live_activity` — `unavailable`: `ContentView.swift`, `NSClassFromString("HovankiLiveActivityHost")` вернул `nil` — хост не в таргете приложения или у класса нет `@objc(HovankiLiveActivityHost)`. `refused` — Настройки → Hovanki → Live Activities.
- `gatt.link` на iPhone: в приложении два `CBPeripheralManager` (игры и связи) и два `CBCentralManager`. Если в журнале `advertise_failed` или реклама игры пропадает, когда включается связь, — это они мешают друг другу; прислать `adv` и `link` за эту минуту.
- `range` с `config` и ошибкой `the peer's token did not unarchive` — токен партнёра не разархивировался (`NSKeyedUnarchiver` с secure coding): проверить, что оба iPhone на одной сборке.
- `range` `invalidated` с кодом `-5884` — пользователь не разрешил Nearby Interaction (вопрос появляется при первом `run`): Настройки → Конфиденциальность → Nearby Interaction → Hovanki.

**Какие прогоны пробовать первыми:** E3 (вибрация из фона) и E6 (ranging на заблокированном) из [radio-lab.md §11](radio-lab.md#11-выход-1-пошагово-iphone--мак) — с новыми группами теста вибрации; затем в `big_run` блоки 3 (вибрация и режимы: `b3_audio_*`, `b3_live_activity_*`, `b3_notification_wake`) и 4 (два кармана: `b4_gatt_*`, `b4_uwb_*`, `b4_both_*`) — «Пауза» на пульте между ними, чтобы посмотреть живой вид.

## 6. Шаг 6 — Wi-Fi Aware — отложен (2026-09-30)

Решение владельца по исследованию: связь iPhone–Android по Wi-Fi Aware не подтверждена ни одной работающей парой, а пар iPhone–iPhone с iOS 26 и iPhone 12+ у обоих — 5–8 % (цифры и источники — ADR 0017, «Отличия реализации», «Шаг 6 отложен»). Не делаем, пока не появится подтверждённая связь iPhone–Android или доля Android с Aware 4.0. Ниже — план на этот случай.

- Только iPhone 12+ на iOS 26. API только на Swift (`WiFiAware` + `Network`) → мост `WifiAwareHost` в `iosApp`, интерфейс в `:radar`.
- Сопряжение — системным окном `DeviceDiscoveryUI`, один раз на пару, в блоке 0 прогона.
- Нужен entitlement `com.apple.developer.wifi-aware` и `WiFiAwareServices` в `Info.plist`. **Сначала владелец проверяет**, даёт ли его бесплатный аккаунт (Signing & Capabilities → «+ Capability» → Wi-Fi Aware). Не даёт — шаг ждёт платного аккаунта, прогон идёт без него.
- Android: `WifiAwareManager`, publish/subscribe, сопряжение по стандарту.
- Журнал `aware`: сопряжение, соединение, отчёт о связи (ищем в нём уровень сигнала).

## 7. Шаг 7 — сценарий большого прогона ✅ (2026-09-30)

- Сценарий `BIG_RUN` в `:shared/lab/LabScenarios.kt` по ADR 0017 §6: блоки 0–8, у каждого шага — техники по меткам, указания, истина. Проверка сценария (валидатор) — тест.
- Чек-лист в [radio-lab-tests.md](radio-lab-tests.md): телефоны, заряд, разрешения, «Фокус», рулетка, два человека, как создать прогон, что делать, если что-то красное в живом виде.

**Как сделано** (решения — «Отличия реализации» в [ADR 0017](adr/0017-radar-techniques-and-big-run.md#шаг-7-2026-09-30)):

- `LabRunScripts.BIG_RUN` (`big_run`, версия 1, метки `A`, `B`, `droid`, `mac`) в `ALL`: админка и сервер показывают его в списке сценариев сами, `of(version)` для локального прогона Мака по-прежнему знает только `RADIO`. 107 шагов, у каждого таймер, всего 6412 с (≈ 107 мин, в ADR — 108): блок 0 — 600 с, 1 — 297, 2 — 1185, 3 — 890, 4 — 1200, 5 — 860, 6 — 600, 7 — 600, 8 — 180. Id шагов начинаются с блока (`b0_`…`b8_`).
- В каждом шаге у каждого телефона — `PhoneSetup` (с техниками по id каталога §2.3), место и действие (истина кармана), указание на английском; у шага — указание для пульта и расстояния пар (истина полос), кроме чоканья и ухода в мини-игре, где расстояние меняется.
- Тесты в `LabRunScriptTest`: сценарий строится (валидатор: заблокированный шаг не меняет рекламу), идёт сам 90–110 мин, блоки 0–8 по порядку и каждый в пределах минуты от таблицы §6, каждый шаг называет `A`, `B` и `droid` с указанием и истиной, у каждой пары три касания в блоке 1 и одно в блоке 8, в блоке 5 у каждой пары все шесть расстояний и в руке, и в кармане, все id техник — из списка каталога (id шага 5 помечены как ещё не написанные), блок 2 блокирует `A` и `B`, а шаг сразу после заблокированного не меняет рекламу iPhone (телефон ещё заблокирован, когда он начинается).
- Чек-лист и блоки для тестировщиков — [radio-lab-tests.md](radio-lab-tests.md#большой-прогон-big_run-100-мин), «Большой прогон».

**Проверено:** `:shared:jvmTest`, `:server:test`, `:e2e:unitTest`, `:e2e:test --tests '*LabRunTest*'` (e2e-прогон по-прежнему идёт по `E2E`), `spotlessApply`.

**Не проверено:** сам прогон — ни один шаг не пройден на телефонах; длительности шагов (35 с на точку дорожки, 33 с на касание) — догадки, «Повторить» и «Пауза» на пульте для того и есть. Техники шага 5 (`mode.audio`, `mode.notification_wake`, `pulse.core_haptics.audio`, `gatt.link`, `uwb.ni`, `mode.live_activity`, `pulse.live_activity`) названы, но не написаны: телефон пишет `unknown techniques …: left out` и идёт с остальными, пока их нет (так же он пропускает `pulse.core_haptics` — это пульс лаборатории, `PhoneSetup.pulse`, id только называет его для отчёта). С шагом 5 они включатся в тех же шагах без правки сценария, если id останутся теми же и `LabController.setTechniques` будет искать их не только в `RadarCatalog` (сейчас — только там; режимы и пульсы шагу 5 нужно будет туда подключить, например через `DeviceCatalog`). *Сделано в шаге 5 (§5): `setTechniques` знает эти id сам (`LabController.LAB_TECHNIQUES`), без `DeviceCatalog`.*

## 8. Как добавить технику

Для любой новой идеи — одна и та же последовательность:

1. **Строка в каталоге ADR 0017 §2.3:** id, что это, главный вопрос, **критерий «оставляем, если»** — до кода.
2. **Код** в своём пакете: `:radar/channel/<id>/`, `:radar/link/<id>/`, `:device/mode/<id>/`, `:device/pulse/<id>/` или чистая функция в `:shared`. Чужие техники не импортировать; общее — через интерфейсы модуля.
3. **Регистрация** в каталоге платформы (`RadarCatalog`, `DeviceCatalog`) со статусом `LAB` и `available()` — что нужно телефону.
4. **Журнал:** каждое действие и каждый результат — событие с `tech = <id>`; ошибки — текстом ОС, не «failed».
5. **Симулятор:** если техника меняет эфир — правило в `OsRules` (чистая функция в `commonMain`), которым пользуются `SimulatedAir` и `JvmAirHost`, чтобы боты вели себя честно.
6. **Отчёт:** карточка техники — функция в `:shared/lab`, которая по событиям прогона считает её критерий.
7. **Сценарий:** шаги, где она включена одна и вместе с остальными.
8. **Тесты:** кодек или правило — `commonTest`; карточка — на придуманном журнале.

Выбросить технику — обратный порядок: удалить пакет, строку каталога, правило симулятора, карточку; в ADR 0017 перенести строку на «кладбище» с цифрами из отчёта.

## 9. Что нужно от владельца

- Модели: второй iPhone (модель, iOS), Android (модель, версия, есть ли UWB и Wi-Fi RTT).
- Сборки iOS после шагов 2, 3 и каждой части шагов 5–6; ошибки Xcode — в ту же сессию.
- Действия в Xcode из §5.4.
- Включить `RADIO_LAB` на своём сервере перед выходом, выключить после.
