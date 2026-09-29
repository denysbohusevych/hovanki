package app.hovanki.server.game

import app.hovanki.shared.debug.DebugQuest
import app.hovanki.shared.geo.distanceTo
import app.hovanki.shared.protocol.Audience
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import app.hovanki.shared.protocol.GameRules
import app.hovanki.shared.protocol.GeoPoint
import app.hovanki.shared.protocol.ItemId
import app.hovanki.shared.protocol.ItemKind
import app.hovanki.shared.protocol.LocationSample
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.QuestId
import app.hovanki.shared.protocol.QuestKind
import app.hovanki.shared.protocol.QuestStatus
import app.hovanki.shared.protocol.QuestView
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.rules.BoardRules
import app.hovanki.shared.rules.CatchRules
import app.hovanki.shared.rules.GlowWindow
import app.hovanki.shared.rules.QuestCatalog
import app.hovanki.shared.rules.Sectors

/** What the board's rules look at when time passes: built by [Game] for every [Board.advance]. */
internal class BoardContext(
    val nowMillis: Long,
    val zoneCenter: GeoPoint,
    /** The last glow that started (docs/adr/0009); null before the first one or without glows. */
    val lastGlow: GlowWindow?,
    /** The radar's band between two players right now. */
    val radarBand: (PlayerId, PlayerId) -> RadarBand,
)

/**
 * The board of a game (docs/adr/0013-quests-sparks-and-sensors.md): the items the host placed, the quests and the
 * sparks. Pure like [Game], which owns it: the time comes in, the players' state is read from [Player], and the sparks
 * are written there. Every rule judges only by several fixes with a good accuracy, like the zone and the catches.
 */
internal class Board(private val rules: GameRules) {
    val items = LinkedHashMap<ItemId, Item>()
    val customQuests = LinkedHashMap<QuestId, CustomQuest>()
    var teamQuest: TeamQuest? = null
        private set

    /** The first seeker who got «hot» on a hider («Beater»). */
    private var firstHotSeeker: PlayerId? = null
    private var firstCatchGiven = false

    // ---- The lobby: what the host places ----

    fun place(item: Item) {
        if (items.size >= BoardRules.MAX_ITEMS) {
            throw GameException(ErrorCode.WRONG_STATE, "At most ${BoardRules.MAX_ITEMS} items", ErrorReason.ITEM_LIMIT)
        }
        items[item.id] = item
    }

    fun remove(itemId: ItemId) {
        items.remove(itemId) ?: throw GameException(ErrorCode.NOT_FOUND, "No such item")
    }

    fun addCustomQuest(quest: CustomQuest) {
        if (customQuests.size >= QuestCatalog.MAX_CUSTOM_QUESTS) {
            throw GameException(ErrorCode.WRONG_STATE, "At most ${QuestCatalog.MAX_CUSTOM_QUESTS} quests")
        }
        customQuests[quest.id] = quest
    }

    // ---- The search ----

    /** The search starts: every player of the audience gets each catalog quest the host picked. */
    fun startQuests(players: Collection<Player>, kinds: List<QuestKind>, newId: () -> QuestId, nowMillis: Long) {
        for (kind in kinds.distinct()) {
            val spec = QuestCatalog.spec(kind)
            if (spec.team) {
                if (players.count { it.role == Role.SEEKER && it.isPlayingNow } >=
                    2
                ) {
                    teamQuest = TeamQuest(newId(), spec)
                }
                continue
            }
            for (player in players) {
                if (!player.isPlayingNow || !QuestCatalog.isFor(spec.audience, player.role)) continue
                val quest = PlayerQuest(newId(), spec, nowMillis)
                quest.metersBase = player.odometer.distanceMeters
                if (kind == QuestKind.RELOCATE) quest.deadlineMillis = nowMillis + QuestCatalog.RELOCATE_SECONDS * 1000L
                player.quests[quest.id] = quest
            }
        }
    }

