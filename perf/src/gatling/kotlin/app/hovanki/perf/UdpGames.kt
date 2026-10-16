package app.hovanki.perf

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.live.LiveDatagrams
import app.hovanki.shared.protocol.ApiRoutes
import app.hovanki.shared.protocol.CreateGameRequest
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.JoinGameRequest
import app.hovanki.shared.protocol.LiveOpenRequest
import app.hovanki.shared.protocol.LiveOpenResponse
import app.hovanki.shared.protocol.LiveTransports
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerSession
import app.hovanki.shared.protocol.ServerFeature
import app.hovanki.shared.protocol.SessionResponse
import app.hovanki.shared.protocol.StartGameRequest
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.GameSetup
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * [OrdinaryGames] on the live channel v2's UDP (docs/live-v2/spec.md §5, docs/perf.md «UDP»): the same games, players,
 * walks and ladder, every phone with its own UDP socket. Gatling has no UDP, so this is a program of its own: the
 * players' HTTP (create, join, the live channel's open, start) on a pool of blocking calls, their datagrams on
 * [UdpPhones]. It prints what OrdinaryGames prints (the plan, the levels, the ladder's stop) and writes `client.json`
 * with the requests' times instead of Gatling's simulation.log, so perf/run.sh reports it as any other run. The run
 * turns `LIVE_V2` and `LIVE_UDP` on through the lab's admin, and back off after it if they were off.
 *
 * `./gradlew :perf:udpRun -Pperf.games=100 -Pperf.steps=10`, or inside the stand's network `perf/gatling.sh UdpGames`
 * (perf/up.sh --container: the server's UDP is not published, a UDP generator on the host would go through Docker's
 * proxy). Takes OrdinaryGames' `perf.games`, `perf.players`, `perf.rampSeconds`, `perf.minutes`, `perf.steps`,
 * `perf.targetMs`, `perf.origin`, `perf.spacing` and `perf.baseUrl` (its first address), and
 * - `perf.workers` (the machine's CPUs): threads that drive the phones' sockets;
 * - `perf.phonesPerSocket` (1): sessions sharing one socket, for a machine whose open files are fewer than the phones;
 * - `perf.httpThreads` (32): threads of the players' HTTP calls.
 */
object UdpGames {
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            UdpRun().run()
        } catch (e: Throwable) {
            e.printStackTrace()
            1
        }
        exitProcess(code)
    }
}

private class UdpRun {
    private val games = LabConfig.int("games", 10)
    private val players = LabConfig.int("players", 30)
    private val rampSeconds = LabConfig.int("rampSeconds", 60)
    private val minutes = LabConfig.int("minutes", 5)
    private val steps = LabConfig.int("steps", 1)
    private val targetMillis = LabConfig.int("targetMs", 50).toLong()
    private val origin = LabConfig.string("origin", "50.4476,30.5396").split(',').map { it.trim().toDouble() }
        .let { (lat, lon) -> GeoPoint(lat, lon) }
    private val spacingMeters = LabConfig.int("spacing", 2_000).toDouble()
    private val workers = LabConfig.int("workers", Runtime.getRuntime().availableProcessors())
    private val phonesPerSocket = LabConfig.int("phonesPerSocket", 1).coerceAtLeast(1)
    private val baseUrl = LabConfig.baseUrls.first()
    private val seekers = (players / 10).coerceAtLeast(1)
    private val levelSeconds = rampSeconds + minutes * 60
    private val setup = GameSetup(
        radiusMeters = ZONE_RADIUS_METERS,
        hidingMinutes = 1,
        seekingMinutes = (steps * levelSeconds / 60 + 5).coerceIn(GameSetup.SEEKING_MINUTES),
    )
    private val tables = List(games * steps) { Table(it) }
    private val run = Random.nextInt(36 * 36 * 36 * 36).toString(36).padStart(4, '0')

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .apply { if (baseUrl.startsWith("https://")) sslContext(labCertificates()) }
        .build()
    private val calls: ScheduledExecutorService = Executors.newScheduledThreadPool(LabConfig.int("httpThreads", 32))

