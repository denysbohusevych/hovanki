# Протокол v2: что поменять в приложении

Руководство для того, кто будет переводить приложение на v2. Правила протокола — в
[spec.md](spec.md) (дальше «спека»). Здесь — где они живут в коде и в каком порядке их делать.
Пути и имена классов сверены с `main` на 14.10.2026.

## 1. Главное

- **Экраны не меняются.** v2 — ещё одна реализация `GameConnection` (`clientCore/.../network/GameConnection.kt`).
  Она отдаёт те же `ConnectionEvent`: `Snapshot` с полным `GameSnapshot`, `Chat`, `Problem`, `Ended`. Снимок
  собирается из состояния v2 (спека, §9). `GameSessionManager`, `LobbyState` и все экраны его не отличают от снимка
  v1.
- **Команды не меняются.** `HttpGameApi`, ответы команд со снимком и `GameSessionManager.applySnapshot` с его
  «новее тот, у кого больше `serverTimeMillis`» остаются как есть.
- **v1 остаётся** в приложении как запасной путь. `WebSocketGameConnection` и `PollingGameConnection` не трогаем.
- **Один код для всех платформ.** Протокол, состояние и сборка снимка — в `commonMain`. Отдельно для платформ:
  - шифрование AES-GCM (Android и JVM — `javax.crypto`, iOS — мост Swift на CryptoKit);
  - сигнал «сеть сменилась».

  UDP — через Ktor (`aSocket().udp()`), он есть на JVM, Android и iOS.

## 2. Где что будет

| модуль | пакет | что |
|---|---|---|
| `:shared` | `src/commonMain/proto/live_v2.proto` | схема; Wire генерирует классы в `app.hovanki.shared.live.v2` |
| `:shared` | `protocol/Live.kt` | `LiveOpenRequest`, `LiveOpenResponse`, `LiveUdpEndpoint` (JSON); `ApiRoutes.LIVE_OPEN`, `ApiRoutes.LIVE_WS`; `ServerFeature.LIVE_V2`, `LIVE_UDP` |
| `:shared` | `live/` (**уже в коде**, шаг 2) | `LiveLimits` (спека, §14); `LiveClientStates` (до 8 состояний телефона, применение `Down`, §8.6) поверх `LiveStates.apply`; `LiveSnapshots` (состояние → `GameSnapshot`, §9) и обратный ему `LiveSections.fromSnapshot` (им пользуется сервер); `LiveUplink` (кусок `SyncRequest` → `Up`, §7.1, §10); `LiveDownlink` (чат и ошибки `Down`, §11, §13); `LiveDatagrams`, `LiveReassembler`, `LiveReplayWindow` (заголовок, куски, окно повторов, §5); интерфейс `LiveAead`. Серверное (`LiveDelta`, `LiveServerStates`) телефону не нужно. Полная таблица — спека, §19 |
| `:clientCore` | `network/live/` | **сделано (шаг 3b, §10):** `LiveApi`/`HttpLiveApi` (открытие по HTTPS); `LiveSocket`, `LiveSocketOpener`, `KtorLiveSocketOpener` (WebSocket v2); `LiveGameConnection : GameConnection`; `LiveFirstGameConnection`; `LiveChannelException`. **Сделано (шаг 4, §11):** `LiveUdp`, `UdpLiveSocket`, `KtorLiveDatagramOpener`, `NetworkChanges` |
| `:clientCore` | `jvmMain`, `androidMain` | `JdkLiveAeads` (**сделано**) |
| `:composeApp` | `iosMain`, `network/` | **сделано (§12):** мост `LiveCryptoBridge`, `installLiveCryptoBridge`, `IosLiveAeads`; `IosNetworkChanges` на `nw_path_monitor`. В `composeApp`, а не в `:clientCore`: Swift видит только фреймворк `ComposeApp` |
| `iosApp` | `LiveCrypto.swift` | реализация моста на CryptoKit (**сделано**, §12) |
| `:composeApp` | `di/Koin.kt`, `di/PlatformModule.android.kt`, `di/PlatformModule.ios.kt` | новая привязка `GameConnection` (§5), **сделано**; `LiveUdp` на Android с `AndroidNetworkChanges` и на iOS с мостом и `IosNetworkChanges` (**сделано**) |
| `e2e` | `bot/`, `scenario/` | **сделано:** `FakeNetwork.liveSockets`, `FakeNetwork.liveDatagrams`, `-Pe2e.transport=v2ws` и `v2`, `LiveV2Test` |

Почему состояние и сборка снимка в `:shared`, а не в `:clientCore`: тест равенства на сервере (спека, §18)
собирает снимок тем же кодом, что телефон. Сервер и приложение не могут разойтись молча.

