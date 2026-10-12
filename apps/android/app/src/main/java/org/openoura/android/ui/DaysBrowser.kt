package org.openoura.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.openoura.android.R
import org.openoura.android.data.Summary
import org.openoura.android.ui.components.SectionHeader
import org.openoura.android.ui.components.ShapedIcon
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Every day with a night or activity, newest first — the counterpart to the web's
 * `openDaysBrowser` and the iOS `AllDaysView`. Tapping a row opens its report on the
 * sleep half when there is a night to show, otherwise on activity.
 *
 * Grouped by month under sticky headers, each month one segmented block, so three months
 * of history reads as three things rather than ninety.
 */
@Composable
fun DaysBrowser(
    summary: Summary,
    onOpenDay: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val months = remember(summary.days) {
        val fmt = DateTimeFormatter.ofPattern("MMMM yyyy")
        summary.days.groupBy { it.take(7) }.map { (ym, days) ->
            (runCatching { YearMonth.parse(ym).format(fmt) }.getOrDefault(ym)) to days
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        months.forEach { (month, days) ->
            stickyHeader(key = "h-$month") {
                SectionHeader(
                    month,
                    Modifier
                        .fillMaxWidth()
                        .background(cs.surface)
                        .padding(top = 8.dp, bottom = 6.dp),
                )
            }
            itemsIndexed(days, key = { _, d -> d }) { i, day ->
                val night = summary.nightForDay(day)
                val ds = summary.activityDaily[day]
                val bits = buildList {
                    night?.let { n -> add("%.1f h sleep".format(n.inBedH ?: 0.0)) }
                    ds?.steps?.takeIf { it > 0 }?.let { add("${it.roundToInt()} steps") }
                    // A day can have a movement profile with zero steps (today, before
                    // you've walked anywhere); "no data" would be wrong.
                    if (ds != null && (ds.steps ?: 0.0) <= 0.0) add("${(ds.activeKcal ?: 0.0).roundToInt()} kcal")
                }
                SegmentedListItem(
                    onClick = { onOpenDay(day, night != null) },
                    shapes = ListItemDefaults.segmentedShapes(i, days.size),
                    colors = groupColors(),
                    leadingContent = {
                        ShapedIcon(if (night != null) R.drawable.ic_sym_bedtime else R.drawable.ic_sym_directions_walk)
                    },
                    supportingContent = { Text(if (bits.isEmpty()) "no data" else bits.joinToString(" · ")) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // A day without a night opens straight onto activity; the icon
                            // says so, and the efficiency is only there when sleep is.
                            night?.efficiency?.let {
                                Text("${it.roundToInt()}%", style = MaterialTheme.typography.titleMediumEmphasized)
                            }
                            Icon(painterResource(R.drawable.ic_sym_chevron_right), contentDescription = null)
                        }
                    },
                ) { Text(dayTitle(day)) }
            }
        }
    }
}
