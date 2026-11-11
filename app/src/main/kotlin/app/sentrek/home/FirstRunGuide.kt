package app.sentrek.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import kotlinx.coroutines.launch

private class GuidePage(val icon: ImageVector, val title: Int, val text: Int)

/** Downloading last: the page that ends with the button to do it. */
private val PAGES = listOf(
    GuidePage(SentrekIcons.Search, R.string.guide_press_title, R.string.guide_press_text),
    GuidePage(SentrekIcons.MyLocation, R.string.guide_location_title, R.string.guide_location_text),
    GuidePage(SentrekIcons.Plan, R.string.guide_walk_title, R.string.guide_walk_text),
    GuidePage(SentrekIcons.Download, R.string.guide_download_title, R.string.guide_download_text),
)

/**
 * The first time the app opens: the things that aren't obvious from the map itself, a page
 * each, swiped or with Next. It ends on downloading, with the button to do it ([onDownload]),
 * since the map is only sure to work offline once that's done. Skip is always there; it never
 * shows again unless asked for in Settings › About.
 */
@Composable
fun FirstRunGuide(onDone: () -> Unit, onDownload: () -> Unit) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val index = pager.currentPage
    val last = index == PAGES.lastIndex
    BackHandler { if (index > 0) scope.launch { pager.animateScrollToPage(index - 1) } else onDone() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(vertical = 24.dp)) {
            val step = stringResource(R.string.guide_step, index + 1, PAGES.size)
            // Dots for the eye, "2 of 4" for screen readers.
            Row(
                Modifier.padding(horizontal = 24.dp).semantics { contentDescription = step },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(PAGES.size) { i ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                CircleShape,
                            ),
                    )
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                val page = PAGES[i]
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
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
            }
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(if (last) R.string.guide_later else R.string.guide_skip))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { if (last) onDownload() else scope.launch { pager.animateScrollToPage(index + 1) } },
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text(stringResource(if (last) R.string.guide_download_now else R.string.guide_next)) }
            }
        }
    }
}