## 3. Как устроен `LiveGameConnection`

```
connect(session, outbox, chatAfter, extras, intervalMillis): Flow<ConnectionEvent> = flow {
  loop:
    open = liveApi.open(session)                    // спека §4; 404+FEATURE_DISABLED → throw FallbackToV1
    transport = pickTransport(open)                 // UDP, если open.udp и сеть не помечена; иначе WebSocket v2
    states = LiveClientStates()                     // новая сессия — новые состояния
    run until close / expiry / transport failure:
      каждые intervalMillis(последний снимок) (в лобби — IDLE_UP_MILLIS), и сразу при chat_more:
        up = buildUp()                              // §3.1
        transport.send(up)
      на каждый Down: applyDown(d)                  // спека §8.6 → emit Snapshot / Chat / Problem / Ended
      за REOPEN_BEFORE_MILLIS до expiresAt: открыть новую сессию рядом и перейти на неё (спека §4)
}
```

### 3.1. Сборка Up

- **`seq`** — счётчик сессии с 1.
- **`ack_state`** — `states.ackState`: `state_id` состояния, которое сейчас на экране.
- **`time_ms`** — `ServerClock.now()`. `LiveGameConnection` получает `ServerClock` в конструкторе: `GameConnection`
  его сейчас не знает. Альтернатива — брать время из `samples`: у них уже серверное время.
- **Точки.** Своя очередь «отправлено, не подтверждено» внутри `LiveGameConnection`: `List<Pair<seq, samples>>`.
  Каждый `Up` = все неподтверждённые + `outbox.drain()`, старые первыми, не больше 100 (лишние старые выкинуть, как
  `LocationOutbox` выкидывает при переполнении). `Down.ack_up = n` убирает всё, что ушло в `Up` с `seq ≤ n`.
  - Когда соединение кончается (`close`, уход на v1, отмена), неподтверждённые точки — обратно в
    `outbox.requeue(...)`, чтобы их довёз v1.
  - Менять `LocationOutbox` не нужно.
- **`extras()`** вызвать ровно один раз на `Up`: он забирает услышанное радаром.
  - **`nearby`** → `sightings`, один раз. Токены не из 8 строчных hex не отправлять (`RadarToken.isWellFormed`).
  - **`device`** → сравнить с последним, который сервер подтвердил. Отличается — положить в `Up` и класть в каждый
    следующий, пока `ack_up` не дойдёт до `seq`, где он впервые ушёл. `null` (телефон ничего не знает) — не
    отправлять.
- **`chat_after`** — `chatAfter()`, `null` → поле не ставить.
- **`path_response`** — последний `path_challenge`, на который ещё не ответили.
- **`full`** — после `Down`, база которого потерялась (спека, §8.3).

### 3.2. Применение Down

Ровно по алгоритму спеки §8.6: его делает `LiveClientStates.apply(stateId, baseStateId, state)`. `false` — базы нет
или `order` не сходится: следующий `Up` идёт с `full = true` (`states.needFull`). Что выходит наружу:

| `Down` | событие |
|---|---|
| с состоянием или без (кроме push чата без состояния), `server_time_ms` не меньше прошлого выданного | `ConnectionEvent.Snapshot(snapshot, Transport.LIVE_UDP / LIVE_WS, bytes)`; `bytes` — сумма байт датаграмм этого `Down` (для `sync.bytes` полевого журнала) |
| `chat_push = true` | `ConnectionEvent.Chat(messages)`, без снимка (как `ServerFrame.Chat` v1); сервер шлёт его отдельным `Down`, без состояния |
| `error` | `ConnectionEvent.Problem(ApiException(error), retryInMillis)` |
| `close` `SESSION_REJECTED` / `GAME_NOT_FOUND` | `ConnectionEvent.Ended(...)`, поток кончается |
| `close` `REOPEN` / `REPLACED` / незнакомая | новое открытие, событие не нужно |
| `close` `TOO_MANY` | `Problem`, открыть заново через `retry_after_s` |
| `close` `FALLBACK_V1` | бросить `FallbackToV1` — ловит обёртка (§5) |

`Transport` (`GameConnection.kt:78`) получает два новых значения: `LIVE_UDP` и `LIVE_WS`. Их читают:

- `Diagnostics.onSynced` — подпись «by udp», «by ws2»;
- `FieldSession` — `SyncFields`: нужны новые значения журнала `udp`, `ws2` в `:shared` `FieldSchema`.

Протокола это не касается.

### 3.3. Сборка GameSnapshot

