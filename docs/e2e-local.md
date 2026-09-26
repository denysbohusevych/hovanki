# Локальный запуск e2e-тестов

Пошаговое руководство: как на своей машине, из Android Studio или терминала, прогнать end-to-end тесты. Как устроены сами тесты, сценарии и наблюдатель — в [e2e.md](e2e.md).

| Слой | Что проверяет | Что нужно | Время | Как запустить |
|---|---|---|---|---|
| Боты | правила, протокол, клиент–сервер: целые партии headless-ботов на коде `:clientCore` | только JDK | ~3 мин | конфигурация `E2E bots (no emulators)` или `./gradlew :e2e:test` |
| Устройства | настоящее приложение на эмуляторах вместе с ботами: экраны, тапы, фон, перезапуск | Android SDK, 1–2 эмулятора, Maestro | несколько минут на сценарий | конфигурации `E2E emulators: …` или `./gradlew :e2e:devices` |
| Устройства, iOS | то же на iOS-симуляторе | Mac, Xcode 26.4+, Maestro | ~20 мин | `e2e/run-devices.sh --ios 1` |

Что запускать перед PR — в конце, [Перед PR](#перед-pr).

## Подготовка (один раз)

### JDK и Android Studio

- **Android Studio** — актуальная стабильная, с плагином Kotlin Multiplatform. Откройте корень репозитория и дождитесь Gradle sync.
- **Gradle JDK:** Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK — 17 или новее (встроенный JetBrains Runtime подходит). JDK 21 для сервера и e2e Gradle скачает сам (toolchain + foojay).
- **Android SDK** с платформой 37 и пакетами Android Emulator и Android SDK Platform-Tools (Settings → Languages & Frameworks → Android SDK). Android Studio записывает путь к SDK в `local.properties` (`sdk.dir=…`); оттуда e2e берёт `adb`. Без Android Studio задайте `ANDROID_HOME`.

### Эмуляторы

Device Manager → Create Virtual Device:

- **Устройство:** Pixel 6 или похожее. Размер экрана не важен: флоу ищут элементы по test tags, а не по координатам.
- **Образ:** Google APIs или Google Play, API 33–35:
  - нужны Google Play services: через них работает FusedLocationProvider;
  - геолокацию оркестратор включает сам (`cmd location`); если на старом образе эта команда не работает, включите Location в настройках эмулятора один раз;
  - ABI: `arm64-v8a` на Mac с Apple Silicon, `x86_64` на Intel/AMD.
- **Память:** 2 ГБ и больше (Show Advanced Settings → RAM).
- **Сколько:** два. С одним эмулятором сценарии тоже идут, но код с экрана прячущегося-телефона вводит только второй телефон-ищущий ([роли](#роли-устройств)).

Эмулятору нужно аппаратное ускорение: Hypervisor.framework на Mac, KVM на Linux, WHPX или AEHD на Windows. Android Studio подскажет, если его нет.

### Maestro

Maestro 2.10+ (в CI закреплена 2.10.0). Ему нужна Java 17+ (`JAVA_HOME` или `java` в `PATH`).

- **macOS, Linux:**

  ```bash
  curl -fsSL https://get.maestro.mobile.dev | bash
  ```

  Ставится в `~/.maestro/bin`. Добавлять в `PATH` не обязательно: `:e2e:devices` ищет Maestro там сам.
- **Windows:** скачайте `maestro.zip` со страницы [релизов Maestro](https://github.com/mobile-dev-inc/maestro/releases) и разложите так, чтобы запускалка оказалась в `%USERPROFILE%\.maestro\bin\maestro.bat` — там её ищет `:e2e:devices`. Если Maestro лежит в другом месте, передайте путь: `-Pe2e.maestro=C:\путь\к\maestro.bat`. Подробности — [в документации Maestro](https://docs.maestro.dev/getting-started/installing-maestro/windows).

При первом прогоне на эмуляторе Maestro ставит на него своё driver-приложение — первый шаг сценария идёт дольше.

### Проверка готовности

```bash
./gradlew :e2e:unitTest            # e2e собирается, юнит-тесты инструментов зелёные
~/.maestro/bin/maestro --version   # Windows: %USERPROFILE%\.maestro\bin\maestro.bat --version
adb devices                        # запущенные эмуляторы: emulator-5554  device
```

`adb` лежит в `<sdk>/platform-tools`. Если его нет в `PATH` терминала, это не страшно: `:e2e:devices` добавляет `platform-tools` в `PATH` сам.

## Боты: без эмуляторов

### Из Android Studio

- Все сценарии: конфигурация **E2E bots (no emulators)** (папка E2E в списке конфигураций) → Run. Результаты — деревом тестов в окне Run.
- Один класс или один тест: откройте его в `e2e/src/test/kotlin/app/hovanki/e2e/scenarios/` и нажмите зелёную стрелку у имени класса или метода.
- Отладка: те же действия через Debug. Сервер поднимается в том же JVM, так что точки остановки работают и в `:server` (`Game`, `GameService`), и в `:clientCore`.

### Из терминала

```bash
./gradlew :e2e:test                                           # все сценарии, ~3 мин
./gradlew :e2e:test --tests '*ZoneTest*'                      # один класс
./gradlew :e2e:test --tests '*NetworkTest.networkOutageOf30Seconds'
```

### Против отдельно запущенного сервера

Когда нужно смотреть логи сервера или отлаживать его отдельно от ботов:

1. Сервер с профилем `e2e`: `SPRING_PROFILES_ACTIVE=e2e ./gradlew :server:bootRun`. В Android Studio — Gradle-конфигурация `:server:bootRun` с переменной окружения `SPRING_PROFILES_ACTIVE=e2e`; её можно запустить через Debug.
2. Сценарии против него: `HOVANKI_E2E_SERVER_URL=http://localhost:8080 ./gradlew :e2e:test`. В Android Studio — скопируйте конфигурацию `E2E bots (no emulators)` и добавьте ту же переменную в Environment variables.

Без профиля `e2e` у сервера нет эндпоинта наблюдателя, и сценарии падают с понятной ошибкой.

### Отчёт

`e2e/build/reports/e2e/<scenario>.md`: результат, итоговое состояние по данным сервера, задержки `/sync` и таймлайн по секундам. При падении тот же таймлайн — в сообщении ошибки теста. Как читать — [e2e.md](e2e.md#как-читать-отчёт).

## Устройства: из Android Studio на своих эмуляторах

### Запуск

1. Запустите один или два эмулятора в Device Manager. Дождитесь рабочего стола.
2. Выберите конфигурацию из папки **E2E** и нажмите Run:

   | Конфигурация | Что запускает |
   |---|---|
   | `E2E emulators: full-round` | `./gradlew :e2e:devices`, сценарий `full-round` |
   | `E2E emulators: restart` | то же, `-Pe2e.scenario=restart` |
   | `E2E emulators: all scenarios` | то же, `-Pe2e.scenario=all` |

   Конфигурации лежат в `.run/` в репозитории, Android Studio подхватывает их сама. Если папки E2E в списке нет — File → Sync Project with Gradle Files.
3. Смотрите на эмуляторы: приложение запускается, игроки входят в игру, фазы сменяются. Прогресс и итог по каждому сценарию — в окне Run.
4. Когда прогон закончится, откройте отчёт: `e2e/build/reports/devices/<scenario>/index.html`.

То же из терминала:

```bash
./gradlew :e2e:devices                                        # full-round на всех запущенных эмуляторах
./gradlew :e2e:devices -Pe2e.scenario=all -Pe2e.bots=2
./gradlew :e2e:devices -Pe2e.emulators=emulator-5556          # только этот эмулятор
./gradlew :e2e:devices -Pe2e.port=8081                        # если 8080 занят
./gradlew :e2e:devices -Pe2e.scenario=all -Pe2e.failFast=true # остановиться на первом упавшем сценарии
```

### Параметры

В Android Studio: Run → Edit Configurations → нужная конфигурация → поле Run, например `:e2e:devices -Pe2e.scenario=restart -Pe2e.bots=1`. Удобнее скопировать конфигурацию (Copy Configuration) и поменять копию, чем править общую из `.run/`.

| Свойство | По умолчанию | Что задаёт |
|---|---|---|
| `e2e.scenario` | `full-round` | `full-round`, `restart` или `all` ([что проверяют](e2e.md#сценарии-на-устройствах)) |
| `e2e.bots` | `3` | сколько headless-ботов играет вместе с телефонами |
| `e2e.emulators` | `auto` | `auto` — все запущенные эмуляторы; или serial через запятую: `emulator-5554,emulator-5556` |
| `e2e.port` | `8080` | порт сервера на компьютере; эмуляторы ходят на `http://10.0.2.2:<порт>` |
| `e2e.failFast` | `false` | `true` — после первого упавшего сценария остальные пропускаются |
| `e2e.maestro` | `~/.maestro/bin/maestro`, иначе `maestro` из `PATH` | путь к Maestro |

### Что происходит

1. Gradle собирает jar сервера (`:server:bootJar`) и debug APK (`:androidApp:assembleDebug`). Повторные прогоны без изменений в коде собирают почти мгновенно.
2. `adb devices` — список запущенных эмуляторов (`emulator-NNNN` в состоянии `device`). Физические телефоны не берутся: они не видят сервер по `10.0.2.2` и не принимают `emu geo fix`.
3. На каждом эмуляторе:
   - запоминаются и выключаются анимации и диалоги «приложение не отвечает»;
   - ставится debug-сборка поверх имеющейся (`adb install -r -g`), разрешения на геолокацию и уведомления выдаются сразу. Если стоящая сборка подписана другим ключом, она сначала удаляется;
   - включается геолокация.
4. Сервер из jar стартует с профилем `e2e` на `:8080`, его лог — `e2e/build/reports/devices/logs/server.log`.
5. Сервер прогревается короткой игрой ботов, потом идут сценарии: Maestro тапает по экранам, оркестратор раз в секунду двигает геопозицию эмуляторов (`emu geo fix`), боты играют через API, наблюдатель проверяет правду сервера. В каждой фазе — скриншоты.
6. В конце сервер гасится, настройки эмуляторов возвращаются. Эмуляторы остаются запущенными.

### Роли устройств

- Первый эмулятор (по порядку в `adb devices` или в `e2e.emulators`) — хост: создаёт игру на телефоне.
- Второй — ищущий: ловит ботов и прячущихся-телефонов, вводя 4 цифры кода.
- Остальные эмуляторы и все боты прячутся.
- С одним эмулятором ищущий — хост в `full-round` и бот в `restart`; проверка «код с экрана прячущегося-телефона» тогда не выполняется.

### Во время прогона

- Смотреть на эмуляторы можно, трогать нельзя: свои тапы и клавиатура собьют флоу Maestro.
- Не запускайте в это время приложение из Android Studio (конфигурация `androidApp`): это переустановит и перезапустит приложение под тестом.
- Остановить прогон — Stop в окне Run. Сервер гасится вместе с ним.

### После прогона на эмуляторе

- стоит debug-сборка из текущего кода;
- сохранённой игры нет: первый шаг сценария стирает её (`forgetSavedGame`), сервер из прошлой игры всё равно уже погашен;
- геопозиция — последняя точка сценария (парк в центре Киева, рядом с `50.4476, 30.5396`);
- установлено driver-приложение Maestro (`dev.mobile.maestro`), оно не мешает;
- анимации и прочие настройки — как были до прогона.

### Отладка оркестратора

Та же конфигурация через **Debug** запускает оркестратор под отладчиком: срабатывают точки остановки в `e2e/src/main/kotlin/app/hovanki/e2e/devices/` (`DeviceScenarios`, `DevicePlayer`, `Maestro`). На остановке Maestro больше не тапает — видно, какой экран был на телефонах в этот момент. Приложение и игровые таймеры сервера при этом идут дальше, так что после долгой паузы сценарий, скорее всего, упадёт по таймауту.

Сервер в этом режиме — отдельный процесс из jar, отладчик к нему не подключается. Логику сервера удобнее отлаживать на слое ботов, где сервер живёт в том же JVM, что и тесты.

Отдельный флоу Maestro можно прогнать руками на запущенном приложении:

```bash
maestro --device emulator-5554 test -e APP_ID=app.hovanki -e ID=home_screen e2e/maestro/await-visible.yaml
maestro --device emulator-5554 hierarchy     # дерево элементов текущего экрана с test tags
```

Переменные флоу — это `${...}` в его тексте (`${APP_ID}`, `${ID}` и т.д.), значения передаются через `-e`. Test tags — в `TestTags` (`clientCore`, пакет `app.hovanki.client.automation`).

## Скриптом: headless-эмуляторы, iOS

`e2e/run-devices.sh` делает то же, что CI: сам создаёт AVD `hovanki-e2e-N` на образе `google_atd`, запускает их без окна, после прогона гасит. Нужен bash (macOS, Linux; на Windows — WSL). Удобен, когда не хочется трогать свои эмуляторы, и для iOS.

```bash
e2e/run-devices.sh --android 2 --bots 3 --scenario full-round   # 2 headless-эмулятора
e2e/run-devices.sh --android-serials emulator-5554 --keep       # свой запущенный эмулятор
e2e/run-devices.sh --ios 1 --bots 3 --scenario all              # iOS-симулятор (Mac)
e2e/run-devices.sh --android 1 --ios 1 --bots 2                 # смешанная партия (Mac)
```

Для эмуляторов скрипту нужен пакет **Android SDK Command-line Tools** (`avdmanager`, `sdkmanager`): Android Studio по умолчанию его не ставит — Settings → Languages & Frameworks → Android SDK → SDK Tools → Android SDK Command-line Tools (latest). SDK он находит сам, как `:e2e:devices`: `sdk.dir` в `local.properties`, `ANDROID_HOME`, `~/Library/Android/sdk` (Mac), `~/Android/Sdk` (Linux).

Для iOS нужен Mac на Apple Silicon с Xcode 26.4+. Скрипт сам соберёт приложение для симулятора, создаст симулятор, поставит приложение, а после прогона удалит симулятор. Все опции — `e2e/run-devices.sh --help` и [e2e.md](e2e.md#запуск-скриптом).

## Отчёт устройств

`e2e/build/reports/devices/`:

| Файл | Что там |
|---|---|
| `index.md` | список сценариев со статусами |
| `<scenario>/index.html` | начинать отсюда: скриншоты по фазам с каждого устройства и таймлайн с проверками «✓» и предупреждениями «⚠» |
| `<scenario>/report.md` | то же в markdown |
| `<scenario>/logs/Android-N.log` | logcat эмулятора за сценарий |
| `<scenario>/final-state.json` | полное состояние игры с сервера на момент конца |
| `logs/server.log`, `logs/access*.log` | лог сервера и все запросы к нему (без заголовков и токенов) |
| `commands.log` | каждая команда `adb` и `maestro` с кодом выхода |

Если сценарий упал, в окне Run и в `report.md` есть: вывод Maestro, дамп экрана (`id: текст [границы]`) и скриншот `failed-<flow>`. Обычно этого хватает, чтобы понять, на каком экране и почему остановилось. Подробнее — [e2e.md](e2e.md#отчёт).

## Частые проблемы

| Симптом | Причина | Что делать |
|---|---|---|
| `No running emulators: start one or two in Android Studio` | эмулятор не запущен, ещё грузится или в состоянии `offline` | дождаться рабочего стола; `adb devices` должен показывать `device` |
| `Cannot run program "adb"` | не найден Android SDK | проверить `sdk.dir` в `local.properties` или задать `ANDROID_HOME` |
| `Android SDK not found` (скрипт) | SDK не в `local.properties`, не в `ANDROID_HOME` и не на месте по умолчанию | открыть проект в Android Studio (она запишет `sdk.dir`) или `export ANDROID_HOME=<путь к SDK>` |
| `avdmanager not found` (скрипт) | не установлены Android SDK Command-line Tools | Settings → Languages & Frameworks → Android SDK → SDK Tools → Android SDK Command-line Tools (latest) |
| `Maestro not found` | Maestro не установлен или лежит не в `~/.maestro/bin` | [поставить](#maestro) или передать `-Pe2e.maestro=<путь>` |
| `Port 8080 is busy` | работает свой `bootRun` или сервер, оставшийся от прерванного прогона | остановить его или взять другой порт: `-Pe2e.port=8081` |
| `The server exited on start` | сервер упал при старте, хвост лога — в сообщении | смотреть `e2e/build/reports/devices/logs/server.log` |
| В логе: `the installed app.hovanki is signed with another key, uninstalling it` | на эмуляторе стояла сборка, подписанная другим ключом (release или debug с другой машины); обновить её поверх нельзя (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) | ничего: задача сама удаляет её и ставит debug-сборку заново; теряется только сохранённая игра, которую сценарии всё равно стирают |
| Приложение на эмуляторе: «Cannot reach the server» | сервер не доступен с эмулятора: файрвол не пускает входящие к `java` | разрешить входящие для Java (macOS спросит при первом запуске, Windows — «Брандмауэр Защитника»); из эмулятора сервер — `http://10.0.2.2:<порт>` |
| `the app is still in the foreground after HOME (no launcher?)` | в образе нет лаунчера | взять обычный образ Google APIs / Google Play |
| Сценарий ждёт геопозицию, раскрытие `STALE_SIGNAL` не снимается | на эмуляторе нет Google Play services или выключена геолокация | образ Google APIs / Google Play; включить Location в настройках эмулятора |
| Флоу падает на тапе, на скриншоте чужой диалог или другое приложение | тапнули по эмулятору во время прогона, либо системный диалог | не трогать эмулятор; перезапустить сценарий |
| Всё очень медленно, флоу падают по таймауту | мало памяти или CPU на двух эмуляторах | закрыть лишнее, дать эмуляторам по 2 ГБ, прогнать на одном: `-Pe2e.emulators=emulator-5554` |
| В Android Studio нет папки E2E в конфигурациях | проект открыт не из корня репозитория или не синхронизирован | открыть корень, File → Sync Project with Gradle Files |

Если непонятно, что случилось, — `commands.log` показывает последнюю команду перед падением, а `<scenario>/logs/Android-N.log` — падения приложения (`FATAL EXCEPTION`).

## Перед PR

| Что меняли | Что прогнать локально |
|---|---|
| Правила, протокол, клиент–сервер (`:shared`, `:server`, `:clientCore`, `:e2e`) | боты: `./gradlew :e2e:test` |
| UI, платформенный код, Maestro-флоу (`:composeApp`, `androidApp`, `e2e/maestro`) | устройства: `E2E emulators: all scenarios` на двух эмуляторах |
| `iosApp`, iOS-код в `composeApp` | `e2e/run-devices.sh --ios 1 --scenario all` на Mac или ночной workflow ([ci-cd.md](ci-cd.md#что-запускать-перед-pr)) |

В описании PR напишите, что именно запускали и на каких устройствах.
