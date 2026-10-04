package app.hovanki.client.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.pause_host_resumes
import app.hovanki.client.resources.pause_resume
import app.hovanki.client.resources.pause_text
import app.hovanki.client.resources.pause_title
import app.hovanki.client.resources.sos_call_emergency
import app.hovanki.client.resources.sos_dialog_text
import app.hovanki.client.resources.sos_dialog_title
import app.hovanki.client.resources.sos_distance
import app.hovanki.client.resources.sos_end
import app.hovanki.client.resources.sos_hold
import app.hovanki.client.resources.sos_im_ok
import app.hovanki.client.resources.sos_mine
import app.hovanki.client.resources.sos_needs_help
import app.hovanki.client.resources.sos_no_place
import app.hovanki.client.resources.sos_title
import app.hovanki.client.ui.common.Haptic
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.common.rememberHaptics
import app.hovanki.client.ui.theme.Palette
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The round on pause (docs/adr/0019-pause-and-sos.md): a card over the map, which stays visible for the SOS markers.
 * With an SOS: who calls for help and how far, the emergency number, «I’m OK» for the caller and «Remove SOS» for the
 * host; the host lets the round go on once no SOS is left.
 */
@Composable
fun PauseCard(state: GameUiState, onResume: () -> Unit, onEndSos: (SosUi) -> Unit, modifier: Modifier = Modifier) {
    val sos = state.sos.isNotEmpty()
    PopCard(
        modifier = modifier.fillMaxWidth().testTag(TestTags.GAME_PAUSE),
        border = if (sos) Palette.Sos else Palette.Ink,
        borderWidth = if (sos) 3.dp else 2.dp,
        shadow = 4.dp,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(if (sos) Res.string.sos_title else Res.string.pause_title),
            style = MaterialTheme.typography.headlineSmall,
            color = if (sos) Palette.Sos else Palette.Ink,
            fontWeight = FontWeight.Bold,
        )
        for (call in state.sos) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (call.isMe) {
                        stringResource(Res.string.sos_mine)
                    } else {
                        stringResource(Res.string.sos_needs_help, call.name)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!call.isMe) {
                    val distance = call.metersAway?.let { stringResource(Res.string.sos_distance, it.roundToInt()) }
                    Text(
                        text = distance ?: if (call.point == null) stringResource(Res.string.sos_no_place) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Ink2,
                    )
                }
                if (call.isMe || state.canPause) {
                    PopButton(
                        text = stringResource(if (call.isMe) Res.string.sos_im_ok else Res.string.sos_end),
                        onClick = { onEndSos(call) },
                        enabled = !state.isBusy,
                        style = PopStyle.Outline,
                        height = 44.dp,
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.SOS_END),
                    )
                }
            }
        }
        if (sos) EmergencyCallButton()
        Text(text = stringResource(Res.string.pause_text), style = MaterialTheme.typography.bodyMedium)
        if (state.canPause) {
            PopButton(
                text = stringResource(Res.string.pause_resume),
                onClick = onResume,
                enabled = !sos && !state.isBusy,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.PAUSE_RESUME),
            )
        } else if (!sos) {
            Text(
                text = stringResource(Res.string.pause_host_resumes),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Ink2,
            )
        }
    }
}

/** The emergency number on the phone's dialer: the player still taps «call» there themselves. */
@Composable
private fun EmergencyCallButton() {
    val uriHandler = LocalUriHandler.current
    PopButton(
        text = stringResource(Res.string.sos_call_emergency, EMERGENCY_NUMBER),
        onClick = {
            // A phone without a dialer (a tablet) has nothing to open it: nothing happens then.
            runCatching { uriHandler.openUri("tel:$EMERGENCY_NUMBER") }
        },
        style = PopStyle.Sos,
        modifier = Modifier.fillMaxWidth().testTag(TestTags.SOS_EMERGENCY),
    )
}

/**
 * «SOS» from the round's menu: what it does, the emergency number, and the call for help only after holding the button
 * ([HOLD_MILLIS]), so a pocket or a stray tap never sends one.
 */
@Composable
fun SosDialog(onSend: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sos_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.sos_dialog_text, EMERGENCY_NUMBER))
                HoldToSend(text = stringResource(Res.string.sos_hold), onDone = onSend)
                EmergencyCallButton()
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** A button that fires only after being held [HOLD_MILLIS]; a screen reader's tap fires it at once. */
@Composable
private fun HoldToSend(text: String, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Palette.Sos.copy(alpha = 0.18f))
            .border(2.dp, Palette.Ink, shape)
            .semantics {
                onClick(label = text) {
                    done()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        haptics(Haptic.TICK)
                        val hold = scope.launch {
                            progress.animateTo(1f, tween(HOLD_MILLIS, easing = LinearEasing))
                            haptics(Haptic.SUCCESS)
                            done()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            hold.cancel()
                            scope.launch { progress.animateTo(0f, tween(RELEASE_MILLIS)) }
                        }
                    },
                )
            }
            .testTag(TestTags.SOS_HOLD),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(Palette.Sos),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (progress.value > 0.5f) Color.White else Palette.Ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** Europe's and most GSM phones' emergency number: it reaches the local one almost everywhere. */
private const val EMERGENCY_NUMBER = "112"
private const val HOLD_MILLIS = 2_000
private const val RELEASE_MILLIS = 200
