package app.hovanki.client.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.location.rememberLocationConsentNeeded
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.consent_allow
import app.hovanki.client.resources.consent_later
import app.hovanki.client.resources.consent_pocket
import app.hovanki.client.resources.consent_text
import app.hovanki.client.resources.consent_title
import app.hovanki.client.resources.consent_when
import app.hovanki.client.resources.consent_who
import app.hovanki.client.resources.ic_clock
import app.hovanki.client.resources.ic_friends
import app.hovanki.client.resources.ic_location
import app.hovanki.client.resources.ic_pocket
import app.hovanki.client.ui.theme.Motion
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The consent screen for location (GDPR, docs/design.md «Экраны»): before the system dialog, the player learns who
 * sees their location, when it is deleted and that it is sent from the pocket too. Shown only while the player has
 * not decided yet ([rememberLocationConsentNeeded]); afterwards the requests go straight to the system.
 */
@Stable
class LocationConsentState {
    /** The system request waiting for the player's «Allow»; null while the screen is closed. */
    var pending: (() -> Unit)? by mutableStateOf(null)
        internal set

    internal fun ask(request: () -> Unit) {
        pending = request
    }
}

/** Provided by the app's root together with [LocationConsentLayer]; without it requests skip the consent screen. */
val LocalLocationConsent = staticCompositionLocalOf<LocationConsentState?> { null }

/**
 * Like [rememberLocationPermissionRequester], with the consent screen first when the player has not decided on
 * location yet. «Not now» closes the screen and does not call [onResult].
 */
@Composable
fun rememberLocationRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val request = rememberLocationPermissionRequester(onResult)
    val consentNeeded = rememberLocationConsentNeeded()
    val consent = LocalLocationConsent.current
    return remember(request, consentNeeded, consent) {
        { if (consent != null && consentNeeded()) consent.ask(request) else request() }
    }
}

/** The consent screen over everything while a request waits for it. */
@Composable
fun LocationConsentLayer(state: LocationConsentState) {
    val pending = state.pending
    // Kept while the screen slides out after the answer.
    var shown by remember { mutableStateOf(pending) }
    if (pending != null) shown = pending
    AnimatedVisibility(
        visible = pending != null,
        enter = slideInVertically(tween(Motion.SCREEN_MILLIS)) { it },
        exit = slideOutVertically(tween(Motion.SCREEN_MILLIS)) { it } + fadeOut(),
    ) {
        LocationConsentScreen(
            onAllow = {
                state.pending = null
                shown?.invoke()
            },
            onLater = { state.pending = null },
        )
    }
    SystemBackHandler(enabled = pending != null) { state.pending = null }
}

@Composable
private fun LocationConsentScreen(onAllow: () -> Unit, onLater: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .appSafeDrawingPadding()
            .testTag(TestTags.LOCATION_CONSENT),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PopSurface(
                shape = CircleShape,
                color = Palette.Lime,
                shadow = 4.dp,
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(84.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_location),
                    contentDescription = null,
                    tint = Palette.Ink,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(text = stringResource(Res.string.consent_title), style = MaterialTheme.typography.displaySmall)
            Text(text = stringResource(Res.string.consent_text), style = MaterialTheme.typography.bodyLarge)
            ConsentPoint(Res.drawable.ic_friends, Palette.Violet, Color.White, stringResource(Res.string.consent_who))
            ConsentPoint(Res.drawable.ic_clock, Palette.Lime, Palette.Ink, stringResource(Res.string.consent_when))
            ConsentPoint(Res.drawable.ic_pocket, Palette.Orange, Palette.Ink, stringResource(Res.string.consent_pocket))
            Spacer(Modifier.weight(1f))
            PopButton(
                text = stringResource(Res.string.consent_allow),
                onClick = onAllow,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.LOCATION_CONSENT_ALLOW),
            )
            PopButton(
                text = stringResource(Res.string.consent_later),
                onClick = onLater,
                style = PopStyle.Quiet,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.LOCATION_CONSENT_LATER),
            )
        }
    }
}

@Composable
private fun ConsentPoint(icon: DrawableResource, color: Color, iconColor: Color, text: String) {
    PopCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(color, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}
