package app.hovanki.client.ui.results

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.award_first_catch
import app.hovanki.client.resources.award_hunter
import app.hovanki.client.resources.award_last_standing
import app.hovanki.client.resources.award_marathon
import app.hovanki.client.resources.award_sparks
import app.hovanki.client.resources.award_sparks_value
import app.hovanki.client.resources.award_survivor
import app.hovanki.client.resources.award_whole_search
import app.hovanki.client.resources.awards_title
import app.hovanki.client.resources.ic_home
import app.hovanki.client.resources.ic_pause
import app.hovanki.client.resources.ic_play
import app.hovanki.client.resources.ic_trophy
import app.hovanki.client.resources.lobby_you
import app.hovanki.client.resources.phase_hiding
import app.hovanki.client.resources.phase_seeking
import app.hovanki.client.resources.replay_loading
import app.hovanki.client.resources.replay_pause
import app.hovanki.client.resources.replay_play
import app.hovanki.client.resources.replay_time
import app.hovanki.client.resources.replay_title
import app.hovanki.client.resources.results_back
import app.hovanki.client.resources.results_caught
import app.hovanki.client.resources.results_eliminated
import app.hovanki.client.resources.results_finds
import app.hovanki.client.resources.results_hiders_win
import app.hovanki.client.resources.results_into_search
import app.hovanki.client.resources.results_seekers
import app.hovanki.client.resources.results_seekers_win
import app.hovanki.client.resources.results_survived
import app.hovanki.client.resources.results_title
import app.hovanki.client.resources.results_to_the_end
import app.hovanki.client.resources.results_you_caught
import app.hovanki.client.resources.results_you_eliminated
import app.hovanki.client.resources.results_you_survived
import app.hovanki.client.resources.sparks_count
import app.hovanki.client.session.Award
import app.hovanki.client.session.AwardKind
import app.hovanki.client.session.Replay
import app.hovanki.client.session.awards
import app.hovanki.client.session.catchesBy
import app.hovanki.client.session.hiderTally
import app.hovanki.client.session.searchMillisAt
import app.hovanki.client.ui.chat.ChatIconButton
import app.hovanki.client.ui.chat.ChatPanel
import app.hovanki.client.ui.chat.ChatViewModel
import app.hovanki.client.ui.common.Avatar
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.PlayerAccount
import app.hovanki.client.ui.common.PlayerAccountBadge
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopIconButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.formatElapsed
import app.hovanki.client.ui.common.rememberReduceMotion
import app.hovanki.client.ui.game.ZoneTimeline
import app.hovanki.client.ui.history.SaveRoutesOffer
import app.hovanki.client.ui.history.distanceText
import app.hovanki.client.ui.theme.Hovanki
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.GameSnapshot
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.UserId
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/**
 * Final standings of the finished game ([snapshot] no longer changes) on lime (docs/design.md, «Итоги»): when each
 * hider was out and who found them, awards, the replay of everybody's way once the tracks are loaded, players to add as
 * friends, and the chat: the game keeps polling for it until the player goes back to the start.
 */
