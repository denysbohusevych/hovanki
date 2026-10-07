package app.hovanki.client.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.entitlement_cosmetics
import app.hovanki.client.resources.entitlement_map_styles
import app.hovanki.client.resources.entitlement_premium_host
import app.hovanki.client.resources.profile_extras
import app.hovanki.client.ui.common.PopCard
import app.hovanki.client.ui.common.SecondaryText
import app.hovanki.shared.protocol.Entitlement
import app.hovanki.shared.protocol.UserProfile
import app.hovanki.shared.protocol.knownEntitlements
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The account's paid extras (docs/adr/0023-entitlements.md), only when it has any: for now admins grant them, there is
 * nothing to buy yet.
 */
@Composable
fun ExtrasCard(user: UserProfile) {
    val extras = user.knownEntitlements
    if (extras.isEmpty()) return
    PopCard(
        modifier = Modifier.fillMaxWidth().testTag(TestTags.PROFILE_EXTRAS),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SecondaryText(stringResource(Res.string.profile_extras))
        for (extra in extras) {
            Text(text = stringResource(extra.title), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private val Entitlement.title: StringResource
    get() = when (this) {
        Entitlement.PREMIUM_HOST -> Res.string.entitlement_premium_host
        Entitlement.MAP_STYLES -> Res.string.entitlement_map_styles
        Entitlement.COSMETICS -> Res.string.entitlement_cosmetics
    }
