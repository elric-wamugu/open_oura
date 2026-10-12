package org.openoura.android.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.color.ColorProviders
import androidx.glance.color.DynamicThemeColorProviders
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.openoura.android.MainActivity
import org.openoura.android.data.WidgetSnapshot
import org.openoura.android.data.WidgetStore
import org.openoura.android.ui.theme.BrandDark
import org.openoura.android.ui.theme.BrandLight
import org.openoura.android.ui.theme.DarkData
import org.openoura.android.ui.theme.DataColors
import org.openoura.android.ui.theme.LightData
import org.openoura.android.ui.theme.ThemePrefs

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
//
// Colours: the composition runs in this app's process, but what it produces is drawn by
// the launcher, which knows nothing of the app's theme. So colours go over as Glance
// ColorProviders that carry both a day and a night value and are resolved on the home
// screen — the widget follows the system's dark mode without being redrawn. They come
// from the same generated brand scheme as the app, or from the wallpaper when the app's
// "Wallpaper colours" setting is on.

/** The app's scheme, as the launcher can use it. Read per update, so the setting applies. */
private fun widgetColors(context: Context): ColorProviders =
    if (ThemePrefs.dynamicColor(context)) DynamicThemeColorProviders
    else ColorProviders(light = BrandLight, dark = BrandDark)

private fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** A fixed data colour (stage, status) with its day and night values. */
private fun data(pick: (DataColors) -> Color): ColorProvider =
    ColorProvider(day = pick(LightData), night = pick(DarkData))

// InlinedApi: the system widget radius only exists from API 31, and that is also the only
// level at which Glance applies cornerRadius at all — below it the call is a no-op, so the
// resource is never looked up where it is missing.
@SuppressLint("InlinedApi")
@Composable
private fun Shell(padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            // The launcher's own widget radius, so these sit flush with the system's.
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .padding(padding)
            .clickable(actionStartActivity<MainActivity>()),
        // Centre the content: a widget occupies a whole cell whatever its content height,
        // and top-aligned text leaves the lower half visibly empty.
        verticalAlignment = Alignment.Vertical.CenterVertically,
        content = content,
    )
}

/**
 * The widget's headline figure on a rounded primary-container pill — the same "one
 * number that matters, on a tonal shape" the app's tiles use.
 */
@Composable
private fun Hero(label: String?, value: String, unit: String, size: TextUnit = 28.sp) {
    val on = GlanceTheme.colors.onPrimaryContainer
    Column(
        GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.primaryContainer)
            .cornerRadius(20.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        label?.let { Text(it, style = TextStyle(color = on, fontSize = 12.sp, fontWeight = FontWeight.Medium)) }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = TextStyle(color = on, fontSize = size, fontWeight = FontWeight.Bold))
            if (unit.isNotEmpty()) {
                Spacer(GlanceModifier.width(4.dp))
                Text(unit, style = TextStyle(color = on, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun Label(text: String) = Text(
    text,
    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
private fun Value(value: String, unit: String, tint: ColorProvider = GlanceTheme.colors.onSurface, size: TextUnit = 18.sp) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = TextStyle(color = tint, fontSize = size, fontWeight = FontWeight.Bold))
        if (unit.isNotEmpty()) {
            Spacer(GlanceModifier.width(3.dp))
            Text(unit, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

@Composable
private fun Sub(text: String) =
    Text(text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))

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
        val colors = widgetColors(context)
        provideContent { GlanceTheme(colors) { VitalsContent(s) } }
    }
}

@Composable
private fun VitalsContent(s: WidgetSnapshot) = Shell {
    // Falling resting HR is the training-responsive signal, so it leads.
    Hero("Resting HR", s.rhr?.toString() ?: "—", if (s.rhr != null) "bpm" else "")
    Spacer(GlanceModifier.height(6.dp))
    Sub(if (s.rhr == null) empty() else vsBaseline(s.rhr, s.rhrBaseline, ""))
    Spacer(GlanceModifier.height(8.dp))
    Label("HRV")
    Row(verticalAlignment = Alignment.Bottom) {
        Value(s.hrv?.toString() ?: "—", if (s.hrv != null) "ms" else "")
        Spacer(GlanceModifier.width(6.dp))
        if (s.hrv != null) {
            Text(
                vsBaseline(s.hrv, s.hrvBaseline, ""),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            )
        }
    }
}

class VitalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VitalsWidget()
}

// ── last night (medium) ──────────────────────────────────────────────────────────────

class SleepWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetStore.read(context)
        val colors = widgetColors(context)
        // A bitmap has no day/night variants, so its colours are picked for the mode in
        // force now. A mode switch leaves it on the old palette until the next refresh.
        val bar = stageBarBitmap(s, if (context.isNight()) DarkData else LightData)
        provideContent { GlanceTheme(colors) { SleepContent(s, bar) } }
    }
}