@Composable
fun ResultsScreen(
    snapshot: GameSnapshot,
    viewModel: ResultsViewModel = koinViewModel(),
    chat: ChatViewModel = koinViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val saveRoutes by viewModel.saveRoutes.collectAsStateWithLifecycle()
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val streetZone by viewModel.streetZone.collectAsStateWithLifecycle()
    if (chatState.isOpen) {
        ChatPanel(chat)
        return
    }
    val me = snapshot.me
    val hiders = snapshot.players.filter { it.role == Role.HIDER }
    val survivors = hiders.filter { it.status == PlayerStatus.ACTIVE }
    val list = PlayerList(
        snapshot = snapshot,
        myId = me.playerId,
        accounts = accounts,
        isBusy = isBusy,
        onAddFriend = viewModel::addFriend,
    )
    val reduceMotion = rememberReduceMotion()
    // The title pops in once, when the results open.
    val pop = remember { Animatable(if (reduceMotion) 1f else 0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, Motion.pop()) }
    val caught = hiders.filter { it.status == PlayerStatus.CAUGHT }
    val eliminated = hiders.filter { it.status == PlayerStatus.ELIMINATED }
    // The numbers of everybody; a big game's list below has only the player and their friends.
    val tally = snapshot.hiderTally()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenColumn(modifier = Modifier.weight(1f).testTag(TestTags.RESULTS_SCREEN)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f))
                ChatIconButton(unread = chatState.unread, onClick = chat::open, size = 44.dp)
            }
            PopCard(
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Lime,
                borderWidth = 2.5.dp,
                shadow = 6.dp,
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapsText(stringResource(Res.string.results_title))
                Text(
                    text = stringResource(
                        if (tally.survived == 0) Res.string.results_seekers_win else Res.string.results_hiders_win,
                    ),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
                if (me.role == Role.HIDER) {
                    val personal = when (me.status) {
                        PlayerStatus.ACTIVE -> Res.string.results_you_survived
                        PlayerStatus.CAUGHT -> Res.string.results_you_caught
                        PlayerStatus.ELIMINATED -> Res.string.results_you_eliminated
                    }
                    Text(text = stringResource(personal), style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResultCount(Res.string.results_caught, tally.caught, reduceMotion, Modifier.weight(1f))
                    ResultCount(Res.string.results_survived, tally.survived, reduceMotion, Modifier.weight(1f))
                    if (tally.eliminated > 0) {
                        ResultCount(Res.string.results_eliminated, tally.eliminated, reduceMotion, Modifier.weight(1f))
                    }
                }
            }

            PopCard(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val groups = listOf(
                    Res.string.results_survived to survivors,
                    Res.string.results_caught to caught,
                    Res.string.results_eliminated to eliminated,
                    Res.string.results_seekers to snapshot.players.filter { it.role == Role.SEEKER },
                ).filter { it.second.isNotEmpty() }
                groups.forEachIndexed { index, (title, players) ->
                    // The groups drop in one after another.
                    val drop = remember { Animatable(if (reduceMotion) 1f else 0f) }
                    LaunchedEffect(Unit) {
                        delay(GROUP_DELAY_MILLIS * (index + 1))
                        drop.animateTo(1f, Motion.pop())
                    }
                    Column(
                        modifier = Modifier.graphicsLayer {
                            alpha = drop.value.coerceIn(0f, 1f)
                            translationY = (1f - drop.value) * -GROUP_DROP_DP.dp.toPx()
                        },
                    ) {
                        if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                        PlayerGroup(title, players, list)
                    }
                }
            }
            // The results keep polling for the chat: new snapshots, the same final state.
            val awards = remember(tracks, snapshot.finishedAtMillis) { snapshot.awards(tracks) }
            if (awards.isNotEmpty()) Awards(awards, snapshot, reduceMotion)
            // Keyed on what makes the replay, not on every poll: the slider stays where the player left it.
            val replay = remember(tracks, snapshot.finishedAtMillis) { Replay.of(snapshot, tracks) }
            if (tracks == null || replay != null) {
                val hidingSeconds = snapshot.settings.hidingSeconds
                ReplayCard(
                    replay = replay,
                    zone = ZoneTimeline(snapshot.settings.zone, snapshot.zoneStartedAtMillis, streetZone),
                    hidingStartMillis = snapshot.zoneStartedAtMillis?.let { it - hidingSeconds * 1000L }
                        ?: replay?.startMillis,
                    reduceMotion = reduceMotion,
                )
            }
            SaveRoutesOffer(saveRoutes = saveRoutes, isBusy = isBusy, onSave = viewModel::turnOnSaveRoutes)
            CommandStatus(
                isBusy = false,
                message = message,
                onDismiss = viewModel::dismissMessage,
                errorTag = TestTags.SOCIAL_ERROR,
            )
        }
        PopButton(
            text = stringResource(Res.string.results_back),
            onClick = viewModel::leave,
            style = PopStyle.Dark,
            height = 58.dp,
            icon = Res.drawable.ic_home,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                .testTag(TestTags.RESULTS_BACK),
        )
    }
}

/** A number on the results card that counts up when the results open. */
@Composable
private fun ResultCount(label: StringResource, count: Int, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val shown = remember { Animatable(if (reduceMotion) count.toFloat() else 0f) }
    LaunchedEffect(count) { shown.animateTo(count.toFloat(), tween(COUNT_UP_MILLIS, easing = FastOutSlowInEasing)) }
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Ink).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = shown.value.roundToInt().toString(),
            style = Hovanki.text.timer,
            color = Palette.Lime,
        )
        CapsText(stringResource(label), color = Color.White)
    }
}

private const val COUNT_UP_MILLIS = 600
private const val GROUP_DELAY_MILLIS = 80L
private const val GROUP_DROP_DP = 24

