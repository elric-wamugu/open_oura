package org.openoura.android.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.LocalContext
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Box
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.size
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import org.openoura.android.MainActivity
import org.openoura.android.R
import org.openoura.android.data.WidgetBus
import org.openoura.android.data.WidgetSnapshot
import org.openoura.android.data.WidgetStore
import org.openoura.android.ui.theme.BrandDark
import org.openoura.android.ui.theme.DarkData
import org.openoura.android.ui.theme.DataColors

// Home-screen widgets.
//
// App Widgets are RemoteViews, not Compose — Glance only gives them a Compose-shaped API.
// Practical consequences, all of which shaped what is below:
//   * No Canvas. Anything proportional has to be drawn into a Bitmap in the app process and
//     passed as an Image. Both rings here are that: an arc is not something RemoteViews
//     can draw, and Glance's only weight is `defaultWeight()` (equal shares).
//   * No arbitrary modifiers. Rounded corners come from Glance's own `cornerRadius`.
//   * The update must be cheap and must work with the app closed, so these read the small
//     WidgetSnapshot and never touch the Rust core or the 200 KB summary.
//
// Colours: fixed, and dark. The widgets sit on a near-black card with light text whatever
// the system's mode and whatever the wallpaper — like a watch face, they are read against
// a home screen, not inside the app, and the tinted container they used to take from the
// app's scheme (or from the wallpaper) fought with the stage colours drawn on top of it.
// So nothing here follows the app's "Wallpaper colours" setting. The data colours are the
// app's own dark-mode set; the two status colours are the same ones the app warns in.
private val Surface = solid(Color(0xFF1C1F26))
private val TextStrong = solid(Color(0xFFF1F3F5))
private val TextMuted = solid(Color(0xFFAAB1BB))
private val TrackColor = Color(0xFF343944)
private val Warn = solid(DarkData.warn.color)
private val Danger = solid(BrandDark.error)

/** One colour for day and night alike. */
private fun solid(c: Color): ColorProvider = ColorProvider(day = c, night = c)

// InlinedApi: the system widget radius only exists from API 31, and that is also the only
// level at which Glance applies cornerRadius at all — below it the call is a no-op, so the
// resource is never looked up where it is missing.
@SuppressLint("InlinedApi")
@Composable
private fun Shell(corner: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(Surface)
            // The launcher's own widget radius, so these sit flush with the system's.
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            // Centre the content: a widget occupies a whole cell whatever its content
            // height, and top-aligned text leaves the lower half visibly empty.
            verticalAlignment = Alignment.Vertical.CenterVertically,
            content = content,
        )
        // Drawn over the content, in the top-right corner, with its own tap.
        corner?.let {
            Box(GlanceModifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.TopEnd) { it() }
        }
    }
}

/** A small glyph in a text colour: the app's own icons, tinted for the dark card. */
@Composable
private fun Glyph(icon: Int, size: Dp, tint: ColorProvider = TextMuted) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = GlanceModifier.size(size),
    )
}

/**
 * The refresh button: sync with the ring from the home screen. While a sync it started is
 * under way the same icon turns in the same place, and is not a button — a second tap
 * would only queue behind the first.
 *
 * The turning one is a plain Android layout, not Glance: a widget cannot run a Compose
 * animation, but RemoteViews can host a ProgressBar, and a ProgressBar turns whatever
 * drawable it is given. See `widget_sync_spinner.xml`.
 */
@Composable
private fun RefreshButton(syncing: Boolean) {
    if (syncing) {
        AndroidRemoteViews(RemoteViews(LocalContext.current.packageName, R.layout.widget_sync_spinner))
        return
    }
    // 36 dp to press, 18 dp to look at.
    Box(
        GlanceModifier.size(36.dp).cornerRadius(18.dp).clickable(actionRunCallback<SyncFromWidget>()),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_sym_sync),
            contentDescription = "Sync with the ring",
            colorFilter = ColorFilter.tint(TextMuted),
            modifier = GlanceModifier.size(18.dp),
        )
    }
}

private fun empty(): String = "Open to sync"

/** Ring bitmaps are drawn at this size and scaled by the launcher: sharp up to ~130 dp. */
private const val RING_PX = 400
private const val RING_STROKE_PX = 44f

private fun ringCanvas(): Triple<Bitmap, Canvas, android.graphics.RectF> {
    val bmp = createBitmap(RING_PX, RING_PX)
    val inset = RING_STROKE_PX / 2
    return Triple(bmp, Canvas(bmp), android.graphics.RectF(inset, inset, RING_PX - inset, RING_PX - inset))
}

private fun ringPaint(color: Color, round: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = RING_STROKE_PX
    strokeCap = if (round) Paint.Cap.ROUND else Paint.Cap.BUTT
    this.color = color.toArgb()
}

/**
 * Last night's stages as a ring, or a plain track when the night was not staged.
 *
 */