    /** A catch was confirmed by [seekerId]: the first one of the round gives «First catch». */
    fun onCatch(seekerId: PlayerId, players: Collection<Player>) {
        if (firstCatchGiven) return
        firstCatchGiven = true
        for (player in players) {
            for (quest in player.quests.values.filter { it.kind == QuestKind.FIRST_CATCH && it.isActive }) {
                if (player.id == seekerId) done(player, quest) else quest.status = QuestStatus.FAILED
            }
        }
    }

    /** [player] is out of the round (caught, eliminated, left): their quests still running fail. */
    fun onOut(player: Player) {
        player.quests.values.filter { it.isActive }.forEach { it.status = QuestStatus.FAILED }
    }

    /** Applies everything that happens by itself as time passes, during the search. */
    fun advance(players: Collection<Player>, context: BoardContext) {
        val playing = players.filter { it.isPlayingNow }
        for (player in playing) {
            val recent = player.track.recentUsableFixes(context.nowMillis)
            val fix = player.track.latestUsable()
            for (quest in player.quests.values) {
                if (!quest.isActive) continue
                judge(player, quest, fix, recent, playing, context)
                if (quest.isActive && quest.deadlineMillis?.let { context.nowMillis > it } == true) {
                    quest.status = QuestStatus.FAILED
                }
            }
            takeItems(player, recent)
        }
        teamQuest?.takeIf { it.status == QuestStatus.ACTIVE }?.let { judgeTeam(it, playing, context) }
    }