`LiveSnapshots.toGameSnapshot(states.shown, serverTimeMillis, chat)` в `:shared`, по таблицам спеки §9. Один
объект `LiveSnapshots` на сессию: его `LiveJson` помнит последние байты JSON-секций и не разбирает их заново. Что
легко пропустить:

- **`lastSeenMillis`** игрока без `presence.last_seen_ms` — это `serverTimeMillis` этого же снимка (спека, §9.1).
  `LobbyState.OFFLINE_AFTER_MILLIS` переехать в `LiveLimits.OFFLINE_AFTER_MILLIS` и читаться оттуда.
- **Секреты и токены** обратно в строчный hex.
- **JSON-секции** разбирать `protocolJson` (`ignoreUnknownKeys = true`): новые поля нового сервера пропускаются, как в
  v1.
- **Порядок** списков `players`, `catches`, `radar.contacts` — по `order` (спека, §8.5).
- **`chat`** в снимке — сообщения из этого `Down` (как в v1 снимок несёт новые сообщения). Курсор двигает
  `GameSessionManager`, как сейчас.

Собирать снимок на каждый `Down` дёшево: десятки объектов. Кэшировать неизменные части (настройки, доска) между
снимками — можно, но не обязательно.

## 4. Транспорты

```kotlin
/** One way to carry Up and Down of a live session: UDP datagrams or WebSocket v2 frames. */
interface LiveTransport {
    val kind: Transport                       // LIVE_UDP or LIVE_WS
    suspend fun send(up: Up)                  // throws LiveTransportException when the way is gone
    val downs: Flow<ReceivedDown>             // ReceivedDown(down: Down, bytes: Int)
    suspend fun close()
}
```

### 4.1. UDP (`UdpLiveTransport`)

- **Сокет:** `aSocket(selector).udp().connect(InetSocketAddress(host, port))`. Один сокет на сессию.
- **Отправка:** `Up` → protobuf → больше 1170 байт — куски → каждая датаграмма: заголовок + `LiveAead.seal` (спека,
  §5.1–5.4). Всё, кроме `seal`, — `LiveDatagrams` в `:shared`.
- **Приём:**
  1. проверить заголовок и `session_id`;
  2. RESET (`0x22`) — сравнить с `resetToken` за постоянное время; совпало — закрыть транспорт с причиной «reopen»;
  3. `LiveAead.open` — не открылось: выбросить;
  4. окно повторов;
  5. сборка кусков, разбор `Down`.
- **Смена сети:** `NetworkChanges` (Android `ConnectivityManager.NetworkCallback.onAvailable/onLost`, iOS
  `nw_path_monitor`) → закрыть сокет, открыть новый, продолжить с тем же `session_id` и следующим `pn`. Сервер
  проверит новый адрес (спека, §5.6). Новую сессию не открывать.
- **UDP не проходит** (спека, §5.7):
  - при открытии 3 `Up` по 1,5 с без ответа;
  - в игре 3 `Up` подряд без `Down`;

  → `LiveTransportException(udpBlocked = true)`. `LiveGameConnection` переходит на WebSocket v2 в той же сессии и
  помечает «UDP не идёт» до смены сети или на 10 минут.

### 4.2. WebSocket v2 (`WsLiveTransport`)

- Ktor `webSocketSession(wss://…/api/v2/live/ws?s=<sessionId>)`, заголовок `Authorization` и подпротокол
  `hovanki.live.v2`.
- Бинарные кадры: `Up` → `Frame.Binary`, `Down` ← `Frame.Binary`. Текстовые кадры не ждём.
- Таймауты открытия и ответа — как у v1 (`SocketLimits.OPEN_TIMEOUT_MILLIS`, `REPLY_TIMEOUT_MILLIS`).
- Коды закрытия → `Close.reason` по таблице спеки §6.
- Для e2e тот же шов, что у v1: `GameSocketOpener` → сделать рядом `LiveSocketOpener`, чтобы `FakeNetwork.sockets`
  мог подставить свой.

### 4.3. Шифрование (`LiveAead`)

```kotlin
// :shared, app.hovanki.shared.live — one object per key and direction, the key stays inside it.
interface LiveAead {
    /** AES-128-GCM: the ciphertext followed by the 16-byte tag. */
    fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    /** Null when the tag doesn't match. */
    fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray?
}
```

`LiveDatagrams.seal(aead, sessionId, pn, plaintext)` и `LiveDatagrams.open(aead, datagram)` собирают и разбирают
датаграмму вокруг него. Пример реализации на JDK — `JdkAead` в `shared/src/jvmTest/.../LiveAeadVectorsTest.kt`: он же
проверяет байты спеки §18.

