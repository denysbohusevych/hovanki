package app.hovanki.client.ui.results

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hovanki.client.automation.TestTags
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.ic_share
import app.hovanki.client.resources.results_share
import app.hovanki.client.resources.story_everyone
import app.hovanki.client.resources.story_mine
import app.hovanki.client.resources.story_preview
import app.hovanki.client.resources.story_title
import app.hovanki.client.share.encodePng
import app.hovanki.client.ui.common.Panel
import app.hovanki.client.ui.common.PopButton
import app.hovanki.client.ui.common.PopStyle
import app.hovanki.client.ui.theme.Palette
import org.jetbrains.compose.resources.stringResource

/**
 * The story picture before it leaves the game (docs/adr/0024-instagram-stories.md): the player sees exactly what goes
 * out, picks only their own way on green or everybody's on ink (when there is a scheme), and shares it.
 */
@Composable
internal fun StoryPanel(story: Story, texts: StoryTexts, onShare: (ByteArray) -> Unit, onClose: () -> Unit) {
    val renderer = rememberStoryRenderer()
    var everyone by rememberSaveable { mutableStateOf(true) }
    val style = if (everyone && story.scheme != null) StoryStyle.EVERYONE else StoryStyle.MINE
    val role = story.result.player.role
    val picture = remember(texts, story.scheme, style) { renderer.render(texts, story.scheme, role, style) }
    Panel(title = stringResource(Res.string.story_title), onClose = onClose, screen = "story") {
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (story.scheme != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PopButton(
                        text = stringResource(Res.string.story_mine),
                        onClick = { everyone = false },
                        style = if (everyone) PopStyle.Outline else PopStyle.Dark,
                        height = 44.dp,
                        modifier = Modifier.weight(1f),
                    )
                    PopButton(
                        text = stringResource(Res.string.story_everyone),
                        onClick = { everyone = true },
                        style = if (everyone) PopStyle.Dark else PopStyle.Outline,
                        height = 44.dp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shape = RoundedCornerShape(18.dp)
                Image(
                    bitmap = picture,
                    contentDescription = stringResource(Res.string.story_preview),
                    modifier = Modifier
                        .aspectRatio(STORY_WIDTH_PX.toFloat() / STORY_HEIGHT_PX)
                        .clip(shape)
                        .border(2.dp, Palette.Ink, shape)
                        .testTag(TestTags.STORY_PREVIEW),
                )
            }
            PopButton(
                text = stringResource(Res.string.results_share),
                onClick = { onShare(picture.encodePng()) },
                height = 58.dp,
                icon = Res.drawable.ic_share,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.STORY_SHARE),
            )
        }
    }
}