@Composable
private fun SleepContent(s: WidgetSnapshot, bar: Bitmap?) = Shell {
    Label("Last night")
    Spacer(GlanceModifier.height(4.dp))
    if (s.sleepHours == null) {
        Value("—", "", size = 28.sp)
        Sub(empty())
        return@Shell
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            GlanceModifier
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(20.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val on = GlanceTheme.colors.onPrimaryContainer
            Text("%.1f".format(s.sleepHours), style = TextStyle(color = on, fontSize = 28.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(3.dp))
            Text("h", style = TextStyle(color = on, fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
        Spacer(GlanceModifier.width(12.dp))
        // Coded like the app's vitals pill: in range green, short of it blue, between plain.
        s.efficiency?.let {
            val tint = when {
                it >= 85 -> data { d -> d.normal.color }
                it >= 75 -> GlanceTheme.colors.onSurface
                else -> data { d -> d.low.color }
            }
            Value("$it%", "efficiency", tint, size = 20.sp)
        }
    }
    Spacer(GlanceModifier.height(10.dp))
    bar?.let {
        Image(
            provider = ImageProvider(it),
            contentDescription = "Sleep stages",
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxWidth().height(12.dp).cornerRadius(6.dp),
        )
        Spacer(GlanceModifier.height(8.dp))
    }
    Sub(
        listOfNotNull(
            s.deepPct?.let { "deep $it%" },
            s.remPct?.let { "REM $it%" },
            s.spo2?.let { "SpO₂ $it%" },
        ).joinToString(" · "),
    )
}

/**
 * Stage proportions as a rendered bitmap.
 *
 * Glance exposes only `defaultWeight()` — equal shares, no numeric weight — so four boxes in
 * a Row come out the same width whatever the percentages are, which would be actively
 * misleading. Proportional segments therefore have to be drawn, and a widget cannot host a
 * Canvas, so it is drawn into a Bitmap here in the app process and handed over as an Image.
 * Thin gaps separate the stages, as in the app's own stage bar.
 */
private fun stageBarBitmap(s: WidgetSnapshot, d: DataColors, widthPx: Int = 600, heightPx: Int = 24): Bitmap? {
    val parts = listOf(s.deepPct to d.deep, s.lightPct to d.light, s.remPct to d.rem, s.wakePct to d.wake)
        .mapNotNull { (p, c) -> p?.takeIf { it > 0 }?.let { it to c } }
    val total = parts.sumOf { it.first }
    if (total <= 0) return null
    val gap = 4f
    val usable = widthPx - gap * (parts.size - 1)
    val bmp = createBitmap(widthPx, heightPx)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var x = 0f
    parts.forEach { (pct, color) ->
        val w = usable * (pct.toFloat() / total)
        paint.color = color.toArgb()
        canvas.drawRect(x, 0f, x + w, heightPx.toFloat(), paint)
        x += w + gap
    }
    return bmp
}

class SleepWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SleepWidget()
}

// ── today (2x2) ──────────────────────────────────────────────────────────────────────

/**
 * The smallest useful shape: the steps figure alone, for a cell too short for anything else.
 * 40 dp is what Android's conservative `70n - 30` formula promises for a single row.
 */
private val TODAY_COMPACT = DpSize(110.dp, 40.dp)

/** Label, figure and the two supporting lines. Fits a two-row cell. */
private val TODAY_FULL = DpSize(110.dp, 110.dp)

class ActivityWidget : GlanceAppWidget() {
    // Keyed off the space actually granted, not the cell count. "One row" is 40 dp by the
    // formula but much more on a real launcher, so a widget that decided by cells would
    // render the stripped-down layout with room for everything. Glance picks the largest
    // declared size that fits, so only a genuinely tiny cell falls back.
    override val sizeMode = SizeMode.Responsive(setOf(TODAY_COMPACT, TODAY_FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = WidgetStore.read(context)
        val colors = widgetColors(context)
        provideContent { GlanceTheme(colors) { ActivityContent(s) } }
    }
}

@Composable
private fun ActivityContent(s: WidgetSnapshot) {
    val compact = LocalSize.current.height < TODAY_FULL.height
    val steps = s.steps?.let { "%,d".format(it) } ?: "—"
    Shell(padding = if (compact) 8.dp else 12.dp) {
        if (compact) {
            // The figure has to carry the widget on its own: no pill, no label — "steps"
            // next to the number already says what it is.
            Value(steps, if (s.steps != null) "steps" else "", GlanceTheme.colors.primary, size = 20.sp)
            return@Shell
        }
        Hero("Today", steps, if (s.steps != null) "steps" else "")
        if (s.steps == null) {
            Spacer(GlanceModifier.height(6.dp))
            Sub(empty())
        } else {
            // One fact per line, not a single " · "-joined row. At two cells wide that row
            // no longer fits and RemoteViews would silently clip it.
            Spacer(GlanceModifier.height(6.dp))
            s.peakHr?.let { Sub("peak $it bpm") }
            s.batteryPct?.let { Sub("ring $it%") }
        }
    }
}

class ActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActivityWidget()
}
