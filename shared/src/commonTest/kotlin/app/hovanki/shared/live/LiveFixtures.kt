package app.hovanki.shared.live

import app.hovanki.shared.protocol.Activity
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.BigGameId
import app.hovanki.shared.protocol.BigGameInfo
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.BoardItem
import app.hovanki.shared.protocol.BuildingsState
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.CapacityState
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.CatchView
import app.hovanki.shared.protocol.ChatChannel
import app.hovanki.shared.protocol.ChatMessage
import app.hovanki.shared.protocol.DistanceBand
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePause
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.Hint
import app.hovanki.shared.protocol.HintKind
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.MyState
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PerkView
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayAgain
import app.hovanki.shared.protocol.PlayerCounts
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.protocol.QuestView
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.RadarContact
import app.hovanki.shared.protocol.RadarState
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SosCall
import app.hovanki.shared.protocol.SosLocation
import app.hovanki.shared.protocol.StreetZoneState
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.UwbPeer
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.protocol.ZoneCapacity
import app.hovanki.shared.protocol.protocolJson
import app.hovanki.shared.rules.shrinkingZone
import kotlin.random.Random

/** Snapshots for the tests of v2: one with every field set, random ones, and what a v1 phone gets of them. */
object LiveFixtures {
    const val NOW = 1_760_457_600_000L

    val settings =
        GameSettings(zone = shrinkingZone(GeoPoint(50.4501, 30.5234)), hidingSeconds = 240, glowEverySeconds = 300)

