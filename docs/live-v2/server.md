# Протокол v2: что поменять на сервере и в деплое

Руководство для того, кто будет делать серверную часть v2. Правила протокола — в
[spec.md](spec.md) (дальше «спека»). Пути сверены с `main` на 14.10.2026.

Код v2 в новых пакетах (`server/live/v2/`, `shared/live/`) ведёт тред «Радикальные оптимизации сервера», существующий
код сервера и стенд — тред «Локальный стенд для оптимизации сервера». Порядок шагов: [plan.md](plan.md).

## 1. Главное

- **Caddy уходит.** TLS держит сам сервер, сертификат получает lego по DNS (DuckDNS), Spring перечитывает его без
  рестарта (§2).
- **UDP в той же JVM.** Обычный `DatagramChannel` из JDK, без Netty (§4.3). Замер: 7,5 мкс на синхронизацию с
  AES-GCM (разбор треда «Радикальные оптимизации сервера» от 14.10.2026 (вне репозитория)).
- **WebSocket v2** — ещё один обработчик Spring рядом с `GameSocketHandler` (§4.4).
- **Состояние v2 режется из снимка v1.** Сервер собирает тот же `GameSnapshot`, что отдал бы в v1, и режет его на
  секции чистой функцией из `:shared`. Поэтому видимость в v2 совпадает с v1 по построению, а не по тестам (§3).
  Дешёвая сборка из общих кусков (`LiveView`) — следующий шаг, ради процессора (§3.3).
- **Игра не меняется.** `GameService.sync` тот же, правила те же. Одно изменение: снимок для push не должен трогать
  `lastSeen` (§3.2).

## 2. Без Caddy

### 2.1. Что делал Caddy и кто делает это теперь

`deploy/compose.yaml`: `caddy reverse-proxy --from ${DOMAIN} --to server:8080`.

| что | Caddy | теперь |
|---|---|---|
| TLS на 443 | да | Tomcat, `server.ssl.bundle` из PEM |
| сертификат Let's Encrypt и продление | сам, HTTP-01 / TLS-ALPN-01 | lego по таймеру systemd, DNS-01 через DuckDNS |
| перечитать сертификат | сам | Spring Boot: `spring.ssl.bundle.pem.<имя>.reload-on-update: true` |
| HTTP/2 | да | `server.http2.enabled: true` |
| 80 → 443 | да | не нужен: в приложениях адрес `https://` вшит при сборке, админку открывают по https. Порт 80 закрыт |
| `X-Forwarded-For` | да | не нужен: сервер видит настоящий адрес телефона (Docker при пробросе порта сохраняет адрес источника) |

### 2.2. Настройки сервера

Новый профиль `tls` или переменные в `compose.yaml`. Локальная разработка и e2e остаются на обычном HTTP 8080.

```yaml
server:
  port: 8443
  http2:
    enabled: true
  ssl:
    bundle: hovanki
  # No proxy in front anymore: the address is the phone's own.
  forward-headers-strategy: none
spring:
  ssl:
    bundle:
      pem:
        hovanki:
          reload-on-update: true
          keystore:
            certificate: /etc/hovanki/tls/fullchain.pem
            private-key: /etc/hovanki/tls/privkey.pem
hovanki:
  live:
    udp:
      port: 8443          # inside the container
      public-port: 443    # what LiveOpenResponse.udp tells the phones
    reset-secret: ${HOVANKI_LIVE_RESETSECRET:}
```

- **Второй коннектор на 8080** — обычный HTTP, внутри сети compose, без публикации. Через него
  `deploy/hovanki-update.sh` спрашивает `/actuator/restarthold` (он ходит на `:8081`, потом на `:8080`). Сделать через
  `TomcatServletWebServerFactory.addAdditionalTomcatConnectors`.
- **`forward-headers-strategy: native`** без прокси безвреден: Tomcat верит `X-Forwarded-For` только от частных
  адресов. Но `none` честнее, и его надо поставить в том же PR, где уходит Caddy.
