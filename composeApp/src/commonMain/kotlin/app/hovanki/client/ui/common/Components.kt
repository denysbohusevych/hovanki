package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_allow
import app.hovanki.client.resources.action_back
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.action_leave_game
import app.hovanki.client.resources.action_leave_other_game
import app.hovanki.client.resources.connection_reconnecting
import app.hovanki.client.resources.error_network
import app.hovanki.client.resources.error_no_location
import app.hovanki.client.resources.error_not_found
import app.hovanki.client.resources.error_saved_game_finished
import app.hovanki.client.resources.error_saved_game_gone
import app.hovanki.client.resources.error_session_lost
import app.hovanki.client.resources.error_too_far
import app.hovanki.client.resources.home_connecting
import app.hovanki.client.resources.home_locating
import app.hovanki.client.resources.ic_back
import app.hovanki.client.resources.ic_warning
import app.hovanki.client.resources.location_not_shared
import app.hovanki.client.resources.problem_code_missing
import app.hovanki.client.resources.problem_location_denied
import app.hovanki.client.resources.problem_name_missing
import app.hovanki.client.resources.problem_no_location_fix
import app.hovanki.client.resources.resuming_game
import app.hovanki.client.resources.working
import app.hovanki.client.session.ConnectionStatus
import app.hovanki.client.session.SessionError
import app.hovanki.client.ui.theme.Palette
import app.hovanki.shared.protocol.ErrorCode
import app.hovanki.shared.protocol.ErrorReason
import org.jetbrains.compose.resources.painterResource
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
        CircularProgressIndicator(color = Palette.Ink, trackColor = Palette.Green, strokeWidth = 5.dp)
    }
}

/**
 * The app was started again during a game and checks with the server whether it is still on (a moment, or longer
 * without a connection). The player may give up and leave the game.
 */
@Composable
fun ResumingScreen(isReconnecting: Boolean, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp).testTag(TestTags.RESUMING),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Palette.Ink, trackColor = Palette.Green, strokeWidth = 5.dp)
        Text(
            text = stringResource(Res.string.resuming_game),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (isReconnecting) {
            Text(
                text = stringResource(Res.string.connection_reconnecting),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TestTags.BANNER_RECONNECTING),
            )
        }
        PopButton(
            text = stringResource(Res.string.action_leave_game),
            onClick = onLeave,
            style = PopStyle.Outline,
            modifier = Modifier.testTag(TestTags.RESUMING_LEAVE),
        )
    }
}

/**
 * A highlighted message with an optional action button on the right: white and outlined, pink for errors. The first
 * text inside is the message (UI automation reads it through the banner's tag).
 */