private fun stageRingBitmap(s: WidgetSnapshot, d: DataColors, track: Color): Bitmap {
    val (bmp, canvas, box) = ringCanvas()
    val arcs = stageArcs(s)
    if (arcs.isEmpty()) {
        canvas.drawArc(box, 0f, 360f, false, ringPaint(track))
        return bmp
    }
    arcs.forEach { arc ->
        val color = when (arc.stage) {
            RingStage.DEEP -> d.deep
            RingStage.LIGHT -> d.light
            RingStage.REM -> d.rem
            RingStage.AWAKE -> d.wake
        }
        canvas.drawArc(box, arc.startDeg, arc.sweepDeg, false, ringPaint(color))
    }
    return bmp
}

/** The ring's charge as an arc over a track, clockwise from twelve o'clock. */
private fun batteryRingBitmap(pct: Int?, fill: Color, track: Color): Bitmap {
    val (bmp, canvas, box) = ringCanvas()
    canvas.drawArc(box, 0f, 360f, false, ringPaint(track))
    val level = pct?.coerceIn(0, 100) ?: 0
    if (level > 0) canvas.drawArc(box, -90f, 360f * level / 100f, false, ringPaint(fill, round = level < 100))
    return bmp
}

/** A ring with something written in its middle. */
@Composable
private fun Ring(bitmap: Bitmap, size: Dp, describe: String, centre: @Composable ColumnScope.() -> Unit) {
    Box(GlanceModifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = describe,
            contentScale = ContentScale.Fit,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally, content = centre)
    }
}

// ── last night and today: the stage ring ─────────────────────────────────────────────

/** Wide enough for the three figures to sit beside the ring. */
private val STATS_BESIDE_MIN_WIDTH = 230.dp

/** Tall enough, when not that wide, for them to sit under it. */
private val STATS_BELOW_MIN_HEIGHT = 190.dp

/**
 * What the three figures take up when stacked under the ring, in their compact size: three
 * 15 sp lines at about 22 dp each, the gaps between them and the gap above. Erring high —
 * the first cut reserved too little and the launcher clipped the last line.
 */
private val STATS_HEIGHT = 86.dp

/** Smaller than this either way and there is no free corner for the refresh button. */
private val REFRESH_MIN_SIZE = 100.dp

/** How the stage ring widget arranges itself in the space it was given. */
internal enum class RingLayout { RING_ONLY, STATS_BESIDE, STATS_BELOW }

internal fun ringLayout(width: Dp, height: Dp): RingLayout = when {
    width >= STATS_BESIDE_MIN_WIDTH && height >= 90.dp -> RingLayout.STATS_BESIDE
    height >= STATS_BELOW_MIN_HEIGHT && width >= 140.dp -> RingLayout.STATS_BELOW
    else -> RingLayout.RING_ONLY
}

class StageRingWidget : GlanceAppWidget() {
    // Exact, so LocalSize is the space actually granted. With Responsive it is the
    // *declared* size that was matched, and a ring sized from that came out a fraction of
    // its cell: the same four cells are very different sizes from one launcher to the next.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read inside the composition, keyed on the bus: see WidgetBus for why reading
            // once out here left a tapped widget showing what it had before the sync.
            val version by WidgetBus.version.collectAsState()
            val s = remember(version) { WidgetStore.read(context) }
            val ring = remember(version) { stageRingBitmap(s, DarkData, TrackColor) }
            val syncing = remember(version) { WidgetSync.isSyncing(context) }
            StageRingContent(s, ring, syncing)
        }
    }
}

@Composable
private fun StageRingContent(s: WidgetSnapshot, ring: Bitmap, syncing: Boolean) {
    val size = LocalSize.current
    val layout = ringLayout(size.width, size.height)
    // The ring takes the room there is, within what its bitmap stays sharp at. 24 dp is
    // the shell's padding, top and bottom.
    val room = when (layout) {
        RingLayout.STATS_BELOW -> minOf(size.width - 24.dp, size.height - 24.dp - STATS_HEIGHT)
        else -> minOf(size.width, size.height) - 24.dp
    }
    val ringSize = room.coerceIn(40.dp, 150.dp)
    // In a cell this small the corner is the ring: a button there would sit on top of it.
    val refresh = minOf(size.width, size.height) >= REFRESH_MIN_SIZE
    Shell(corner = if (refresh) ({ RefreshButton(syncing) }) else null) {
        when (layout) {
            RingLayout.STATS_BESIDE -> Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                StageRing(s, ring, ringSize)
                Spacer(GlanceModifier.width(16.dp))
                Column(GlanceModifier.defaultWeight()) { Stats(s) }
                // Keeps the figures' right edge clear of the refresh button above them.
                Spacer(GlanceModifier.width(20.dp))
            }
            RingLayout.STATS_BELOW -> Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            ) {
                StageRing(s, ring, ringSize)
                Spacer(GlanceModifier.height(6.dp))
                Stats(s, compact = true)
            }
            RingLayout.RING_ONLY -> Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            ) { StageRing(s, ring, ringSize) }
        }
    }
}

