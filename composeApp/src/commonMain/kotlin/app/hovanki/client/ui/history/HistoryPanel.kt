package app.hovanki.client.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.history_catches
import app.hovanki.client.resources.history_caught
import app.hovanki.client.resources.history_eliminated
import app.hovanki.client.resources.history_empty
import app.hovanki.client.resources.history_lost
import app.hovanki.client.resources.history_more
import app.hovanki.client.resources.history_open
import app.hovanki.client.resources.history_players
import app.hovanki.client.resources.history_route
import app.hovanki.client.resources.history_won
import app.hovanki.client.resources.ic_arrow_right
import app.hovanki.client.resources.ic_check
import app.hovanki.client.resources.ic_clock
import app.hovanki.client.resources.lobby_hider
import app.hovanki.client.resources.lobby_seeker
import app.hovanki.client.resources.results_route_saved
import app.hovanki.client.resources.results_save_routes_button
import app.hovanki.client.resources.results_save_routes_text
import app.hovanki.client.resources.results_save_routes_title
import app.hovanki.client.resources.route_delete
import app.hovanki.client.resources.route_delete_confirm
import app.hovanki.client.resources.route_delete_warning
import app.hovanki.client.resources.route_empty
import app.hovanki.client.resources.route_expires
import app.hovanki.client.resources.route_moving
import app.hovanki.client.resources.route_title
import app.hovanki.client.resources.routes_explain
import app.hovanki.client.resources.routes_off_confirm
import app.hovanki.client.resources.routes_off_warning
import app.hovanki.client.resources.routes_save
import app.hovanki.client.resources.routes_title
import app.hovanki.client.resources.stats_catches
import app.hovanki.client.resources.stats_distance
import app.hovanki.client.resources.stats_empty
import app.hovanki.client.resources.stats_games
import app.hovanki.client.resources.stats_longest_hide
import app.hovanki.client.resources.stats_speed
import app.hovanki.client.resources.stats_title
import app.hovanki.client.resources.stats_wins
import app.hovanki.client.ui.common.CommandStatus
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.client.ui.common.formatDate
import app.hovanki.client.ui.common.formatDateTime
import app.hovanki.client.ui.theme.Palette
import app.hovanki.client.ui.theme.color
import app.hovanki.client.ui.theme.onColor
import app.hovanki.shared.protocol.GameHistoryEntry
import app.hovanki.shared.protocol.PlayerStats
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.Role
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The profile's statistics (docs/adr/0007-game-history-and-routes.md): only the player's own, from the server. */
@Composable
fun StatsCard(stats: PlayerStats?, modifier: Modifier = Modifier) {
    PopCard(
        modifier = modifier.fillMaxWidth().testTag(TestTags.PROFILE_STATS),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(Res.string.stats_title), style = MaterialTheme.typography.titleMedium)
        if (stats == null || stats.games == 0) {
            SecondaryText(stringResource(Res.string.stats_empty))
            return@PopCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(Res.string.stats_games, stats.games.toString(), Modifier.weight(1f), TestTags.STATS_GAMES)
            StatTile(Res.string.stats_wins, stats.wins.toString(), Modifier.weight(1f))
            StatTile(Res.string.stats_catches, stats.catches.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                Res.string.stats_distance,
                distanceText(stats.distanceMeters),
                Modifier.weight(1f),
                TestTags.STATS_DISTANCE,
            )
            val speed = stats.averageSpeedMetersPerSecond?.let { speedText(it) } ?: "—"
            StatTile(Res.string.stats_speed, speed, Modifier.weight(1f))
            val longestHide = stats.longestHideSeconds?.let { formatCountdown(it * 1000L) } ?: "—"
            StatTile(Res.string.stats_longest_hide, longestHide, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(label: StringResource, value: String, modifier: Modifier = Modifier, tag: String? = null) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
        SecondaryText(stringResource(label))
    }
}

/**
 * «Save my routes» (explicit consent, off by default): what it means is written right under the switch. Turning it off
 * asks first, because every saved route is deleted.
 */
@Composable
fun RoutesCard(viewModel: HistoryViewModel, saveRoutes: Boolean, isBusy: Boolean, modifier: Modifier = Modifier) {
    PopCard(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(Res.string.routes_title), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.routes_save),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = saveRoutes && !viewModel.confirmingRoutesOff,
                onCheckedChange = viewModel::setSaveRoutes,
                enabled = !isBusy,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Palette.Ink,
                    checkedTrackColor = Palette.Lime,
                    checkedBorderColor = Palette.Ink,
                    uncheckedThumbColor = Palette.Ink3,
                    uncheckedTrackColor = Palette.Paper,
                    uncheckedBorderColor = Palette.Ink,
                ),
                modifier = Modifier.testTag(TestTags.PROFILE_SAVE_ROUTES),
            )
        }
        SecondaryText(stringResource(Res.string.routes_explain))
        if (viewModel.confirmingRoutesOff) {
            PopCard(
                modifier = Modifier.fillMaxWidth(),
                color = Palette.Pink,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = stringResource(Res.string.routes_off_warning), style = MaterialTheme.typography.bodyMedium)
                PopButton(
                    text = stringResource(Res.string.routes_off_confirm),
                    onClick = viewModel::confirmRoutesOff,
                    enabled = !isBusy,
                    style = PopStyle.Danger,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_SAVE_ROUTES_OFF_CONFIRM),
                )
                PopButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = viewModel::cancelRoutesOff,
                    style = PopStyle.Quiet,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * On the results screen of a logged-in player: without «save my routes», an offer to turn it on (this game's route is
 * kept too, the game is still on the server); with it, where to find the route. Nothing for a guest ([saveRoutes]
 * null).
 */
