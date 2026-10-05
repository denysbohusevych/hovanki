package app.hovanki.client.ui.spectator

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_back
import app.hovanki.client.resources.connection_reconnecting
import app.hovanki.client.resources.ic_exit
import app.hovanki.client.resources.lobby_hider
import app.hovanki.client.resources.lobby_players
import app.hovanki.client.resources.lobby_seeker
import app.hovanki.client.resources.phase_hiding
import app.hovanki.client.resources.phase_seeking
import app.hovanki.client.resources.spectator_caught
import app.hovanki.client.resources.spectator_delay
import app.hovanki.client.resources.spectator_ended
import app.hovanki.client.resources.spectator_finished
import app.hovanki.client.resources.spectator_leave
import app.hovanki.client.resources.spectator_live
import app.hovanki.client.resources.spectator_lobby
import app.hovanki.client.resources.spectator_out
import app.hovanki.client.resources.spectator_title
import app.hovanki.client.session.Replay
import app.hovanki.client.session.ReplayLine
import app.hovanki.client.spectator.SpectatorState
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.SpectatorsChip
import app.hovanki.client.ui.common.SystemBackHandler
import app.hovanki.client.ui.common.collectScreenState
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.game.ZoneTimeline
import app.hovanki.client.ui.lobby.spectatorDelayText
import app.hovanki.client.ui.results.ReplayMap
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import app.hovanki.shared.protocol.SpectatedPlayer
import app.hovanki.shared.protocol.SpectatorSnapshot
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Watching an open game (docs/adr/0011-spectators-and-recordings.md): the zone and everybody on the map with the last
 * minute of their way, the game's delay behind; the phase and its time then; who plays what and who is out. Nothing is
 * shared from this phone. Back or «Stop watching» ends it.
 */
@Composable
fun SpectatorScreen(viewModel: SpectatorViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectScreenState()
    SpectatorContent(state, viewModel::onEvent)
}

@Composable
private fun SpectatorContent(state: SpectatorState, onEvent: (SpectatorEvent) -> Unit) {
    val leave = { onEvent(SpectatorEvent.Leave) }
    SystemBackHandler(enabled = true, onBack = leave)
    val snapshot = state.snapshot
    if (snapshot == null) {
        LoadingScreen()
        return
    }
    if (state.ended) {
        Ended(onBack = leave)
        return
    }
    // The moment shown moves on between the polls, for the zone and the clock.
    var shownAt by remember { mutableLongStateOf(state.shownAtMillis() ?: snapshot.atMillis) }
    LaunchedEffect(state) {
        while (true) {
            shownAt = state.shownAtMillis() ?: snapshot.atMillis
            delay(TICK_MILLIS)
        }
    }
    val replay = remember(snapshot) { snapshot.replay() }
    val zone = remember(snapshot.settings.zone, snapshot.zoneStartedAtMillis, state.streetZone) {
        ZoneTimeline(snapshot.settings.zone, snapshot.zoneStartedAtMillis, state.streetZone)
    }

    Column(modifier = Modifier.fillMaxSize().testTag(TestTags.SPECTATOR_SCREEN)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.spectator_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                PopButton(
                    text = stringResource(Res.string.spectator_leave),
                    onClick = leave,
                    style = PopStyle.Outline,
                    icon = Res.drawable.ic_exit,
                    height = 40.dp,
                    modifier = Modifier.testTag(TestTags.SPECTATOR_LEAVE),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PopChip(
                    text = phaseText(snapshot, shownAt),
                    modifier = Modifier.testTag(TestTags.SPECTATOR_PHASE),
                )
                PopChip(
                    text = if (snapshot.delaySeconds > 0) {
                        stringResource(Res.string.spectator_delay, spectatorDelayText(snapshot.delaySeconds))
                    } else {
                        stringResource(Res.string.spectator_live)
                    },
                    color = Palette.Lime,
                    contentColor = Palette.Ink,
                    border = Palette.Ink,
                    modifier = Modifier.testTag(TestTags.SPECTATOR_DELAY),
                )
                if (snapshot.spectators > 0) SpectatorsChip(snapshot.spectators)
            }
            if (state.isReconnecting) Banner(text = stringResource(Res.string.connection_reconnecting))
        }
        ReplayMap(
            replay = replay,
            zone = zone,
            atMillis = shownAt,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(2.dp, Palette.Ink, RoundedCornerShape(18.dp)),
        )
        Players(snapshot, modifier = Modifier.heightIn(max = PLAYERS_MAX_HEIGHT).padding(16.dp))
    }
}