- **JVM и Android:** `Cipher.getInstance("AES/GCM/NoPadding")`, `GCMParameterSpec(128, nonce)`, `updateAAD(aad)`. Есть
  на всех Android с minSdk 26. Объект `Cipher` не потокобезопасный: брать на вызов или держать по одному на
  транспорт.
- **iOS** — §6.
- **Нет реализации** (iOS-сборка без моста) — `udp` не предлагать, сразу WebSocket v2.

## 5. Кто выбирает v2

Сейчас `Koin.kt:147` — `AdaptiveGameConnection(WebSocketGameConnection, PollingGameConnection)`. Он начинает с опроса
и переходит на сокет, когда в снимке есть `LIVE_SOCKET`. Новая обёртка — `LiveFirstGameConnection(live, v1)`, где
`v1` — тот самый `AdaptiveGameConnection`:

1. **Первый снимок.** Сессия начинается с v1, как сейчас: первый снимок приходит из ответа команды (создать, войти)
   или первого опроса при возобновлении.
2. **Переход на v2.** В снимке есть `ServerFeature.LIVE_V2` в `enabledFeatures`, и v2 не запрещён на время → закрыть
   v1-поток, запустить `LiveGameConnection`. Неподтверждённые точки не теряются: v1 вернул их в `outbox`.
3. **`FallbackToV1`** (404 с `FEATURE_DISABLED`, `CLOSE_FALLBACK_V1`) → обратно на v1, v2 не пробовать
   `V1_AFTER_DISABLED_MILLIS` (10 минут).
4. **WebSocket v2 тоже не открывается** (нет сети до 443/tcp, 3 разрыва за минуту, как правило v1) → v1 на
   10 минут.
5. **`Ended`** с любого пути — конец, как сейчас.

Почему не встроить v2 в `AdaptiveGameConnection`: тот уже держит своё правило «сокет или опрос», и его тест
(`AdaptiveGameConnectionTest`) остаётся как есть.

## 6. iOS

- **UDP.** Ktor UDP на iOS работает через POSIX-сокеты. Apple советует для UDP Network.framework (TN3151): у
  BSD-сокетов надо самим следить за сменой сети. Мы так и делаем (`NetworkChanges` через `nw_path_monitor`).
  - Пока игра идёт, приложение не засыпает (фоновый режим `location`), сокет живёт.
  - Если шаг 0 плана (проверка на телефонах) покажет проблемы, план Б — мост Swift на `NWConnection`, за тем же
    `LiveTransport`.
- **AES-GCM.** CryptoKit есть только из Swift, а в публичном CommonCrypto нет GCM. Поэтому мост, как
  `LiveActivityBridge`:

```swift
// iosApp/iosApp/LiveCrypto.swift — installed at start: LiveCryptoBridgeKt.installLiveCryptoBridge(bridge: LiveCrypto())
import CryptoKit
final class LiveCrypto: NSObject, LiveCryptoBridge {
    func seal(key: Data, nonce: Data, aad: Data, plaintext: Data) -> Data? {
        guard let n = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.seal(plaintext, using: SymmetricKey(data: key), nonce: n, authenticating: aad)
        else { return nil }
        return box.ciphertext + box.tag
    }
    func open(key: Data, nonce: Data, aad: Data, sealed: Data) -> Data? {
        guard sealed.count >= 16, let n = try? AES.GCM.Nonce(data: nonce),
              let box = try? AES.GCM.SealedBox(nonce: n, ciphertext: sealed.dropLast(16), tag: sealed.suffix(16))
        else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }
}
```

  - `LiveCryptoBridge` — протокол Kotlin в `composeApp/iosMain` (`NSData` на входе и выходе). Ключ мост получает в
    каждом вызове, а `LiveAead` из `IosLiveAeads.forKey(key)` хранит его на стороне Kotlin.
  - Прежде чем поставить мост, Kotlin проверяет его байтами спеки (§18, `LiveAeadCheck`). Не прошёл — моста нет, UDP
    не предлагается.
  - Мост собирается и проверяется только на Mac с Xcode. Владелец собирает его на своём Mac (Remote Control, как
    стенд). Вслепую из облака iOS не чинить.
  - Запасной путь без Swift — библиотека `cryptography-kotlin` 0.6 с провайдером CryptoKit. Минусы: версия 0.x,
    свой плагин Gradle, известная поломка линковки, если Xcode не в `/Applications/Xcode.app`.
- **Сборка без моста** просто не предлагает UDP (§4.3). Приложение работает на WebSocket v2.

## 7. Android

- **Сеть в фоне.** Во время игры работает служба переднего плана (`BackgroundTracker`), у неё нет ограничений сети
  (Doze её не трогает). UDP работает в кармане так же, как опрос.
