package org.openoura.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openoura.android.data.Summary
import org.openoura.android.ui.theme.Oura
import kotlin.math.roundToInt

/**
 * Every day with a night or activity, newest first — the counterpart to the web's
 * `openDaysBrowser` and the iOS `AllDaysView`. Tapping a row opens its report on the
 * sleep tab when there is a night to show, otherwise on activity.
 */
@Composable
fun DaysBrowser(
    summary: Summary,
    onOpenDay: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Oura.colors
    BackHandler(onBack = onBack)
    val days = summary.days

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                color = c.muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, c.line, RoundedCornerShape(999.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text("${days.size} days", color = c.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(days) { day ->
                val night = summary.nightForDay(day)
                val ds = summary.activityDaily[day]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(8.dp))
                        .clickable { onOpenDay(day, night != null) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(dayTitle(day), color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        val bits = buildList {
                            night?.let { n ->
                                add("%.1fh sleep".format(n.inBedH ?: 0.0))
                                n.efficiency?.let { add("${it.roundToInt()}% eff") }
                            }
                            ds?.steps?.takeIf { it > 0 }?.let { add("${it.roundToInt()} steps") }
                            // A day can have a movement profile with zero steps (today,
                            // before you've walked anywhere); "no data" would be wrong.
                            if (ds != null && (ds.steps ?: 0.0) <= 0.0) {
                                add("${(ds.activeKcal ?: 0.0).roundToInt()} kcal")
                            }
                        }
                        Text(
                            if (bits.isEmpty()) "no data" else bits.joinToString(" · "),
                            color = c.faint, fontSize = 11.sp,
                        )
                    }
                    // A day without a night opens straight onto activity; say so rather
                    // than letting the tap land on an empty sleep tab.
                    Text(
                        if (night != null) "›" else "activity ›",
                        color = c.faint,
                        fontSize = if (night != null) 16.sp else 11.sp,
                    )
                }
            }
        }
    }
}
