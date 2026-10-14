# 2026-10-14 OrdinaryGames: правила без лишних списков и сокет без буфера на 256 КБ

До: `67a5d4e` (main). После: правки этого журнала. Машина: облачный контейнер, 4 ядра, 16 ГБ. Сервер «на машине»
(`perf/server.sh`: куча 480 МБ, Serial GC, 2 ядра для JVM), перед каждым прогоном перезапущен.

## Опрос: аллокации правил игры

```bash
./gradlew :perf:gatlingRun --simulation app.hovanki.perf.OrdinaryGames -Pperf.games=40 -Pperf.rampSeconds=20 -Pperf.minutes=3
perf/profile.sh alloc 60   # на второй минуте
```

1 200 игроков, 400 синхронизаций/с.

| | до | после |
|---|---|---|
| аллокации, МБ/с | 45,7 | 35,9 |
| на синхронизацию, КБ | 112 | 88 |
| `Game.advanceRules` в профиле `alloc`, % | 23,4 | 3,6 |
| /sync p95 / p99 / max, мс | 2 / 5 / 297 | 2 / 5 / 224 |
| ошибок | 0 | 0 |

Что поменяли: `LocationTrack.recentFixes` отдаёт кусок трека (`subList`) вместо нового списка, `recentUsableFixes` —
его же, когда все точки годятся; `BuildingRules.isConfidentlyInside` и `hasLeft` считают одним проходом без
`mapNotNull`; сетка `BuildingMap` ищет клетку двоичным поиском по `LongArray`, а не в `Map<Long, …>`, которая упаковывала
каждый ключ в `java.lang.Long`; `Game.checkZone` и `checkBuildings` идут по игрокам без промежуточного списка. Что
осталось в правилах — сам `subList` (2,5 %): его уберёт проверка правил раз в секунду и в обычной игре, это отдельное
изменение поведения.

## Сокет: буфер кадра

```bash
./gradlew :perf:gatlingRun --simulation app.hovanki.perf.OrdinaryGames -Pperf.transport=socket \
  -Pperf.games=10 -Pperf.steps=6 -Pperf.rampSeconds=20 -Pperf.minutes=1
jcmd <pid> GC.class_histogram | head   # на первой ступени, 300 сокетов
```

| | до | после |
|---|---|---|
| `char[]` на 300 сокетах, МБ | 158 | 5 |
| где остановилась лесенка | 560 игроков: p95 выше 50 мс | не остановилась: все 6 ступеней, 1 800 игроков |
| запросов за прогон | 20 655 | 333 900 |
| /sync p95 / p99, мс | 4 / 58 | 1 / 4 |
| ошибок | 0 | 0 |

Что поменяли: `GameSocketHandler` больше не ставит `textMessageSizeLimit` (Tomcat держал под него `CharBuffer` на 256 тыс.
символов, 512 КБ, всю жизнь сокета), а принимает кадр кусками (`supportsPartialMessages`); длинный кадр собирает
`LiveSocket.assemble` до тех же 256 КБ, больше — закрытие `1009`, как раньше.
