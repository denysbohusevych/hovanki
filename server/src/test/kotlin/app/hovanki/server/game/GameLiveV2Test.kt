package app.hovanki.server.game

import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.live.LiveClientStates
import app.hovanki.shared.live.LiveJson
import app.hovanki.shared.live.LiveLimits
import app.hovanki.shared.live.LiveSections
import app.hovanki.shared.live.LiveServerStates
import app.hovanki.shared.live.LiveSnapshots
import app.hovanki.shared.live.LiveValues
import app.hovanki.shared.live.v2.Down
import app.hovanki.shared.live.v2.State
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.RadarToken
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The live channel v2 on real games (docs/live-v2/spec.md §18, item 1): every player's v1 snapshot, cut into sections
 * and sent as deltas, comes back on the phone as the same snapshot, from the lobby to the results; and how many bytes
 * that takes against v1's JSON.
 */
class GameLiveV2Test {
    private val center = GeoPoint(50.4501, 30.5234)
    private val settings =
        GameSettings(
            zone = shrinkingZone(center, steps = 2),
            hidingSeconds = 60,
            seekingSeconds = 360,
            glowEverySeconds = 120,
            glowForSeconds = 15,
            features = GameFeatures(radar = FeatureMode.OPTIONAL, hiderSense = true),
        )
    private var now = 1_700_000_000_000L
    private var secrets = 0

    /**
     * One player's live session: the server's side and the phone's, with the Down as bytes in between. [game]: what the
     * game's sessions share, as the server gives it (`LiveSessions`): the JSON of the game's kinds, the rosters.
     */
    private class Session(game: LiveJson.Game) {
        val server = LiveServerStates()
        val phone = LiveClientStates()
        val json = LiveJson(game)
        val snapshots = LiveSnapshots()
        var seq = 0

        fun exchange(snapshot: GameSnapshot): Pair<Int, GameSnapshot> {
            seq++
            server.acknowledge(phone.ackState)
            val next = server.next(LiveSections.fromSnapshot(snapshot, json), rosters = json.game.players)
            val bytes =
                Down.ADAPTER.encode(
                    Down(
                        reply_to = seq,
                        ack_up = seq,
                        server_time_ms = snapshot.serverTimeMillis,
                        state_id = next?.stateId ?: 0,
                        base_state_id = next?.baseStateId ?: 0,
                        state = next?.state,
                    ),
                )
            val down = Down.ADAPTER.decode(bytes)
            check(phone.apply(down.state_id, down.base_state_id, down.state))
            return bytes.size to snapshots.toGameSnapshot(phone.shown!!, down.server_time_ms)
        }
    }

    /** What a v1 phone gets of [snapshot], as v2 promises it (docs/live-v2/spec.md §1, §9.1, §10). */
    private fun v1(snapshot: GameSnapshot): GameSnapshot {
        val received = protocolJson.decodeFromString<GameSnapshot>(protocolJson.encodeToString(snapshot))
        val at = received.serverTimeMillis
        return received.copy(
            players =
            received.players.map { player ->
                player.copy(
                    location =
                    player.location?.let {
                        it.copy(
                            point = LiveValues.round(it.point),
                            accuracyMeters = LiveValues.roundAccuracy(it.accuracyMeters),
                        )
                    },
                    lastSeenMillis =
                    player.lastSeenMillis?.takeIf { at - it > LiveLimits.OFFLINE_AFTER_MILLIS } ?: at,
                )
            },
            sos =
            received.sos.map { call ->
                call.copy(
                    location =
                    call.location?.let {
                        it.copy(
                            point = LiveValues.round(it.point),
                            accuracyMeters = LiveValues.roundAccuracy(it.accuracyMeters),
                        )
                    },
                )
            },
        )
    }

    /** Bytes per phase: v1's JSON snapshots, v2's Downs. */
    private class Bytes {
        val v1 = HashMap<GamePhase, MutableList<Int>>()
        val v2 = HashMap<GamePhase, MutableList<Int>>()

        fun add(phase: GamePhase, json: Int, down: Int) {
            v1.getOrPut(phase) { ArrayList() } += json
            v2.getOrPut(phase) { ArrayList() } += down
        }

        var caught = 0

        fun table(players: Int): String = buildString {
            appendLine(
                "Game of $players players, $caught caught, bytes per sync " +
                    "(v2: the Down's protobuf, a datagram adds 30):",
            )
            appendLine("| phase | syncs | v1 JSON avg | v2 Down avg | v2 Down p95 | v2 max |")
            appendLine("|---|---|---|---|---|---|")
            for (phase in GamePhase.entries) {
                val json = v1[phase] ?: continue
                val down = v2.getValue(phase).sorted()
                appendLine(
                    "| $phase | ${down.size} | ${json.average().toInt()} | ${down.average().toInt()} | " +
                        "${down[(down.size * 95 / 100).coerceAtMost(down.size - 1)]} | ${down.last()} |",
                )
            }
        }
    }

    @Test
    fun everyPlayerSeesTheSameGameThroughV2() {
        val (bytes, players) = play(players = 12, random = Random(14))
        println(bytes.table(players))
        assertTrue(bytes.caught > 0, "the game had catches")
        // The round: a delta per sync is a small fraction of v1's snapshot.
        val seeking = bytes.v2.getValue(GamePhase.SEEKING).average()
        val seekingJson = bytes.v1.getValue(GamePhase.SEEKING).average()
        assertTrue(seeking * 10 < seekingJson, "v2 $seeking against v1 $seekingJson bytes")
    }

