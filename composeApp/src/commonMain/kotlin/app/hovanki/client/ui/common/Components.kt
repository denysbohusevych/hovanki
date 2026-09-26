package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_allow
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.connection_reconnecting
import app.hovanki.client.resources.error_network
import app.hovanki.client.resources.error_no_location
import app.hovanki.client.resources.error_not_found
import app.hovanki.client.resources.error_session_lost
import app.hovanki.client.resources.error_too_far
import app.hovanki.client.resources.location_not_shared
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.SessionError
import app.hovanki.shared.protocol.ErrorCode
import org.jetbrains.compose.resources.stringResource

/** Scrollable screen body with the app's standard paddings. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().testTag(TestTags.LOADING), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** A highlighted message with an optional action button on the right. */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = if (isError) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    Card(modifier = modifier.fillMaxWidth(), colors = colors) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/** Connection, location and error notices shared by the lobby and the game screens. */
@Composable
fun SessionBanners(
    connectionStatus: ConnectionStatus,
    isSharingLocation: Boolean,
    error: SessionError?,
    onDismissError: () -> Unit,
    onLocationPermissionGranted: () -> Unit,
) {
    val requestLocation = rememberLocationPermissionRequester { granted ->
        if (granted) onLocationPermissionGranted()
    }
    if (connectionStatus == ConnectionStatus.RECONNECTING) {
        Banner(
            text = stringResource(Res.string.connection_reconnecting),
            modifier = Modifier.testTag(TestTags.BANNER_RECONNECTING),
        )
    }
    if (!isSharingLocation) {
        Banner(
            text = stringResource(Res.string.location_not_shared),
            modifier = Modifier.testTag(TestTags.BANNER_NO_LOCATION),
            isError = true,
            actionLabel = stringResource(Res.string.action_allow),
            onAction = requestLocation,
        )
    }
    if (error != null) {
        Banner(
            text = error.describe(),
            modifier = Modifier.testTag(TestTags.BANNER_ERROR),
            isError = true,
            actionLabel = stringResource(Res.string.action_dismiss),
            onAction = onDismissError,
        )
    }
}

@Composable
fun SessionError.describe(): String = when (this) {
    is SessionError.Rejected -> when (code) {
        ErrorCode.NOT_FOUND -> stringResource(Res.string.error_not_found)

        ErrorCode.TOO_FAR -> stringResource(Res.string.error_too_far)

        ErrorCode.NO_LOCATION -> stringResource(Res.string.error_no_location)

        // The server's own words, e.g. "Wrong code, attempts left: 3".
        else -> message
    }

    is SessionError.Network -> stringResource(Res.string.error_network)

    SessionError.SessionLost -> stringResource(Res.string.error_session_lost)
}

/** "m:ss", rounded up so a countdown shows 0:00 only when the time is really over. */
fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "${totalSeconds / 60}:$seconds"
}
