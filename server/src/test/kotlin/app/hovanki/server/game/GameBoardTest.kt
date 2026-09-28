package app.hovanki.server.game

import app.hovanki.shared.geo.moveBy
import app.hovanki.shared.geo.offsetFrom
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CustomQuestRequest
import app.hovanki.shared.protocol.DistanceBand
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameFeatures
import app.hovanki.shared.protocol.GameId
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.GameSettings
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.HintKind
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PerkView
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestReviewRequest
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.protocol.QuestView
import app.hovanki.shared.protocol.UsePerkRequest
import app.hovanki.shared.protocol.UserId
import app.hovanki.shared.protocol.VisibilityReason
import app.hovanki.shared.protocol.VisibleLocation
import app.hovanki.shared.rules.BoardRules
import app.hovanki.shared.rules.PerkCatalog
import app.hovanki.shared.rules.QuestCatalog
import app.hovanki.shared.rules.shrinkingZone
import app.hovanki.shared.totp.catchCodeTotp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The board (docs/adr/0011-quests-sparks-and-sensors.md): what the host places in the lobby, the quests judged on the
 * fixes, the sparks and the perks. The glow is on (every minute for 10 s): most perks work on its spots.
 */
class GameBoardTest {
    private val center = GeoPoint(50.4501, 30.5234)
    private val features = GameFeatures(quests = true, perks = true, checkpoints = true, pickups = true)
    private val settings = GameSettings(
        zone = shrinkingZone(center, initialRadiusMeters = 300.0, steps = 0),
        hidingSeconds = 60,
        seekingSeconds = 900,
        glowEverySeconds = 60,
        glowForSeconds = 10,
        features = features,
        quests = listOf(QuestKind.RELOCATE, QuestKind.SPRINT, QuestKind.FIRST_CATCH),
    )
    private val seeker = PlayerId("seeker")
    private val hider = PlayerId("hider")
    private val other = PlayerId("other")
    private var now = 1_700_000_000_000L
    private var secretsGiven = 0
    private var seekingStart = 0L
    private val newCode = { "QRCODE23" }

    /** Where everybody stands; [feedAll] reports it. */
    private val where = mutableMapOf(seeker to center, hider to center, other to center)

    /** A lobby of three: the seeker (host) and the hider have accounts, the other one (a hider too) is a guest. */
    private fun game(settings: GameSettings = this.settings): Game =
        Game(GameId("g"), "ABC234", seeker, settings, now).apply {
            addPlayer(seeker, "Seeker", now, UserId("u-seeker"))
            addPlayer(hider, "Hider", now, UserId("u-hider"))
            addPlayer(other, "Other", now)
        }

    private fun newSecret(): String = "%040x".format(++secretsGiven)

    private fun Game.begin(seekers: Set<PlayerId> = setOf(seeker)) {
        start(seeker, seekers, ::newSecret, now)
        now += 60_000
        advance(now)
        seekingStart = now
        check(phase == GamePhase.SEEKING)
    }

    private fun Game.feedAll() {
        for ((player, point) in where) recordLocations(player, listOf(LocationSample(point, 5.0, now)), now)
        advance(now)
    }

    /** Everybody stands where they are and reports it every 2 s until [secondsIntoSearch]. */
    private fun Game.until(secondsIntoSearch: Int) {
        val target = seekingStart + secondsIntoSearch * 1000L
        while (now < target) {
            now = minOf(now + 2_000, target)
            feedAll()
        }
    }

    private fun Game.stays(seconds: Int) = until(((now - seekingStart) / 1000).toInt() + seconds)

    /** [player] walks to [to] in [seconds] (fixes every 2 s along the way), the others stand. */
    private fun Game.walks(player: PlayerId, to: GeoPoint, seconds: Int) {
        val from = where.getValue(player)
        val offset = to.offsetFrom(from)
        val start = now
        val target = now + seconds * 1000L
        while (now < target) {
            now = minOf(now + 2_000, target)
            val share = (now - start).toDouble() / (target - start)
            where[player] = from.moveBy(offset.eastMeters * share, offset.northMeters * share)
            feedAll()
        }
        where[player] = to
    }

    private fun Game.sparks(player: PlayerId): Int = snapshotFor(player, now).me.sparks

    private fun Game.perks(player: PlayerId): List<PerkView> = snapshotFor(player, now).me.perks

