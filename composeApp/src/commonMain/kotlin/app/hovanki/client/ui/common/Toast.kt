package app.hovanki.client.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_check
import app.hovanki.client.ui.theme.Palette
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** A short note in an ink capsule with a lime icon (docs/design.md, `Toast`), read out by screen readers. */
@Composable
fun Toast(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = Res.drawable.ic_check,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Palette.Ink)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon?.let {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = Palette.Lime,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(text = text, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
    }
}

/** True for [millis] every time [trigger] changes to a new value above zero: when to show a [Toast]. */
@Composable
fun rememberToastVisible(trigger: Int, millis: Long = TOAST_MILLIS): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger <= 0) return@LaunchedEffect
        visible = true
        delay(millis)
        visible = false
    }
    return visible
}

private const val TOAST_MILLIS = 2_000L
