# ADR 0007. Наблюдаемость: метрики, логи, трейсы и телеметрия клиента

- **Статус:** Proposed (черновик-план, в коде не реализовано)
- **Дата:** 2026-09-28
- **Автор:** Denys Bohusevych (черновик — Claude Code)

> **План, а не описание кода.** Сегодня у сервера есть только `/actuator/health` и `/actuator/info`, логи — консоль контейнера с ротацией Docker, у приложения — Logcat и консоль. Документ фиксирует, какой стек наблюдаемости берём, что измеряем, что логируем, как собираем краши и метрики с телефонов и в каком порядке это внедряем. При принятии статус меняется на Accepted, рецепты «как добавить метрику или событие» переезжают в [architecture.md](../architecture.md), настройка — в [deploy.md](../deploy.md). Инструменты администратора и модератора (роли, жалобы, баны, режим обслуживания) — отдельный [ADR 0008](0008-admin.md).

## Контекст

Что есть:

- **Сервер.** Spring Boot 4.1 со `spring-boot-starter-actuator`; наружу через Caddy открыты `health` (с probes) и `info`. Реестр метрик Micrometer есть, но их никто не экспортирует и не читает. Логи — Logback в консоль обычным текстом, `docker compose logs`, ротация 3 файла по 10 МБ ([deploy.md](../deploy.md#обслуживание)). Логируются непредвиденные ошибки (`ApiExceptionHandler`), очистка игр (`GameJanitor`), здания (`BuildingLoader`), почта (`Mailer`), ежедневная ретенция (`DataRetention`). Access-лог Caddy выключен.
- **Клиент.** Ktor `Logging` на уровне INFO (метод, URL, статус) через `Logger.SIMPLE`; несколько `Log.w` и `println` в платформенном коде. Краши никто не собирает: о проблемах узнаём от тестировщиков словами, без стека и без версии.
- **Правило приватности** из [ADR 0004](0004-accounts-friends-chat.md#11-сроки-хранения-и-gdpr): в логах нет координат, токенов, паролей, кодов из писем, email и текста чата. Держится на внимательности, тестом не закреплено.
- **Инфраструктура.** Одна EC2 `t3.micro` (1 ГБ RAM: сервер с `mem_limit` 640 МБ, Caddy, ОС), RDS `db.t4g.micro`, AWS Free plan, около $30 в месяц и правило «машину и базу крупнее не брать» ([deploy.md](../deploy.md#сколько-стоит)). Сервер один, игры в памяти, каждый push в `main` перезапускает сервер и обрывает игры.
- **E2E.** Наблюдатель читает полное состояние игр через debug-эндпоинты — только в профиле `e2e`.

Зачем нужна наблюдаемость:

- узнавать, что сервер упал или не отвечает, раньше игроков;
- после уличного теста видеть, что было: сколько игр, сколько точек GPS отброшено и почему, как часто ищущие видели `STALE_SIGNAL`, отвечал ли Overpass, ушли ли письма;
- разбирать «у меня вылетело» и «в фоне координаты не уходили» с телефона тестировщика, к которому нет доступа;
- понимать нагрузку до масштабирования: память JVM при N играх, длительность `sync`, пул соединений с базой;
- не сделать из наблюдаемости ещё одно хранилище персональных данных.

Ограничения:

- на машине нет памяти под агенты и тем более под Prometheus, Grafana и Loki: всё, что стоит больше ~50 МБ RAM, туда не влезает;
- данные игроков — в ЕС; бэкенд наблюдаемости либо тоже в ЕС, либо не получает персональных данных вовсе (мы делаем и то и другое);
- бесплатно или почти бесплатно;
- ничего из наблюдаемости не попадает в горячий путь `sync`: никаких запросов в базу и блокировок игры дольше необходимого.

## Решение коротко

| Вопрос | Решение |
|---|---|
| Бэкенд | Grafana Cloud, бесплатный тариф, стек в регионе EU: метрики (Prometheus-совместимые), логи (Loki), трейсы (Tempo), алерты, проверки снаружи (Synthetic Monitoring). Всё по открытым протоколам (OTLP), поэтому переезд на свой стек — смена адреса |
| Как данные туда попадают | Сервер шлёт сам по OTLP (Micrometer OTLP registry + `spring-boot-starter-opentelemetry`), без агента на машине. Логи — тоже из JVM через OpenTelemetry Logback appender; консоль контейнера остаётся (JSON ECS) для `docker compose logs` |
| Метрики сервера | Стандартные Micrometer (`http.server.requests`, JVM, Hikari, система) плюс доменные `hovanki.*` из событий `Game`; теги — только из enum'ов, никаких id |
| Логи сервера | Структурные (ECS JSON), `game.id` и `player.id` через MDC, события игры и аккаунта по списку, тест на утечки персональных данных |
| Трейсы | Позже и выборочно: Micrometer Tracing + OpenTelemetry, 100 % запросов кроме `sync`, ~1 % для `sync` |
| Алерты | «Сервер не отвечает» снаружи, 5xx, память JVM, пул базы, почта, Overpass, диск, ретенция не отработала → Telegram и email |
| Клиент: логи | Kermit в `:clientCore` вместо `println`/`Log.w`; кольцевой буфер последних строк для отчёта о проблеме |
| Клиент: краши | Sentry Kotlin Multiplatform (Android и iOS), регион EU, без персональных данных, за выключателем в профиле |
| Клиент: метрики | Свой эндпоинт `POST /api/v1/telemetry`: агрегаты без персональных данных → Micrometer на сервере → те же дашборды |
| Доступ разработчика | Management-порт actuator (8081) только внутри машины: `loggers`, `metrics`, `threaddump`, `heapdump` через SSH-туннель |

## 1. Стек: почему Grafana Cloud и OTLP без агента

| Вариант | За | Против | Вердикт |
|---|---|---|---|
| **Grafana Cloud Free + OTLP из JVM** | $0: 10 тыс. активных серий метрик, 50 ГБ логов и 50 ГБ трейсов в месяц, хранение 14 дней (на сентябрь 2026, проверить при старте); ничего не ставить на машину; стек в ЕС; алерты и проверки снаружи в комплекте; открытые протоколы | Данные у третьей стороны (логи без персональных данных — приемлемо); лимиты бесплатного тарифа; 14 дней истории | **Берём** |
| Свой стек на той же машине (Prometheus или VictoriaMetrics + Loki + Grafana) | Всё своё | Не влезает в 1 ГБ RAM рядом с сервером; ещё одна система, за которой надо следить | Нет. Вариант при переезде на VPS с 4 ГБ ([deploy.md](../deploy.md#сколько-стоит)): VictoriaMetrics + VictoriaLogs + Grafana OSS, сервер переключается одной переменной |
| AWS CloudWatch (метрики через `micrometer-registry-cloudwatch2`, логи через `awslogs`, X-Ray) | Тот же аккаунт; метрики EC2 и RDS уже там | Платно за каждую пользовательскую метрику и за ГБ логов; слабые дашборды и запросы к логам | Нет. Но бесплатные алармы CloudWatch на RDS и EC2 — да (раздел 6) |
| Grafana Alloy или Promtail на машине | Соберёт и логи Docker, и метрики хоста; стандартный путь Grafana | 100–150 МБ RAM — не влезает | Позже, вместе с машиной побольше |
| Только Sentry (сервер и клиент) | Одна система для ошибок | Это не метрики и не логи; алерты об ошибках, а не о здоровье | Sentry — только для крашей клиента (раздел 7) |

Детали:

- **Метрики** — `io.micrometer:micrometer-registry-otlp` (версия из BOM Spring Boot) и `management.otlp.metrics.export.url`. Шаг 60 секунд: бесплатный тариф считает активные серии, а не частоту отправки.
- **Трейсы и логи** — `org.springframework.boot:spring-boot-starter-opentelemetry` (в Boot 4 это Micrometer Tracing с мостом в OpenTelemetry и OTLP-экспортёры трейсов и логов). Мост Logback → OpenTelemetry Boot не подключает сам: нужен `io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0` и `OpenTelemetryAppender.install(openTelemetry)` при старте. У артефакта суффикс `-alpha` — так помечена вся инструментация OpenTelemetry для Java, это давно рабочий код, и Boot документирует именно его. Если мост окажется капризным — план Б: Docker logging driver для Loki (`grafana/loki-docker-driver`, читает stdout контейнера, сервер не трогаем).
- **Настройка стандартными переменными OpenTelemetry**, Boot 4.1 маппит их в свои свойства сам: `OTEL_EXPORTER_OTLP_ENDPOINT` (`https://otlp-gateway-prod-<регион>.grafana.net/otlp`), `OTEL_EXPORTER_OTLP_HEADERS` (`Authorization=Basic <base64 от instanceId:token>`), `OTEL_SERVICE_NAME=hovanki-server`, `OTEL_RESOURCE_ATTRIBUTES=deployment.environment=prod,service.version=<тег образа>`. Всё это — в `.env` на машине, `compose.yaml` передаёт их в контейнер вместе с `OTEL_METRICS_EXPORTER=otlp`, `OTEL_TRACES_EXPORTER=otlp` и `OTEL_LOGS_EXPORTER=otlp`. В `application.yaml` экспорт выключен (`management.otlp.metrics.export.enabled`, `management.tracing.export.otlp.enabled`, `management.logging.export.otlp.enabled` = `false`): иначе реестр OTLP слал бы метрики на `localhost:4318` и каждую минуту писал бы в лог об отказе; локальная разработка и тесты ничего никуда не шлют.
- **Локально** тот же OTLP при желании направляется в `grafana/otel-lgtm` (Grafana + Prometheus + Loki + Tempo в одном контейнере для разработки) — профиль `observability` в `deploy/compose.dev.yaml`. Необязательно, но удобно проверить дашборды до продакшена.
- **RAM.** Экспортёры внутри JVM — примерно +30–50 МБ heap и классов. После включения смотреть `jvm.memory.used` и OOM-kill контейнера (`docker inspect`); при необходимости поднять `mem_limit` до 700 МБ: Caddy ~40 МБ, ОС ~150 МБ — влезает.

## 2. Метрики сервера

### 2.1 Из коробки

Boot настраивает сам: `http.server.requests` (шаблон URI, статус, `outcome`, `exception`; включить гистограмму процентилей для `/sync`, `/games/join`, `/accounts/login`), `jvm.*`, `process.*`, `system.cpu.*`, `disk.free` и `disk.total`, `hikaricp.connections.*` (active, pending, timeout), `jdbc.connections.*`, `tomcat.*`, `logback.events` по уровням («сколько ERROR за 5 минут» без чтения логов), `tasks.scheduled.execution` (`@Scheduled`: janitor, ретенция, очистка лимитов).

Общие теги на всё: `service.name`, `service.version`, `deployment.environment`. Версия берётся из `buildInfo()` (`springBoot { buildInfo() }` в `server/build.gradle.kts`): тогда и `/actuator/info` показывает версию и коммит.

### 2.2 Доменные `hovanki.*`

`Game` остаётся чистым объектом: он **накапливает события** (`GameEvent`, sealed class в `server/game`, без Micrometer), `GameService.update` забирает их после `advance` под блокировкой (`game.drainEvents()`) и отдаёт `GameMetrics` **после снятия блокировки**. Те же события кормят структурные логи (раздел 3) — одно место, где написано, что считается событием игры. Счётчики аккаунтов, почты, лимитов и зданий — прямо в соответствующих сервисах.

| Метрика | Тип | Теги | Откуда | Зачем |
|---|---|---|---|---|
| `hovanki.games.active` | gauge | `phase` | `GameJanitor` пересчитывает раз в минуту (он уже обходит все игры под блокировкой); экспорт блокировки не трогает | Сколько игр идёт; условие для обновления сервера без обрыва игр (раздел 6) |
| `hovanki.players.online` | gauge | — | Игроки с `sync` за последние `staleLocationRevealSeconds` (новый чистый `Game.activePlayers(now)`), тот же обход | Реальная нагрузка |
| `hovanki.games.created` | counter | `account` = guest / user | `GameService.create` | Воронка |
| `hovanki.games.started` | counter | — | событие `PhaseChanged` | Воронка |
| `hovanki.game.players`, `hovanki.game.seekers` | distribution | — | старт игры | Размер компаний |
| `hovanki.games.finished` | counter | `reason` = all_caught / timeout | событие `Finished` | Как заканчиваются игры |
| `hovanki.game.duration` | timer | — | длительность SEEKING | Длина партии |
| `hovanki.games.abandoned` | counter | `phase` | `GameJanitor`: удалена по `idle-retention`, не дойдя до FINISHED | Брошенные лобби |
| `hovanki.joins` | counter | `result` = new / returning / replayed / not_found, `account` | `GameService.join` | Перезаходы и потерянные ответы |
| `hovanki.location.fixes` | counter | `result` = accepted / mock / out_of_order / implausible / unusable_accuracy | событие `FixesRecorded` (счётчики уже есть для `DebugFixCounts`) | Качество GPS в поле |
| `hovanki.sync.samples` | distribution | — | точек в одном `sync` (0 = приложение живо, GPS молчит) | GPS в фоне |
| `hovanki.reveals` | counter | `reason` = out_of_zone / mock_location / stale_signal / inside_building | момент появления причины, не каждый снапшот | Как часто срабатывают правила |
| `hovanki.eliminations` | counter | — | выбывание за зону | — |
| `hovanki.catches.claimed`, `hovanki.catches.rejected_at_claim` | counter | `reason` = no_location / too_far | заявка | Работает ли находка |
| `hovanki.catches.resolved` | counter | `outcome` = confirmed / rejected, `by` = code / timeout / vote / default_rule / game_end | исход заявки | Споры и таймауты |
| `hovanki.catch.code_attempts` | distribution | — | попыток кода на заявку | QR против ручного ввода |
| `hovanki.chat.messages`, `hovanki.chat.reports` | counter | `team` | `Game.sendChat`, `reportChat` | Модерация |
| `hovanki.buildings.load` | timer | `result` = ok / unavailable, `host` (из настроек) | `BuildingLoader` | Overpass |
| `hovanki.buildings.count` | distribution | — | зданий на игру | Застроенность зон |
| `hovanki.mail.sent` | counter | `purpose`, `result` = ok / failed / dropped | `Mailer` | Доставка кодов |
| `hovanki.mail.queue` | gauge | — | очередь `Mailer` | — |
| `hovanki.ratelimit.hits` | counter | `limit` (enum `RateLimit`) | `RateLimiter` | Перебор паролей, спам |
| `hovanki.accounts.registered`, `hovanki.accounts.deleted` | counter | — | `AccountService` | Рост |
| `hovanki.logins` | counter | `result` = ok / wrong_credentials / limited | `AccountService.login` | Атаки и удобство входа |
| `hovanki.accounts.total` | gauge | — | `SELECT count(*)` раз в 10 минут по расписанию, не в запросе | Рост |
| `hovanki.invites.sent`, `hovanki.friend_requests` | counter | `kind` | `social/` | Социальные функции |
| `hovanki.retention.deleted` | counter | `table` | `DataRetention` | GDPR |
| `hovanki.retention.runs` | counter | — | `DataRetention` | Алерт «не отработала» |
| `hovanki.client.*` | counter / distribution | см. 7.3 | телеметрия клиента | Телефоны |

Правила:

- Теги — только из enum'ов и конечных списков. Никогда `gameId`, `playerId`, `userId`, email, IP, произвольные строки: кардинальность — это деньги и место в 10 тыс. серий (сейчас выйдет 1–2 тыс.).
- Имена `hovanki.<область>.<что>`; Micrometer сам превращает их в `hovanki_games_active` для Prometheus.
- Считаем **переходы**, не состояния: раскрытие — один раз при появлении причины.
- Никакого Micrometer в `Game`: события → `GameMetrics`. Тесты: `GameTest` проверяет события через `drainEvents()`, `GameMetricsTest` — что событие становится нужным счётчиком (`SimpleMeterRegistry`), `GameApiTest` — сквозной пример «партия → `hovanki.games.finished` = 1».

### 2.3 Дашборды

JSON дашбордов хранится в репозитории (`deploy/grafana/*.json`) и импортируется руками: их три и меняются они редко.

- **Сервер**: запросы и ошибки по маршрутам, латентность `sync` (p50, p95), JVM, Hikari, диск, `logback.events`.
- **Игра**: активные игры и игроки, воронка created → started → finished, находки по исходам, раскрытия, качество GPS, здания, почта, лимиты, аккаунты.
- **Клиенты**: раздел 7.3.

## 3. Логи сервера

- **Формат.** `logging.structured.format.console=ecs` — JSON в stdout без дополнительных зависимостей. Свойство задаётся в `compose.yaml` (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`), а не в `application.yaml`: разработчик и тесты видят обычный текст. `docker compose logs` становится JSON, рецепт для deploy.md: `docker compose logs --no-log-prefix server | jq -r '[."@timestamp", .log.level, .message] | @tsv'`.
- **Корреляция.** С Micrometer Tracing в MDC приходят `traceId` и `spanId`, ECS кладёт их в `trace.id` и `span.id`: из лога в трейс и обратно.
- **Контекст.** `PlayerRefArgumentResolver`, разобрав Bearer-токен, кладёт в MDC `game.id` и `player.id`, интерцептор очищает MDC после ответа: эти id эфемерны и умирают с игрой, персональными данными их не считаем. `user.id` в MDC **не кладём**: псевдоним аккаунта — персональные данные; он появляется только в аудите админа ([ADR 0008](0008-admin.md)). IP клиента не логируем: лимиты считаются по нему, но в лог идёт только имя лимита.
- **Что логируем** на INFO, из тех же `GameEvent`: игра создана (id, гость или аккаунт, радиус зоны — без центра, центр = позиция хоста); игра стартовала (игроков, ищущих, тайминги); смена фазы; конец игры (причина, длительность, пойманных); игрок вошёл (new или returning); заявка и исход находки; раскрытие с причиной; выбывание. Уже есть: здания, почта (без адреса), очистка, ретенция. WARN: Overpass недоступен, очередь почты переполнена, много mock-точек от одного игрока за игру, `INTERNAL` с трассой. DEBUG (выключен): отклонённые 4xx с кодом и причиной — включать на время через `loggers`.
- **Чего не логируем** — как в ADR 0004, плюс: тела запросов и заголовки никогда; `ApiError.message` — только для 5xx.
- **Гигиена — тестом.** `LogHygieneTest` в `server/src/test`: `ListAppender` на корневом логгере во время сценариев с реальными данными (регистрация с email, `sync` с координатами, чат с текстом, письмо с кодом) и проверка, что в сообщениях нет этого email, шести цифр кода, токена, координат из запроса и текста сообщения. Правило перестаёт держаться на внимательности.
- **Уровни на лету** — actuator `loggers` на management-порту (раздел 6), не через перезапуск.
- **Хранение.** Loki — 14 дней; на машине — 3 × 10 МБ. Логи персональных данных не содержат, поэтому срок отдельно не регулируем, но строку «Логи сервера (без персональных данных): Grafana Cloud EU, 14 дней; на машине 30 МБ» добавляем в таблицу сроков ADR 0004.

## 4. Трейсы (позже)

- **Зачем.** Из чего складываются 300 мс `join` с аккаунтом (BCrypt, база, блокировка игры), сколько запросы одной игры ждут на `synchronized(game)`, сколько занимает Overpass.
- **Как.** `spring-boot-starter-opentelemetry`; свой `Sampler` по маршруту: `management.tracing.sampling.probability` 1.0 для всего, кроме `sync`, и 0.01 для `sync`. Tail sampling (оставлять только трейсы с ошибкой) в Micrometer Tracing нет, поэтому ошибки ищем в логах с `trace.id`, где трейс есть.
- **Спаны.** HTTP — автоматически; JDBC — `net.ttddyy.observation:datasource-micrometer-spring-boot` (проверить совместимость с Boot 4, иначе `Observation` руками вокруг репозиториев); Overpass — `Observation` вокруг `OverpassBuildingSource.load` (`java.net.http` не инструментирован); блокировка игры — `Observation` `game.lock` с тегом маршрута: время ожидания блокировки — главный признак «горячей» игры.
- Не раньше шага 6 в разделе 9: метрики и логи дают почти всю пользу.

## 5. Алерты

Правила — в `deploy/grafana/alerts.yaml`, контакт-пойнты — Telegram-бот и email. Правило алерта: он либо ведёт к действию, либо его нет; для любопытства есть дашборд.

| Правило | Условие | Почему |
|---|---|---|
| Сервер не отвечает | Synthetic Monitoring: `GET https://hovanki.duckdns.org/readyz` (пока порт не разделён — `/actuator/health`) не отвечает 200 два раза подряд, интервал 1 минута, две локации в ЕС | Главный алерт; работает, даже когда сервер ничего не может отправить |
| Метрики пропали | `hovanki_games_active` отсутствует 5 минут, а проверка снаружи зелёная | Иначе тишина выглядит как «всё хорошо» |
| Ошибки | `http_server_requests` со статусом 5xx > 1 % за 5 минут или `logback_events{level="error"}` > 0 | Непредвиденных ошибок должно быть ноль |
| Память | heap после GC > 85 % от max 10 минут; `process_uptime` сбросился (контейнер перезапущен) | OOM-kill при 640 МБ — тихая смерть игр |
| База | `hikaricp_connections_pending` > 0 дольше минуты; `hikaricp_connections_timeout` растёт | Пул из 5 соединений |
| Почта | `hovanki_mail_sent{result="failed"}` или `dropped` > 0 за 15 минут | Люди не получают коды и молчат |
| Здания | доля `hovanki_buildings_load{result="unavailable"}` > 50 % за 30 минут | Overpass лежит: игры идут без правила зданий |
| Диск | `disk_free` < 15 % | Образы Docker |
| Ретенция | `hovanki_retention_runs` не рос 25 часов | На этом cron держатся сроки GDPR |
| Перебор паролей | `hovanki_ratelimit_hits{limit="LOGIN_PER_IP"}` > 100 в час | — |
| Клиенты | `hovanki_client_sync_failed` > 20 % от успешных `sync` 15 минут; crash-free сессий < 99 % (Sentry) | Проблема в приложении или сети, не на сервере |
| RDS и EC2 (CloudWatch, бесплатно) | FreeStorageSpace < 2 ГБ, CPU > 80 % 15 минут, StatusCheckFailed | Независимый от Grafana канал |

## 6. Деплой, health и доступ разработчика

- **`compose.yaml`**: переменные `OTEL_*` из `.env` (раздел 1), `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, `MANAGEMENT_SERVER_PORT=8081` и `ports: ["127.0.0.1:8081:8081"]`. Management-порт слушает только на loopback машины, Caddy проксирует только 8080, так что `loggers`, `heapdump` и прочее снаружи недостижимы даже при ошибке в конфигурации прокси. Публичная проверка живости остаётся на 8080: `management.endpoint.health.probes.add-additional-paths=true` даёт `/livez` и `/readyz` на основном порту (проверить в Boot 4.1); `/actuator/health` с 8080 уходит — обновить deploy.md, Synthetic Monitoring и всё, что на него ходит (`grep -r actuator/health`).
- **Доступ разработчика**: `ssh -L 8081:127.0.0.1:8081 ubuntu@<машина>` → `http://localhost:8081/actuator/loggers`, `.../metrics/hovanki.games.active`, `.../threaddump`. `heapdump` содержит координаты и токены: только на машину, удалить после анализа; `env` показывает значения секретов скрытыми (`show-values: never`). Рецепты — в deploy.md.
- **Обновление без обрыва игр** (roadmap): `hovanki-update.sh` перед `docker compose up -d` включает режим обслуживания ([ADR 0008](0008-admin.md#7-инструменты-оператора)) и ждёт, пока сумма `hovanki.games.active` по фазам кроме `finished` на `http://127.0.0.1:8081/actuator/metrics/hovanki.games.active` не станет нулём (опрос раз в минуту, не дольше 2 часов, потом обновляет всё равно и пишет об этом в журнал). Сохранение игр при перезапуске — отдельная задача, этот шаг закрывает половину пункта.
- **Docker healthcheck** не делаем: в образе Temurin нет `curl`/`wget`, проверка снаружи и `restart: unless-stopped` покрывают случай.
- **CloudWatch**: бесплатные алармы на RDS (FreeStorageSpace, CPUUtilization, DatabaseConnections) и EC2 (StatusCheckFailed) на email.
- **Секреты**: токен Grafana Cloud (права только на запись) и DSN Sentry — в `.env` на машине и в GitHub Secrets для сборок; в репозитории их нет; список — в deploy.md и ci-cd.md.
- **deploy.md** получает раздел «Мониторинг»: где дашборды, как читать логи (Grafana Explore и `jq`), как открыть туннель, как менять уровень логов, как выключить экспорт (убрать переменные и `docker compose up -d`).

## 7. Клиент

Три задачи — три инструмента:

| Задача | Инструмент | Куда уходит | Персональные данные |
|---|---|---|---|
| Что произошло на этом телефоне (разработчик рядом или тестировщик прислал отчёт) | Kermit: Logcat/OSLog + кольцевой буфер | Никуда само; буфер — в «Сообщить о проблеме» | Нет: правило редакции как на сервере |
| Приложение упало или зависло | Sentry KMP | Sentry, регион EU | Минимум: без IP, без id пользователя, без имени устройства; версия ОС и модель — да, без них не воспроизвести |
| Как ведут себя все телефоны (версии, сбои `sync`, качество GPS, фон, батарея) | Свой `POST /api/v1/telemetry` → Micrometer | Наш сервер → Grafana, только агрегаты | Нет: счётчики и гистограммы с тегами из конечных списков |

### 7.1 Логи: Kermit

- `co.touchlab:kermit` (2.2 на сентябрь 2026) в `:clientCore` commonMain как `api`; логгер с тегом на класс: `private val log = Logger.withTag("GameSessionManager")`. Заменить `println`, `Log.w` и Ktor `Logger.SIMPLE` (Ktor получает адаптер на Kermit; INFO с методом, URL и статусом — только в debug-сборках, в release только ошибки). E2E-боты пишут в консоль и в отчёт сценария по своему `LogWriter`.
- Писатели: платформенный (Logcat, OSLog) — Verbose в debug, Info в release; `RingBufferLogWriter` — последние 300 строк в памяти, стираются при закрытии, идут в «Сообщить о проблеме» и как breadcrumbs в Sentry; `SentryLogWriter` — Warn и выше как breadcrumbs.
- Правило редакции такое же, как на сервере: никаких координат, токенов, паролей, кодов, email и текста чата. `LocationSample` целиком не логируем: только accuracy и возраст точки.
- **«Сообщить о проблеме»** (профиль): текст игрока + кольцевой буфер + `BuildInfo` + `gameId`, если в игре → `POST /api/v1/client/problems` → строка `problem_reports` (текст, буфер, версия; 90 дней, см. таблицу сроков ADR 0004) → очередь в админке ([ADR 0008](0008-admin.md)). Единственное место, где текст с телефона уходит на сервер, и он написан самим игроком для нас.

### 7.2 Краши: Sentry Kotlin Multiplatform

- `io.sentry:sentry-kotlin-multiplatform` (0.27 на сентябрь 2026; проверить совместимость с Kotlin 2.4.20 и версию Sentry Cocoa из таблицы совместимости SDK) в `:composeApp` commonMain за интерфейсом `CrashReporter` в `:clientCore` (`NoopCrashReporter` для ботов и тестов). iOS: Sentry Cocoa через SPM в `iosApp`, версия ровно из таблицы; наш framework статический — линковку проверить (Gradle-плагин Sentry KMP или `linkerOpts`). Android: транзитивно `sentry-android` с ANR и NDK-крашами (maplibre — нативный код).
- **Почему Sentry, а не Firebase Crashlytics.** Официальный KMP SDK: одна инициализация и одни breadcrumbs на обе платформы; регион EU при создании организации; бесплатный тариф (1 пользователь, 5 тыс. ошибок в месяц, алерты на email) хватает, команда — $26 в месяц; запасной путь — свой Sentry или GlitchTip на VPS. Crashlytics сильнее на Android, но это Google-аккаунт, данные в США, KMP через сторонние обёртки, а соседний Analytics потянул бы согласие.
- **Настройка.** `dsn` из `BuildConstants.SENTRY_DSN` (Gradle-свойство `hovanki.sentryDsn`; пусто — выключено; debug-сборки выключены всегда); `environment` = `preview` или `release`; `release` = версия и номер сборки из `BuildInfo`, тег `commit`; `sendDefaultPii=false`; в настройках проекта — «не хранить IP»; `beforeSend` убирает `user`, `device.name` и любые пары чисел, похожие на координаты, из сообщений (страховка: по правилу их там быть не должно); `tracesSampleRate=0` (перфоманс не берём); скриншоты выключены; breadcrumbs — из Kermit (Warn и выше) и смена экрана по имени; теги без персональных данных: `phase`, `role`, `buildings_state`, `platform`, `os`.
- **Выключатель.** Профиль → «Диагностика» → «Отправлять отчёты об ошибках и обезличенную статистику»: один переключатель на Sentry и телеметрию 7.3, по умолчанию включён, хранится через `ClientStorage` (ключ `diagnostics`), Sentry применяет при следующем запуске, телеметрия — сразу. Основание — законный интерес (стабильность), персональных данных нет; записать в политику конфиденциальности (roadmap «Юридическое»), в App Privacy — Diagnostics: Crash Data и Performance Data, not linked to identity, not used for tracking; в Data safety — App info and performance: Crash logs, Diagnostics. Sentry Cocoa несёт свой privacy manifest.
- Release health (crash-free сессии по версиям) смотрим в Sentry; в Grafana не тащим.

### 7.3 Телеметрия: свой эндпоинт

- **Почему свой.** Дешевле и чище аналитического SDK: нет третьей стороны и идентификаторов, только счётчики; сервер уже есть и умеет Micrometer; e2e-боты ходят тем же кодом и проверяют его.
- **Протокол** (`:shared`, `Telemetry.kt`): `POST /api/v1/telemetry`, тело `TelemetryBatch(events: List<TelemetryEvent>)`, `TelemetryEvent(name: String, count: Int = 1, value: Double? = null, tags: Map<String, String> = emptyMap())`, ответ 204. Токен — игровой или аккаунтный, если есть; иначе без токена (`app_start` идёт до входа), лимит `telemetry-per-ip` 60 батчей в час; батч — не больше 50 событий и 8 КБ. Сервер принимает только имена из белого списка `TelemetryNames` (в `:shared`, чтобы клиент и сервер согласовывали его компилятором) и теги из белого списка ключей (`platform`, `app_version`, `os`, `result`, `kind`, `screen`, `bucket`) со значениями `[a-z0-9._-]{1,32}`; остальное молча отбрасывается. В базу ничего не пишется: событие → `Counter` или `DistributionSummary` `hovanki.client.<name>`; `value` принимается только для имён из списка гистограмм. Один WARN-лог на `client_error` с классом ошибки и экраном, без текста.
- **Клиент** (`:clientCore`, `telemetry/`): `Telemetry.count(name, tags)` и `Telemetry.record(name, value, tags)`; очередь до 200 событий (старые выбрасываются); `TelemetryUploader` отправляет раз в 60 секунд, пока приложение в игре или на экране, при уходе в фон (новый маленький платформенный сервис `AppLifecycle`: foreground/background) и при 25 и более событиях; одна попытка без повторов — лучше потерять статистику, чем мешать `sync`. Выключается переключателем «Диагностика» и `LaunchOptions.telemetry=false`; боты по умолчанию не шлют, сценарий телеметрии включает явно.
- **Общие теги** каждого батча: `platform` (android, ios), `app_version` (ограничено числом релизов), `os` (major-версия). Не отправляем модель устройства, язык, размер экрана и любые идентификаторы.
- **События первой очереди** — то, что хотим знать, а не всё, что можем:

| Событие | Когда | Что даёт |
|---|---|---|
| `app_start` | холодный старт | Распределение версий и ОС; вместе с `hovanki.games.created` — сколько запускают и сколько играют |
| `resume` `result` = ok / finished / gone / network | возврат в сохранённую игру | Сколько игр теряется на перезапуске |
| `sync_failed` `kind` = timeout / dns / connect / http_5xx / http_4xx | каждая неудача `sync` — сервер их не видит | «Сеть или сервер?» рядом с серверным `http.server.requests` |
| `sync_rtt` `value` = мс | каждый успешный `sync` | Реальная задержка на мобильной сети |
| `location_fix` `bucket` = le10 / le20 / le50 / gt50 | каждая точка от `LocationProvider` | Качество GPS в поле, доля непригодных точек |
| `location_gap` `value` = секунд | пауза между точками дольше 30 с | GPS пропадает в кармане — вопрос roadmap «Фон на реальных телефонах» |
| `background_stopped` `kind` = os_killed / permission / error | `BackgroundTracker` остановлен не игроком | Энергосбережение Xiaomi и Samsung, ограничения iOS |
| `permission` `kind` = location / background / camera / notifications, `result` | ответ на системный запрос | Где отваливаются на онбординге |
| `catch_code` `kind` = qr / manual, `result` = ok / wrong | ввод кода ищущим | Работает ли сканер в поле |
| `battery` `value` = процентов в час | конец игры дольше 10 минут: разница уровня батареи (новый сервис `DeviceStatus.batteryLevel()`) | Roadmap «Батарея» |
| `client_error` `kind` = `<ErrorCode>_<reason>`, `screen` | `ApiResult.Rejected` или `SessionError.Rejected`, показанные игроку | Какие ошибки видят люди |
| `map_error` `kind` = tiles / style | карта не загрузилась | OpenFreeMap |

- **Дашборд «Клиенты»**: версии, ошибки `sync` против успешных, RTT p50 и p95, качество GPS и паузы, остановки фона, батарея, ошибки по экранам.

## 8. Тесты и e2e

- `:server`: `GameTest` — события; `GameMetricsTest`; `TelemetryControllerTest` — белые списки, лимиты, 204 на мусор; `LogHygieneTest`.
- E2E: сценарий `MetricsTest` — после партии счётчики совпадают с правдой наблюдателя (созданные, стартовавшие, законченные игры, находки, раскрытия): метрики сверяет код, который уже знает правду; сервер в тестах поднимается в процессе, `MeterRegistry` читается напрямую. Сценарий `TelemetryTest`: бот с включённой телеметрией играет партию → `hovanki.client.sync_rtt` посчитан, мусорные события не прошли.
- Sentry на устройствах автоматически не проверяем (в debug выключен): один раз руками на preview-сборке.

## 9. Порядок внедрения

| Шаг | Что | Оценка | Результат |
|---|---|---|---|
| 0 | Аккаунт Grafana Cloud (EU), Synthetic Monitoring на health, контакт-пойнты Telegram и email, алармы CloudWatch на RDS и EC2 | полдня, без кода | Узнаём о падении первыми |
| 1 | Метрики: `buildInfo()`, `micrometer-registry-otlp`, `OTEL_*` в compose и `.env`, `GameEvent` и `GameMetrics`, счётчики аккаунтов, почты, лимитов, зданий, ретенции, дашборды «Сервер» и «Игра», алерты раздела 5 | 2 дня | Видим игры и здоровье |
| 2 | Логи: ECS в контейнере, OTLP-экспорт логов, MDC `game.id` и `player.id`, события в лог, `LogHygieneTest`, рецепты в deploy.md | 1 день | Поиск и корреляция |
| 3 | Management-порт 8081, `/livez` и `/readyz`, туннель, `hovanki-update.sh` ждёт ноль игр | полдня | Ops без перезапусков; обновления не рвут игры |
| 4 | Клиент: Kermit, кольцевой буфер, «Сообщить о проблеме», `CrashReporter` и Sentry (Android, iOS), переключатель «Диагностика», тексты для App Privacy и Data safety | 2–3 дня | Знаем о крашах |
| 5 | Телеметрия: протокол, эндпоинт, `Telemetry` и `TelemetryUploader`, `AppLifecycle`, `DeviceStatus`, 12 событий, дашборд «Клиенты», e2e-сценарий | 2–3 дня | Ответы на вопросы roadmap про фон и батарею |
| 6 | Трейсы: стартер, sampler, наблюдения JDBC, Overpass и блокировки игры | 1 день | Когда появятся вопросы к латентности |

Шаги 0–3 — до первого уличного теста с чужими телефонами; 4–5 — до раздачи preview-сборок тестировщикам шире; 6 — по потребности.

## 10. Чего не делаем

- Не ставим агентов на машину (Alloy, Promtail, node_exporter) — до переезда на машину побольше.
- Не собираем аналитику поведения (воронки экранов, время в приложении, идентификаторы установок): это не наблюдаемость, и она потребовала бы согласия.
- Не логируем каждый запрос: `sync` каждые 3 секунды на игрока — шум; метрики и трейсы с семплированием покрывают.
- Не храним метрики и логи в PostgreSQL и не рисуем дашборды в приложении: Grafana.
- Не шлём серверные ошибки в Sentry: одна система для сервера (Grafana), одна для крашей клиента (Sentry).
- Не отдаём Grafana и Sentry ни координат, ни id пользователей, ни email.

## Последствия

- Новые зависимости сервера: OTLP registry, стартер OpenTelemetry, Logback appender; +30–50 МБ памяти — проверить `mem_limit`.
- Новые зависимости клиента: Kermit, Sentry KMP (плюс Sentry Cocoa в Xcode); APK больше на 1–2 МБ; privacy manifest и анкеты магазинов.
- Появляются внешние аккаунты (Grafana Cloud, Sentry) и их токены в `.env` и GitHub Secrets; перечислить в deploy.md и ci-cd.md; ротация — руками.
- Правило «никаких персональных данных в наблюдаемости» закреплено тестом и белыми списками; новые метрики и события проходят через `GameEvent` и `TelemetryNames`, а не добавляются где попало.
- Бесплатные тарифы могут измениться; переезд на свой стек — смена `OTEL_EXPORTER_OTLP_ENDPOINT` и хостинг Grafana.
- Новые протокольные маршруты (`/telemetry`, `/client/problems`) и таблица `problem_reports` со сроком хранения — в таблицу API architecture.md и в таблицу сроков ADR 0004 при реализации.

## Открытые вопросы

- Хватит ли 14 дней истории Grafana Cloud Free? Для «до и после релиза» — да, для сезонности — нет (выгрузка агрегатов раз в месяц или платный тариф).
- Нужны ли трейсы при одном сервисе — решаем после шагов 1–2.
- Один переключатель диагностики или два (краши отдельно от статистики) — вместе с политикой конфиденциальности.
- Делать ли `GameEvent` сразу основой для «разбора после игры» из roadmap: те же события с координатами внутри игры — да, но отдельной задачей.
- Boot 4.1: `add-additional-paths` при отдельном management-порту и совместимость `datasource-micrometer` — проверить на шагах 3 и 6.