- **Проверить замером на стенде, сколько памяти Tomcat держит на одно TLS-соединение** (JSSE, буферы
  `SecureNioChannel`). Это решает, нужен ли шаг 1б (§2.5).

### 2.3. Сертификат: lego и DuckDNS

- **lego v5.5.2** (бинарник с релизов GitHub в `/usr/local/bin/lego`).
- **Запуск:** `lego run --dns duckdns -d ${DOMAIN}` с `DUCKDNS_TOKEN_FILE=/etc/hovanki/duckdns.token`. У v5 одна
  команда `run`: она и получает, и продлевает, когда осталась треть срока (с ARI). Точные флаги путей и хука —
  сверить с документацией v5 при внедрении (`lego migrate` для старых установок).
- **Таймер:** `deploy/hovanki-cert.timer` + `.service` — дважды в день `lego run …` с `--deploy-hook`. Хук копирует
  `fullchain.pem` и `privkey.pem` в `/etc/hovanki/tls/` с правами для uid пользователя `hovanki` из образа. Spring
  перечитает их сам.
- **Почему DNS-01, а не HTTP-01.** Порт 80 не нужен, и первый сертификат можно получить, пока Caddy ещё держит 80 и
  443: переход без простоя.
- **Цена DNS-01.** Токен DuckDNS лежит на машине. Он даёт власть над всеми именами аккаунта DuckDNS. Файл
  `root:root 600`, в `.env` не класть.
- **Лимиты Let's Encrypt:** 5 одинаковых сертификатов в неделю. Не крутить `lego run` в цикле при ошибке.

### 2.4. compose и машина

```yaml
services:
  server:
    ports:
      - "443:8443/tcp"
      - "443:8443/udp"
    volumes:
      - type: bind
        source: /etc/hovanki/tls
        target: /etc/hovanki/tls
        read_only: true
  # caddy: under the profile `caddy`, for a rollback only (§2.6)
```

- **Память.** Без Caddy ~110 КБ на сокет и сам Caddy уходят с машины. `mem_limit` сервера можно поднять с 640 МБ до
  ~850 МБ (ВМ на 1 ГБ). Решить по замеру после шага.
- **Группа безопасности AWS** — открыть 443/udp (делает Denys).
- **Ядро** (`deploy/aws-user-data.sh` и вручную на текущей машине):

```
net.core.rmem_max = 8388608
net.core.wmem_max = 8388608
net.netfilter.nf_conntrack_max = 262144
net.core.somaxconn = 4096
```

  Каждый UDP-поток через проброс порта Docker — это запись conntrack (~300 байт ядра). 30 000 телефонов — около
  9 МБ, а предел по умолчанию на машине с 1 ГБ может быть 65 536 или меньше.

- **Буферы сокета UDP** в сервере: `SO_RCVBUF` и `SO_SNDBUF` по 4 МБ. Без `rmem_max` ядро тихо урежет.

### 2.5. Шаг 1б, если Tomcat с TLS окажется тяжёлым

Если на стенде одно TLS-соединение Tomcat стоит больше ~20 КБ, а соединений много (запасной WebSocket, старые
приложения), то 443/tcp принимает край на Netty в той же JVM:

- TLS — `SslHandler`, BoringSSL освобождает буфер чтения, когда он пуст;
- WebSocket v1 и v2 — сам край;
- остальной HTTP — прокси на Tomcat 127.0.0.1:8080: `HttpServerCodec` / `HttpClientCodec`, после `101` — сырые байты.

Готового обратного прокси в Netty нет, это ~500 строк. Делать только по замеру. После v2 TCP-соединения у
телефонов короткие (команды), а постоянные — только запасной WebSocket.

### 2.6. Откат

- **Caddy остаётся в `compose.yaml` под профилем `caddy`.** Откат:
  1. убрать TLS-настройки сервера (переменная);
  2. вернуть `ports` Caddy;
  3. `docker compose --profile caddy up -d`.

  Сертификаты Caddy лежат в своём томе, он их помнит.