    private fun judge(
        player: Player,
        quest: PlayerQuest,
        fix: LocationSample?,
        recent: List<LocationSample>,
        playing: List<Player>,
        context: BoardContext,
    ) {
        val now = context.nowMillis
        val meters = player.odometer.distanceMeters
        fun seekers() = playing.filter { it.role == Role.SEEKER && it.id != player.id }
        fun hotOnSeeker() = seekers().any { context.radarBand(player.id, it.id) >= RadarBand.HOT }
        when (quest.kind) {
            QuestKind.RELOCATE -> {
                val start = player.seekingStartFix ?: return
                quest.progress =
                    Sectors.meters(recent.minOfOrNull { CatchRules.minPossibleDistanceMeters(start, it) } ?: 0.0)
                if (recent.isConfidentlyAway(start, QuestCatalog.RELOCATE_METERS.toDouble())) done(player, quest)
            }

            QuestKind.AFTER_GLOW -> {
                val last = context.lastGlow
                if (last != null && player.glowMarkIndex > quest.lastGlowIndex && !last.isOpenAt(now)) {
                    quest.lastGlowIndex = player.glowMarkIndex
                    quest.anchor = player.glowMark
                    quest.anchorAtMillis = last.endMillis + QuestCatalog.AFTER_GLOW_SECONDS * 1000L
                }
                val mark = quest.anchor ?: return
                val until = quest.anchorAtMillis ?: return
                if (now > until) {
                    quest.anchor = null
                } else if (recent.isConfidentlyAway(mark, QuestCatalog.AFTER_GLOW_METERS.toDouble())) {
                    quest.anchor = null
                    quest.progress++
                    if (quest.progress >= quest.spec.target) done(player, quest)
                }
            }

            QuestKind.FREEZE -> {
                fix ?: return
                val anchor = quest.anchor
                if (anchor == null ||
                    anchor.point.distanceTo(fix.point) > QuestCatalog.FREEZE_RADIUS_METERS + fix.accuracyMeters
                ) {
                    quest.anchor = fix
                    quest.anchorAtMillis = fix.timestampMillis
                    quest.progress = 0
                } else {
                    quest.progress = ((fix.timestampMillis - checkNotNull(quest.anchorAtMillis)) / 1000).toInt()
                    if (quest.progress >= quest.spec.target) done(player, quest)
                }
            }

            QuestKind.SPRINT -> {
                val log = quest.metersLog
                log.addLast(now to meters)
                val windowStart = now - QuestCatalog.SPRINT_SECONDS * 1000L
                while (log.size >= 2 && log[1].first <= windowStart) log.removeFirst()
                quest.progress = Sectors.meters(meters - log.first().second)
                if (quest.progress >= quest.spec.target) done(player, quest)
            }

            QuestKind.SPY -> {
                if (quest.sinceMillis == null && hotOnSeeker()) quest.sinceMillis = now
                quest.sinceMillis?.let { since ->
                    quest.progress = ((now - since) / 1000).toInt()
                    if (quest.progress >= quest.spec.target) done(player, quest)
                }
            }

            QuestKind.MEETING -> {
                val burning = playing.any {
                    it.role == Role.HIDER && it.id != player.id &&
                        context.radarBand(player.id, it.id) == RadarBand.BURNING
                }
                if (burning) {
                    val since = quest.sinceMillis ?: now.also { quest.sinceMillis = it }
                    quest.progress = ((now - since) / 1000).toInt()
                    if (quest.progress >= quest.spec.target) done(player, quest)
                } else {
                    quest.sinceMillis = null
                    quest.progress = 0
                }
            }

            QuestKind.SHADOW -> {
                if (hotOnSeeker()) quest.metersBase = meters
                quest.progress = Sectors.meters(meters - quest.metersBase)
                if (quest.progress >= quest.spec.target) done(player, quest)
            }

            QuestKind.SWEEP -> {
                if (fix == null) {
                    quest.lastSector = null
                    return
                }
                val sector = Sectors.sectorOf(fix.point, context.zoneCenter)
                if (sector == quest.lastSector) {
                    val spent = (quest.sectorMillis[sector] ?: 0L) + (now - checkNotNull(quest.lastSectorAtMillis))
                    quest.sectorMillis[sector] = spent
                    if (spent >= QuestCatalog.SWEEP_SECONDS * 1000L) quest.sectorsVisited += sector
                } else {
                    quest.lastSector = sector
                    quest.sectorMillis[sector] = 0L
                }
                quest.lastSectorAtMillis = now
                quest.progress = quest.sectorsVisited.size
                if (quest.progress >= quest.spec.target) done(player, quest)
            }

            QuestKind.BEATER -> {
                if (firstHotSeeker != null) {
                    if (firstHotSeeker != player.id) quest.status = QuestStatus.FAILED
                    return
                }
                val hot = playing.any {
                    it.role == Role.HIDER && context.radarBand(player.id, it.id) >= RadarBand.HOT
                }
                if (hot) {
                    firstHotSeeker = player.id
                    done(player, quest)
                }
            }

            QuestKind.ON_THE_TRAIL -> {
                val last = context.lastGlow
                if (last != null && last.index > quest.lastGlowIndex && !last.isOpenAt(now)) {
                    quest.lastGlowIndex = last.index
                    quest.deadlineMillis = null
                    quest.anchorAtMillis = last.endMillis + QuestCatalog.ON_THE_TRAIL_SECONDS * 1000L
                }
                val until = quest.anchorAtMillis ?: return
                if (now > until || recent.size < rules.minFixesForDecision) return
                val marks = playing.filter { it.role == Role.HIDER && it.glowMarkIndex == quest.lastGlowIndex }
                    .mapNotNull { it.glowMark }
                if (marks.any { mark ->
                        recent.all {
                            CatchRules.minPossibleDistanceMeters(it, mark) <=
                                QuestCatalog.ON_THE_TRAIL_METERS
                        }
                    }
                ) {
                    done(player, quest)
                }
            }

            QuestKind.SPLIT_UP, QuestKind.FIRST_CATCH, QuestKind.CUSTOM -> Unit
        }
    }

