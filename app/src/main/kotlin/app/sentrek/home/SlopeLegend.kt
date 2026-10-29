package app.sentrek.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sentrek.R
import app.sentrek.geo.dem.Slope
import app.sentrek.map.MapSlopes

/** What the slope colours mean, under the scale bar while they're shown. */
@Composable
fun SlopeLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.slope_legend), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        for ((band, color) in MapSlopes.colors.withIndex()) {
            Box(Modifier.size(10.dp).background(Color(color), RoundedCornerShape(2.dp)))
            Text("${Slope.BANDS[band].toInt()}°", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
