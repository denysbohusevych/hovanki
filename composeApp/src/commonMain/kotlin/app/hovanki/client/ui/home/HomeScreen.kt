package app.hovanki.client.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.location.rememberLocationPermissionRequester
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_dismiss
import app.hovanki.client.resources.app_name
import app.hovanki.client.resources.home_code_label
import app.hovanki.client.resources.home_connecting
import app.hovanki.client.resources.home_create
import app.hovanki.client.resources.home_create_hint
import app.hovanki.client.resources.home_join
import app.hovanki.client.resources.home_locating
import app.hovanki.client.resources.home_name_label
import app.hovanki.client.resources.home_or_join
import app.hovanki.client.resources.home_server_hint
import app.hovanki.client.resources.home_server_label
import app.hovanki.client.resources.home_tagline
import app.hovanki.client.resources.problem_code_missing
import app.hovanki.client.resources.problem_location_denied
import app.hovanki.client.resources.problem_name_missing
import app.hovanki.client.resources.problem_no_location_fix
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.describe
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeScreen(viewModel: HomeViewModel = koinViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val sessionError by viewModel.sessionError.collectAsStateWithLifecycle()
    val requestLocationThenCreate = rememberLocationPermissionRequester { granted -> viewModel.createGame(granted) }
    // Asked before joining as well, so location is already on when the round starts.
    val requestLocationThenJoin = rememberLocationPermissionRequester { viewModel.joinGame() }
    val isBusy = status.activity != null

    ScreenColumn(modifier = Modifier.testTag(TestTags.HOME_SCREEN)) {
        Text(
            text = stringResource(Res.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = stringResource(Res.string.home_tagline), style = MaterialTheme.typography.bodyLarge)

        OutlinedTextField(
            value = viewModel.playerName,
            onValueChange = viewModel::onPlayerNameChange,
            label = { Text(stringResource(Res.string.home_name_label)) },
            singleLine = true,
            enabled = !isBusy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_NAME),
        )
        Button(
            onClick = { if (viewModel.canCreateGame()) requestLocationThenCreate() },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_CREATE),
        ) {
            Text(stringResource(Res.string.home_create))
        }
        Text(
            text = stringResource(Res.string.home_create_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text(text = stringResource(Res.string.home_or_join), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = viewModel.joinCode,
            onValueChange = viewModel::onJoinCodeChange,
            label = { Text(stringResource(Res.string.home_code_label)) },
            singleLine = true,
            enabled = !isBusy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN_CODE),
        )
        OutlinedButton(
            onClick = { if (viewModel.canJoinGame()) requestLocationThenJoin() },
            enabled = !isBusy,
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_JOIN),
        ) {
            Text(stringResource(Res.string.home_join))
        }

        status.activity?.let { activity ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag(TestTags.HOME_BUSY),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = when (activity) {
                        HomeActivity.LOCATING -> stringResource(Res.string.home_locating)
                        HomeActivity.CONNECTING -> stringResource(Res.string.home_connecting)
                    },
                )
            }
        }
        status.problem?.let { problem ->
            Banner(
                text = problem.describe(),
                modifier = Modifier.testTag(TestTags.HOME_PROBLEM),
                isError = true,
                actionLabel = stringResource(Res.string.action_dismiss),
                onAction = viewModel::dismissProblems,
            )
        }
        sessionError?.let { error ->
            Banner(
                text = error.describe(),
                modifier = Modifier.testTag(TestTags.BANNER_ERROR),
                isError = true,
                actionLabel = stringResource(Res.string.action_dismiss),
                onAction = viewModel::dismissProblems,
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = viewModel.serverAddress,
            onValueChange = viewModel::onServerAddressChange,
            label = { Text(stringResource(Res.string.home_server_label)) },
            supportingText = { Text(stringResource(Res.string.home_server_hint)) },
            singleLine = true,
            enabled = !isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_SERVER),
        )
    }
}

@Composable
private fun HomeProblem.describe(): String = when (this) {
    HomeProblem.NAME_MISSING -> stringResource(Res.string.problem_name_missing)
    HomeProblem.CODE_MISSING -> stringResource(Res.string.problem_code_missing)
    HomeProblem.LOCATION_DENIED -> stringResource(Res.string.problem_location_denied)
    HomeProblem.NO_LOCATION_FIX -> stringResource(Res.string.problem_no_location_fix)
}