    private fun judgeTeam(quest: TeamQuest, playing: List<Player>, context: BoardContext) {
        val now = context.nowMillis
        val seekers = playing.filter { it.role == Role.SEEKER }
        val sectors = seekers.mapNotNull {
            it.track.latestUsable()?.let { fix -> Sectors.sectorOf(fix.point, context.zoneCenter) }
        }
        val apart = seekers.size >= 2 && sectors.size == seekers.size && sectors.distinct().size == sectors.size
        if (!apart) {
            quest.sinceMillis = null
            quest.progress = 0
            return
        }
        val since = quest.sinceMillis ?: now.also { quest.sinceMillis = it }
        quest.progress = ((now - since) / 1000).toInt()
        if (quest.progress >= quest.spec.target) {
            quest.status = QuestStatus.DONE
            for (seeker in seekers) {
                seeker.sparks += quest.spec.sparks
                seeker.questsDone++
            }
        }
    }

    /** Items reached by GPS: quest points, geo checkpoints and pickups, for [player]'s audience. */
    private fun takeItems(player: Player, recent: List<LocationSample>) {
        if (recent.size < rules.minFixesForDecision) return
        for (item in items.values) {
            if (item.kind == ItemKind.CHECKPOINT_SCAN || !BoardRules.isFor(item.audience, player.role)) continue
            if (player.id in item.takenBy || (item.kind == ItemKind.PICKUP && item.takenBy.isNotEmpty())) continue
            val there = recent.all { it.minDistanceTo(item.point) <= rules.itemReachMeters }
            if (there) take(item, player)
        }
    }

    /** A scan checkpoint: the code, and the fixes must not prove the player far from it. */
    fun scan(player: Player, code: String, nowMillis: Long) {
        val item =
            items.values.firstOrNull { it.kind == ItemKind.CHECKPOINT_SCAN && it.code == code.trim().uppercase() }
                ?: throw GameException(ErrorCode.NOT_FOUND, "No such checkpoint in this game")
        if (!BoardRules.isFor(item.audience, player.role)) throw GameException(ErrorCode.FORBIDDEN, "Not for your team")
        if (player.id in item.takenBy) {
            throw GameException(ErrorCode.WRONG_STATE, "You took this checkpoint already", ErrorReason.CHECKPOINT_TAKEN)
        }
        val fixes = player.track.recentUsableFixes(nowMillis)
        if (fixes.isEmpty()) throw GameException(ErrorCode.NO_LOCATION, "No accurate GPS fix yet, step into the open")
        val closest = fixes.minOf { it.minDistanceTo(item.point) }
        if (closest > rules.catchMaxDistanceMeters) {
            throw GameException(ErrorCode.TOO_FAR, "GPS says you are too far from this checkpoint")
        }
        take(item, player)
    }

    private fun take(item: Item, player: Player) {
        val first = item.takenBy.isEmpty()
        item.takenBy += player.id
        when (item.kind) {
            ItemKind.QUEST_POINT -> {
                player.sparks += item.sparks
                player.questsDone++
            }

            ItemKind.CHECKPOINT_GEO, ItemKind.CHECKPOINT_SCAN -> {
                player.sparks += if (first) item.sparks else BoardRules.laterSparks(item.sparks)
                player.questsDone++
            }

            ItemKind.PICKUP -> {
                val perk = checkNotNull(item.perk)
                player.perksOwned[perk] = (player.perksOwned[perk] ?: 0) + 1
                player.sparks += item.sparks
            }
        }
    }

    // ---- The host's own quests ----

    fun markDone(player: Player, questId: QuestId) {
        val quest = customQuests[questId]?.takeIf { QuestCatalog.isFor(it.audience, player.role) }
            ?: throw GameException(ErrorCode.NOT_FOUND, "No such quest for you", ErrorReason.QUEST_NOT_ACTIVE)
        if (player.id in quest.doneBy || player.id in quest.pending) {
            throw GameException(
                ErrorCode.WRONG_STATE,
                "Already done or waiting for the host",
                ErrorReason.QUEST_NOT_ACTIVE,
            )
        }
        quest.pending += player.id
    }