    /** Every field of the snapshot set to something that is not its default. */
    fun everything(now: Long = NOW): GameSnapshot = GameSnapshot(
        gameId = GameId("g1"),
        joinCode = "KQ7M",
        hostId = PlayerId("p1"),
        phase = GamePhase.SEEKING,
        settings = settings,
        serverTimeMillis = now,
        phaseEndsAtMillis = now + 600_000,
        zoneStartedAtMillis = now - 60_000,
        players =
        listOf(
            PlayerView(
                id = PlayerId("p1"),
                name = "Аня",
                role = Role.SEEKER,
                status = PlayerStatus.ACTIVE,
                location =
                VisibleLocation(
                    GeoPoint(50.45012345678, 30.52341234567),
                    4.27,
                    now - 1_500,
                    VisibilityReason.TEAMMATE,
                ),
                userId = UserId("u1"),
                lastSeenMillis = now - 1_000,
                capabilities = Capabilities(
                    Platform.IOS,
                    BluetoothState.ON,
                    uwb = true,
                    onScreen = false,
                    activitySensor = true,
                ),
                sparks = 7,
            ),
            PlayerView(
                id = PlayerId("p2"),
                name = "Sam",
                role = Role.HIDER,
                status = PlayerStatus.CAUGHT,
                location =
                VisibleLocation(
                    point = GeoPoint(-33.8688197, -151.2092955),
                    accuracyMeters = 20.04,
                    atMillis = now - 90_000,
                    reason = VisibilityReason.OUT_OF_ZONE,
                    cause = VisibilityReason.INSIDE_BUILDING,
                ),
                outAtMillis = now - 30_000,
                caughtBy = PlayerId("p1"),
                lastSeenMillis = now - 45_000,
                left = true,
                capabilities = Capabilities(),
            ),
            PlayerView(
                id = PlayerId("p3"),
                name = "Guest",
                role = Role.HIDER,
                status = PlayerStatus.ELIMINATED,
            ),
        ),
        me =
        MyState(
            playerId = PlayerId("p1"),
            role = Role.SEEKER,
            status = PlayerStatus.ACTIVE,
            catchCodeSecret = "00ff10a0b0c0d0e0f00112233445566778899aab",
            outOfZoneDeadlineMillis = now + 30_000,
            insideBuildingRevealAtMillis = now + 60_000,
            radarSecret = "0123456789abcdef0123456789abcdef01234567",
            radar =
            RadarState(
                listOf(
                    RadarContact(RadarBand.HOT, PlayerId("p2"), now - 2_000),
                    RadarContact(RadarBand.WARM),
                ),
            ),
            seekerTokens = listOf("00000000", "ffffffff"),
            hiderTokens = listOf("3fa91c07", "0b44e2d1"),
            bluetoothDeadlineMillis = now + 90_000,
            uwbPeers = listOf(UwbPeer(PlayerId("p2"), "dG9rZW4=", Platform.ANDROID)),
            sparks = 12,
            hint = Hint(HintKind.DIRECTION, now + 10_000, sector = 3, band = DistanceBand.CLOSE),
            perks = listOf(
                PerkView(
                    PerkKind.ERASE_TRAIL,
                    price = 5,
                    owned = 1,
                    usesLeft = 2,
                    canUse = true,
                    availableAtMillis = now,
                ),
            ),
        ),
        catches =
        listOf(
            CatchView(
                CatchId("c1"),
                PlayerId("p1"),
                PlayerId("p2"),
                CatchStatus.DISPUTED,
                now - 10_000,
                now + 20_000,
                true,
                false,
            ),
            CatchView(CatchId("c2"), PlayerId("p1"), PlayerId("p3"), CatchStatus.CONFIRMED, now - 50_000),
        ),
        buildings = BuildingsState.READY,
        chat =
        listOf(
            ChatMessage(41, PlayerId("p2"), "Привіт 👋", now - 5_000),
            ChatMessage(42, PlayerId("p1"), "where?", now - 1_000, ChatChannel.SEEKERS),
        ),
        finishedAtMillis = now + 1,
        streetZone = StreetZoneState.UNAVAILABLE,
        mapRevision = 3,
        rolesDrawnAtMillis = now - 300_000,
        enabledFeatures = listOf("RADAR", "QUESTS", "SOMETHING_NEWER"),
        items =
        listOf(
            BoardItem(
                id = ItemId("i1"),
                kind = ItemKind.QUEST_POINT,
                point = GeoPoint(50.451, 30.524),
                name = "Fountain",
                audience = Audience.HIDERS,
                sparks = 3,
                takenBy = listOf(PlayerId("p2")),
            ),
        ),
        quests = listOf(
            QuestView(
                QuestId("q1"),
                QuestKind.RELOCATE,
                sparks = 4,
                status = QuestStatus.ACTIVE,
                progress = 120,
                target = 200,
            ),
        ),
        capacity = ZoneCapacity(CapacityState.READY, players = 40, fewCovers = true),
        bigGame = BigGameInfo(
            BigGameId("b1"),
            "Великі хованки",
            now + 86_400_000,
            "Europe/Kyiv",
            signedUp = 120,
            playerLimit = 300,
        ),
        counts = PlayerCounts(
            players = 120,
            seekers = 20,
            hidersActive = 90,
            hidersCaught = 7,
            hidersEliminated = 3,
        ),
        spectators = 4,
        pause = GamePause(now - 3_000, sos = true),
        sos = listOf(
            SosCall(
                PlayerId("p2"),
                "Sam",
                now - 3_000,
                SosLocation(
                    GeoPoint(50.4499999, 30.5200001),
                    7.31,
                    now - 4_000,
                ),
            ),
        ),
        playAgain = PlayAgain("ZX8P"),
    )

    /** The smallest snapshot: everything else at its default. */
    fun bare(now: Long = NOW): GameSnapshot = GameSnapshot(
        gameId = GameId("g0"),
        joinCode = "AAAA",
        hostId = PlayerId("p1"),
        phase = GamePhase.LOBBY,
        settings = settings,
        serverTimeMillis = now,
        players = listOf(PlayerView(PlayerId("p1"), "Host", Role.HIDER, PlayerStatus.ACTIVE, lastSeenMillis = now)),
        me = MyState(PlayerId("p1"), Role.HIDER, PlayerStatus.ACTIVE),
    )