/** The game's phase at the moment shown, with its time left then; the lobby and the end in words. */
@Composable
private fun phaseText(snapshot: SpectatorSnapshot, shownAt: Long): String {
    val left = snapshot.phaseEndsAtMillis?.let { formatCountdown(it - shownAt) }
    return when (snapshot.phase) {
        GamePhase.LOBBY -> stringResource(Res.string.spectator_lobby)
        GamePhase.HIDING -> listOfNotNull(stringResource(Res.string.phase_hiding), left).joinToString(" · ")
        GamePhase.SEEKING -> listOfNotNull(stringResource(Res.string.phase_seeking), left).joinToString(" · ")
        GamePhase.FINISHED -> stringResource(Res.string.spectator_finished)
    }
}

/** Seekers first, then the hiders: name, how it went for them so far, and the role in its color. */
@Composable
private fun Players(snapshot: SpectatorSnapshot, modifier: Modifier = Modifier) {
    PopCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = stringResource(Res.string.lobby_players, snapshot.players.size),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            val players = snapshot.players.sortedBy { if (it.role == Role.SEEKER) 0 else 1 }
            players.forEachIndexed { index, player ->
                if (index > 0) HorizontalDivider(color = Palette.Line, thickness = 1.5.dp)
                PlayerRow(player)
            }
        }
    }
}

@Composable
private fun PlayerRow(player: SpectatedPlayer) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = player.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        when (player.status) {
            PlayerStatus.ACTIVE -> Unit
            PlayerStatus.CAUGHT -> SecondaryText(stringResource(Res.string.spectator_caught))
            PlayerStatus.ELIMINATED -> SecondaryText(stringResource(Res.string.spectator_out))
        }
        PopChip(
            text = stringResource(if (player.role == Role.HIDER) Res.string.lobby_hider else Res.string.lobby_seeker),
            color = player.role.color,
            contentColor = player.role.onColor,
            border = Palette.Ink,
        )
    }
}

/** The server ended the watching: the game is over and gone, or the host closed it. */
@Composable
private fun Ended(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        PopCard(
            modifier = Modifier.fillMaxWidth().testTag(TestTags.SPECTATOR_ENDED),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.spectator_ended),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            PopButton(
                text = stringResource(Res.string.action_back),
                onClick = onBack,
                style = PopStyle.Dark,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Everybody's last minute as the replay's lines: the replay's map draws the spectators' view. A player without a way
 * in that minute stands where they were last.
 */
private fun SpectatorSnapshot.replay(): Replay {
    val lines = players.mapNotNull { player ->
        val points = player.trail.ifEmpty { listOfNotNull(player.location) }
        if (points.isEmpty()) return@mapNotNull null
        val view = PlayerView(
            id = player.id,
            name = player.name,
            role = player.role,
            status = player.status,
            outAtMillis = player.outAtMillis,
            caughtBy = player.caughtBy,
        )
        ReplayLine(view, points)
    }
    return Replay(lines, startMillis = atMillis - TRAIL_MILLIS, endMillis = atMillis)
}

private const val TICK_MILLIS = 1_000L
private const val TRAIL_MILLIS = 60_000L
private val PLAYERS_MAX_HEIGHT = 260.dp