@Composable
fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionModifier: Modifier = Modifier,
) {
    PopSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isError) Palette.Pink else Palette.Paper,
        contentColor = if (isError) Color.White else Palette.Ink,
        borderWidth = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (isError) {
                Icon(
                    painter = painterResource(Res.drawable.ic_warning),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (actionLabel != null) {
                TextButton(
                    onClick = onAction,
                    modifier = actionModifier,
                    colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** A highlighted [notice] with an OK button; nothing without one. */
@Composable
fun NoticeBanner(notice: Notice?, onDismiss: () -> Unit, modifier: Modifier = Modifier, isError: Boolean = true) {
    if (notice == null) return
    Banner(
        text = notice.text(),
        modifier = modifier,
        isError = isError,
        actionLabel = stringResource(Res.string.action_dismiss),
        onAction = onDismiss,
    )
}

/**
 * An account or social command under way ([isBusy], [TestTags.ACCOUNT_BUSY]) and how it went ([message]: an error is
 * tagged [errorTag], news [infoTag]).
 */
@Composable
fun CommandStatus(
    isBusy: Boolean,
    message: FormMessage?,
    onDismiss: () -> Unit,
    errorTag: String = TestTags.ACCOUNT_ERROR,
    infoTag: String? = null,
) {
    if (isBusy) {
        BusyRow(text = stringResource(Res.string.working), modifier = Modifier.testTag(TestTags.ACCOUNT_BUSY))
    }
    if (message != null) {
        val tag = if (message.isError) errorTag else infoTag
        NoticeBanner(
            notice = message.notice,
            onDismiss = onDismiss,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            isError = message.isError,
        )
    }
}

/** A small spinner next to what is under way. */
@Composable
fun BusyRow(text: String, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = Palette.Ink,
            trackColor = Palette.Green,
            strokeWidth = 3.dp,
        )
        Text(text = text)
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
    val requestLocation = rememberLocationRequest { granted ->
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

/**
 * Creating or joining a game: what is under way, a problem found on the phone, and the server's refusal
 * ([sessionError]), with the tags the device orchestrator watches.
 */
@Composable
fun StartStatusBanners(
    status: StartStatus,
    sessionError: SessionError?,
    onDismiss: () -> Unit,
    onLeaveOtherGame: (() -> Unit)? = null,
) {
    status.activity?.let { activity ->
        BusyRow(
            text = when (activity) {
                StartActivity.LOCATING -> stringResource(Res.string.home_locating)
                StartActivity.CONNECTING -> stringResource(Res.string.home_connecting)
            },
            modifier = Modifier.testTag(TestTags.HOME_BUSY),
        )
    }
    status.problem?.let { problem ->
        Banner(
            text = problem.describe(),
            modifier = Modifier.testTag(TestTags.HOME_PROBLEM),
            isError = true,
            actionLabel = stringResource(Res.string.action_dismiss),
            onAction = onDismiss,
        )
    }
    sessionError?.let { error ->
        // Still playing a round of another game: the way on is leaving it, which the player has to choose.
        val inAnotherGame = error is SessionError.Rejected && error.reason == ErrorReason.IN_ANOTHER_GAME
        val leaveOther = onLeaveOtherGame?.takeIf { inAnotherGame }
        Banner(
            text = error.describe(),
            modifier = Modifier.testTag(TestTags.BANNER_ERROR),
            isError = true,
            actionLabel = stringResource(
                if (leaveOther != null) Res.string.action_leave_other_game else Res.string.action_dismiss,
            ),
            onAction = leaveOther ?: onDismiss,
        )
    }
}

@Composable
fun StartProblem.describe(): String = when (this) {
    StartProblem.NAME_MISSING -> stringResource(Res.string.problem_name_missing)
    StartProblem.CODE_MISSING -> stringResource(Res.string.problem_code_missing)
    StartProblem.LOCATION_DENIED -> stringResource(Res.string.problem_location_denied)
    StartProblem.NO_LOCATION_FIX -> stringResource(Res.string.problem_no_location_fix)
}

@Composable
fun SessionError.describe(): String = when (this) {
    // The exact cause when the server sent one (rate limit, logged out, not friends...).
    is SessionError.Rejected -> reason?.let {
        reasonNotice(it, retryAfterSeconds, untilMillis = untilMillis).text()
    } ?: when (code) {
        ErrorCode.NOT_FOUND -> stringResource(Res.string.error_not_found)

        ErrorCode.TOO_FAR -> stringResource(Res.string.error_too_far)

        ErrorCode.NO_LOCATION -> stringResource(Res.string.error_no_location)

        // The server's own words, e.g. "Wrong code, attempts left: 3".
        else -> message
    }

    is SessionError.Network -> stringResource(Res.string.error_network)

    SessionError.SessionLost -> stringResource(Res.string.error_session_lost)

    SessionError.SavedGameFinished -> stringResource(Res.string.error_saved_game_finished)

    SessionError.SavedGameGone -> stringResource(Res.string.error_saved_game_gone)
}

/** A password: hidden, one line; the keyboard's action key runs [onImeAction]. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    onImeAction: () -> Unit = {},
) {
    PopTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = if (supportingText != null) {
            { Text(supportingText) }
        } else {
            null
        },
        isError = isError,
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Back to the previous step of a form, like the system back action. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(Res.drawable.ic_back),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.action_back))
    }
}

/** Version, build number and commit, small, so testers can name the build. */
@Composable
fun BuildLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(TestTags.HOME_BUILD),
    )
}

/** A secondary text under a title, a field or a button. */
@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** "m:ss", rounded up so a countdown shows 0:00 only when the time is really over. */
fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 999) / 1000
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return "${totalSeconds / 60}:$seconds"
}

/** "m:ss" of a time that passed (rounded down: 0:59 is not a minute yet); "h:mm:ss" from an hour on. */
fun formatElapsed(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = totalSeconds / 60
    if (minutes < 60) return "$minutes:$seconds"
    return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$seconds"
}
