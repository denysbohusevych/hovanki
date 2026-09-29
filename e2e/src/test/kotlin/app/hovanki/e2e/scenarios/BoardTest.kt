package app.hovanki.e2e.scenarios

import app.hovanki.e2e.route.Route
import app.hovanki.e2e.route.offset
import app.hovanki.e2e.scenario
import app.hovanki.e2e.scenario.GameSetups
import app.hovanki.e2e.scenario.GameSetups.PARK
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.HintKind
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.PerkKind
import app.hovanki.shared.protocol.PlaceItemRequest
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.rules.CheckpointPayload
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The board (docs/adr/0011-quests-sparks-and-sensors.md): the host places a quest point, a checkpoint by code and a
 * perk on the map in the lobby and makes up a quest in words; in the round a hider scans the checkpoint's code at
 * the place, reaches the quest point by GPS, picks the perk up and uses it, and gets the host's quest confirmed. The
 * privacy audit checks that the round shows each team only its items and the code to nobody.
 */
class BoardTest {
    @Test
    fun theHostPlacesTheBoardAndThePlayersEarnSparks() = scenario("The board") {
        enableAllFeatures()
        val sam = player("Sam", at = PARK)
        val anna = player("Anna", at = PARK.offset(eastMeters = 40.0))
        val fountain = PARK.offset(eastMeters = 90.0)
        val bridge = PARK.offset(eastMeters = 40.0, northMeters = 80.0)
        val bench = PARK.offset(eastMeters = 90.0, northMeters = 50.0)

        sam.createsGame(GameSetups.board())
        join(anna)
        sam.placesItem(
            PlaceItemRequest(ItemKind.QUEST_POINT, fountain, name = "Fountain", audience = Audience.HIDERS, sparks = 2),
        )
        sam.placesItem(PlaceItemRequest(ItemKind.CHECKPOINT_SCAN, bridge, name = "Bridge"))
        sam.placesItem(PlaceItemRequest(ItemKind.PICKUP, bench, audience = Audience.HIDERS, perk = PerkKind.SENSE))
        sam.addsQuest("Sing a song", Audience.HIDERS, sparks = 3)
        val code = eventually("the host sees the checkpoint's code") {
            sam.snapshot?.items?.firstOrNull { it.kind == ItemKind.CHECKPOINT_SCAN }?.code
        }
        eventually("Anna sees the whole board, without the code") {
            anna.snapshot?.items?.takeIf { items -> items.size == 3 && items.all { it.code == null } }
        }
        check(
            anna.snapshot?.quests?.any { it.kind == QuestKind.CUSTOM && it.text == "Sing a song" } == true,
            "and the host's quest",
        )

        sam.startsGame(seekers = listOf(sam))
        awaitPhase(GamePhase.SEEKING, within = 20.seconds)
        eventually("in the round Sam sees only the items for the seekers") {
            sam.snapshot?.items?.takeIf { items -> items.size == 1 && items.single().kind == ItemKind.CHECKPOINT_SCAN }
        }
        delay(3.seconds)

        // The checkpoint by code: scanned at the place, and it counts once.
        val payload = CheckpointPayload(gameId, code).encode()
        expectRejected(anna.scanCheckpoint(payload), ErrorCode.TOO_FAR, "the code scanned from 80 m away")
        anna.walksToAndArrives(bridge, speed = Route.RUNNING)
        delay(2.seconds)
        anna.scansCheckpoint(payload)
        eventually("Anna took the checkpoint") {
            anna.snapshot?.items?.firstOrNull { it.kind == ItemKind.CHECKPOINT_SCAN }?.takeIf { anna.id in it.takenBy }
        }
        expectRejected(anna.scanCheckpoint(payload), ErrorReason.CHECKPOINT_TAKEN, "a checkpoint counts once")
        val afterCheckpoint = eventually("sparks for the checkpoint") { anna.snapshot?.me?.sparks?.takeIf { it > 0 } }

        // The quest point by GPS, then the perk lying on the bench.
        anna.walksToAndArrives(fountain, speed = Route.RUNNING)
        eventually("Anna reached the quest point and earned its sparks") {
            anna.snapshot?.me?.sparks?.takeIf { it >= afterCheckpoint + 2 }
        }
        anna.walksToAndArrives(bench, speed = Route.RUNNING)
        eventually("Anna picked up the sense") {
            anna.snapshot?.me?.perks?.firstOrNull { it.perk == PerkKind.SENSE }?.takeIf { it.owned == 1 && it.canUse }
        }
        anna.usesPerk(PerkKind.SENSE)
        eventually("Anna's phone hints where the nearest seeker is") {
            anna.snapshot?.me?.hint?.takeIf { it.kind == HintKind.SENSE }
        }

        // The host's own quest: Anna says it is done, Sam confirms, the sparks arrive.
        val quest = checkNotNull(anna.snapshot?.quests?.single { it.kind == QuestKind.CUSTOM })
        val before = checkNotNull(anna.snapshot?.me?.sparks)
        anna.saysQuestDone(quest.id)
        eventually("Sam sees Anna waiting for his answer") {
            sam.snapshot?.quests?.singleOrNull { it.id == quest.id }?.pending?.takeIf { anna.id in it }
        }
        sam.reviewsQuest(quest.id, anna, approved = true)
        eventually("Anna got the sparks for the host's quest") {
            anna.snapshot?.me?.sparks?.takeIf { it == before + 3 }
        }
        check(anna.snapshot?.quests?.single { it.id == quest.id }?.status == QuestStatus.DONE, "the quest is done")
    }
}