@Composable
fun SaveRoutesOffer(saveRoutes: Boolean?, isBusy: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier) {
    when (saveRoutes) {
        null -> Unit

        true -> Row(
            modifier = modifier.fillMaxWidth().testTag(TestTags.RESULTS_ROUTE_SAVED),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(Res.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = stringResource(Res.string.results_route_saved), style = MaterialTheme.typography.bodyMedium)
        }

        false -> PopCard(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.results_save_routes_title),
                style = MaterialTheme.typography.titleMedium,
            )
            SecondaryText(stringResource(Res.string.results_save_routes_text))
            PopButton(
                text = stringResource(Res.string.results_save_routes_button),
                onClick = onSave,
                enabled = !isBusy,
                style = PopStyle.Outline,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.RESULTS_SAVE_ROUTES),
            )
        }
    }
}

/** Opens the history panel. */
@Composable
fun HistoryButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    PopButton(
        text = stringResource(Res.string.history_open),
        onClick = onClick,
        enabled = enabled,
        icon = Res.drawable.ic_clock,
        style = PopStyle.Outline,
        modifier = modifier.fillMaxWidth().testTag(TestTags.PROFILE_HISTORY),
    )
}

/** The player's games, newest first; a game with a saved route opens it. */
@Composable
fun HistoryPanel(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    Panel(
        title = stringResource(Res.string.history_open),
        onClose = viewModel::close,
        modifier = Modifier.testTag(TestTags.HISTORY_PANEL),
    ) {
        ScreenColumn {
            if (state.isLoaded && state.games.isEmpty()) {
                SecondaryText(stringResource(Res.string.history_empty), Modifier.testTag(TestTags.HISTORY_EMPTY))
            }
            for (game in state.games) GameRow(game, onRoute = { viewModel.openRoute(game) }, enabled = !isBusy)
            if (state.hasMore) {
                PopButton(
                    text = stringResource(Res.string.history_more),
                    onClick = viewModel::loadMore,
                    enabled = !isBusy,
                    style = PopStyle.Quiet,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.HISTORY_MORE),
                )
            }
            CommandStatus(isBusy = isBusy, message = message, onDismiss = viewModel::dismissMessage)
        }
    }
}