    fun review(questId: QuestId, player: Player, approved: Boolean) {
        val quest = customQuests[questId] ?: throw GameException(ErrorCode.NOT_FOUND, "No such quest")
        if (!quest.pending.remove(player.id)) {
            throw GameException(
                ErrorCode.WRONG_STATE,
                "Nothing to review for this player",
                ErrorReason.QUEST_NOT_ACTIVE,
            )
        }
        if (approved) {
            quest.doneBy += player.id
            player.sparks += quest.sparks
            player.questsDone++
        }
    }

    // ---- Views ----

    /** The quests as [viewer] sees them: their own, the team's for a seeker, the host's for their audience. */
    fun questViewsFor(viewer: Player, isHost: Boolean): List<QuestView> = buildList {
        for (quest in viewer.quests.values) {
            add(
                QuestView(
                    id = quest.id,
                    kind = quest.kind,
                    sparks = quest.spec.sparks,
                    status = quest.status,
                    audience = quest.spec.audience,
                    progress = quest.progress.coerceAtMost(quest.spec.target),
                    target = quest.spec.target,
                    deadlineMillis = quest.deadlineMillis,
                ),
            )
        }
        teamQuest?.takeIf { viewer.role == Role.SEEKER }?.let { quest ->
            add(
                QuestView(
                    id = quest.id,
                    kind = quest.spec.kind,
                    sparks = quest.spec.sparks,
                    status = quest.status,
                    audience = Audience.SEEKERS,
                    progress = quest.progress.coerceAtMost(quest.spec.target),
                    target = quest.spec.target,
                ),
            )
        }
        for (quest in customQuests.values) {
            val mine = QuestCatalog.isFor(quest.audience, viewer.role)
            if (!mine && !isHost) continue
            add(
                QuestView(
                    id = quest.id,
                    kind = QuestKind.CUSTOM,
                    sparks = quest.sparks,
                    status = when {
                        viewer.id in quest.doneBy -> QuestStatus.DONE
                        viewer.id in quest.pending -> QuestStatus.PENDING_REVIEW
                        else -> QuestStatus.ACTIVE
                    },
                    audience = quest.audience,
                    text = quest.text,
                    progress = if (viewer.id in quest.doneBy) 1 else 0,
                    target = 1,
                    pending = if (isHost) quest.pending.toList() else emptyList(),
                    doneBy = quest.doneBy.toList(),
                ),
            )
        }
    }

    fun debugQuests(players: Collection<Player>): List<DebugQuest> = buildList {
        for (player in players) {
            for (quest in player.quests.values) {
                add(
                    DebugQuest(
                        quest.id,
                        quest.kind,
                        player.id,
                        quest.status,
                        quest.progress,
                        quest.spec.target,
                        quest.spec.sparks,
                    ),
                )
            }
        }
        teamQuest?.let {
            add(DebugQuest(it.id, it.spec.kind, null, it.status, it.progress, it.spec.target, it.spec.sparks))
        }
        for (quest in customQuests.values) {
            add(DebugQuest(quest.id, QuestKind.CUSTOM, null, QuestStatus.ACTIVE, quest.doneBy.size, 1, quest.sparks))
        }
    }

    private fun done(player: Player, quest: PlayerQuest) {
        quest.status = QuestStatus.DONE
        quest.progress = quest.spec.target
        player.sparks += quest.spec.sparks
        player.questsDone++
    }

    /** The smallest real distance from a fix to [point], given the fix's accuracy. */
    private fun LocationSample.minDistanceTo(point: GeoPoint): Double =
        (this.point.distanceTo(point) - accuracyMeters).coerceAtLeast(0.0)

    /** Several fixes in the window, every one of them provably farther than [meters] from [from]. */
    private fun List<LocationSample>.isConfidentlyAway(from: LocationSample, meters: Double): Boolean =
        size >= rules.minFixesForDecision && all { CatchRules.minPossibleDistanceMeters(from, it) >= meters }

    private val PlayerQuest.isActive: Boolean get() = status == QuestStatus.ACTIVE
}