- **`NetworkChanges`** — `ConnectivityManager.registerDefaultNetworkCallback`. На `onAvailable` новой сети —
  пересоздать сокет.
- **AES-GCM** — `javax.crypto`, общий с JVM код. Можно `jvmAndAndroidMain`, если такой набор исходников появится;
  пока копия на 20 строк в двух местах.

## 8. Тесты

**`clientCore/src/commonTest`** (JVM и iOS), с ненастоящим `LiveTransport`:

1. **Первый обмен.** Открытие → `Up{ack 0}` → полное состояние → `Snapshot`, равный ожидаемому `GameSnapshot`.
2. **Дельты.** Цепочка `Down` с потерей, перестановкой и повтором: показанный снимок всегда равен правде на
   `server_time_ms` (спека, §8).
3. **База потерялась** → `full = true` в следующем `Up`.
4. **Точки.** Остаются до `ack_up`; при уходе на v1 возвращаются в `outbox`; не больше 100 в `Up`.
5. **`device`.** Уходит при изменении и повторяется до подтверждения; без изменений его нет.
6. **Сигналы радара** уходят один раз.
7. **Чат.** Курсор; `chat_more` → `Up` сразу; `chat_push` → `ConnectionEvent.Chat` без снимка.
8. **Закрытия.** `close` каждой причины → нужное событие.
9. **RESET** → новое открытие.
10. **Срок сессии.** Новая открывается за 5 минут до `expiresAt`, переход без потери снимков.
11. **Обёртка** (`LiveFirstGameConnection`):
    - v1 → v2 по `LIVE_V2`;
    - `FEATURE_DISABLED` → v1 на 10 минут;
    - UDP молчит → WebSocket v2;
    - WebSocket v2 не открывается → v1.

**`shared/src/commonTest`:**

- тестовые векторы спеки §18;
- куски;
- окно повторов;
- `toGameSnapshot` на всех полях, с пустыми и нулевыми значениями.

**e2e** (`e2e/`):

- **Датаграммы.** `FakeNetwork` получает `datagrams`: потеря, задержка, перестановка, повтор, смена адреса (как
  переход Wi-Fi → LTE) и «UDP закрыт».
- **Транспорт ботов.** Боты связаны как приложение (`LiveFirstGameConnection`), выбор — на весь прогон:
  `-Pe2e.transport=v2ws` гоняет все сценарии на WebSocket v2, `-Pe2e.transport=v2` — на UDP с запасными путями
  (оба сделаны).
- **Проверка видимости.** `SnapshotAudit` проверяет снимки, собранные из v2, теми же правилами.
- **Новый сценарий** `LiveV2Test`:
  - потеря 5 % датаграмм;
  - смена адреса в раунде;
  - UDP закрыт → WebSocket v2;
  - сервер выключает `LIVE_V2` посреди игры → v1;
  - рестарт сервера → RESET.

**На телефонах** — шаг 0 и шаг 6 плана ([plan.md](plan.md)).

## 9. Порядок работы и чек-лист PR

1. **`:shared`:** схема, Wire, `LiveLimits`, `LiveDatagrams`, `LiveClientStates`/`LiveStates`, `LiveSnapshots`,
   `LiveUplink`, `LiveDownlink`, тесты. **Сделано 14.10** (шаг 2 плана). Сервер делает свой шаг 3 на этом же коде.
2. **`:clientCore`:** **сделано 14.10** (шаг 3b, §10).
   - `LiveApi`, `LiveGameConnection` поверх WebSocket v2, `LiveFirstGameConnection`, тесты;
   - e2e на `-Pe2e.transport=v2ws`.

   Это первый релиз: v2 по WebSocket.
3. **UDP:** **сделано 16.10** для Android и ботов (шаг 4, §11): `UdpLiveSocket`, `JdkLiveAeads`, `NetworkChanges`,
   тесты, e2e на `v2`. Мост iOS — впереди.
4. **Полевой журнал:** новые значения `SyncFields`; диагностика — «by udp», «by ws2».

Перед PR:

- `./gradlew spotlessApply`
- `./gradlew :shared:jvmTest :clientCore:jvmTest`
- `./gradlew :e2e:test`, `./gradlew :e2e:test -Pe2e.transport=v2`
- iOS — на Mac владельца: `./gradlew :shared:iosSimulatorArm64Test :clientCore:iosSimulatorArm64Test` и сборка
  `iosApp`
- в PR написать, что запускали (CLAUDE.md, «Run the long tests yourself before a PR»)

Документы того же PR:

