package app.hovanki.client.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.catchcode.CatchCodeScanner
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.action_confirm
import app.hovanki.client.resources.action_dispute
import app.hovanki.client.resources.action_found
import app.hovanki.client.resources.action_leave
import app.hovanki.client.resources.action_leave_game
import app.hovanki.client.resources.claim_code_label
import app.hovanki.client.resources.claim_disputed_mine
import app.hovanki.client.resources.claim_enter_code
import app.hovanki.client.resources.claim_time_left
import app.hovanki.client.resources.hider_claim_hint
import app.hovanki.client.resources.hider_claim_title
import app.hovanki.client.resources.hider_code_next
import app.hovanki.client.resources.hider_disputed
import app.hovanki.client.resources.hiders_left
import app.hovanki.client.resources.hint_hider_hiding
import app.hovanki.client.resources.hint_hider_seeking
import app.hovanki.client.resources.hint_seeker_hiding
import app.hovanki.client.resources.leave_text
import app.hovanki.client.resources.leave_title
import app.hovanki.client.resources.no_hiders_to_claim
import app.hovanki.client.resources.out_of_zone_warning
import app.hovanki.client.resources.phase_hiding
import app.hovanki.client.resources.phase_seeking
import app.hovanki.client.resources.radar_legend
import app.hovanki.client.resources.role_hider
import app.hovanki.client.resources.role_seeker
import app.hovanki.client.resources.seeker_found_hint
import app.hovanki.client.resources.seeker_found_title
import app.hovanki.client.resources.status_caught
import app.hovanki.client.resources.status_eliminated
import app.hovanki.client.resources.vote_confirm
import app.hovanki.client.resources.vote_done
import app.hovanki.client.resources.vote_reject
import app.hovanki.client.resources.vote_title
import app.hovanki.client.resources.zone_final
import app.hovanki.client.resources.zone_inside
import app.hovanki.client.resources.zone_outside
import app.hovanki.client.resources.zone_radius
import app.hovanki.client.resources.zone_shrinking
import app.hovanki.client.resources.zone_shrinks_in
import app.hovanki.client.session.CatchCode
import app.hovanki.client.ui.common.Banner
import app.hovanki.client.ui.common.LoadingScreen
import app.hovanki.client.ui.common.ScreenColumn
import app.hovanki.client.ui.common.SessionBanners
import app.hovanki.client.ui.common.formatCountdown
import app.hovanki.shared.protocol.CatchId
import app.hovanki.shared.protocol.CatchStatus
import app.hovanki.shared.protocol.GamePhase
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.PlayerStatus
import app.hovanki.shared.protocol.PlayerView
import app.hovanki.shared.protocol.Role
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun GameScreen(viewModel: GameViewModel = koinViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState
    if (state == null) {
        LoadingScreen()
        return
    }
    var showLeaveDialog by rememberSaveable { mutableStateOf(false) }

    ScreenColumn {
        PhaseHeader(state)
        SessionBanners(
            connectionStatus = state.connectionStatus,
            isSharingLocation = state.isSharingLocation,
            error = state.error,
            onDismissError = viewModel::dismissError,
            onLocationPermissionGranted = viewModel::onLocationPermissionGranted,
        )
        state.outOfZoneMillisLeft?.let { millisLeft ->
            Banner(text = stringResource(Res.string.out_of_zone_warning, formatCountdown(millisLeft)), isError = true)
        }
        when (state.myStatus) {
            PlayerStatus.ACTIVE -> Unit
            PlayerStatus.CAUGHT -> Banner(text = stringResource(Res.string.status_caught))
            PlayerStatus.ELIMINATED -> Banner(text = stringResource(Res.string.status_eliminated))
        }

        when (state.myRole) {
            Role.HIDER -> HiderPanel(state, onDispute = viewModel::dispute)

            Role.SEEKER -> SeekerPanel(
                state = state,
                onClaim = viewModel::claimCatch,
                onConfirm = viewModel::confirmCatch,
                onScanned = viewModel::onCodeScanned,
            )
        }
        state.votes.forEach { claim ->
            VoteCard(claim, isBusy = state.isBusy, onVote = { confirm -> viewModel.vote(claim.id, confirm) })
        }

        ZoneRadar(
            zone = state.zone,
            myLocation = state.myLocation,
            markers = state.markers,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        Text(
            text = stringResource(Res.string.radar_legend),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ZoneInfo(state)

        TextButton(onClick = { showLeaveDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.action_leave_game))
        }
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text(stringResource(Res.string.leave_title)) },
            text = { Text(stringResource(Res.string.leave_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::leave) { Text(stringResource(Res.string.action_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PhaseHeader(state: GameUiState) {
    val phaseTitle = if (state.phase == GamePhase.HIDING) Res.string.phase_hiding else Res.string.phase_seeking
    val roleTitle = if (state.myRole == Role.HIDER) Res.string.role_hider else Res.string.role_seeker
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(phaseTitle), style = MaterialTheme.typography.headlineSmall)
            Text(text = stringResource(roleTitle), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(Res.string.hiders_left, state.hidersLeft, state.hidersTotal),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.phaseMillisLeft?.let { millisLeft ->
            Text(
                text = formatCountdown(millisLeft),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ZoneInfo(state: GameUiState) {
    val zone = state.zone
    val change = zone.millisUntilChange
    val schedule = when {
        !state.isZoneRunning -> null
        change == null -> stringResource(Res.string.zone_final)
        zone.isShrinking -> stringResource(Res.string.zone_shrinking, formatCountdown(change))
        else -> stringResource(Res.string.zone_shrinks_in, formatCountdown(change))
    }
    val radius = stringResource(Res.string.zone_radius, zone.current.radiusMeters.roundToInt())
    Text(text = listOfNotNull(radius, schedule).joinToString(", "), style = MaterialTheme.typography.bodyLarge)
    state.metersToZoneBorder?.let { meters ->
        if (meters >= 0) {
            Text(text = stringResource(Res.string.zone_inside, meters.roundToInt()))
        } else {
            Text(
                text = stringResource(Res.string.zone_outside, abs(meters).roundToInt()),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HiderPanel(state: GameUiState, onDispute: (CatchId) -> Unit) {
    val claim = state.claimAgainstMe
    when {
        state.myStatus != PlayerStatus.ACTIVE -> Unit

        claim != null && claim.status == CatchStatus.AWAITING_CODE ->
            ShowCodeCard(claim, state.catchCode, isBusy = state.isBusy, onDispute = { onDispute(claim.id) })

        claim != null -> Banner(text = stringResource(Res.string.hider_disputed))

        state.phase == GamePhase.HIDING -> HintText(stringResource(Res.string.hint_hider_hiding))

        else -> HintText(stringResource(Res.string.hint_hider_seeking))
    }
}

/** The hider's side of a catch: the current code, big enough to be read out or scanned. */
@Composable
private fun ShowCodeCard(claim: ClaimUi, code: CatchCode?, isBusy: Boolean, onDispute: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.hider_claim_title, claim.seekerName),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.hider_claim_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            // TODO(QR): also render CatchCodePayload(gameId, myId, code).encode() as a QR code for the seeker's camera.
            if (code != null) {
                Text(
                    text = code.code,
                    style = MaterialTheme.typography.displayLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                )
                Text(text = stringResource(Res.string.hider_code_next, formatCountdown(code.millisUntilNext)))
            }
            claim.millisLeft?.let { millisLeft ->
                Text(text = stringResource(Res.string.claim_time_left, formatCountdown(millisLeft)))
            }
            OutlinedButton(onClick = onDispute, enabled = !isBusy) {
                Text(stringResource(Res.string.action_dispute))
            }
        }
    }
}

@Composable
private fun SeekerPanel(
    state: GameUiState,
    onClaim: (PlayerId) -> Unit,
    onConfirm: (CatchId, String) -> Unit,
    onScanned: (ClaimUi, String) -> Unit,
) {
    val claim = state.myClaim
    when {
        state.myStatus != PlayerStatus.ACTIVE -> Unit

        state.phase == GamePhase.HIDING -> HintText(stringResource(Res.string.hint_seeker_hiding))

        claim != null && claim.status == CatchStatus.AWAITING_CODE -> EnterCodeCard(
            claim = claim,
            codeDigits = state.codeDigits,
            isBusy = state.isBusy,
            onConfirm = { code -> onConfirm(claim.id, code) },
            onScanned = { text -> onScanned(claim, text) },
        )

        claim != null -> Banner(text = stringResource(Res.string.claim_disputed_mine, claim.hiderName))

        else -> HiderList(state.huntableHiders, isBusy = state.isBusy, onClaim = onClaim)
    }
}

@Composable
private fun HiderList(hiders: List<PlayerView>, isBusy: Boolean, onClaim: (PlayerId) -> Unit) {
    Text(text = stringResource(Res.string.seeker_found_title), style = MaterialTheme.typography.titleMedium)
    if (hiders.isEmpty()) {
        HintText(stringResource(Res.string.no_hiders_to_claim))
        return
    }
    HintText(stringResource(Res.string.seeker_found_hint))
    hiders.forEach { hider ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = hider.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Button(onClick = { onClaim(hider.id) }, enabled = !isBusy) {
                Text(stringResource(Res.string.action_found))
            }
        }
    }
}

/** The seeker's side of a catch: scan the QR code or type the digits the hider shows. */
@Composable
private fun EnterCodeCard(
    claim: ClaimUi,
    codeDigits: Int,
    isBusy: Boolean,
    onConfirm: (String) -> Unit,
    onScanned: (String) -> Unit,
) {
    var code by rememberSaveable(claim.id.value) { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.claim_enter_code, claim.hiderName),
                style = MaterialTheme.typography.titleMedium,
            )
            claim.millisLeft?.let { millisLeft ->
                Text(text = stringResource(Res.string.claim_time_left, formatCountdown(millisLeft)))
            }
            CatchCodeScanner(onScanned = onScanned, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter { it.isDigit() }.take(codeDigits) },
                label = { Text(stringResource(Res.string.claim_code_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onConfirm(code) },
                enabled = code.length == codeDigits && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.action_confirm))
            }
        }
    }
}

@Composable
private fun VoteCard(claim: ClaimUi, isBusy: Boolean, onVote: (confirm: Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(Res.string.vote_title, claim.seekerName, claim.hiderName),
                style = MaterialTheme.typography.titleMedium,
            )
            claim.millisLeft?.let { millisLeft ->
                Text(text = stringResource(Res.string.claim_time_left, formatCountdown(millisLeft)))
            }
            if (claim.canVote) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onVote(true) }, enabled = !isBusy) {
                        Text(stringResource(Res.string.vote_confirm))
                    }
                    OutlinedButton(onClick = { onVote(false) }, enabled = !isBusy) {
                        Text(stringResource(Res.string.vote_reject))
                    }
                }
            } else {
                HintText(stringResource(Res.string.vote_done))
            }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}