/** How the players are shown in each group: the viewer marked, when they were out, accounts to add as friends. */
private class PlayerList(
    val snapshot: GameSnapshot,
    val myId: PlayerId,
    val accounts: Map<PlayerId, PlayerAccount>,
    val isBusy: Boolean,
    val onAddFriend: (UserId) -> Unit,
)

@Composable
private fun PlayerGroup(title: StringResource, players: List<PlayerView>, list: PlayerList) {
    val youTag = stringResource(Res.string.lobby_you)
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        CapsText(stringResource(title), color = Palette.Ink2, modifier = Modifier.padding(vertical = 4.dp))
        players.forEach { player ->
            Row(
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(name = player.name, color = player.role.color, contentColor = player.role.onColor, size = 32.dp)
                Column(modifier = Modifier.weight(1f)) {
                    val name = if (player.id == list.myId) "${player.name} · $youTag" else player.name
                    Text(text = name, style = MaterialTheme.typography.titleSmall)
                    PlayerOutcome(player, list.snapshot)
                    list.accounts[player.id]?.let { account ->
                        PlayerAccountBadge(
                            playerId = player.id,
                            account = account,
                            isBusy = list.isBusy,
                            onAddFriend = { account.userId?.let(list.onAddFriend) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Under a name: a hider's time into the search when they were out (and who found them), «until the end» for the ones
 * nobody found, how many a seeker found.
 */
@Composable
private fun PlayerOutcome(player: PlayerView, snapshot: GameSnapshot) {
    val text = when {
        player.role == Role.SEEKER -> stringResource(Res.string.results_finds, snapshot.catchesBy(player.id))

        player.status == PlayerStatus.ACTIVE -> stringResource(Res.string.results_to_the_end)

        else -> player.outAtMillis?.let(snapshot::searchMillisAt)?.let {
            stringResource(Res.string.results_into_search, formatElapsed(it))
        }
    } ?: return
    val seeker = player.caughtBy?.let { id -> snapshot.players.firstOrNull { it.id == id } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SecondaryText(text)
        if (seeker != null) {
            PopChip(text = seeker.name, color = Palette.Orange, contentColor = Palette.Ink, border = Palette.Ink)
        }
        // The sparks left at the end (docs/adr/0013-quests-sparks-and-sensors.md), in a game that had them.
        val sparks = player.sparks
        if (sparks != null && sparks > 0) {
            PopChip(
                text = stringResource(Res.string.sparks_count, sparks),
                color = Palette.Lime,
                contentColor = Palette.Ink,
                border = Palette.Ink,
                modifier = if (player.id ==
                    snapshot.me.playerId
                ) {
                    Modifier.testTag(TestTags.RESULTS_SPARKS)
                } else {
                    Modifier
                },
            )
        }
    }
}

/** Badges in pink (docs/design.md, «Итоги»): they drop in one after another. */
@Composable
private fun Awards(awards: List<Award>, snapshot: GameSnapshot, reduceMotion: Boolean) {
    val names = snapshot.players.associate { it.id to it.name }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(TestTags.RESULTS_AWARDS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsText(stringResource(Res.string.awards_title), color = Palette.Ink2)
        awards.forEachIndexed { index, award ->
            val drop = remember(award) { Animatable(if (reduceMotion) 1f else 0f) }
            LaunchedEffect(award) {
                delay(AWARD_DELAY_MILLIS * index)
                drop.animateTo(1f, Motion.pop())
            }
            PopCard(
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    alpha = drop.value.coerceIn(0f, 1f)
                    translationY = (1f - drop.value) * -GROUP_DROP_DP.dp.toPx()
                },
                color = Palette.Pink,
                shadow = 3.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_trophy),
                        contentDescription = null,
                        tint = Palette.Ink,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(award.kind.title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${names[award.playerId].orEmpty()} · ${awardDetail(award)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private val AwardKind.title: StringResource
    get() = when (this) {
        AwardKind.FIRST_CATCH -> Res.string.award_first_catch
        AwardKind.HUNTER -> Res.string.award_hunter
        AwardKind.SURVIVOR -> Res.string.award_survivor
        AwardKind.LAST_STANDING -> Res.string.award_last_standing
        AwardKind.MARATHON -> Res.string.award_marathon
        AwardKind.SPARKS -> Res.string.award_sparks
    }

@Composable
private fun awardDetail(award: Award): String = when (award.kind) {
    AwardKind.FIRST_CATCH, AwardKind.LAST_STANDING ->
        stringResource(Res.string.results_into_search, formatElapsed(award.value))

    AwardKind.HUNTER -> stringResource(Res.string.results_finds, award.value.toInt())

    AwardKind.SURVIVOR -> stringResource(Res.string.award_whole_search, formatElapsed(award.value))

    AwardKind.MARATHON -> distanceText(award.value.toDouble())

    AwardKind.SPARKS -> stringResource(Res.string.award_sparks_value, award.value.toInt())
}

private const val AWARD_DELAY_MILLIS = 80L

/**
 * The replay (docs/design.md, «Итоги»): the map with everybody's way and a slider over the round; on the results and
 * for a recording from the history (docs/adr/0011-spectators-and-recordings.md). The ways draw themselves in for
 * 1.5 s when they arrive, the slider starts at the end; «play» runs the round again. [replay] null: still loading.
 * [hidingStartMillis]: when hiding started, for the time under the slider.
 */
@Composable
internal fun ReplayCard(
    replay: Replay?,
    zone: ZoneTimeline,
    hidingStartMillis: Long?,
    reduceMotion: Boolean,
    mapHeight: Dp = 300.dp,
) {
    PopCard(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag(TestTags.REPLAY),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsText(stringResource(Res.string.replay_title), color = Palette.Ink2)
        if (replay == null) {
            SecondaryText(stringResource(Res.string.replay_loading))
            return@PopCard
        }
        var at by remember(replay) { mutableStateOf(replay.endMillis) }
        var playing by remember(replay) { mutableStateOf(false) }
        val drawIn = remember(replay) { Animatable(if (reduceMotion) 1f else 0f) }
        LaunchedEffect(replay) { drawIn.animateTo(1f, tween(DRAW_IN_MILLIS, easing = FastOutSlowInEasing)) }
        LaunchedEffect(playing) {
            if (!playing) return@LaunchedEffect
            if (at >= replay.endMillis) at = replay.startMillis
            val step = replay.durationMillis * PLAY_FRAME_MILLIS / PLAY_MILLIS
            while (at < replay.endMillis) {
                delay(PLAY_FRAME_MILLIS)
                at = (at + step).coerceAtMost(replay.endMillis)
            }
            playing = false
        }
        val shownAt = replay.startMillis + ((at - replay.startMillis) * drawIn.value).toLong()
        ReplayMap(
            replay = replay,
            zone = zone,
            atMillis = shownAt,
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight)
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, Palette.Ink, RoundedCornerShape(14.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PopIconButton(
                icon = if (playing) Res.drawable.ic_pause else Res.drawable.ic_play,
                contentDescription = stringResource(if (playing) Res.string.replay_pause else Res.string.replay_play),
                onClick = { playing = !playing },
                style = PopStyle.Dark,
                size = 44.dp,
                iconSize = 20.dp,
            )
            val description = stringResource(Res.string.replay_time)
            Slider(
                value = (at - replay.startMillis).toFloat(),
                onValueChange = {
                    playing = false
                    at = replay.startMillis + it.toLong()
                },
                valueRange = 0f..replay.durationMillis.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Palette.Ink,
                    activeTrackColor = Palette.Ink,
                    inactiveTrackColor = Palette.Line,
                ),
                modifier = Modifier.weight(1f).semantics { contentDescription = description }
                    .testTag(TestTags.REPLAY_SLIDER),
            )
        }
        CapsText(replayTime(hidingStartMillis, zone.startedAtMillis, shownAt), color = Palette.Ink2)
    }
}

/** «Hiding 2:10» before the search ([searchFromMillis]), «Seeking 12:34» after it started. */
@Composable
private fun replayTime(hidingStartMillis: Long?, searchFromMillis: Long?, atMillis: Long): String =
    if (searchFromMillis == null || atMillis < searchFromMillis) {
        val hidingFrom = hidingStartMillis ?: atMillis
        "${stringResource(Res.string.phase_hiding)} ${formatElapsed(atMillis - hidingFrom)}"
    } else {
        "${stringResource(Res.string.phase_seeking)} ${formatElapsed(atMillis - searchFromMillis)}"
    }

private const val DRAW_IN_MILLIS = 1_500

/** «Play» runs the whole round in this long, a frame every [PLAY_FRAME_MILLIS]. */
private const val PLAY_MILLIS = 12_000L
private const val PLAY_FRAME_MILLIS = 50L
