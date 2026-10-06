package app.hovanki.client.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.app_name
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/** The mark: a green disc in a thick ink ring. */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 28.dp) {
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Palette.Ink, radius = r)
        drawCircle(Palette.Green, radius = r * 0.5f)
    }
}

/** The mark and the name, lowercase, as in the design. */
@Composable
fun Logo(modifier: Modifier = Modifier, markSize: Dp = 28.dp, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(markSize / 3),
    ) {
        LogoMark(size = markSize)
        Text(text = stringResource(Res.string.app_name).lowercase(), style = style)
    }
}