    private fun Game.quest(player: PlayerId, kind: QuestKind): QuestView =
        snapshotFor(player, now).quests.single { it.kind == kind }

    private fun Game.seen(target: PlayerId = hider): VisibleLocation? =
        snapshotFor(seeker, now).players.single { it.id == target }.location

    /** The host's quest of [QuestCatalog.MAX_SPARKS] that [players] did: sparks to spend, during the round. */
    private fun Game.giveSparks(players: List<PlayerId>) {
        addCustomQuest(seeker, CustomQuestRequest("Sparks", Audience.ALL, QuestCatalog.MAX_SPARKS), now)
        val id = snapshotFor(seeker, now).quests.last { it.kind == QuestKind.CUSTOM }.id
        for (player in players) {
            markQuestDone(player, id, now)
            reviewQuest(seeker, id, QuestReviewRequest(player, approved = true), now)
        }
    }

    private fun place(kind: ItemKind, point: GeoPoint, perk: PerkKind? = null) =
        PlaceItemRequest(kind, point, perk = perk)

    @Test
    fun theHostPlacesItemsInTheLobby() {
        val game = game()
        val spot = center.moveBy(100.0, 0.0)
        val notHost =
            assertFailsWith<GameException> { game.placeItem(hider, place(ItemKind.QUEST_POINT, spot), newCode, now) }
        assertEquals(ErrorCode.FORBIDDEN, notHost.code)
        val farAway = place(ItemKind.QUEST_POINT, center.moveBy(2_000.0, 0.0))
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertFailsWith<GameException> {
                game.placeItem(seeker, farAway, newCode, now)
            }.code,
        )
        val noPerk = place(ItemKind.PICKUP, spot)
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertFailsWith<GameException> {
                game.placeItem(seeker, noPerk, newCode, now)
            }.code,
        )

        val checkpoint = game.placeItem(
            seeker,
            PlaceItemRequest(ItemKind.CHECKPOINT_SCAN, spot, name = " Fountain ", audience = Audience.HIDERS),
            newCode,
            now,
        )
        assertEquals("QRCODE23", checkpoint.code)
        assertEquals("Fountain", checkpoint.name)
        assertEquals(BoardRules.defaultSparks(ItemKind.CHECKPOINT_SCAN), checkpoint.sparks)
        // The host sees the code in the lobby, the others never.
        assertEquals("QRCODE23", game.snapshotFor(seeker, now).items.single().code)
        assertNull(game.snapshotFor(hider, now).items.single().code)

        val pickup = game.placeItem(seeker, place(ItemKind.PICKUP, spot, PerkKind.SENSE), newCode, now)
        assertNull(pickup.code)
        assertEquals(0, pickup.sparks)
        assertEquals(listOf(checkpoint.id, pickup.id), game.snapshotFor(other, now).items.map { it.id })
        game.removeItem(seeker, pickup.id, now)
        assertEquals(listOf(checkpoint.id), game.snapshotFor(other, now).items.map { it.id })
        assertEquals(
            ErrorCode.NOT_FOUND,
            assertFailsWith<GameException> {
                game.removeItem(seeker, pickup.id, now)
            }.code,
        )

        // The board has room for so many.
        repeat(BoardRules.MAX_ITEMS - 1) { game.placeItem(seeker, place(ItemKind.QUEST_POINT, spot), newCode, now) }
        val full =
            assertFailsWith<GameException> { game.placeItem(seeker, place(ItemKind.QUEST_POINT, spot), newCode, now) }
        assertEquals(ErrorReason.ITEM_LIMIT, full.reason)

        // In the round: the items of the viewer's audience, without the codes; nothing is placed any more.
        game.begin()
        assertEquals(
            BoardRules.MAX_ITEMS - 1,
            game.snapshotFor(seeker, now).items.size,
            "the hiders' checkpoint is not the seeker's",
        )
        assertNull(game.snapshotFor(hider, now).items.single { it.id == checkpoint.id }.code)
        val late =
            assertFailsWith<GameException> { game.placeItem(seeker, place(ItemKind.QUEST_POINT, spot), newCode, now) }
        assertEquals(ErrorCode.WRONG_STATE, late.code)
    }

    @Test
    fun onlyWhatTheFeaturesAllow() {
        val game = game(settings.copy(features = GameFeatures(quests = true, checkpoints = true), quests = emptyList()))
        val spot = center.moveBy(100.0, 0.0)
        val off =
            assertFailsWith<GameException> {
                game.placeItem(seeker, place(ItemKind.PICKUP, spot, PerkKind.SENSE), newCode, now)
            }
        assertEquals(ErrorReason.FEATURE_DISABLED, off.reason)
        game.placeItem(seeker, place(ItemKind.CHECKPOINT_GEO, spot), newCode, now)
        game.placeItem(seeker, place(ItemKind.QUEST_POINT, spot), newCode, now)
        game.addCustomQuest(seeker, CustomQuestRequest("Wave at a stranger"), now)

        // The host turns the checkpoints off: theirs go; then the quests: the quest points and the host's quests go.
        game.updateSettings(seeker, settings.copy(features = GameFeatures(quests = true), quests = emptyList()), now)
        assertEquals(listOf(ItemKind.QUEST_POINT), game.snapshotFor(seeker, now).items.map { it.kind })
        assertEquals(1, game.snapshotFor(seeker, now).quests.size)
        game.updateSettings(seeker, settings.copy(features = GameFeatures(), quests = emptyList()), now)
        assertEquals(emptyList(), game.snapshotFor(seeker, now).items)
        assertEquals(emptyList(), game.snapshotFor(seeker, now).quests)
        val noQuests =
            assertFailsWith<GameException> { game.addCustomQuest(seeker, CustomQuestRequest("Anything"), now) }
        assertEquals(ErrorReason.FEATURE_DISABLED, noQuests.reason)
    }

    @Test
    fun itemsAreTakenByStandingThere() {
        val game = game()
        val spot = center.moveBy(0.0, 80.0)
        val questPoint = game.placeItem(
            seeker,
            PlaceItemRequest(ItemKind.QUEST_POINT, spot, name = "Bridge"),
            newCode,
            now,
        )
        val pickup = game.placeItem(
            seeker,
            PlaceItemRequest(ItemKind.PICKUP, spot, audience = Audience.HIDERS, perk = PerkKind.ERASE_TRAIL),
            newCode,
            now,
        )
        game.begin()
        // Judged on several fixes there, never on the first one.
        game.walks(hider, spot, seconds = 20)
        assertEquals(0, game.sparks(hider))
        game.stays(22)
        assertEquals(3, game.sparks(hider))
        val items = game.snapshotFor(hider, now).items
        assertEquals(listOf(hider), items.single { it.id == questPoint.id }.takenBy)
        assertEquals(listOf(hider), items.single { it.id == pickup.id }.takenBy)
        assertEquals(1, game.perks(hider).single { it.perk == PerkKind.ERASE_TRAIL }.owned)
        assertEquals(
            3,
            game.snapshotFor(other, now).players.single {
                it.id == hider
            }.sparks,
            "everybody sees the sparks",
        )

        // A quest point once per player; the pickup is gone.
        game.walks(other, spot, seconds = 20)
        game.stays(22)
        assertEquals(3, game.sparks(other))
        assertEquals(0, game.perks(other).single { it.perk == PerkKind.ERASE_TRAIL }.owned)
        assertEquals(listOf(hider), game.snapshotFor(other, now).items.single { it.id == pickup.id }.takenBy)

        // The seeker: the quest point (for everybody), not the hiders' pickup, which they don't even see.
        game.walks(seeker, spot, seconds = 20)
        game.stays(22)
        assertEquals(3, game.sparks(seeker))
        assertEquals(listOf(questPoint.id), game.snapshotFor(seeker, now).items.map { it.id })
    }

    @Test
    fun aCheckpointByScan() {
        val game = game()
        val spot = center.moveBy(-60.0, 0.0)
        game.placeItem(seeker, PlaceItemRequest(ItemKind.CHECKPOINT_SCAN, spot, sparks = 6), newCode, now)
        game.begin()
        val noFix = assertFailsWith<GameException> { game.scanCheckpoint(hider, "QRCODE23", now) }
        assertEquals(ErrorCode.NO_LOCATION, noFix.code)
        game.stays(4)
        assertEquals(
            ErrorCode.NOT_FOUND,
            assertFailsWith<GameException> {
                game.scanCheckpoint(hider, "WRONG123", now)
            }.code,
        )
        val tooFar = assertFailsWith<GameException> { game.scanCheckpoint(hider, "QRCODE23", now) }
        assertEquals(ErrorCode.TOO_FAR, tooFar.code)

        game.walks(hider, spot, seconds = 20)
        game.scanCheckpoint(hider, " qrcode23 ", now)
        assertEquals(6, game.sparks(hider))
        val again = assertFailsWith<GameException> { game.scanCheckpoint(hider, "QRCODE23", now) }
        assertEquals(ErrorReason.CHECKPOINT_TAKEN, again.reason)

        // The next one there gets half.
        game.walks(other, spot, seconds = 20)
        game.scanCheckpoint(other, "QRCODE23", now)
        assertEquals(3, game.sparks(other))
        assertEquals(listOf(hider, other), game.snapshotFor(hider, now).items.single().takenBy)
    }

    @Test
    fun theCatalogQuestsAreJudgedOnTheFixes() {
        val game = game()
        game.begin(seekers = setOf(seeker, other))
        game.stays(2)
        assertEquals(listOf(QuestKind.RELOCATE, QuestKind.SPRINT), game.snapshotFor(hider, now).quests.map { it.kind })
        assertEquals(
            listOf(QuestKind.SPRINT, QuestKind.FIRST_CATCH),
            game.snapshotFor(seeker, now).quests.map {
                it.kind
            },
        )
        val relocate = game.quest(hider, QuestKind.RELOCATE)
        assertEquals(seekingStart + QuestCatalog.RELOCATE_SECONDS * 1000L, relocate.deadlineMillis)
        assertEquals(QuestCatalog.RELOCATE_METERS, relocate.target)

        // «Relocate»: 250 m away from where the search found the hider, within the time.
        game.walks(hider, center.moveBy(0.0, 250.0), seconds = 100)
        game.stays(20)
        assertEquals(QuestStatus.DONE, game.quest(hider, QuestKind.RELOCATE).status)
        assertEquals(2, game.sparks(hider))

        // «Sprint»: 300 m within three minutes; the metres so far show as the progress.
        val sprint = game.quest(hider, QuestKind.SPRINT)
        assertEquals(QuestStatus.ACTIVE, sprint.status)
        assertTrue(sprint.progress in 200..260, "walked ${sprint.progress} m")
        game.walks(hider, center.moveBy(0.0, 150.0), seconds = 40)
        assertEquals(QuestStatus.DONE, game.quest(hider, QuestKind.SPRINT).status)
        assertEquals(4, game.sparks(hider))

        // «First catch»: the seeker who makes it; the other seeker's quest fails with it.
        game.walks(seeker, where.getValue(hider), seconds = 60)
        game.claimCatch(seeker, hider, CatchId("c1"), now)
        val secret = checkNotNull(game.debugState(now).players.single { it.id == hider }.catchCodeSecret)
        game.confirmCatch(CatchId("c1"), seeker, catchCodeTotp(secret, settings.rules).codeAt(now), now)
        assertEquals(QuestStatus.DONE, game.quest(seeker, QuestKind.FIRST_CATCH).status)
        assertEquals(QuestStatus.FAILED, game.quest(other, QuestKind.FIRST_CATCH).status)
        assertEquals(3, game.sparks(seeker))
        assertEquals(QuestStatus.DONE, game.quest(hider, QuestKind.SPRINT).status, "what was done stays")
    }

    @Test
    fun freezeAndSweep() {
        val game = game(settings.copy(quests = listOf(QuestKind.FREEZE, QuestKind.SWEEP)))
        game.begin()
        // «Freeze»: five minutes within 15 m; moving further starts over.
        game.stays(100)
        assertTrue(game.quest(hider, QuestKind.FREEZE).progress in 95..100)
        game.walks(hider, center.moveBy(30.0, 0.0), seconds = 10)
        assertTrue(game.quest(hider, QuestKind.FREEZE).progress < 10)
        game.stays(QuestCatalog.FREEZE_SECONDS + 2)
        assertEquals(QuestStatus.DONE, game.quest(hider, QuestKind.FREEZE).status)
        assertEquals(1, game.sparks(hider))

        // «Sweep»: four sectors of the zone, twenty seconds in each.
        for (bearing in listOf(30.0, 90.0, 150.0, 210.0)) {
            val radians = bearing * PI / 180
            game.walks(seeker, center.moveBy(eastMeters = 100 * sin(radians), northMeters = 100 * cos(radians)), 30)
            game.stays(22)
        }
        assertEquals(QuestStatus.DONE, game.quest(seeker, QuestKind.SWEEP).status)
        assertEquals(QuestCatalog.SWEEP_SECTORS, game.quest(seeker, QuestKind.SWEEP).progress)
    }

    @Test
    fun theHostsOwnQuestsAreConfirmedByTheHost() {
        val game = game()
        assertEquals(
            ErrorCode.FORBIDDEN,
            assertFailsWith<GameException> {
                game.addCustomQuest(hider, CustomQuestRequest("Selfie"), now)
            }.code,
        )
        val tooLong = CustomQuestRequest("x".repeat(QuestCatalog.MAX_CUSTOM_TEXT + 1))
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertFailsWith<GameException> {
                game.addCustomQuest(seeker, tooLong, now)
            }.code,
        )
        val noSparks = CustomQuestRequest("Selfie", sparks = 0)
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertFailsWith<GameException> {
                game.addCustomQuest(seeker, noSparks, now)
            }.code,
        )
        game.addCustomQuest(seeker, CustomQuestRequest("  Selfie at the\nfountain ", Audience.HIDERS, sparks = 4), now)

        val hostView = game.snapshotFor(seeker, now).quests.single()
        assertEquals(QuestKind.CUSTOM, hostView.kind)
        assertEquals("Selfie at the fountain", hostView.text)
        assertEquals(Audience.HIDERS, hostView.audience)
        val notYet = assertFailsWith<GameException> { game.markQuestDone(hider, hostView.id, now) }
        assertEquals(ErrorReason.QUEST_NOT_ACTIVE, notYet.reason)

        game.begin()
        game.markQuestDone(hider, hostView.id, now)
        assertEquals(QuestStatus.PENDING_REVIEW, game.quest(hider, QuestKind.CUSTOM).status)
        assertEquals(listOf(hider), game.quest(seeker, QuestKind.CUSTOM).pending)
        val twice = assertFailsWith<GameException> { game.markQuestDone(hider, hostView.id, now) }
        assertEquals(ErrorReason.QUEST_NOT_ACTIVE, twice.reason)
        val notTheHost = assertFailsWith<GameException> {
            game.reviewQuest(hider, hostView.id, QuestReviewRequest(hider, approved = true), now)
        }
        assertEquals(ErrorCode.FORBIDDEN, notTheHost.code)

        game.reviewQuest(seeker, hostView.id, QuestReviewRequest(hider, approved = true), now)
        assertEquals(QuestStatus.DONE, game.quest(hider, QuestKind.CUSTOM).status)
        assertEquals(4, game.sparks(hider))
        assertEquals(listOf(hider), game.quest(other, QuestKind.CUSTOM).doneBy)

        // Refused: the other one may say so again.
        game.markQuestDone(other, hostView.id, now)
        game.reviewQuest(seeker, hostView.id, QuestReviewRequest(other, approved = false), now)
        assertEquals(QuestStatus.ACTIVE, game.quest(other, QuestKind.CUSTOM).status)
        assertEquals(0, game.sparks(other))
        val nothingToReview = assertFailsWith<GameException> {
            game.reviewQuest(seeker, hostView.id, QuestReviewRequest(other, approved = true), now)
        }
        assertEquals(ErrorReason.QUEST_NOT_ACTIVE, nothingToReview.reason)

        // The history keeps the numbers, for accounts.
        game.endNow(now)
        val record = assertNotNull(game.takeFinishedRecord())
        val hiderResult = record.results.single { it.userId == UserId("u-hider") }
        assertEquals(4 to 1, hiderResult.sparks to hiderResult.questsDone)
    }

    @Test
    fun eraseTrailRemovesTheGlowSpot() {
        val game = game()
        game.begin()
        game.giveSparks(listOf(hider))
        game.until(75)
        assertNotNull(game.seen(), "the spot the first glow left")
        val notMine = assertFailsWith<GameException> { game.usePerk(seeker, UsePerkRequest(PerkKind.ERASE_TRAIL), now) }
        assertEquals(ErrorReason.PERK_UNAVAILABLE, notMine.reason)

        game.usePerk(hider, UsePerkRequest(PerkKind.ERASE_TRAIL), now)
        assertNull(game.seen())
        assertEquals(QuestCatalog.MAX_SPARKS - 3, game.sparks(hider))
        val view = game.perks(hider).single { it.perk == PerkKind.ERASE_TRAIL }
        assertEquals(2, view.usesLeft)
        assertFalse(view.canUse)
        assertEquals(now + settings.rules.perkCooldownSeconds * 1000L, view.availableAtMillis)
        val tooSoon = assertFailsWith<GameException> { game.usePerk(hider, UsePerkRequest(PerkKind.ERASE_TRAIL), now) }
        assertEquals(ErrorReason.PERK_UNAVAILABLE, tooSoon.reason)

        game.until(110)
        val nothing = assertFailsWith<GameException> { game.usePerk(hider, UsePerkRequest(PerkKind.ERASE_TRAIL), now) }
        assertEquals(ErrorReason.PERK_UNAVAILABLE, nothing.reason, "no spot to erase")

        // Without sparks: no.
        val poor = assertFailsWith<GameException> { game.usePerk(other, UsePerkRequest(PerkKind.SENSE), now) }
        assertEquals(ErrorReason.NOT_ENOUGH_SPARKS, poor.reason)
        assertFalse(game.perks(other).single { it.perk == PerkKind.SENSE }.canUse)
    }

    @Test
    fun perksOffOrOnlyTheOnesFound() {
        val none = game(settings.copy(features = GameFeatures(quests = true), quests = emptyList()))
        none.begin()
        val off = assertFailsWith<GameException> { none.usePerk(hider, UsePerkRequest(PerkKind.SENSE), now) }
        assertEquals(ErrorReason.FEATURE_DISABLED, off.reason)
        assertEquals(emptyList(), none.perks(hider))

        val found = game(settings.copy(features = GameFeatures(pickups = true, quests = true), quests = emptyList()))
        val spot = center.moveBy(0.0, 50.0)
        found.placeItem(seeker, place(ItemKind.PICKUP, spot, PerkKind.SENSE), newCode, now)
        found.begin()
        found.giveSparks(listOf(hider))
        assertEquals(emptyList(), found.perks(hider), "no shop: only what was found")
        val notFound = assertFailsWith<GameException> { found.usePerk(hider, UsePerkRequest(PerkKind.SENSE), now) }
        assertEquals(ErrorReason.PERK_UNAVAILABLE, notFound.reason)
        found.walks(hider, spot, seconds = 20)
        found.stays(22)
        assertEquals(listOf(PerkKind.SENSE), found.perks(hider).map { it.perk })
        found.usePerk(hider, UsePerkRequest(PerkKind.SENSE), now)
        assertEquals(QuestCatalog.MAX_SPARKS, found.sparks(hider), "a found perk is free")
        assertEquals(emptyList(), found.perks(hider), "used up")
        assertNotNull(found.snapshotFor(hider, now).me.hint)
    }

    @Test
    fun aDecoyPassesForTheGlowSpot() {
        val game = game()
        game.begin()
        game.giveSparks(listOf(hider))
        val decoy = center.moveBy(120.0, 0.0)
        game.until(30)
        val early =
            assertFailsWith<GameException> { game.usePerk(hider, UsePerkRequest(PerkKind.DECOY, point = decoy), now) }
        assertEquals(ErrorReason.PERK_UNAVAILABLE, early.reason, "before the first glow")
        game.until(75)
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertFailsWith<GameException> {
                game.usePerk(hider, UsePerkRequest(PerkKind.DECOY), now)
            }.code,
        )
        val outside = UsePerkRequest(PerkKind.DECOY, point = center.moveBy(400.0, 0.0))
        assertEquals(ErrorCode.BAD_REQUEST, assertFailsWith<GameException> { game.usePerk(hider, outside, now) }.code)
        game.usePerk(hider, UsePerkRequest(PerkKind.DECOY, point = decoy), now)

        val seen = assertNotNull(game.seen())
        assertEquals(decoy, seen.point)
        assertEquals(VisibilityReason.GLOW, seen.cause)
        assertEquals(seekingStart + 70_000 - 1, seen.atMillis, "stamped like the real one")
        // The next glow shows the truth again.
        game.until(135)
        assertEquals(center, assertNotNull(game.seen()).point)
    }

    @Test
    fun invisibleSkipsTheNextGlow() {
        val game = game()
        game.begin()
        game.giveSparks(listOf(hider))
        game.until(30)
        game.usePerk(hider, UsePerkRequest(PerkKind.INVISIBLE), now)
        game.until(65)
        assertNull(game.seen(), "not live during the glow")
        assertNotNull(game.seen(other), "the others glow")
        game.until(75)
        assertNull(game.seen(), "no spot either")
        game.until(125)
        assertEquals(VisibilityReason.GLOW, assertNotNull(game.seen()).cause, "the glow after shows them")
        assertEquals(0, game.perks(hider).single { it.perk == PerkKind.INVISIBLE }.usesLeft)
    }

    @Test
    fun spotlightAndFreshTrailForTheSeekers() {
        val game = game(settings.copy(glowEverySeconds = 120))
        game.begin()
        game.giveSparks(listOf(seeker))
        game.until(30)
        assertEquals(
            ErrorCode.BAD_REQUEST,
            assertFailsWith<GameException> {
                game.usePerk(seeker, UsePerkRequest(PerkKind.SPOTLIGHT), now)
            }.code,
        )
        val onASeeker = UsePerkRequest(PerkKind.SPOTLIGHT, targetId = seeker)
        assertEquals(
            ErrorCode.WRONG_STATE,
            assertFailsWith<GameException> {
                game.usePerk(seeker, onASeeker, now)
            }.code,
        )
        game.usePerk(seeker, UsePerkRequest(PerkKind.SPOTLIGHT, targetId = hider), now)
        val lit = assertNotNull(game.seen())
        assertEquals(VisibilityReason.SPOTLIGHT, lit.cause)
        assertEquals(VisibilityReason.OUT_OF_ZONE, lit.reason)
        game.until(34)
        assertNull(game.seen(), "a few seconds only")

        // «Fresh trail»: the glow leaves the hider at A, they move to B; the trail moves the spot to a minute ago.
        val b = center.moveBy(0.0, 60.0)
        game.until(131)
        assertEquals(center, assertNotNull(game.seen()).point)
        game.walks(hider, b, seconds = 10)
        game.until(220)
        assertEquals(center, assertNotNull(game.seen()).point, "the glow's spot until then")
        game.usePerk(seeker, UsePerkRequest(PerkKind.FRESH_TRAIL, targetId = hider), now)
        val fresh = assertNotNull(game.seen())
        assertEquals(b, fresh.point)
        assertEquals(VisibilityReason.FRESH_TRAIL, fresh.cause)
        assertEquals(VisibilityReason.OUT_OF_ZONE, fresh.reason)
        assertTrue(fresh.atMillis <= now - PerkCatalog.FRESH_TRAIL_AGE_MILLIS)
        // The next glow takes over.
        game.until(255)
        assertEquals(VisibilityReason.GLOW, assertNotNull(game.seen()).cause)
    }

    @Test
    fun hintsPointAtTheOtherTeam() {
        val game = game()
        game.begin()
        game.giveSparks(listOf(hider, seeker))
        where[seeker] = center.moveBy(100.0, 0.0)
        game.stays(4)
        game.usePerk(hider, UsePerkRequest(PerkKind.SENSE), now)
        val sense = assertNotNull(game.snapshotFor(hider, now).me.hint)
        assertEquals(HintKind.SENSE, sense.kind)
        assertEquals(2, sense.sector, "east")
        assertEquals(DistanceBand.CLOSE, sense.band)
        assertEquals(now + PerkCatalog.spec(PerkKind.SENSE).effectSeconds * 1000L, sense.untilMillis)

        game.usePerk(seeker, UsePerkRequest(PerkKind.DIRECTION), now)
        val direction = assertNotNull(game.snapshotFor(seeker, now).me.hint)
        assertEquals(6, direction.sector, "west: the hiders")
        assertNull(direction.band)
        game.stays(11)
        assertNull(game.snapshotFor(seeker, now).me.hint, "ten seconds")
        game.stays(20)
        assertNull(game.snapshotFor(hider, now).me.hint, "half a minute")
        game.usePerk(seeker, UsePerkRequest(PerkKind.RADIUS), now)
        val radius = assertNotNull(game.snapshotFor(seeker, now).me.hint)
        assertNull(radius.sector)
        assertEquals(DistanceBand.CLOSE, radius.band)
    }
}