- `docs/architecture.md`: таблица API и раздел о живом канале;
- новый ADR 0026 (из [ADR 0026](../adr/0026-live-v2.md));
- запись в `docs/devlog.md`, если игрок что-то заметит. Игрок v2 не видит, но «связь держится в метро» — это
  заметно.

## 10. Как сделано (шаг 3b) и чем отличается

Пакет `app.hovanki.client.network.live` в `:clientCore`, всё в `commonMain`. В приложении `Koin.kt` связывает
`LiveFirstGameConnection(LiveGameConnection(...), AdaptiveGameConnection(...))`; боты e2e — так же. Пока сервер не
включил `LIVE_V2`, приложение работает как раньше: обёртка только смотрит на `enabledFeatures` в снимках v1.

| класс | что |
|---|---|
| `LiveApi`, `HttpLiveApi` | `POST /api/v2/live/open`; в запросе `transports = ["ws"]`, с шагом 4 `["udp", "ws"]`, где у платформы есть UDP |
| `LiveSocket`, `LiveSocketOpener`, `KtorLiveSocketOpener` | шов WebSocket v2, как `GameSocket` v1: бинарные кадры, `Authorization`, подпротокол `hovanki.live.v2`, адрес из `wsPath` ответа (только путь этого сервера) |
| `LiveGameConnection` | живая сессия: открытие, `Up` по расписанию, `Down` → `ConnectionEvent`, переподключение, новая сессия |
| `LiveFirstGameConnection` | v2 или v1 (§5) |
| `LiveChannelException` | сбой канала: код закрытия, работал ли канал, «сервер выключил v2» |

Что вышло не так, как написано выше:

- **Нет отдельного `LiveTransport`.** Шов — `LiveSocket`: «отправить сообщение, получить сообщение, код закрытия».
  `LiveGameConnection` сам кодирует `Up` и разбирает `Down`. UDP (шаг 4) встанет за тот же шов: датаграммы, шифрование и
  куски будут внутри своей реализации `LiveSocket`, которая отдаёт целые `Down`.
- **Один `Up` в пути**, как у сокета v1: следующий — после ответа и паузы. Ответа нет `REPLY_TIMEOUT_MILLIS` (10 с) —
  WebSocket открывается заново на той же сессии, состояния сохраняются.
- **Пауза между `Up`.** Темп игры (`intervalMillis`, не меньше 500 мс), но не больше `IDLE_UP_MILLIS` (10 с); в лобби и
  на итогах — ровно 10 с: всё, что там происходит, приходит push-ем. Push сам по себе `Up` не вызывает. Сразу, без
  паузы, `Up` уходит, когда база потерялась (`full`) и когда сервер сказал `chat_more`.
- **Ожидания только читают.** Таймаут (ответа, паузы) стоит только вокруг чтения `Down`; разбирается `Down` и выдаёт
  события уже после ожидания. Иначе таймаут, сработавший посреди `emit` (приложение медленно разбирает снимок), ломает
  поток навсегда: Kotlin запрещает `emit` после того, как прошлый `emit` бросил исключение, и приложение молча перестаёт
  синхронизироваться. Это нашёл прогон e2e на v2 (`RadarTest`). `Down`, прочитанный ровно в момент таймаута, может
  потеряться — это как потеря пакета: следующий `Up` подтверждает то, что есть, и сервер шлёт дельту от него.
- **Отказ `Up`** (`Down.error`) — `ConnectionEvent.Problem` с `ApiException` того же статуса, что дал бы v1 (`429` для
  `TOO_MANY_REQUESTS`), следующий `Up` — через `retry_after_s` или паузу ошибки (1 → 5 с).
- **Точки.** Все неподтверждённые идут в каждом `Up` (не больше 100, лишние старые выкидываются) и снимаются, когда
  `ack_up` дошёл до последнего `Up`, который их нёс. Отказанный `Up` их не подтверждает. Конец соединения (отмена,
  переход на v1, конец сессии) — обратно в `outbox`.
- **Отчёт телефона** идёт, пока он отличается от подтверждённого; новая сессия начинает с «ничего не подтверждено».
- **Новая сессия до срока** — по монотонным часам телефона: `expiresAtMillis − serverTimeMillis` ответа минус 5 минут.
  Неверные часы телефона её не сдвигают. Старый WebSocket закрывается, потом открывается новая сессия (не «после первого
  `Down` новой»): между ними может пропасть один push, ответ на первый `Up` новой сессии всё равно несёт полное
  состояние.
- **Коды закрытия WebSocket:** 4401 и 4404 — `Ended`; 4410 — новая сессия без события; 4429 — `Problem`, потом новая
  сессия; 4503 — «сервер выключил v2»; другой код или его нет — та же сессия заново после паузы (1 → 5 с). Открытие:
  401 — `Ended(SESSION_REJECTED)`, 404 с `FEATURE_DISABLED` — «выключил», 404 — `Ended(GAME_NOT_FOUND)`, 429 — ждать
  `Retry-After`.