    @Test
    fun aBigLobbyToo() {
        val (bytes, players) = play(players = Game.MAX_PLAYERS, random = Random(15))
        println(bytes.table(players))
    }

    /** A whole game, every player syncing every 3 s; every snapshot checked against v1. */
    private fun play(players: Int, random: Random): Pair<Bytes, Int> {
        val ids = (1..players).map { PlayerId("player$it") }
        val host = ids.first()
        val seekers = ids.take(maxOf(1, players / 4)).toSet()
        val game = Game(GameId("g"), "ABC234", host, settings, now)
        val sessions = HashMap<PlayerId, Session>()
        val shared = LiveJson.Game()
        val positions = HashMap<PlayerId, GeoPoint>()
        val bytes = Bytes()
        // One player goes silent for a while in every phase: the others see them offline, then back.
        val silent = ids.last()

        fun sync(player: PlayerId) {
            if (player == silent && (now / 1_000) % 90 in 30L..70L) return
            if (game.phase == GamePhase.HIDING || game.phase == GamePhase.SEEKING) {
                val moved = positions.getValue(
                    player,
                ).moveBy(random.nextDouble(-6.0, 6.0), random.nextDouble(-6.0, 6.0))
                positions[player] = moved
                game.recordLocations(
                    player,
                    listOf(LocationSample(moved, random.nextDouble(3.0, 25.0), now - 500)),
                    now,
                )
                hearNeighbours(game, player, positions)
            }
            game.advance(now)
            val snapshot = game.snapshotFor(player, now)
            val (downBytes, received) = sessions.getOrPut(player) { Session(shared) }.exchange(snapshot)
            assertEquals(v1(snapshot), received, "${snapshot.phase}, ${player.value} at $now")
            bytes.add(snapshot.phase, protocolJson.encodeToString(snapshot).length, downBytes)
        }

        fun tick(seconds: Int) {
            repeat(seconds / 3) {
                now += 3_000
                ids.filter { it in game.debugState(now).players.map { p -> p.id } }.forEach(::sync)
            }
        }

        // The lobby fills up one by one.
        for (id in ids) {
            game.addPlayer(id, id.value, now)
            game.recordDevice(id, DeviceReport(Platform.ANDROID, BluetoothState.ON), now)
            positions[id] = center.moveBy(random.nextDouble(-80.0, 80.0), random.nextDouble(-80.0, 80.0))
            tick(3)
        }
        game.setRoles(host, seekers, now)
        tick(15)
        game.start(host, seekers, { "%040x".format(++secrets) }, now)
        tick(settings.hidingSeconds + 3)
        check(game.phase == GamePhase.SEEKING)
        var caught = 0
        while (game.phase == GamePhase.SEEKING) {
            tick(3)
            // A seeker close to an active hider claims them and the hider shows the code.
            if (caught < 3) {
                val pair =
                    seekers.firstNotNullOfOrNull { seeker ->
                        ids.filter { it !in seekers && game.isActiveHider(it) }
                            .firstOrNull { positions.getValue(it).distanceTo(positions.getValue(seeker)) < 15 }
                            ?.let { seeker to it }
                    }
                if (pair != null) {
                    val (seeker, hider) = pair
                    game.recordLocations(seeker, listOf(LocationSample(positions.getValue(seeker), 4.0, now)), now)
                    game.recordLocations(hider, listOf(LocationSample(positions.getValue(hider), 4.0, now)), now)
                    val catchId = CatchId("c$now")
                    game.claimCatch(seeker, hider, catchId, now)
                    tick(3)
                    val secret = checkNotNull(game.debugState(now).players.single { it.id == hider }.catchCodeSecret)
                    game.confirmCatch(catchId, seeker, catchCodeTotp(secret, settings.rules).codeAt(now), now)
                    caught++
                    bytes.caught = caught
                }
            }
            if (caught == 2) game.leave(ids.first { it !in seekers && game.isActiveHider(it) }, now)
        }
        tick(30)
        return bytes to players
    }

    /** Read without a snapshot: a snapshot would mark the player connected. */
    private fun Game.isActiveHider(id: PlayerId): Boolean = debugState(now).players.single { it.id == id }.let {
        it.role == Role.HIDER && it.status == PlayerStatus.ACTIVE &&
            !it.left
    }

    /** Everybody within 60 m of [player] hears their phone: the seekers' radar and the hiders' sense have something. */
    private fun hearNeighbours(game: Game, player: PlayerId, positions: Map<PlayerId, GeoPoint>) {
        val secret = game.debugState(now).players.single { it.id == player }.radarSecret ?: return
        val token = RadarToken.at(secret, now)
        for ((other, point) in positions) {
            if (other == player) continue
            val distance = point.distanceTo(positions.getValue(player))
            if (distance <
                60
            ) {
                game.recordSightings(other, listOf(NearbySighting(token, (-50 - distance / 2).toInt(), now)), now)
            }
        }
    }

    @Test
    fun theFullStateOfALobbyIsSmallerThanItsJson() {
        val game = Game(GameId("g"), "ABC234", PlayerId("p0"), settings, now)
        repeat(10) { game.addPlayer(PlayerId("p$it"), "Player $it", now) }
        val snapshot = game.snapshotFor(PlayerId("p0"), now)
        val full = State.ADAPTER.encode(LiveSections.fromSnapshot(snapshot)).size
        val json = protocolJson.encodeToString(snapshot).length
        println("Lobby of 10: full state $full bytes, v1 JSON $json bytes")
        assertTrue(full < json)
    }
}
