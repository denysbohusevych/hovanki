package app.hovanki.client.ui.field

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.lab.FieldSession
import app.hovanki.client.lab.FieldStatus
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.field_wrong_hint
import app.hovanki.client.resources.field_wrong_send
import app.hovanki.client.resources.field_wrong_text
import app.hovanki.client.resources.field_wrong_title
import app.hovanki.client.ui.common.Haptic
import app.hovanki.client.ui.common.PopTextField
import app.hovanki.client.ui.common.rememberHaptics
import app.hovanki.device.ShakeDetector
import app.hovanki.device.lab.LabProbes
import app.hovanki.device.lab.LabSensorReading
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** The words a tester may add to a mark, at most. */
private const val MAX_WORDS = 300

/**
 * «Something is wrong» of the field test build (docs/adr/0018-field-test-build.md §5), over every screen: shaking the
 * phone while the field log is on asks [FieldMarks] for the dialog, as the game's menu does; the dialog sends the mark
 * ([FieldSession.somethingWrong]) with a few words if the tester wrote any. Nothing outside the field build: without a
 * field log running there is nothing to mark.
 */
@Composable
fun FieldMarkLayer() {
    val session = koinInject<FieldSession>()
    if (!session.isFieldBuild) return
    val marks = koinInject<FieldMarks>()
    val probes = koinInject<LabProbes>()
    val state by session.state.collectAsStateWithLifecycle()
    val isOpen by marks.isOpen.collectAsStateWithLifecycle()
    val active = state.status == FieldStatus.ON
    val haptics = rememberHaptics()

    // The accelerometer is read only while the log runs, and only to count the jolts of a shake.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val detector = ShakeDetector()
        try {
            probes.sensors().collect { reading ->
                if (reading is LabSensorReading.Motion && detector.add(reading.atMillis, reading.magnitudeG)) {
                    haptics(Haptic.TICK)
                    marks.request()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No sensor: the menu's button still works.
        }
    }
    LaunchedEffect(active) { if (!active) marks.dismiss() }

    if (isOpen && active) {
        var words by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = marks::dismiss,
            title = { Text(stringResource(Res.string.field_wrong_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.field_wrong_text))
                    PopTextField(
                        value = words,
                        onValueChange = { words = it.take(MAX_WORDS) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(Res.string.field_wrong_hint)) },
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        session.somethingWrong(words.trim().ifEmpty { null })
                        marks.dismiss()
                    },
                ) { Text(stringResource(Res.string.field_wrong_send)) }
            },
            dismissButton = {
                TextButton(onClick = marks::dismiss) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}
