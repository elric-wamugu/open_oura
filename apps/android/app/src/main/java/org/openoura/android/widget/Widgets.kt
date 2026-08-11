package org.openoura.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.layout.ContentScale
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.openoura.android.MainActivity
import org.openoura.android.data.WidgetSnapshot
import org.openoura.android.data.WidgetStore
import kotlin.math.roundToInt

// Home-screen widgets.
//
// App Widgets are RemoteViews, not Compose — Glance only gives them a Compose-shaped API.
// Practical consequences, all of which shaped what is below:
//   * No Canvas. Anything proportional has to be drawn into a Bitmap in the app process and
//     passed as an Image. The stage bar needs exactly this: Glance offers only
//     `defaultWeight()` (equal shares, no numeric weight), so boxes in a Row would render
//     four equal blocks regardless of the real percentages.
//   * No arbitrary modifiers. Rounded corners come from Glance's own `cornerRadius`.
//   * The update must be cheap and must work with the app closed, so these read the small
//     WidgetSnapshot and never touch the Rust core or the 200 KB summary.

// The dashboard palette, hard-coded: a widget renders in the launcher's process, so it has
// no access to the app's theme.
private val BG = Color(0xFF16181D)
private val TEXT = Color(0xFFE8E9EC)
private val MUTED = Color(0xFF9AA0AA)
private val FAINT = Color(0xFF6B7280)
private val ACCENT = Color(0xFF2DD4BF)
private val WARN = Color(0xFFE0A44E)
private val DEEP = Color(0xFF5D6989)
private val LIGHT = Color(0xFF8C97B3)
private val REM = Color(0xFF6FA1A8)
private val WAKE = Color(0xFFC1B08F)

@Composable
private fun Shell(content: @Composable () -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BG)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        // Centre the content: a widget occupies a whole cell whatever its content height,
        // and top-aligned text leaves the lower half visibly empty.
        verticalAlignment = Alignment.Vertical.CenterVertically,
        content = { content() },
    )
}

@Composable
private fun Label(text: String) =
    Text(text, style = TextStyle(color = androidx.glance.unit.ColorProvider(FAINT), fontSize = 10.sp, fontWeight = FontWeight.Medium))

@Composable
private fun Big(value: String, unit: String, tint: Color = TEXT) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = TextStyle(color = androidx.glance.unit.ColorProvider(tint), fontSize = 26.sp, fontWeight = FontWeight.Medium))
        if (unit.isNotEmpty()) {
            Spacer(GlanceModifier.width(3.dp))
            Text(unit, style = TextStyle(color = androidx.glance.unit.ColorProvider(MUTED), fontSize = 11.sp))
        }
    }
}

@Composable
private fun Sub(text: String) =
    Text(text, style = TextStyle(color = androidx.glance.unit.ColorProvider(MUTED), fontSize = 11.sp))

/** "12 below baseline 65" — the comparison is the point, not the bare number. */
private fun vsBaseline(v: Int?, base: Int?, unit: String): String = when {
    v == null -> "no reading yet"
    base == null -> "baseline pending"
    else -> {
        val d = v - base
        val dir = if (d == 0) "at" else if (d < 0) "below" else "above"
        if (d == 0) "at baseline $base$unit" else "${kotlin.math.abs(d)}$unit $dir baseline $base"
    }
}

private fun empty(): String = "Open to sync"

// ── vitals (small) ───────────────────────────────────────────────────────────────────

class VitalsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetStore.read(context)
        provideContent { GlanceTheme { VitalsContent(s) } }
    }
}

@Composable
private fun VitalsContent(s: WidgetSnapshot) = Shell {
    Label("RESTING HR")
    // Falling resting HR is the training-responsive signal, so it leads.
    Big(s.rhr?.toString() ?: "—", if (s.rhr != null) "bpm" else "", if (s.rhr != null) ACCENT else MUTED)
    Sub(if (s.rhr == null) empty() else vsBaseline(s.rhr, s.rhrBaseline, ""))
    Spacer(GlanceModifier.height(8.dp))
    Label("HRV")
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            s.hrv?.toString() ?: "—",
            style = TextStyle(color = androidx.glance.unit.ColorProvider(TEXT), fontSize = 16.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            if (s.hrv != null) vsBaseline(s.hrv, s.hrvBaseline, " ms") else "",
            style = TextStyle(color = androidx.glance.unit.ColorProvider(FAINT), fontSize = 10.sp),
        )
    }
}

class VitalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VitalsWidget()
}

// ── last night (medium) ──────────────────────────────────────────────────────────────

class SleepWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetStore.read(context)
        provideContent { GlanceTheme { SleepContent(s) } }
    }
}

@Composable
private fun SleepContent(s: WidgetSnapshot) = Shell {
    Label("LAST NIGHT")
    if (s.sleepHours == null) {
        Big("—", "")
        Sub(empty())
        return@Shell
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Big("%.1f".format(s.sleepHours), "h")
        Spacer(GlanceModifier.width(10.dp))
        s.efficiency?.let {
            Big("$it", "% eff", if (it >= 85) ACCENT else if (it >= 75) TEXT else WARN)
        }
    }
    Spacer(GlanceModifier.height(8.dp))
    StageBar(s)
    Spacer(GlanceModifier.height(6.dp))
    Sub(
        listOfNotNull(
            s.deepPct?.let { "deep $it%" },
            s.remPct?.let { "rem $it%" },
            s.spo2?.let { "spo₂ $it%" },
        ).joinToString(" · "),
    )
}

/**
 * Stage proportions as a rendered bitmap.
 *
 * This is the constraint the plan warned about. Glance exposes only `defaultWeight()` —
 * equal shares, no numeric weight — so four boxes in a Row come out the same width whatever
 * the percentages are, which would be actively misleading. Proportional segments therefore
 * have to be drawn, and a widget cannot host a Canvas, so it is drawn into a Bitmap here in
 * the app process and handed over as an Image.
 */
private fun stageBarBitmap(s: WidgetSnapshot, widthPx: Int = 600, heightPx: Int = 24): Bitmap? {
    val parts = listOf(s.deepPct to DEEP, s.lightPct to LIGHT, s.remPct to REM, s.wakePct to WAKE)
        .mapNotNull { (p, c) -> p?.takeIf { it > 0 }?.let { it to c } }
    val total = parts.sumOf { it.first }
    if (total <= 0) return null
    val bmp = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var x = 0f
    parts.forEach { (pct, color) ->
        val w = widthPx * (pct.toFloat() / total)
        paint.color = color.toArgb()
        canvas.drawRect(x, 0f, x + w + 0.5f, heightPx.toFloat(), paint)
        x += w
    }
    return bmp
}

@Composable
private fun StageBar(s: WidgetSnapshot) {
    val bmp = stageBarBitmap(s) ?: return
    Image(
        provider = ImageProvider(bmp),
        contentDescription = "Sleep stages",
        contentScale = ContentScale.FillBounds,
        modifier = GlanceModifier.fillMaxWidth().height(8.dp).cornerRadius(2.dp),
    )
}

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}

// ── today (2x2) ──────────────────────────────────────────────────────────────────────

class ActivityWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetStore.read(context)
        provideContent { GlanceTheme { ActivityContent(s) } }
    }
}

@Composable
private fun ActivityContent(s: WidgetSnapshot) = Shell {
    Label("TODAY")
    Big(s.steps?.let { "%,d".format(it) } ?: "—", if (s.steps != null) "steps" else "", ACCENT)
    Spacer(GlanceModifier.height(6.dp))
    if (s.steps == null) {
        Sub(empty())
    } else {
        // One fact per line, not a single " · "-joined row. At two cells wide that row no
        // longer fits and RemoteViews would silently clip it; the square shape trades the
        // width for the vertical space to stack instead.
        s.peakHr?.let { Sub("peak $it bpm") }
        s.batteryPct?.let { Sub("ring $it%") }
    }
}

class ActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActivityWidget()
}