    private val startedAt = System.currentTimeMillis()
    private val stats = ClientStats(startedAt)
    private val watch = LoadWatch(targetMillis, fromMillis = startedAt + levelSeconds * 1000L)
    private val phones = UdpPhones(workers, phonesPerSocket, stats, watch, Events())
    private val playing = ConcurrentHashMap.newKeySet<UdpPhone>()
    private val stopped = AtomicBoolean()
    private val reopens = AtomicInteger()

    /** The server's UDP address by the name the open gives: looked up once, not per phone. */
    private val addresses = ConcurrentHashMap<String, InetAddress>()

    /** One game's meeting point, as OrdinaryGames': the host leaves the code there, the players their ids. */
    private inner class Table(index: Int) {
        val center: GeoPoint =
            origin.moveBy(eastMeters = index % 10 * spacingMeters, northMeters = index / 10 * spacingMeters)

        @Volatile var joinCode: String? = null
        val joined = ConcurrentLinkedQueue<PlayerId>()
        private val started = AtomicBoolean(false)

        @Volatile var startBy = Long.MAX_VALUE

        fun open(code: String) {
            startBy = System.currentTimeMillis() + (rampSeconds + START_PATIENCE_SECONDS) * 1000L
            joinCode = code
        }

        fun ready(): Boolean =
            joined.size >= players - 1 || (joined.size > seekers && System.currentTimeMillis() > startBy)

        fun claimStart(): Boolean = started.compareAndSet(false, true)

        fun seekers(): List<PlayerId> = joined.take(seekers)
    }

    private class Seat(val game: Int, val seat: Int)

    /** A player in its game: the phone's [UdpPhone.tag]. */
    private class Player(val table: Table, val session: PlayerSession)