- **Обёртка** уходит на v1 на 10 минут, когда сервер выключил v2, когда канал ни разу не ответил на этом соединении
  или когда он рвался 3 раза за минуту; один разрыв работавшего канала остаётся ему. Неудачи v2 на экран не выходят:
  v1 сообщает о своих.
- **Полевой журнал и диагностика:** `Transport.LIVE_WS` пишется как `ws2` (`SyncFields.LIVE_WS`), в диагностике —
  «by ws2»; сервер считает `ws2_p50`/`ws2_p95` в `srv`. Отчёт полевого теста ещё считает отдельно только сокет v1.
- **e2e.** `-Pe2e.transport=v2ws` включает `LIVE_V2` на всех сценариях. `FakeNetwork.liveSockets` — WebSocket v2 через
  ту же сеть, что сокет v1 (обрывы, задержка, потери); по его `Down` сеть собирает снимок тем же кодом, что телефон, и
  отдаёт боту на аудит приватности каждый снимок — и ответ, и push. `LiveV2Test`: push, сеть, выключение флага.

## 11. Как сделано (шаг 4, UDP) и чем отличается

Тот же пакет `app.hovanki.client.network.live`. UDP встал за швом `LiveSocket` (§10): `LiveGameConnection` не знает
о датаграммах, он только ждёт ответа меньше и считает потерянные `Up`.

| класс | что |
|---|---|
| `LiveUdp` | UDP этого телефона: `LiveDatagramOpener`, `LiveAeads`, `NetworkChanges`. Нет его — в открытии только `ws` |
| `LiveDatagramSocket`, `LiveDatagramOpener`, `KtorLiveDatagramOpener` | шов датаграмм: «подключённый» UDP-сокет Ktor (`ktor-network`), один селектор на приложение |
| `LiveAeads`, `JdkLiveAeads` | AES-128-GCM платформы: `javax.crypto` в `jvmMain` и `androidMain` (две копии) |
| `NetworkChanges`, `AndroidNetworkChanges` | смена сети по умолчанию: `ConnectivityManager.registerDefaultNetworkCallback` (`composeApp`, разрешение `ACCESS_NETWORK_STATE`) |
| `UdpSession` | UDP одной живой сессии: ключи, `pn`, номера сообщений, окно и куски — одни на все её сокеты |
| `UdpLiveSocket` | `LiveSocket` поверх датаграмм: `Up` → куски → `seal`; `Down` ← `open` → окно → куски; RESET и исчерпанный ключ → код 4410; `renew()` — новый порт |

Как работает:

- **Выбор пути** — на каждую ссылку сессии: UDP, если у телефона есть `LiveUdp`, сервер дал адрес и UDP не отложен;
  иначе WebSocket v2. Отказ от UDP не открывает новую сессию: та же сессия идёт по WebSocket v2, её состояния и
  номера `Up` сохраняются.
- **Ответ по UDP ждётся 1,5 с** (`OPEN_UDP_GAP_MILLIS`). Нет ответа — `Up` потерян, следующий уходит сразу, точки
  в нём те же. Второй потерянный подряд — сначала новый сокет (NAT мог забыть порт), третий — UDP откладывается на
  10 минут или до смены сети, дальше WebSocket v2. Любой `Down` обнуляет счёт. Так и при открытии: три `Up` за 4,5 с
  без ответа — WebSocket v2.
- **Проверка адреса.** Сервер проверяет и первый адрес сессии: ответ на первый `Up` — только `path_challenge`.
  Телефон не показывает по нему снимок и сразу шлёт следующий `Up` с `path_response`; ответ на него несёт состояние.
  То же после смены сети или порта.
- **Смена сети** (Android): перед `Up` телефон сравнивает поколение сети с тем, на котором открыт сокет, и при
  изменении берёт новый сокет. На WebSocket v2 смена сети снимает отсрочку UDP.
- **Возврат на UDP** — перед очередным `Up` по WebSocket v2, когда отсрочка кончилась: телефон сам закрывает
  WebSocket, сервер переводит сессию на UDP по первому `Up` с проверенного адреса.
- **Ошибки сокета UDP** (ICMP «порт закрыт», нет сети) — это потеря, а не обрыв: экран не видит `Problem`, счёт
  `Up` решает. RESET своей сессии — новая сессия без события, чужой или поддельный — выбрасывается.
