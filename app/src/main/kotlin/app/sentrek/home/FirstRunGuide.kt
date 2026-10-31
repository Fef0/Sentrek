package app.sentrek.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

private class GuidePage(val icon: ImageVector, val title: Int, val text: Int)

private val PAGES = listOf(
    GuidePage(SentrekIcons.Download, R.string.guide_download_title, R.string.guide_download_text),
    GuidePage(SentrekIcons.Search, R.string.guide_press_title, R.string.guide_press_text),
    GuidePage(SentrekIcons.MyLocation, R.string.guide_location_title, R.string.guide_location_text),
    GuidePage(SentrekIcons.Plan, R.string.guide_walk_title, R.string.guide_walk_text),
)

/**
 * The first time the app opens: the four things that aren't obvious from the map itself, a
 * page each. Skip is always there; it never shows again unless asked for in Settings › About.
 */
@Composable
fun FirstRunGuide(onDone: () -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = PAGES[index]
    val last = index == PAGES.lastIndex
    BackHandler { if (index > 0) index-- else onDone() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.guide_step, index + 1, PAGES.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(88.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(page.icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.size(32.dp))
                Text(stringResource(page.title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(16.dp))
                Text(stringResource(page.text), style = MaterialTheme.typography.bodyLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!last) {
                    TextButton(onClick = onDone, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.guide_skip))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (last) onDone() else index++ },
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text(stringResource(if (last) R.string.guide_start else R.string.guide_next)) }
            }
        }
    }
}