    /**
     * A random snapshot of the same game at [now]: players come, go, change places and move, catches and contacts
     * come and go. Ids are drawn from a small pool so that consecutive snapshots share most of them.
     */
    fun random(random: Random, now: Long): GameSnapshot {
        val base = everything(now)
        val ids = (1..12).map { "p$it" }.shuffled(random).take(random.nextInt(1, 12))
        val players =
            ids.map { id ->
                val n = id.drop(1).toInt()
                PlayerView(
                    id = PlayerId(id),
                    name = "Player $n",
                    role = if (n % 3 == 0) Role.SEEKER else Role.HIDER,
                    status =
                    PlayerStatus.entries[random.nextInt(PlayerStatus.entries.size)].takeIf {
                        random.nextInt(4) == 0
                    }
                        ?: PlayerStatus.ACTIVE,
                    location =
                    if (random.nextBoolean()) {
                        VisibleLocation(
                            point = GeoPoint(
                                50.45 + random.nextDouble(-0.01, 0.01),
                                30.52 + random.nextDouble(-0.01, 0.01),
                            ),
                            accuracyMeters = random.nextDouble(1.0, 60.0),
                            atMillis = now - random.nextLong(0, 30_000),
                            reason = VisibilityReason.entries[random.nextInt(4)],
                            cause = VisibilityReason.entries.random(random).takeIf { random.nextInt(3) == 0 },
                        )
                    } else {
                        null
                    },
                    userId = UserId("u$n").takeIf { n % 2 == 0 },
                    lastSeenMillis =
                    now -
                        if (random.nextInt(5) ==
                            0
                        ) {
                            random.nextLong(20_001, 120_000)
                        } else {
                            random.nextLong(0, 20_000)
                        },
                    sparks = random.nextInt(0, 3).takeIf { random.nextBoolean() },
                )
            }
        val catches =
            (1..4).filter { random.nextBoolean() }.map {
                CatchView(
                    CatchId("c$it"),
                    PlayerId("p1"),
                    PlayerId("p$it"),
                    CatchStatus.entries.random(random),
                    now - it * 1_000L,
                )
            }
        val contacts =
            (1..5).filter { random.nextBoolean() }.map {
                RadarContact(
                    RadarBand.entries.random(random),
                    PlayerId("p$it"),
                    now - it,
                )
            } +
                listOfNotNull(RadarContact(RadarBand.BURNING).takeIf { random.nextInt(4) == 0 })
        return base.copy(
            phase = GamePhase.entries.random(random),
            players = players,
            catches = catches,
            me =
            base.me.copy(
                radar = RadarState(contacts).takeIf { random.nextInt(4) != 0 },
                sparks = random.nextInt(0, 3),
                hint = base.me.hint.takeIf { random.nextBoolean() },
                hiderTokens = base.me.hiderTokens.takeIf { random.nextBoolean() }.orEmpty(),
            ),
            sos = base.sos.takeIf { random.nextInt(3) == 0 }.orEmpty(),
            pause = base.pause.takeIf { random.nextInt(3) == 0 },
            items = base.items.takeIf { random.nextInt(5) == 0 }.orEmpty(),
            counts = base.counts.takeIf { random.nextInt(4) == 0 },
            spectators = random.nextInt(0, 3),
            chat = emptyList(),
        )
    }

    /**
     * What a v1 phone gets of [snapshot], as v2 promises it (docs/live-v2/spec.md §1, §9.1, §10): the JSON round trip
     * of v1, points rounded to E7, accuracy up to decimetres, and the last time seen of a connected player is the
     * snapshot's.
     */
    fun v1(snapshot: GameSnapshot): GameSnapshot {
        val received = protocolJson.decodeFromString(
            GameSnapshot.serializer(),
            protocolJson.encodeToString(GameSnapshot.serializer(), snapshot),
        )
        val now = received.serverTimeMillis
        return received.copy(
            players =
            received.players.map { player ->
                player.copy(
                    location = player.location?.let {
                        it.copy(
                            point = LiveValues.round(it.point),
                            accuracyMeters = LiveValues.roundAccuracy(it.accuracyMeters),
                        )
                    },
                    lastSeenMillis =
                    player.lastSeenMillis?.takeIf { now - it > LiveLimits.OFFLINE_AFTER_MILLIS } ?: now,
                )
            },
            sos =
            received.sos.map { call ->
                call.copy(
                    location = call.location?.let {
                        it.copy(
                            point = LiveValues.round(it.point),
                            accuracyMeters = LiveValues.roundAccuracy(it.accuracyMeters),
                        )
                    },
                )
            },
        )
    }

    fun device() = app.hovanki.shared.protocol.DeviceReport(
        platform = Platform.ANDROID,
        bluetooth = BluetoothState.OFF_BY_PLAYER,
        uwb = true,
        onScreen = false,
        uwbToken = "dXdi",
        activity = Activity.RUNNING,
        activitySensor = true,
        carry = app.hovanki.shared.protocol.Carry.IN_POCKET,
        model = "Pixel 9",
    )
}