    fun run(): Int {
        println("Simulation $SIMULATION started...")
        val admin = LabAdmin.create(run)
        val v2WasOn = admin.setFeature(ServerFeature.LIVE_V2, true, "perf: a UDP run")
        val udpWasOn = admin.setFeature(ServerFeature.LIVE_UDP, true, "perf: a UDP run")
        println(
            "${games * steps} games of $players over udp" +
                (if (steps > 1) ", $steps levels of $games games, ${levelSeconds}s each" else ""),
        )
        val endsAt = startedAt + steps * levelSeconds * 1000L
        val perLevel = games * players
        for (step in 0 until steps) {
            val levelStart = step * levelSeconds * 1000L
            for (i in 0 until perLevel) {
                val game = step * games + i / players
                val at = levelStart + if (rampSeconds > 0) i * rampSeconds * 1000L / perLevel else 0
                calls.schedule({ arrive(Seat(game, i % players), first = true) }, at, TimeUnit.MILLISECONDS)
            }
        }
        var stop: String? = null
        while (System.currentTimeMillis() < endsAt) {
            Thread.sleep(TICK_MILLIS)
            val breakdown = (if (steps > 1) watch.breakdown(System.currentTimeMillis()) else null) ?: continue
            stop = "The ladder stops $breakdown"
            println(stop)
            break
        }
        stopped.set(true)
        calls.shutdownNow()
        phones.stop()
        val ended = System.currentTimeMillis()
        playing.forEach { stats.user(ended, started = false) }
        runCatching {
            if (!udpWasOn) admin.setFeature(ServerFeature.LIVE_UDP, false, "perf: the UDP run is over")
            if (!v2WasOn) admin.setFeature(ServerFeature.LIVE_V2, false, "perf: the UDP run is over")
        }
        val dir = reportsDir().resolve("udpgames-" + LocalDateTime.now().format(STAMP))
        stats.write(dir.resolve("client.json"), SIMULATION, buildJsonObject { putJsonObject("udp") { counters() } })
        println("Simulation $SIMULATION completed in ${(ended - startedAt) / 1000} seconds")
        println(summary())
        println("Load report: $dir")
        return 0
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.counters() {
        val c = phones.counters
        put("ups", c.ups.sum())
        put("answers", c.answers.sum())
        put("pushes", c.pushes.sum())
        put("challenges", c.challenges.sum())
        put("unanswered", c.unanswered.sum())
        put("errors", c.errors.sum())
        put("datagrams_out", c.datagramsOut.sum())
        put("bytes_out", c.bytesOut.sum())
        put("datagrams_in", c.datagramsIn.sum())
        put("bytes_in", c.bytesIn.sum())
        put("dropped", c.dropped.sum())
        put("send_failed", c.sendFailed.sum())
        put("unreachable", c.unreachable.sum())
        put("resets", c.resets.sum())
        put("closes", c.closes.sum())
        put("reopens", reopens.get())
        put("workers", workers)
        put("phones_per_socket", phonesPerSocket)
    }

    private fun summary(): String {
        val c = phones.counters
        val ups = c.ups.sum().coerceAtLeast(1)
        return "UDP: ${c.ups.sum()} Ups, ${c.answers.sum()} answered, ${c.unanswered.sum()} unanswered, " +
            "${c.pushes.sum()} pushes, ${c.errors.sum()} errors, ${c.resets.sum()} resets, ${c.closes.sum()} closes; " +
            "${c.bytesOut.sum() / ups} B up and ${c.bytesIn.sum() / ups} B down per Up, " +
            "${c.datagramsIn.sum()} datagrams in, ${c.dropped.sum()} dropped"
    }

    /** A player comes: creates or joins the game, opens its live session, and its phone starts. */
    private fun arrive(seat: Seat, first: Boolean) {
        if (stopped.get()) return
        if (first) stats.user(System.currentTimeMillis(), started = true)
        val table = tables[seat.game]
        val player = try {
            if (seat.seat == 0) {
                val response = call<SessionResponse>(
                    "create game",
                    ApiRoutes.GAMES,
                    json(CreateGameRequest(name(seat), setup.settings(table.center))),
                )
                response.snapshot.joinCode?.let(table::open)
                Player(table, response.session)
            } else {
                val code = table.joinCode
                if (code == null) {
                    // The host is not in yet; a host that never came takes its game's players with it.
                    if (System.currentTimeMillis() - startedAt > tableDeadline(seat)) throw CallFailed()
                    calls.schedule({ arrive(seat, first = false) }, JOIN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                    return
                }
                val request = JoinGameRequest(code, name(seat), requestId = UUID.randomUUID().toString())
                val response = call<SessionResponse>("join", ApiRoutes.JOIN, json(request))
                table.joined.add(response.session.playerId)
                Player(table, response.session)
            }
        } catch (e: CallFailed) {
            stats.user(System.currentTimeMillis(), started = false)
            return
        }
        val walker = Walker(table.center, ZONE_RADIUS_METERS * 0.6, seed = seat.game * players + seat.seat)
        val phone = UdpPhone(name(seat), player.session.token, isHost = seat.seat == 0, tag = player, walker = walker)
        val live = open(phone)
        if (live == null) {
            stats.user(System.currentTimeMillis(), started = false)
            return
        }
        playing += phone
        phones.add(phone, live)
        val count = watch.players.incrementAndGet()
        if (steps > 1 &&
            count % (games * players) == 0
        ) {
            println("Level ${count / (games * players)} of $steps: $count players")
        }
    }

    /** The live channel's open (spec §4) with the game's token, UDP only; null when it failed or gave no UDP. */
    private fun open(phone: UdpPhone): UdpLive? {
        val response = try {
            call<LiveOpenResponse>(
                "live open",
                ApiRoutes.LIVE_OPEN,
                json(LiveOpenRequest(appVersion = "perf", transports = listOf(LiveTransports.UDP))),
                token = phone.token,
            )
        } catch (e: CallFailed) {
            return null
        }
        val udp = response.udp
        if (udp == null) {
            println("The open gave no UDP address: is LIVE_UDP on and the server's UDP port open?")
            return null
        }
        val decoder = Base64.getDecoder()
        val address = addresses.computeIfAbsent(udp.host) { InetAddress.getByName(it) }
        return UdpLive(
            sessionId = checkNotNull(LiveDatagrams.sessionIdOf(response.sessionId)) { "A bad session id" },
            upKey = decoder.decode(response.upKey),
            downKey = decoder.decode(response.downKey),
            resetToken = decoder.decode(response.resetToken),
            address = InetSocketAddress(address, udp.port),
        )
    }

    private inner class Events : UdpPhones.Events {
        override fun onDown(phone: UdpPhone) {
            if (!phone.isHost || phone.phase != GamePhase.LOBBY) return
            val player = phone.tag as Player
            val table = player.table
            if (!table.ready() || !table.claimStart()) return
            calls.execute {
                runCatching {
                    call<Unit>(
                        "start",
                        ApiRoutes.start(player.session.gameId),
                        json(StartGameRequest(table.seekers())),
                        token = phone.token,
                        decode = false,
                    )
                }
            }
        }

        /** As the app: a RESET or `CLOSE_REOPEN` opens a new session; any other close ends the phone's game. */
        override fun onClosed(phone: UdpPhone, why: String) {
            if (stopped.get()) return
            val again = why == "reset" || why.endsWith("CLOSE_REOPEN") || why.startsWith("socket")
            if (!again) {
                leave(phone)
                return
            }
            reopens.incrementAndGet()
            calls.schedule({
                val live = if (stopped.get()) null else open(phone)
                if (live == null) leave(phone) else phones.add(phone, live)
            }, REOPEN_AFTER_MILLIS, TimeUnit.MILLISECONDS)
        }

        private fun leave(phone: UdpPhone) {
            phones.remove(phone)
            if (playing.remove(phone)) {
                watch.players.decrementAndGet()
                stats.user(System.currentTimeMillis(), started = false)
            }
        }
    }

    private class CallFailed : Exception()

    /** One HTTP call of a player, timed as a request [name]; throws [CallFailed] on anything but 200. */
    private inline fun <reified T> call(
        name: String,
        path: String,
        body: String,
        token: String? = null,
        decode: Boolean = true,
    ): T {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(CALL_TIMEOUT_SECONDS))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "hovanki-perf")
            .apply { if (token != null) header("Authorization", "${ApiRoutes.AUTH_SCHEME} $token") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val sent = System.currentTimeMillis()
        val started = System.nanoTime()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            val message = "$name: ${e.javaClass.simpleName}"
            stats.record(name, sent, (System.nanoTime() - started) / 1_000_000, ok = false, message = message)
            throw CallFailed()
        }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        if (response.statusCode() != 200) {
            stats.record(name, sent, tookMillis, ok = false, message = "$name: status ${response.statusCode()}")
            throw CallFailed()
        }
        stats.record(name, sent, tookMillis, ok = true)
        return if (decode) protocolJson.decodeFromString<T>(response.body()) else Unit as T
    }

    private fun reportsDir(): Path = Path.of(System.getProperty("hovanki.perf.reports") ?: "perf/build/reports/udp")

    private fun name(seat: Seat): String = "G${seat.game}P${seat.seat}"

    /** Until when a player waits for its host: the end of the host's level's ramp and a minute more. */
    private fun tableDeadline(seat: Seat): Long = ((seat.game / games) * levelSeconds + rampSeconds + 60) * 1000L

    companion object {
        const val SIMULATION = "app.hovanki.perf.UdpGames"
        const val ZONE_RADIUS_METERS = 500
        const val START_PATIENCE_SECONDS = 30
        const val JOIN_WAIT_MILLIS = 200L
        const val REOPEN_AFTER_MILLIS = 1_000L
        const val CALL_TIMEOUT_SECONDS = 30L
        const val TICK_MILLIS = 1_000L
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
    }
}