@Composable
private fun StageRing(s: WidgetSnapshot, ring: Bitmap, ringSize: Dp) {
    val hours = ringHours(s)
    val big = if (ringSize >= 100.dp) 26.sp else if (ringSize >= 72.dp) 20.sp else 14.sp
    Ring(ring, ringSize, "Last night's sleep stages") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ringSize >= 72.dp) {
                Glyph(R.drawable.ic_sym_bedtime, if (ringSize >= 100.dp) 18.dp else 14.dp)
                Spacer(GlanceModifier.width(4.dp))
            }
            Text(hours ?: "—", style = TextStyle(color = TextStrong, fontSize = big, fontWeight = FontWeight.Bold))
            if (hours != null) {
                Text("h", style = TextStyle(color = TextStrong, fontSize = if (ringSize >= 100.dp) 14.sp else 11.sp))
            }
        }
        // A one-row cell leaves room inside the ring for the number and nothing else.
        if (ringSize >= 72.dp) {
            Text(
                if (hours == null) empty() else ringCaption(s, roomy = ringSize >= 112.dp),
                style = TextStyle(color = TextMuted, fontSize = 11.sp),
            )
        }
    }
}

/** Steps, Distance, Ring charge — in that order. [compact] is for the stack under the ring. */
@Composable
private fun Stats(s: WidgetSnapshot, compact: Boolean = false) {
    val gap = if (compact) 2.dp else 6.dp
    Stat(R.drawable.ic_sym_directions_walk, "Steps", stepsLabel(s.steps), compact = compact)
    Spacer(GlanceModifier.height(gap))
    Stat(R.drawable.ic_sym_straighten, "Distance", distanceLabel(s.distanceKm), unit = if (s.distanceKm != null) "km" else "", compact = compact)
    Spacer(GlanceModifier.height(gap))
    // The one figure that changes colour: amber and red on the brain's bands, as in the app.
    Stat(
        R.drawable.ic_sym_battery_full,
        "Ring charge",
        s.batteryPct?.let { "$it%" } ?: "—",
        compact = compact,
        tint = if (s.batteryStatus in LOW_BANDS) batteryTint(s.batteryStatus) else TextStrong,
    )
}

private val LOW_BANDS = setOf("low", "very_low", "critical")

/** One figure: its icon and name on the left, the number on the right edge. */
@Composable
private fun Stat(
    icon: Int,
    label: String,
    value: String,
    unit: String = "",
    compact: Boolean = false,
    tint: ColorProvider = TextStrong,
) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, if (compact) 14.dp else 16.dp)
        Spacer(GlanceModifier.width(6.dp))
        Text(
            label,
            style = TextStyle(color = TextMuted, fontSize = if (compact) 12.sp else 13.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(value, style = TextStyle(color = tint, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.Bold))
        if (unit.isNotEmpty()) {
            Spacer(GlanceModifier.width(3.dp))
            Text(unit, style = TextStyle(color = TextMuted, fontSize = 12.sp))
        }
    }
}

/** Coloured on the brain's band like the app's battery chip: amber, then red. */
private fun batteryTint(status: String?): ColorProvider = when (status) {
    "low" -> Warn
    "very_low", "critical" -> Danger
    else -> TextMuted
}

/**
 * Named for the "Today" widget it replaced, and kept that way on purpose: the launcher
 * ties a placed widget to its receiver's class name, so one already on a home screen
 * becomes the stage ring in place. A new name would have made it vanish on update.
 */
class ActivityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StageRingWidget()
}

// ── the ring's battery (small) ───────────────────────────────────────────────────────

class BatteryWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by WidgetBus.version.collectAsState()
            val s = remember(version) { WidgetStore.read(context) }
            val ring = remember(version) {
                val fill = when (s.batteryStatus) {
                    "low" -> DarkData.warnMark
                    "very_low", "critical" -> BrandDark.error
                    else -> BrandDark.primary
                }
                batteryRingBitmap(s.batteryPct, fill, TrackColor)
            }
            BatteryContent(s, ring)
        }
    }
}

@Composable
private fun BatteryContent(s: WidgetSnapshot, ring: Bitmap) {
    val size = LocalSize.current
    val ringSize = (minOf(size.width, size.height) - 24.dp).coerceIn(40.dp, 150.dp)
    Shell {
        Row(
            GlanceModifier.fillMaxSize(),
            horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Ring(ring, ringSize, "Ring battery") {
                Text(
                    s.batteryPct?.let { "$it%" } ?: "—",
                    style = TextStyle(
                        color = TextStrong,
                        fontSize = if (ringSize >= 100.dp) 22.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    if (s.batteryPct != null) "ring" else empty(),
                    style = TextStyle(color = batteryTint(s.batteryStatus), fontSize = 11.sp),
                )
            }
        }
    }
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}