- **В `docs/deploy.md`:** новый раздел про lego и откат; строки «Caddy» в проверках поменять.

## 3. Состояние v2 на сервере

### 3.1. v2.0: режем снимок v1

```
sync по v2 (Up от телефона):
  session.states.acknowledge(up.ack_state)
  snapshot = GameService.sync(player, gameId, request(LiveUplink.samples(up), up.chat_after, LiveUplink.nearby(up),
                              session.device), transport = UDP)
  state    = LiveSections.fromSnapshot(snapshot, session.json)  // :shared, обратная LiveSnapshots.toGameSnapshot
  next     = session.states.next(state)            // LiveServerStates: LiveDelta.between(state, база) + номер состояния
  down     = Down(state_id = next?.stateId, base_state_id = next?.baseStateId, state = next?.state,
                  chat = LiveDownlink.fitChat(snapshot.chat), ack_up = up.seq, …)   // спека §8.2
```

Всё, кроме `GameService.sync` и транспорта, уже в `:shared` (шаг 2 плана, спека §19):

- **`LiveServerStates`** — 8 последних отправленных состояний сессии, как дайджесты (`LiveDigest`: xxHash64 каждой
  секции и каждой части элемента), подтверждённое не выбрасывается. `next` даёт `null`, когда слать нечего (дельта
  пустая, а телефон уже подтвердил последнее отправленное). `forgetAcknowledged()` — на `Up.full = true`.
- **`LiveDelta.between(state, digest)`** — дельта от дайджеста (без самого старого состояния: хватает хешей).
- **`LiveJson`** — один на сессию: кэш байтов JSON-секций (настройки, доска), чтобы не кодировать их на каждый
  `Up`.
- **`LiveUplink`/`LiveDownlink`** — точки, «услышанное», устройство, чат и ошибки v1 ↔ v2.

- **`LiveSections.fromSnapshot`** лежит в `:shared` рядом с `toGameSnapshot` и делает округление (спека, §10) и
  `presence` (спека, §9.1).
- **Тест равенства** — `toGameSnapshot(fromSnapshot(s)) == round(s)` — проверяет обе функции сразу, на всех
  сценариях `GameTest` и случайных партиях.
- **Видимость** — та же, что v1, по построению: v2 не видит ничего, кроме снимка v1 этого же игрока.
- **Цена:** то же, что опрос v1 сегодня (сборка снимка ~30–60 мкс), плюс кодирование секций (~10 мкс) и хеши (~1–2
  мкс). JSON не нужен совсем, а он сегодня — 11–19 % CPU синхронизации. Трафик падает сразу.

### 3.2. Снимок без касания `lastSeen`

`Game.snapshotFor` писал `viewer.lastSeenMillis = now`. Для push это неправильно: телефон ничего не присылал, а
сервер начнёт считать его «на связи».

- Шаг: разделить на `snapshotFor(viewer, now, …)` без побочных эффектов и `touch(viewer, now)`, который зовут
  `sync`, команды и `Up` v2. **Сделал стенд 14.10** (на его ветке, придёт в `main` с его PR):
  `Game.touch(viewerId, now)`, `Game.snapshotFor` только читает, `GameService.snapshotOf` делает оба.
