package app.sentrek.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.R
import app.sentrek.record.Breadcrumbs
import app.sentrek.record.Following
import app.sentrek.record.Recording
import app.sentrek.record.TrackNote
import app.sentrek.ui.ElevationProfile
import app.sentrek.ui.Format
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

/**
 * One card for the walk, however it's going: the route followed, the recording, or both.
 * Closed, three big numbers (what's left of the route, else what's been recorded) and the
 * most urgent note: what can be read at a glance, with the rest of the map free. A tap
 * anywhere on it opens the rest ([expanded], remembered): every note, the route's name and
 * Stop, the user's own note on the route, the recording, the places ahead and the profile.
 * Red while off the route.
 */
@Composable
fun TrailCard(
    recording: Recording?,
    following: Following?,
    notes: List<Note>,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    /** The whole list of places ahead open, not just the next one. */
    placesExpanded: Boolean,
    onExpandPlaces: (Boolean) -> Unit,
    /** Opens the route's sheet (reverse direction and the rest). */
    onOpenRoute: () -> Unit,
    /** Asks first (see FollowConfirmations): a stray tap mustn't end navigation. */
    onStop: (Following) -> Unit,
    /** The followed route's own note and photos, shown open under its name. */
    routeNote: String = "",
    routePhotos: List<String> = emptyList(),
    /** The note and photos open, or in one line; remembered. */
    noteExpanded: Boolean = false,
    onExpandNote: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (recording == null && following == null) return
    val colors = MaterialTheme.colorScheme
    val off = following?.offRoute == true
    val breadcrumbs by Breadcrumbs.status.collectAsStateWithLifecycle()
    // Only an open-close when opening shows something more.
    val more = following != null || notes.size > 1 || (recording != null && breadcrumbs != null)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (off) colors.error else colors.surface.copy(alpha = 0.92f),
        contentColor = if (off) colors.onError else colors.onSurface,
        shadowElevation = 4.dp,
    ) {
        Column(
            Modifier
                .clickable(
                    enabled = more,
                    onClickLabel = stringResource(if (expanded) R.string.trail_card_less else R.string.trail_card_more),
                ) { onExpand(!expanded) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Off route it shows closed too: how far off, and the way out of the route.
            if (following != null && (expanded || off)) RouteTitle(following, onOpenRoute, onStop)
            if (following != null) FollowNumbers(following) else if (recording != null) RecordingNumbers(recording)
            if (expanded) {
                // Under the numbers, so they stay where they were when the card opens.
                if (following != null) {
                    TrackNote(
                        routeNote, routePhotos, noteExpanded, onExpandNote, photoSize = TouchTarget,
                        // A few lines at most: the map stays the main thing on screen.
                        maxHeight = 160.dp,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (following != null && recording != null) RecordingNumbers(recording, Modifier.align(Alignment.CenterHorizontally), compact = true)
                if (recording != null) BreadcrumbLine(Modifier.align(Alignment.CenterHorizontally))
                notes.forEach { TrailNote(it) }
            } else {
                notes.firstOrNull()?.let { first ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrailNote(first, Modifier.weight(1f, fill = false))
                        if (notes.size > 1) Text("+${notes.size - 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (expanded && following != null) {
                PlacesAhead(following.upcoming, showClimb = following.hasElevation, expanded = placesExpanded, onExpand = onExpandPlaces)
                if (following.profile.size >= 2) {
                    ElevationProfile(
                        profile = following.profile,
                        here = following.here,
                        hereLabel = stringResource(R.string.follow_you_are_here),
                        color = if (off) colors.onError else colors.primary,
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                    )
                }
            }
            if (more) {
                Icon(
                    if (expanded) SentrekIcons.ExpandLess else SentrekIcons.ExpandMore,
                    contentDescription = null,
                    tint = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp).align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** The route's name (its sheet a tap away), or how far off it; and Stop. */
@Composable
private fun RouteTitle(following: Following, onOpenRoute: () -> Unit, onStop: (Following) -> Unit) {
    val off = following.offRoute
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (off) stringResource(R.string.follow_off_route, Format.distance(following.offset ?: 0.0)) else following.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = TouchTarget)
                .clickable(onClickLabel = stringResource(R.string.follow_route_options), onClick = onOpenRoute)
                .wrapContentHeight(Alignment.CenterVertically),
        )
        TextButton(onClick = { onStop(following) }, modifier = Modifier.heightIn(min = TouchTarget)) {
            Text(
                stringResource(R.string.follow_stop),
                color = if (off) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** What's left: distance, climb and time at the user's pace. */
@Composable
private fun FollowNumbers(following: Following) {
    val left = following.remaining
    Row {
        FollowStat(stringResource(R.string.follow_distance_left), Format.distance(left.distance), Modifier.weight(1f))
        if (following.hasElevation) {
            FollowStat(stringResource(R.string.follow_ascent_left), "↑ " + Format.elevation(left.ascent), Modifier.weight(1f))
        }
        // To the minute: an estimate, and seconds would only redraw the card.
        FollowStat(
            stringResource(if (following.ownPace) R.string.follow_time_left_own else R.string.follow_time_left),
            Format.hoursMinutes(left.millis),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun FollowStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
