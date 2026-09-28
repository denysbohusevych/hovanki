package app.hovanki.client.ui.play

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.big_game_cancel_signup
import app.hovanki.client.resources.big_game_friends
import app.hovanki.client.resources.big_game_full
import app.hovanki.client.resources.big_game_join
import app.hovanki.client.resources.big_game_sign_up
import app.hovanki.client.resources.big_game_signed_up
import app.hovanki.client.resources.big_game_signed_up_note
import app.hovanki.client.resources.big_game_status_lobby
import app.hovanki.client.resources.big_game_status_running
import app.hovanki.client.resources.big_game_zone
import app.hovanki.client.resources.decimal_separator
import app.hovanki.client.resources.lobby_chip_time
import app.hovanki.client.resources.unit_ha
import app.hovanki.client.resources.unit_km2
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.PopSurface
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.common.formatDateTimeIn
import app.hovanki.client.ui.history.oneDecimal
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.BigGameCard
import app.hovanki.shared.protocol.BigGameStatus
import app.hovanki.shared.rules.areaSquareMeters
import org.jetbrains.compose.resources.stringResource

/**
 * A big game on «Play» (docs/adr/0010-big-games.md): the title, when (the place's time), how large the zone is, how many
 * signed up and which friends, and the one thing to do: sign up, come into the open lobby, or take the sign-up back.
 */
@Composable
fun BigGameCardView(
    game: BigGameCard,
    isBusy: Boolean,
    onSignUp: () -> Unit,
    onCancel: () -> Unit,
    onJoin: () -> Unit,
) {
    val id = game.id.value
    PopSurface(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.bigGame(id)),
        shape = RoundedCornerShape(20.dp),
        color = if (game.canJoin) Palette.Lime else Palette.Paper,
        shadow = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = game.title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = formatDateTimeIn(game.startsAtMillis, game.timeZone),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (game.status) {
                    BigGameStatus.LOBBY -> PopChip(
                        stringResource(Res.string.big_game_status_lobby),
                        color = Palette.Violet,
                    )

                    BigGameStatus.RUNNING -> PopChip(
                        stringResource(Res.string.big_game_status_running),
                        color = Palette.Orange,
                    )

                    else -> Unit
                }
                PopChip(
                    stringResource(Res.string.big_game_zone, areaText(game.zone.areaSquareMeters())),
                    color = Palette.Sand,
                    contentColor = Palette.Ink,
                )
                PopChip(
                    stringResource(Res.string.lobby_chip_time, game.setup.hidingMinutes, game.setup.seekingMinutes),
                    color = Palette.Sand,
                    contentColor = Palette.Ink,
                )
            }
            Text(
                text = stringResource(Res.string.big_game_signed_up, game.signedUp, game.playerLimit),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (game.friends.isNotEmpty()) {
                SecondaryText(stringResource(Res.string.big_game_friends, game.friends.joinToString { it.nickname }))
            }
            when {
                game.canJoin -> PopButton(
                    text = stringResource(Res.string.big_game_join),
                    onClick = onJoin,
                    enabled = !isBusy,
                    style = PopStyle.Dark,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.bigGameJoin(id)),
                )

                game.signedUpByMe -> {
                    SecondaryText(stringResource(Res.string.big_game_signed_up_note))
                    PopButton(
                        text = stringResource(Res.string.big_game_cancel_signup),
                        onClick = onCancel,
                        enabled = !isBusy && game.status != BigGameStatus.RUNNING,
                        style = PopStyle.Quiet,
                        height = 44.dp,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.bigGameCancel(id)),
                    )
                }

                game.status == BigGameStatus.RUNNING -> Unit

                game.signedUp >= game.playerLimit -> SecondaryText(stringResource(Res.string.big_game_full))

                else -> PopButton(
                    text = stringResource(Res.string.big_game_sign_up),
                    onClick = onSignUp,
                    enabled = !isBusy,
                    style = PopStyle.Hider,
                    modifier = Modifier.fillMaxWidth().testTag(TestTags.bigGameSignUp(id)),
                )
            }
        }
    }
}

/** Under a square kilometer in hectares, then square kilometers, one decimal: "12,5 га", "1,3 км²". */
@Composable
private fun areaText(squareMeters: Double): String {
    val separator = stringResource(Res.string.decimal_separator)
    return if (squareMeters < 1_000_000) {
        stringResource(Res.string.unit_ha, oneDecimal(squareMeters / 10_000, separator))
    } else {
        stringResource(Res.string.unit_km2, oneDecimal(squareMeters / 1_000_000, separator))
    }
}