- **Сессия пропала сразу после отказа от UDP** (сервер перезапустился без `reset_secret`: `Up` по UDP пропадали, а
  WebSocket ответил 4410, не дав ни одного `Down`) — отсрочка снимается: новая сессия снова пробует UDP.
- **iOS** — с моста CryptoKit (§12) так же, как Android: `LiveUdp` в Koin есть, когда мост прошёл проверку.
- **Полевой журнал и диагностика:** `Transport.LIVE_UDP` пишется как `udp` (`SyncFields.LIVE_UDP`), в диагностике —
  «by udp»; сервер считает `udp_p50`/`udp_p95` в `srv`.
- **Тесты:** `LiveUdpConnectionTest` на `FakeLiveServer` с UDP (`FakeUdpPath` проверяет адрес как сервер,
  `TestAeads` вместо AES-GCM, чтобы тест шёл и на iOS): путь UDP с проверкой и длинным `Down`, без адреса — WebSocket,
  потери → новый порт → WebSocket → UDP через 10 минут, новая сеть → новый порт, RESET и поддельный RESET, `close`
  по UDP, снятие отсрочки после пропавшей сессии. `JdkLiveAeadsTest` (JVM). e2e — `LiveV2Test` и
  `-Pe2e.transport=v2` ([server.md](server.md), §8).

## 12. Как сделано (мост CryptoKit) и чем отличается

iPhone ходит по UDP так же, как Android: тот же `UdpLiveSocket`, те же потери, новый порт и отсрочка (§11). Своё у
iOS — только шифрование и сигнал смены сети.

| где | что |
|---|---|
| `iosApp/iosApp/LiveCrypto.swift` | `LiveCrypto : LiveCryptoBridge` — `AES.GCM.seal` и `open` CryptoKit, как в §6. Без состояния, ключ в каждом вызове |
| `iosApp/iosApp/ContentView.swift` | `installLiveCrypto()` до `mainViewController()`: мост ставится раньше, чем Koin соберёт модуль платформы |
| `composeApp/.../network/LiveCryptoBridge.kt` | протокол `LiveCryptoBridge` (`NSData`), `installLiveCryptoBridge(bridge)`, `IosLiveAeads` |
| `composeApp/.../network/IosNetworkChanges.kt` | `nw_path_monitor` на своей очереди: поколение растёт, когда у пути меняются статус или интерфейсы (тип и номер) |
| `composeApp/.../di/PlatformModule.ios.kt` | `LiveUdp(KtorLiveDatagramOpener(), IosLiveAeads(bridge), IosNetworkChanges())`, только если мост поставлен |
| `shared/.../live/LiveAeadCheck.kt` | проверка AES-GCM платформы байтами спеки §18: та же датаграмма `Down`, она открывается, а с изменённым байтом — нет |

Чем отличается от §6:

- **Мост в `composeApp`, а не `IosAesGcm` в `:clientCore`.** Фреймворк `ComposeApp` не экспортирует `:clientCore`,
  и Swift не увидел бы протокол. Ровня Android — `AndroidNetworkChanges`, тоже в `composeApp`.
- **Проверка перед установкой.** `installLiveCryptoBridge` возвращает `false` и не ставит мост, если тот не прошёл
  `LiveAeadCheck`: приложение остаётся на WebSocket v2, в журнал Xcode идёт строка. Сломанный мост не теряет `Up`.
- **Смена сети без `NWConnection`:** сокет остаётся POSIX-сокетом Ktor, `nw_path_monitor` только говорит, когда
  взять новый (TN3151). План Б из §6 не понадобился до проверки на телефонах.
- **IPv6.** Сокет Ktor на iOS (POSIX) по умолчанию IPv4 и не соединяется с IPv6-адресом: `localhost` (сначала
  `::1`) или телефон в сети только с IPv6 и NAT64, где IPv4-адрес сервера приходит синтезированным IPv6.
  `KtorLiveDatagramOpener` тогда пробует ещё раз с локальным адресом `::`. На JVM и Android сокет и так двойной.
- **Сокет, который не открылся,** — не потерянный `Up`, а сбой телефона: `LiveGameConnection` отдаёт его в
  `ErrorReporter` (в полевой сборке — Sentry) и уходит на WebSocket v2 до следующей попытки UDP.
- **Проверено** на симуляторе (iPhone 17 Pro, iOS 26.5) с локальным сервером: мост прошёл проверку, синки идут по
  UDP, после трёх потерянных `Up` — WebSocket v2 той же сессии и через `UDP_RETRY_MILLIS` снова UDP. Там же нашлась
  ошибка IPv6. Смена сети и перезапуск сервера с `RESET` — на телефоне, шаг владельца (план, шаг 4).