@Composable
private fun GameRow(game: GameHistoryEntry, onRoute: () -> Unit, enabled: Boolean) {
    PopCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.historyGame(game.gameId)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = formatDateTime(game.finishedAtMillis),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            PopChip(
                text = stringResource(if (game.role == Role.HIDER) Res.string.lobby_hider else Res.string.lobby_seeker),
                color = game.role.color,
                contentColor = game.role.onColor,
                border = Palette.Ink,
            )
        }
        Text(
            text = listOf(
                stringResource(game.outcome()),
                distanceText(game.distanceMeters),
                minutesText((game.finishedAtMillis - game.startedAtMillis) / 1000),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            color = if (game.won) Palette.Ink else Palette.Ink2,
        )
        val details = listOfNotNull(
            stringResource(Res.string.history_players, game.players),
            if (game.role == Role.SEEKER) stringResource(Res.string.history_catches, game.catches) else null,
        )
        SecondaryText(details.joinToString(" · "))
        if (game.hasRoute) {
            PopButton(
                text = stringResource(Res.string.history_route),
                onClick = onRoute,
                enabled = enabled,
                icon = Res.drawable.ic_arrow_right,
                style = PopStyle.Dark,
                height = 44.dp,
                modifier = Modifier.testTag(TestTags.historyRoute(game.gameId)),
            )
        }
    }
}

private fun GameHistoryEntry.outcome(): StringResource = when {
    won -> Res.string.history_won
    status == PlayerStatus.CAUGHT -> Res.string.history_caught
    status == PlayerStatus.ELIMINATED -> Res.string.history_eliminated
    else -> Res.string.history_lost
}

/** A saved route on the map, the game's numbers under it, and deleting it. */
@Composable
fun RoutePanel(viewModel: HistoryViewModel, open: OpenRoute) {
    val message by viewModel.message.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val game = open.game
    Panel(
        title = "${stringResource(Res.string.route_title)} · ${formatDate(game.finishedAtMillis)}",
        onClose = viewModel::closeRoute,
        modifier = Modifier.testTag(TestTags.ROUTE_PANEL),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RouteMap(open.route, Modifier.weight(1f).fillMaxWidth())
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (open.route.points.isEmpty()) SecondaryText(stringResource(Res.string.route_empty))
                PopCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile(Res.string.stats_distance, distanceText(game.distanceMeters), Modifier.weight(1f))
                        StatTile(Res.string.route_moving, minutesText(game.movingSeconds.toLong()), Modifier.weight(1f))
                        val speed = game.averageSpeed()?.let { speedText(it) } ?: "—"
                        StatTile(Res.string.stats_speed, speed, Modifier.weight(1f))
                    }
                }
                SecondaryText(stringResource(Res.string.route_expires, formatDate(open.route.expiresAtMillis)))
                if (viewModel.confirmingRouteDelete) {
                    Text(
                        text = stringResource(Res.string.route_delete_warning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PopButton(
                            text = stringResource(Res.string.action_cancel),
                            onClick = viewModel::cancelDeleteRoute,
                            style = PopStyle.Quiet,
                            modifier = Modifier.weight(1f),
                        )
                        PopButton(
                            text = stringResource(Res.string.route_delete_confirm),
                            onClick = viewModel::deleteRoute,
                            enabled = !isBusy,
                            style = PopStyle.Danger,
                            modifier = Modifier.weight(1f).testTag(TestTags.ROUTE_DELETE_CONFIRM),
                        )
                    }
                } else {
                    PopButton(
                        text = stringResource(Res.string.route_delete),
                        onClick = viewModel::askDeleteRoute,
                        enabled = !isBusy,
                        style = PopStyle.Danger,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.ROUTE_DELETE),
                    )
                }
                CommandStatus(isBusy = isBusy, message = message, onDismiss = viewModel::dismissMessage)
            }
        }
    }
}

/** Distance over the time on the move; null when the player never moved. */
private fun GameHistoryEntry.averageSpeed(): Double? = movingSeconds.takeIf { it > 0 }?.let { distanceMeters / it }
