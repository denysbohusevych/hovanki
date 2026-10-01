package app.hovanki.client.ui.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.lab.FieldSession
import app.hovanki.client.lab.FieldStatus
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.field_broken_battery
import app.hovanki.client.resources.field_broken_catch
import app.hovanki.client.resources.field_broken_connection
import app.hovanki.client.resources.field_broken_crash
import app.hovanki.client.resources.field_broken_gps
import app.hovanki.client.resources.field_broken_other
import app.hovanki.client.resources.field_broken_radar
import app.hovanki.client.resources.field_carry_bag
import app.hovanki.client.resources.field_carry_hand
import app.hovanki.client.resources.field_carry_mixed
import app.hovanki.client.resources.field_carry_pocket
import app.hovanki.client.resources.field_survey_broken
import app.hovanki.client.resources.field_survey_carry
import app.hovanki.client.resources.field_survey_rating
import app.hovanki.client.resources.field_survey_send
import app.hovanki.client.resources.field_survey_text_hint
import app.hovanki.client.resources.field_survey_thanks
import app.hovanki.client.resources.field_survey_title
import app.hovanki.client.ui.common.CapsText
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.PopChip
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** What can break, by the id that goes into the log (`SurveyFields.BROKEN`), and the words the tester reads. */
private val BROKEN: List<Pair<String, StringResource>> = listOf(
    "crash" to Res.string.field_broken_crash,
    "gps" to Res.string.field_broken_gps,
    "radar" to Res.string.field_broken_radar,
    "catch" to Res.string.field_broken_catch,
    "connection" to Res.string.field_broken_connection,
    "battery" to Res.string.field_broken_battery,
    "other" to Res.string.field_broken_other,
)

/** Where the phone was (`SurveyFields.CARRY`). */
private val CARRY: List<Pair<String, StringResource>> = listOf(
    "hand" to Res.string.field_carry_hand,
    "pocket" to Res.string.field_carry_pocket,
    "bag" to Res.string.field_carry_bag,
    "mixed" to Res.string.field_carry_mixed,
)

private const val MAX_WORDS = 500

/**
 * The three questions after the game (docs/adr/0018-field-test-build.md §5) on the results screen of the field test
 * build: how it was (1–5), what broke (from a list and in words), where the phone was. Goes into the log with
 * [FieldSession.survey] and up to the server at once. Nothing in other builds, and nothing when the phone's field log
 * isn't running (not in the game's run: nobody to write it to).
 */
@Composable
fun FieldSurveyCard(modifier: Modifier = Modifier) {
    val session = koinInject<FieldSession>()
    if (!session.isFieldBuild) return
    val state by session.state.collectAsStateWithLifecycle()
    var sent by rememberSaveable { mutableStateOf(false) }
    if (state.status != FieldStatus.ON && !sent) return

    var rating by remember { mutableStateOf<Int?>(null) }
    var broken by remember { mutableStateOf(emptyList<String>()) }
    var carry by remember { mutableStateOf<String?>(null) }
    var words by remember { mutableStateOf("") }

    PopCard(modifier = modifier.fillMaxWidth()) {
        CapsText(stringResource(Res.string.field_survey_title))
        if (sent) {
            Text(text = stringResource(Res.string.field_survey_thanks), style = MaterialTheme.typography.titleMedium)
            return@PopCard
        }
        Question(stringResource(Res.string.field_survey_rating))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in 1..5) {
                Choice(text = value.toString(), selected = rating == value, onClick = { rating = value })
            }
        }
        Question(stringResource(Res.string.field_survey_broken))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in BROKEN) {
                Choice(
                    text = stringResource(label),
                    selected = id in broken,
                    onClick = { broken = if (id in broken) broken - id else broken + id },
                )
            }
        }
        PopTextField(
            value = words,
            onValueChange = { words = it.take(MAX_WORDS) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.field_survey_text_hint)) },
            maxLines = 4,
        )
        Question(stringResource(Res.string.field_survey_carry))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in CARRY) {
                Choice(text = stringResource(label), selected = carry == id, onClick = { carry = id })
            }
        }
        PopButton(
            text = stringResource(Res.string.field_survey_send),
            onClick = {
                // False: the log stopped on the way (the phone left the run); there is nothing to write to.
                session.survey(rating, broken, words.trim().ifEmpty { null }, carry)
                sent = true
            },
            enabled = rating != null,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun Question(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    PopChip(
        text = text,
        color = if (selected) Palette.Ink else Palette.Paper,
        contentColor = if (selected) Palette.Lime else Palette.Ink,
        border = Palette.Ink,
        onClick = onClick,
    )
}