- Push собирает снимок без `touch`: `LiveExchange.push` зовёт `Game.snapshotFor` прямо (разделение стенда в `main` с
  PR #90).
- Тест: push не меняет `lastSeen` (`LiveV2Test.aPushIsNoSignThatThePhoneIsThere`).

### 3.3. Потом: общие куски (`LiveView`)

Это шаг B3 плана, ради процессора. Когда v2.0 уже работает:

- **Ревизия игры.** `Game.revision` растёт в конце каждого блока под замком, который что-то поменял.
- **Общие секции** (G: игра, настройки, SOS, счётчики) кодируются один раз на `(revision, секунда)`, и сразу считается
  их хеш.
- **Секции по роли или аудитории** (R: доска) — один раз на аудиторию.
- **Свои секции каждого игрока** (V: я, радар, поимки, видимые позиции) — на каждого, как сейчас.
- **Снимок v1** собирается из тех же кусков. Тест равенства с v2.0 (`fromSnapshot`) остаётся.

Что учесть (из разбора кода 14.10):

- Многие видимые изменения сейчас никого не толкают: `lastSeen`, раскрытие по устаревшей точке, конец мока, конец
  прожектора, раскрытие вне зоны (толкается только сам прятчик), затухание радара, смена слота токенов, UWB и
  подсказка, изменения доски по времени, возможности телефона в раунде, зрители, позиции, зона, проходящая по
  стоящему прятчику. Для v2.0 это не важно: телефон шлёт `Up` каждые 3 с и получает изменения в ответ. Для кэша по
  ревизии важно: всё, что зависит от времени, должно либо поднимать ревизию, либо входить в ключ кэша (секунда).
- `shiftRound` после паузы двигает все времена раунда: это изменение всех секций со временем.
- Дорогое на каждого зрителя сейчас:
  - HMAC токенов — шаг B2;
  - `revealReason` по каждой цели;
  - `eligibleVoters`.

### 3.4. Хеши и память сессии

- **Хеш.** xxHash64 закодированных байт. Своя реализация на ~60 строк в `server/live/` с тестовыми векторами, или
  `net.openhft:zero-allocation-hashing`. Подойдёт любой хороший 64-битный хеш: телефон хеши не видит.
- **Что хранит сессия.** До `SERVER_STATES_KEPT` (4) состояний: на каждое `LongArray` хешей секций, частей элементов
  и порядков. Для игры на 30 игроков с радаром это ~125 чисел, ~1 КБ на состояние, до 4 КБ на сессию. Для игры на
  10 игроков — ~0,4 КБ на состояние.
- **Остальное в сессии:**
  - ключи — два `SecretKeySpec`. `Cipher` — один на поток через `ThreadLocal`, а не на сессию: он весит килобайты;
  - окно повторов — два `Long`;
  - последний `DeviceReport`;
  - подтверждённый адрес и вызов `path_challenge`;
  - счётчики;
  - сборка кусков — только пока идёт.
- **Цель** — ≤ 3 КБ на сессию в среднем. Мерить `jmap -histo:live` на стенде.

## 4. Код живой сессии

Пакет `server/live/v2/`. Шаг 3a плана сделан: открытие, реестр, WebSocket v2, push и чат (§4.1, §4.2, §4.4–§4.6),
тесты — `LiveV2Test`. Где код разошёлся с этим текстом — §4.7. UDP (§4.3) — шаг 4.

### 4.1. Открытие

`LiveOpenController`: `POST /api/v2/live/open`.

- **Вход.** Токен игры, как у всех маршрутов игры (`PlayerRef`).
- **Проверки:**
  1. `LIVE_V2` выключен → `404` с `FEATURE_DISABLED`;
  2. не больше `MAX_OPENS_PER_MINUTE` открытий на игрока (`RateLimiter`, новый `RateLimit.LIVE_OPEN`);
  3. игра и игрок есть (401 / 404 как `sync`).
- **Сессия.** `LiveSessions.open(player)`: новые ключи из `SecureRandom`, `sessionId` — 8 случайных байт без
  совпадений с живыми сессиями. Четвёртая сессия игрока закрывает самую старую (`CLOSE_REPLACED`).
- **Ответ** по спеке §4. `udp = null`, когда `LIVE_UDP` выключен или UDP-сокет не поднялся.
- **`ApiRoutes.LIVE_OPEN`, `LIVE_WS`** — в `:shared`. Таблица API — в `docs/architecture.md`.

### 4.2. Реестр сессий

`LiveSessions`:

- **Индексы:** `ConcurrentHashMap<Long, LiveSession>` по `sessionId` (8 байт как `Long`) и индекс игрок → сессии.
- **Уборщик раз в 10 с:**
  - без `Up` дольше `SESSION_IDLE_MILLIS` — забыть;
  - старше `SESSION_MAX_AGE_MILLIS` или с ключом у предела — `CLOSE_REOPEN`.
- **Игра удалена** (`GameJanitor`) или токен отозван — сессии игрока закрыть (`CLOSE_GAME_NOT_FOUND` /
  `CLOSE_SESSION_REJECTED`) при следующем `Up`. Отдельно не рассылать.
- **`LIVE_V2` выключили в админке** — всем сессиям `CLOSE_FALLBACK_V1` с ближайшим `Up`.
- **`LIVE_UDP` выключили** — UDP-сессиям `CLOSE_REOPEN`. Они откроются заново без UDP, на WebSocket v2.

### 4.3. UDP

`LiveUdpServer` (`SmartLifecycle`):

- **Сокеты.** Два `DatagramChannel` на `0.0.0.0:8443` с `SO_REUSEPORT`: Linux раскладывает телефоны по ним по адресу.
  У каждого свой поток `live-udp-N`, блокирующий `receive` в прямой `ByteBuffer` потока. Без выделения памяти на
  пакет.
- **Порядок на датаграмму:**
  1. разобрать заголовок;
  2. найти сессию;
  3. `Cipher` потока: `AES/GCM/NoPadding`, `GCMParameterSpec(128, nonce)`, `updateAAD`;
  4. окно повторов;
  5. адрес (спека, §5.6);
  6. куски;
  7. `Up`.
- **Обработка `Up` прямо в потоке приёма:** вызов `GameService.sync` под замком игры, сборка и отправка `Down`. На
  10 000 `Up` в секунду при ~40 мкс это 0,4 ядра, двух потоков хватает. Замок игры держится как сейчас, по одной
  игре.
- **Отправка** — `channel.send` того же сокета (потокобезопасна в JDK). Push отправляет поток, который разносит
  толчки.
- **Отказ.** Порт не поднялся (занят, нет прав) — сервер работает без UDP: в ответе на открытие `udp = null`, в логе
  предупреждение, метрика.
- **e2e.** Свой случайный порт. Шифрование то же, TLS не нужен.

### 4.4. WebSocket v2

`LiveWsHandler : BinaryWebSocketHandler` на `ApiRoutes.LIVE_WS`, рядом с `GameSocketHandler`.

- **Подключение.** Проверить `Authorization` и `?s=`: сессия должна быть этого игрока. Подпротокол
  `hovanki.live.v2`.
- **Кадры.** Каждый бинарный кадр — `Up`, ответ — `Down` тем же кодом, что UDP. Без шифрования и кусков.
- **Транспорт сессии.** Открытый WebSocket становится транспортом сессии: push идёт в него. Годный UDP с проверенным
  адресом возвращает сессию на UDP и закрывает WebSocket (`1000`).
- **То же, что у v1:** `NoSocketCompression` (никакого permessage-deflate: 220 КБ zlib на сокет),
  `ConcurrentWebSocketSessionDecorator`, приём кусками.

### 4.5. Push и чат

- **Толчки.** `PokeSink.poke(gameId, pokes, except)` (`game/Pokes.kt:63`) сейчас реализует `GameSockets`. Сделать
  разносчик на двух получателей: `GameSockets` (v1) и `LivePushes` (v2).
- **`LivePushes`** для каждой живой сессии каждого затронутого игрока:
  - прошло меньше `PUSH_GAP_MILLIS` с прошлого push — отложить на остаток (одна задача на сессию);
  - иначе собрать снимок без `touch` (§3.2) под замком игры, нарезать, дельта от `acked`, отправить.
  - `except` — игрок, который только что получил ответ на свою команду или `Up`: ему не надо.
- **Чат.** Новые сообщения игры приходят так же, как в `GameSockets` для `ServerFrame.Chat`. Каждой сессии, кому
  сообщение видно, — `Down{chat_push = true}`, не больше `MAX_CHAT_BYTES`; не влезло — push без чата, телефон
  заберёт по курсору.

### 4.6. Ошибки синхронизации

`GameException` из `GameService.sync`:

| ошибка | что уходит |
|---|---|
| 401 | `Close{CLOSE_SESSION_REJECTED}` |
| 404 | `Close{CLOSE_GAME_NOT_FOUND}` |
| остальные | `Down{reply_to, error}`, как `ServerFrame.Error` v1 |

Превышение частоты `Up` (12 за 5 с): по UDP лишние молча выбросить, по WebSocket — `Down.error` с
`TOO_MANY_REQUESTS`.

### 4.7. Как сделано (шаг 3a)

| класс | что |
|---|---|
| `LiveOpenController` | `POST /api/v2/live/open`: флаг, токен, тело, игра; ответ с `udp = null` |
| `LiveSessions` | сессии в памяти по id и по игре, открытия игрока, уборка раз в 10 с, метка RESET, остановка сервера |
| `LiveSession` | ключи, состояния (`LiveServerStates`), `LiveJson`, `lastSeq`, отчёт телефона, окно частоты `Up`, ссылка, ожидающий push |
| `LiveExchange` | `Up` → `GameService.sync` → `Down`; push; ошибки и закрытия по §4.6 |
| `LivePushes`, `LivePokeSink` | толчки игры как push v2 (4 потока `live-pushes`); `@Primary`-разносчик на v1 и v2 |
| `LiveWsHandler`, `LiveWsHandshake`, `LiveWsConfig` | WebSocket v2: подпротокол, приём кусками до 128 КБ, коды закрытия |
| `LiveMetrics` | `hovanki.live.open{result}`, `down{kind,state}`, `bytes{dir,transport}`, `close{reason}`, `bad_message`, `oversize`; сессии — `hovanki.live.sessions{transport=ws2\|none}` |
| `LiveProperties` | `hovanki.live.reset-secret` |

Отличия от текста выше:

- **Частота открытий** считается в `LiveSessions`, в памяти рядом с сессиями, а не через `RateLimiter`: новых
  настроек в `application.yaml` не нужно. Ответ тот же: 429 с `Retry-After`.
- **Ушедшая игра и отозванный токен** проверяются на каждом `Up`, на каждом push и уборщиком раз в 10 с, не только
  при следующем `Up`. Так же выключенный `LIVE_V2`: уборщик закрывает сессии кодом 4503 в пределах 10 с.
- **404 из игры при живой игре** — это игрок, которого в ней уже нет (вышел, а токен ещё не отозван): сессия
  закрывается как `CLOSE_SESSION_REJECTED`, а не `CLOSE_GAME_NOT_FOUND`.
- **WebSocket с чужой сессией** (токен другого игрока) закрывается кодом 4410, а не 4401: годный токен не должен
  кончать игру на телефоне. Без токена — 4503, как у v1: подключение потеряло заголовок, решает v1.
- **Чат и состояние в push — разными `Down`**: сначала чат (`chat_push`, без состояния), потом состояние. Так
  правило спеки §8.6 «`Down` без состояния — ничего не изменилось» не ломается чатом.
- **Push без нового.** Если состояние то же, что в последнем отправленном `Down`, push не уходит
  (`LiveServerStates.next(push = true)`): телефон его уже получил или получит в ответе на `Up`. Ответ на `Up`
  правило 4 спеки §8.2 соблюдает как раньше.
- **Новый WebSocket той же сессии** забирает её: прежний закрывается кодом 1000, база дельт сохраняется.
- **Отказанный `Up`** (ошибка, частота) не двигает `ack_up`: точки этого `Up` телефон пришлёт ещё раз.
- **`srv` полевого журнала** пока несёт только `poll` и `socket`, без `ws2`.

## 5. Метрики и логи

| метрика | что |
|---|---|
| `hovanki.game.sync{transport=poll\|socket\|udp\|ws2}` | тот же таймер `GameService.sync`, с новыми транспортами |
| `hovanki.live.sessions{transport}` | живые сессии сейчас |
| `hovanki.live.datagrams{dir=in\|out}`, `hovanki.live.bytes{dir, transport}` | объём |
| `hovanki.live.down{kind=reply\|push, full}` | сколько `Down` и сколько из них полных состояний |
| `hovanki.live.lost{dir}` | пропуски `pn`: оценка потерь |
| `hovanki.live.close{reason}` | закрытия |
| `hovanki.live.auth_fail`, `hovanki.live.bad_message`, `hovanki.live.oversize` | плохое |
| `hovanki.live.transport_switch{to=udp\|ws2}` | сессии, переехавшие между UDP и WebSocket |
| `hovanki.live.open{result}` | открытия |

- **`srv` полевого журнала** (`FieldServerSampler`) — p50 и p95 синхронизации по транспортам, вместе с новыми.
- **Логи:** без ключей, токенов, `resetToken`, `reset_secret`, координат и чата (спека, §16). Ошибка разбора — тип
  исключения и длина, без байт.

## 6. Флаги

- **`ServerFeature.LIVE_V2` и `LIVE_UDP`** — на весь сервер, не выбор игры, как `LIVE_SOCKET`. Выключены по
  умолчанию, включает админ. Оба попадают в `GameSnapshot.enabledFeatures`, по ним приложение решает, пробовать ли v2
  (спека, §3; [client.md](client.md), §5).
- **Добавлять безопасно.** `enabledFeatures` — список строк, старые приложения незнакомое имя пропускают.
- **e2e.** `Observer` держит `LIVE_V2` включённым только при `-Pe2e.transport=v2ws` (сделано, `E2eTransport.features`),
  как `LIVE_SOCKET` при `socket`; `LIVE_UDP` — при `v2`, с UDP.

## 7. Стенд и нагрузка

- **У Gatling нет UDP.** Нагрузку v2 по UDP даёт свой генератор на JVM в `perf/`: N телефонов с настоящим
  AES-GCM и кодеком из `:shared`, как `UdpSync` из замеров. Это и есть генератор на 30 000 из первого этапа стенда:
  его делает тред стенда (договорились 14.10), второй не пишем.
- **WebSocket v2** Gatling умеет (бинарные кадры).
- **Что мерить на каждом шаге:** CPU на синхронизацию, память на игрока (`jmap -histo:live`), байты в каждую сторону,
  p95 и p99 ответа. Лесенка в контейнере как на проде (`perf/up.sh --container`).

## 8. e2e

- **Сервер.** Тот же в процессе. UDP на случайном порту, адрес отдаёт ответ на открытие.
- **Боты.** Связаны как приложение (`LiveFirstGameConnection`); WebSocket v2 идёт через `FakeNetwork.liveSockets`
  (сделано). С UDP — `FakeNetwork.datagrams` с потерями, перестановками и сменой адреса ([client.md](client.md), §8).
- **Новый сценарий** `LiveV2Test` (сделаны push, сеть и выключение `LIVE_V2` посреди игры → v1; остальное — с UDP):
  - UDP закрыт → WebSocket v2;
  - выключение `LIVE_V2` посреди игры → v1;
  - смена адреса;
  - рестарт → RESET → `Ended(GAME_NOT_FOUND)` после открытия;
  - push без `touch`.
- **`DebugEndpointAbsentTest`** — без изменений: v2 ничего отладочного не добавляет.

## 9. Документы

- `docs/adr/0026-live-v2.md` из [ADR 0026](../adr/0026-live-v2.md).
- `docs/architecture.md`: таблица API, раздел о живом канале.
- `docs/deploy.md`: без Caddy, lego, порты, откат.
- `docs/perf.md`: генератор UDP.
- `CLAUDE.md`: строка про ADR 0026 и новые пакеты.
