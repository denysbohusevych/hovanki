package app.hovanki.client.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.action_cancel
import app.hovanki.client.resources.city_bila_tserkva
import app.hovanki.client.resources.city_cherkasy
import app.hovanki.client.resources.city_chernihiv
import app.hovanki.client.resources.city_chernivtsi
import app.hovanki.client.resources.city_dnipro
import app.hovanki.client.resources.city_ivano_frankivsk
import app.hovanki.client.resources.city_kharkiv
import app.hovanki.client.resources.city_kherson
import app.hovanki.client.resources.city_khmelnytskyi
import app.hovanki.client.resources.city_kremenchuk
import app.hovanki.client.resources.city_kropyvnytskyi
import app.hovanki.client.resources.city_kryvyi_rih
import app.hovanki.client.resources.city_kyiv
import app.hovanki.client.resources.city_lutsk
import app.hovanki.client.resources.city_lviv
import app.hovanki.client.resources.city_mykolaiv
import app.hovanki.client.resources.city_none
import app.hovanki.client.resources.city_odesa
import app.hovanki.client.resources.city_picker_title
import app.hovanki.client.resources.city_poltava
import app.hovanki.client.resources.city_rivne
import app.hovanki.client.resources.city_sumy
import app.hovanki.client.resources.city_ternopil
import app.hovanki.client.resources.city_uzhhorod
import app.hovanki.client.resources.city_vinnytsia
import app.hovanki.client.resources.city_zaporizhzhia
import app.hovanki.client.resources.city_zhytomyr
import app.hovanki.shared.rules.Cities
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The city of the city leaderboard (docs/adr/0022-city-leaderboard.md): the player picks it from the fixed list
 * ([Cities.IDS], the biggest first, so Kyiv is on top), or none. Never guessed from the position.
 */
@Composable
fun CityPickerDialog(selected: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val cities = Cities.IDS.mapNotNull { id -> cityName(id)?.let { id to stringResource(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.city_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
                    .testTag(TestTags.CITY_PICKER),
            ) {
                CityOption(stringResource(Res.string.city_none), selected == null, TestTags.cityOption(null)) {
                    onPick(null)
                }
                cities.forEach { (id, name) ->
                    CityOption(name, selected == id, TestTags.cityOption(id)) { onPick(id) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun CityOption(name: String, isSelected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(text = name, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The name of the city [id] in the app's language; null for an id this app doesn't know (a newer server's). */
fun cityName(id: String): StringResource? = when (id) {
    "kyiv" -> Res.string.city_kyiv
    "kharkiv" -> Res.string.city_kharkiv
    "odesa" -> Res.string.city_odesa
    "dnipro" -> Res.string.city_dnipro
    "lviv" -> Res.string.city_lviv
    "zaporizhzhia" -> Res.string.city_zaporizhzhia
    "kryvyi_rih" -> Res.string.city_kryvyi_rih
    "mykolaiv" -> Res.string.city_mykolaiv
    "vinnytsia" -> Res.string.city_vinnytsia
    "poltava" -> Res.string.city_poltava
    "chernihiv" -> Res.string.city_chernihiv
    "cherkasy" -> Res.string.city_cherkasy
    "khmelnytskyi" -> Res.string.city_khmelnytskyi
    "zhytomyr" -> Res.string.city_zhytomyr
    "sumy" -> Res.string.city_sumy
    "rivne" -> Res.string.city_rivne
    "ivano_frankivsk" -> Res.string.city_ivano_frankivsk
    "kropyvnytskyi" -> Res.string.city_kropyvnytskyi
    "ternopil" -> Res.string.city_ternopil
    "lutsk" -> Res.string.city_lutsk
    "kherson" -> Res.string.city_kherson
    "bila_tserkva" -> Res.string.city_bila_tserkva
    "kremenchuk" -> Res.string.city_kremenchuk
    "uzhhorod" -> Res.string.city_uzhhorod
    "chernivtsi" -> Res.string.city_chernivtsi
    else -> null
}
