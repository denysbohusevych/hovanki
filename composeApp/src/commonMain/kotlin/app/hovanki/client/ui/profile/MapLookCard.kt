package app.hovanki.client.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hovanki.client.automation.TestTags
import app.hovanki.client.map.MapSettings
import app.hovanki.client.map.MapTheme
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.map_buildings_3d
import app.hovanki.client.resources.map_buildings_3d_hint
import app.hovanki.client.resources.map_look_title
import app.hovanki.client.resources.map_relief
import app.hovanki.client.resources.map_relief_hint
import app.hovanki.client.resources.map_theme_auto
import app.hovanki.client.resources.map_theme_auto_hint
import app.hovanki.client.resources.map_theme_dark
import app.hovanki.client.resources.map_theme_light
import app.hovanki.client.resources.map_theme_minimal
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.client.ui.lobby.ShapeButton
import app.hovanki.client.ui.lobby.SwitchRow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/** «Map» in the profile: how every map on this phone looks (docs/adr/0025-map-styles-and-height.md). */
@Composable
internal fun MapLookCard() {
    PopCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.MAP_LOOK),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SecondaryText(stringResource(Res.string.map_look_title))
        MapLookOptions()
    }
}

/**
 * The map's theme (light, dark, minimal or by the sun), the 3D houses and the relief; every map follows at once. In the
 * profile and in the round's menu, for guests too: the choice is the phone's, not the account's.
 */
@Composable
internal fun MapLookOptions(modifier: Modifier = Modifier) {
    val settings = koinInject<MapSettings>()
    val look by settings.look.collectAsStateWithLifecycle()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in MapTheme.entries.chunked(2)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (theme in row) {
                    ShapeButton(
                        text = stringResource(theme.title),
                        selected = look.theme == theme,
                        onClick = { settings.update(look.copy(theme = theme)) },
                        modifier = Modifier.weight(1f).testTag(TestTags.mapTheme(theme.name)),
                    )
                }
            }
        }
        if (look.theme == MapTheme.AUTO) SecondaryText(stringResource(Res.string.map_theme_auto_hint))
        SwitchRow(
            text = stringResource(Res.string.map_buildings_3d),
            checked = look.buildings3d,
            onCheckedChange = { settings.update(look.copy(buildings3d = it)) },
            tag = TestTags.MAP_BUILDINGS_3D,
        )
        if (look.buildings3d) SecondaryText(stringResource(Res.string.map_buildings_3d_hint))
        SwitchRow(
            text = stringResource(Res.string.map_relief),
            checked = look.relief,
            onCheckedChange = { settings.update(look.copy(relief = it)) },
            tag = TestTags.MAP_RELIEF,
        )
        if (look.relief) SecondaryText(stringResource(Res.string.map_relief_hint))
    }
}

private val MapTheme.title: StringResource
    get() = when (this) {
        MapTheme.LIGHT -> Res.string.map_theme_light
        MapTheme.DARK -> Res.string.map_theme_dark
        MapTheme.MINIMAL -> Res.string.map_theme_minimal
        MapTheme.AUTO -> Res.string.map_theme_auto
    }
