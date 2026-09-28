package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.protocol.BluetoothState
import app.hovanki.shared.protocol.Capabilities
import app.hovanki.shared.protocol.Carry
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.DeviceReport
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.FeatureMode
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.NearbySighting
import app.hovanki.shared.protocol.Platform
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.RadarContact
import app.hovanki.shared.protocol.RadarState
import app.hovanki.shared.protocol.UwbPeer
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.rules.ProximityRules
import app.hovanki.shared.rules.RadarToken
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The radar by Bluetooth (docs/adr/0010-nearby-radar.md): what the server makes of the sightings the phones report,
 * and the rules built on it: the claim up close, the required radar, the UWB pairing.
 */
class GameRadarTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val features = GameFeatures(radar = FeatureMode.OPTIONAL, hiderSense = true, proximityCatch = true)
    private val settings = GameSettings(
        zone = shrinkingZone(center, steps = 0),
        hidingSeconds = 60,
        seekingSeconds = 600,
        glowEverySeconds = 0,
        glowForSeconds = 0,
        features = features,
    )
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val other = PlayerId("other")
    private var now = 1_700_000_000_000L
    private var secretsGiven = 0

    /** A lobby of three: the seeker (host), the hider and the other one, a hider too unless said otherwise. */
    private fun game(settings: GameSettings = this.settings): Game =
        Game(GameId("g"), "ABC234", seeker, settings, now).apply {
            addPlayer(seeker, "Seeker", now)
            addPlayer(hider, "Hider", now)
            addPlayer(other, "Other", now)
        }

    /** Distinct secrets: with one for everybody the radar tokens would collide. */
    private fun newSecret(): String = "%040x".format(++secretsGiven)

    private fun Game.begin(seekers: Set<PlayerId> = setOf(seeker)) {
        start(seeker, seekers, ::newSecret, now)
        now += 60_000
        advance(now)
        check(phase == GamePhase.SEEKING)
    }

    private fun Game.radarSecretOf(id: PlayerId): String =
        checkNotNull(debugState(now).players.single { it.id == id }.radarSecret)

    /** [observer]'s phone heard [heard]'s token at [rssi] dBm. */
    private fun Game.hears(observer: PlayerId, heard: PlayerId, rssi: Int) {
        val token = RadarToken.at(radarSecretOf(heard), now)
        recordSightings(observer, listOf(NearbySighting(token, rssi, now)), now)
        advance(now)
    }

    private fun Game.radarOf(id: PlayerId): RadarState? = snapshotFor(id, now).me.radar

    private fun Game.report(id: PlayerId, point: GeoPoint) {
        recordLocations(id, listOf(LocationSample(point, 5.0, now)), now)
        advance(now)
    }

    private fun Game.device(
        id: PlayerId,
        bluetooth: BluetoothState,
        platform: Platform = Platform.ANDROID,
        uwb: Boolean = false,
        onScreen: Boolean = true,
        token: String? = null,
        carry: Carry = Carry.UNKNOWN,
        model: String? = null,
    ) = recordDevice(id, DeviceReport(platform, bluetooth, uwb, onScreen, token, carry = carry, model = model), now)

    /** The pair hears each other «burning» for as long as a claim needs (the dwell). */
    private fun Game.meet(observer: PlayerId, heard: PlayerId, rssi: Int = -55) {
        hears(observer, heard, rssi)
        repeat(settings.rules.nearbyDwellSeconds) {
            tick(1)
            hears(observer, heard, rssi)
        }
    }

    private fun Game.catchCode(of: PlayerId): String =
        catchCodeTotp(checkNotNull(snapshotFor(of, now).me.catchCodeSecret), settings.rules).codeAt(now)

    private fun Game.seen(target: PlayerId = hider): VisibleLocation? =
        snapshotFor(seeker, now).players.single { it.id == target }.location

    private fun Game.tick(seconds: Int) {
        now += seconds * 1000L
        advance(now)
    }

    @Test
    fun theSeekerFeelsTheHidersByBands() {
        val game = game()
        assertNull(game.radarOf(seeker), "no radar in the lobby")
        game.begin()
        assertEquals(RadarState(), game.radarOf(seeker), "nothing heard yet")

        game.hears(seeker, hider, -80)
        assertEquals(listOf(RadarContact(RadarBand.WARM, hider, now)), game.radarOf(seeker)?.contacts)

        // Without a reading the signal is gone.
        game.tick(11)
        assertEquals(emptyList(), game.radarOf(seeker)?.contacts)

        // The hider's phone hearing the seeker counts for the same pair.
        game.hears(hider, seeker, -65)
        assertEquals(RadarBand.HOT, game.radarOf(seeker)?.contacts?.single()?.band)
        game.tick(2)
        game.hears(seeker, hider, -50)
        assertEquals(RadarBand.BURNING, game.radarOf(seeker)?.contacts?.single()?.band)

        // The hider with the sense: the nearest seeker, nameless. The other hider hears nobody.
        assertEquals(listOf(RadarContact(RadarBand.BURNING, null, now)), game.radarOf(hider)?.contacts)
        assertEquals(emptyList(), game.radarOf(other)?.contacts)

        // A token of nobody in this game is dropped.
        game.recordSightings(seeker, listOf(NearbySighting("deadbeef", -40, now)), now)
        assertEquals(listOf(hider), game.radarOf(seeker)?.contacts?.map { it.playerId })
    }

    @Test
    fun theSecretsAndTheAbilities() {
        val game = game()
        game.device(hider, BluetoothState.ON, Platform.IOS, uwb = true)
        val inLobby = game.snapshotFor(seeker, now)
        assertNull(inLobby.me.radarSecret)
        assertEquals(
            Capabilities(Platform.IOS, BluetoothState.ON, uwb = true),
            inLobby.players.single { it.id == hider }.capabilities,
        )
        assertNull(inLobby.players.single { it.id == other }.capabilities, "never reported")

        game.start(seeker, setOf(seeker), ::newSecret, now)
        val hiding = game.snapshotFor(hider, now)
        assertNotNull(hiding.me.radarSecret)
        assertNotNull(game.snapshotFor(seeker, now).me.radarSecret, "seekers advertise too")
        assertNull(hiding.me.radar, "the radar works during the search")

        val quiet = game(settings.copy(features = GameFeatures()))
        quiet.begin()
        assertNull(quiet.snapshotFor(hider, now).me.radarSecret)
        assertNull(quiet.radarOf(seeker))
        // Sightings in a game without the radar change nothing.
        quiet.recordSightings(seeker, listOf(NearbySighting("deadbeef", -40, now)), now)
        assertEquals(emptyList(), quiet.debugState(now).radar)
    }

    @Test
    fun aClaimUpCloseNeedsTheRadarToHaveHeardThem() {
        val game = game()
        game.begin()
        game.device(seeker, BluetoothState.ON)
        game.device(hider, BluetoothState.ON)
        game.report(seeker, center)
        game.report(hider, center.moveBy(10.0, 0.0))

        val refused = assertFailsWith<GameException> { game.claimCatch(seeker, hider, CatchId("c1"), now) }
        assertEquals(ErrorCode.TOO_FAR to ErrorReason.NOT_NEARBY, refused.code to refused.reason)

        // One spike off a wall is not a meeting: the pair has to stay «burning» for the dwell.
        game.hears(seeker, hider, -55)
        val spike = assertFailsWith<GameException> { game.claimCatch(seeker, hider, CatchId("c1"), now) }
        assertEquals(ErrorReason.NOT_NEARBY, spike.reason)
        game.meet(seeker, hider)
        game.claimCatch(seeker, hider, CatchId("c1"), now)
    }

    @Test
    fun aPhoneWithoutTheRadarIsJudgedByGpsAlone() {
        val game = game()
        game.begin()
        game.device(seeker, BluetoothState.ON)
        game.device(hider, BluetoothState.DENIED)
        game.report(seeker, center)
        game.report(hider, center.moveBy(10.0, 0.0))

        game.claimCatch(seeker, hider, CatchId("c1"), now)

        assertFailsWith<GameException> { game.claimCatch(seeker, other, CatchId("c2"), now) }.let {
            assertEquals(ErrorCode.WRONG_STATE, it.code, "one open claim at a time, as before")
        }
    }

    @Test
    fun theBurningMomentIsForgottenAfterTheWindow() {
        val game = game()
        game.begin()
        game.device(seeker, BluetoothState.ON)
        game.device(hider, BluetoothState.ON)
        game.meet(seeker, hider)
        game.tick(settings.rules.nearbyWindowSeconds + 1)
        game.report(seeker, center)
        game.report(hider, center.moveBy(10.0, 0.0))

        val refused = assertFailsWith<GameException> { game.claimCatch(seeker, hider, CatchId("c1"), now) }
        assertEquals(ErrorReason.NOT_NEARBY, refused.reason)
    }

    @Test
    fun theRequiredRadarRevealsAHiderWhoTurnsItOff() {
        val game = game(settings.copy(features = features.copy(radar = FeatureMode.REQUIRED)))
        val nobodyReported = assertFailsWith<GameException> { game.start(seeker, setOf(seeker), ::newSecret, now) }
        assertEquals(ErrorReason.FEATURE_MISSING, nobodyReported.reason)
        game.device(seeker, BluetoothState.ON)
        game.device(hider, BluetoothState.ON)
        game.device(other, BluetoothState.OFF)
        val oneOff = assertFailsWith<GameException> { game.start(seeker, setOf(seeker), ::newSecret, now) }
        assertEquals(ErrorReason.FEATURE_MISSING, oneOff.reason)
        game.device(other, BluetoothState.ON)
        game.begin()
        game.report(hider, center)
        assertNull(game.seen())
        assertNull(game.snapshotFor(hider, now).me.bluetoothDeadlineMillis)

        game.device(hider, BluetoothState.OFF)
        val deadline = now + settings.rules.radarOffRevealSeconds * 1000L
        assertEquals(deadline, game.snapshotFor(hider, now).me.bluetoothDeadlineMillis)
        // Still off, still reporting fixes (else the stale rule reveals them first).
        while (now + 10_000 < deadline) {
            game.tick(10)
            game.report(hider, center)
            game.device(hider, BluetoothState.OFF)
            assertNull(game.seen(), "hidden until the deadline")
        }
        game.tick(((deadline - now) / 1000).toInt())
        val revealed = assertNotNull(game.seen())
        assertEquals(VisibilityReason.RADAR_OFF, revealed.cause)
        assertEquals(VisibilityReason.OUT_OF_ZONE, revealed.reason, "the first app versions read the closest reason")
        assertEquals(center, revealed.point)

        game.device(hider, BluetoothState.ON)
        assertNull(game.seen())
        assertNull(game.snapshotFor(hider, now).me.bluetoothDeadlineMillis)

        // A seeker's phone without the radar is the seeker's loss, no rule of the game.
        game.device(seeker, BluetoothState.OFF)
        assertNull(game.snapshotFor(seeker, now).me.bluetoothDeadlineMillis)
    }

    @Test
    fun uwbPeersWhileBothLookAtTheirPhones() {
        val precision = GameFeatures(precisionRadar = true, fairOnly = false)
        val game = game(settings.copy(features = precision))
        game.begin()
        game.report(seeker, center)
        game.report(hider, center.moveBy(50.0, 0.0))
        game.report(other, center.moveBy(200.0, 0.0))
        game.device(seeker, BluetoothState.ON, Platform.IOS, uwb = true, token = "s-token")
        game.device(hider, BluetoothState.ON, Platform.IOS, uwb = true, token = "h-token")
        game.device(other, BluetoothState.ON, Platform.ANDROID, uwb = true, token = "o-token")

        // Phones of one kind only: the Android one is no peer of the iPhone.
        assertEquals(listOf(UwbPeer(hider, "h-token", Platform.IOS)), game.snapshotFor(seeker, now).me.uwbPeers)
        assertEquals(listOf(UwbPeer(seeker, "s-token", Platform.IOS)), game.snapshotFor(hider, now).me.uwbPeers)
        assertEquals(emptyList(), game.snapshotFor(other, now).me.uwbPeers)

        // The hider puts the phone away: nobody ranges with them.
        game.device(hider, BluetoothState.ON, Platform.IOS, uwb = true, onScreen = false, token = "h-token")
        assertEquals(emptyList(), game.snapshotFor(seeker, now).me.uwbPeers)

        // A report older than a few seconds doesn't count: the phone may be in the pocket by now.
        game.device(hider, BluetoothState.ON, Platform.IOS, uwb = true, token = "h-token")
        assertEquals(1, game.snapshotFor(seeker, now).me.uwbPeers.size)
        game.tick(21)
        assertEquals(emptyList(), game.snapshotFor(seeker, now).me.uwbPeers)
    }

    @Test
    fun fairModeNeedsEveryPhoneToHaveUwb() {
        val game = game(settings.copy(features = GameFeatures(precisionRadar = true, fairOnly = true)))
        game.begin()
        game.report(seeker, center)
        game.report(hider, center.moveBy(50.0, 0.0))
        game.report(other, center.moveBy(200.0, 0.0))
        game.device(seeker, BluetoothState.ON, Platform.IOS, uwb = true, token = "s-token")
        game.device(hider, BluetoothState.ON, Platform.IOS, uwb = true, token = "h-token")
        game.device(other, BluetoothState.ON, Platform.IOS, uwb = false, token = null)

        assertEquals(emptyList(), game.snapshotFor(seeker, now).me.uwbPeers, "the other one's phone can't")

        game.device(other, BluetoothState.ON, Platform.IOS, uwb = true, token = "o-token")
        assertEquals(
            listOf(UwbPeer(hider, "h-token", Platform.IOS), UwbPeer(other, "o-token", Platform.IOS)),
            game.snapshotFor(seeker, now).me.uwbPeers,
            "nearest first",
        )
    }

    @Test
    fun theQuestsOfTheRadar() {
        val radarQuests = settings.copy(
            features = features.copy(quests = true),
            quests = listOf(QuestKind.BEATER, QuestKind.MEETING, QuestKind.SHADOW),
        )
        val game = game(radarQuests)
        game.begin()
        fun quest(player: PlayerId, kind: QuestKind) = game.snapshotFor(player, now).quests.single { it.kind == kind }
        assertEquals(listOf(QuestKind.BEATER), game.snapshotFor(seeker, now).quests.map { it.kind })
        assertEquals(listOf(QuestKind.MEETING, QuestKind.SHADOW), game.snapshotFor(hider, now).quests.map { it.kind })

        // «Meeting»: the two hiders burning on each other for ten seconds.
        repeat(6) {
            game.hears(hider, other, -55)
            game.tick(2)
        }
        assertEquals(QuestStatus.DONE, quest(hider, QuestKind.MEETING).status)
        assertEquals(QuestStatus.DONE, quest(other, QuestKind.MEETING).status)
        assertEquals(4, game.snapshotFor(hider, now).me.sparks)

        // «Beater»: the first seeker hot on a hider.
        assertEquals(QuestStatus.ACTIVE, quest(seeker, QuestKind.BEATER).status)
        game.hears(seeker, other, -65)
        assertEquals(QuestStatus.DONE, quest(seeker, QuestKind.BEATER).status)

        // «Shadow»: the other one was hot on the seeker just now, so their metres start over from here.
        assertEquals(0, quest(other, QuestKind.SHADOW).progress)
    }

    @Test
    fun theHiderGetsTheSeekersTokensForThePulse() {
        val game = game()
        game.start(seeker, setOf(seeker), ::newSecret, now)
        assertEquals(emptyList(), game.snapshotFor(hider, now).me.seekerTokens, "only during the search")
        game.tick(60)
        check(game.phase == GamePhase.SEEKING)

        val tokens = game.snapshotFor(hider, now).me.seekerTokens
        assertEquals(RadarToken.candidates(game.radarSecretOf(seeker), now), tokens, "this slot and its neighbours")
        assertEquals(tokens, game.snapshotFor(other, now).me.seekerTokens, "every hider")
        assertEquals(emptyList(), game.snapshotFor(seeker, now).me.seekerTokens, "never to a seeker")
        assertTrue(RadarToken.at(game.radarSecretOf(hider), now) !in tokens, "never a hider's")

        val quiet = game(settings.copy(features = features.copy(hiderSense = false)))
        quiet.begin()
        assertEquals(emptyList(), quiet.snapshotFor(hider, now).me.seekerTokens, "the sense is off")
    }

    @Test
    fun thePocketIsEvenedOutAndMayHide() {
        val game = game()
        game.begin()
        // In the pocket the body takes a lot off the signal: what is heard at -72 dBm is about -60 in the open.
        game.device(hider, BluetoothState.ON, carry = Carry.IN_POCKET)
        game.hears(seeker, hider, -72)
        assertEquals(RadarBand.BURNING, game.radarOf(seeker)?.contacts?.single()?.band)

        // With the pocket stealth on, a hider in the pocket reads about a band colder to the seekers.
        val stealthy = game(settings.copy(features = features.copy(pocketStealth = true)))
        stealthy.begin()
        stealthy.device(hider, BluetoothState.ON, carry = Carry.IN_POCKET)
        stealthy.device(other, BluetoothState.ON, carry = Carry.IN_HAND)
        stealthy.hears(seeker, hider, -72)
        stealthy.hears(seeker, other, -72)
        val bands = stealthy.radarOf(seeker)?.contacts?.associate { it.playerId to it.band }
        assertEquals(
            RadarBand.HOT,
            bands?.get(hider),
            "-72 + ${ProximityRules.POCKET_OFFSET_DB} - ${ProximityRules.STEALTH_DB}",
        )
        assertEquals(RadarBand.WARM, bands?.get(other), "in the hand: as heard")
        // Two hiders' phones in two pockets: evened out twice, no stealth between them.
        stealthy.device(other, BluetoothState.ON, carry = Carry.IN_POCKET)
        stealthy.hears(other, hider, -84)
        assertEquals(RadarBand.BURNING, stealthy.debugState(now).radar.single { it.a == hider && it.b == other }.band)
    }

    @Test
    fun theReadingsByModelGoToTheHistory() {
        val game = game()
        game.begin()
        game.device(seeker, BluetoothState.ON, model = "Pixel 8", carry = Carry.IN_HAND)
        game.device(hider, BluetoothState.ON, platform = Platform.IOS, model = "iPhone15,2", carry = Carry.IN_POCKET)
        // The other one never said what phone it has: its readings are nobody's business.
        game.device(other, BluetoothState.ON)
        game.report(seeker, center)
        game.report(hider, center.moveBy(200.0, 0.0))
        game.hears(seeker, hider, -88)
        game.hears(hider, seeker, -87)
        game.hears(seeker, other, -60)
        game.tick(60)
        game.report(seeker, center)
        game.report(hider, center.moveBy(3.0, 0.0))
        game.meet(seeker, hider, -52)
        game.claimCatch(seeker, hider, CatchId("c1"), now, game.catchCode(hider))
        game.tick(settings.seekingSeconds)
        assertEquals(GamePhase.FINISHED, game.phase)

        val buckets = assertNotNull(game.takeFinishedRecord()).radioCalibration
        fun readings(hearer: String, anchor: CalibrationAnchor, rssi: Int) = buckets
            .filter { it.hearerModel == hearer && it.anchor == anchor && it.rssiDbm == rssi }.sumOf { it.readings }
        assertEquals(1, readings("Pixel 8", CalibrationAnchor.ALL, -88))
        assertEquals(1, readings("Pixel 8", CalibrationAnchor.FAR, -88), "200 m apart by GPS")
        assertEquals(1, readings("iPhone15,2", CalibrationAnchor.FAR, -87))
        assertEquals(settings.rules.nearbyDwellSeconds + 1, readings("Pixel 8", CalibrationAnchor.ALL, -52))
        assertEquals(
            settings.rules.nearbyDwellSeconds + 1,
            readings("Pixel 8", CalibrationAnchor.CATCH, -52),
            "next to each other",
        )
        assertEquals(0, readings("Pixel 8", CalibrationAnchor.FAR, -52))
        assertEquals(0, buckets.count { it.hearerModel.isBlank() || it.heardModel.isBlank() }, "no model, no row")
        val row = buckets.single { it.anchor == CalibrationAnchor.CATCH && it.hearerModel == "Pixel 8" }
        assertEquals("iPhone15,2" to Carry.IN_POCKET, row.heardModel to row.heardCarry)
        assertEquals(Carry.IN_HAND, row.hearerCarry)
        assertEquals(0, buckets.count { it.heardModel == "Other" || it.hearerModel == "Other" })
    }
}
