package app.sentrek.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.record.Following
import app.sentrek.ui.ElevationProfile
import app.sentrek.ui.Format
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

/**
 * What's left of the followed route; turns red when off route. With [showProfile], the route's
 * elevation profile with the user's position on it ([onToggleProfile] hides it for more map).
 */
@Composable
fun FollowPanel(
    following: Following,
    showProfile: Boolean,
    onToggleProfile: () -> Unit,
    /** Opens the route's sheet (reverse direction and the rest). */
    onOpen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val off = following.offRoute
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (off) colors.error else colors.surface.copy(alpha = 0.92f),
        contentColor = if (off) colors.onError else colors.onSurface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (off) stringResource(R.string.follow_off_route, Format.distance(following.offset ?: 0.0)) else following.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The route's sheet: reverse, colour, hide…
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = TouchTarget)
                        .clickable(onClickLabel = stringResource(R.string.follow_route_options), onClick = onOpen)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
                if (following.profile.size >= 2) {
                    IconToggleButton(
                        checked = showProfile,
                        onCheckedChange = { onToggleProfile() },
                        modifier = Modifier.size(TouchTarget),
                        colors = IconButtonDefaults.iconToggleButtonColors(
                            checkedContainerColor = if (off) colors.onError.copy(alpha = 0.2f) else colors.primaryContainer,
                        ),
                    ) {
                        Icon(SentrekIcons.Profile, contentDescription = stringResource(R.string.follow_profile))
                    }
                }
                TextButton(onClick = onStop, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.follow_stop), color = if (off) colors.onError else colors.primary)
                }
            }
            val left = following.remaining
            Row(Modifier.padding(bottom = 8.dp)) {
                FollowStat(stringResource(R.string.follow_distance_left), Format.distance(left.distance), Modifier.weight(1f))
                if (following.hasElevation) {
                    FollowStat(stringResource(R.string.follow_ascent_left), "↑ " + Format.meters(left.ascent), Modifier.weight(1f))
                }
                FollowStat(stringResource(R.string.follow_time_left), Format.duration(left.millis), Modifier.weight(1f))
            }
            if (showProfile && following.profile.size >= 2) {
                ElevationProfile(
                    profile = following.profile,
                    here = following.here,
                    hereLabel = stringResource(R.string.follow_you_are_here),
                    color = if (off) colors.onError else colors.primary,
                    modifier = Modifier.fillMaxWidth().height(88.dp).padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun FollowStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
