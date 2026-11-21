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
import org.openoura.android.ui.SleepInterpretation
import org.openoura.android.data.Goals
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
private val Good = solid(BrandDark.primary)

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

/** A share of a whole as an arc over a track, clockwise from twelve o'clock. */
private fun progressRingBitmap(fraction: Float, fill: Color, track: Color): Bitmap {
    val (bmp, canvas, box) = ringCanvas()
    canvas.drawArc(box, 0f, 360f, false, ringPaint(track))
    val share = fraction.coerceIn(0f, 1f)
    // Round ends while it is an arc; a full ring has no ends to round.
    if (share > 0f) canvas.drawArc(box, -90f, 360f * share, false, ringPaint(fill, round = share < 1f))
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
            RingCard(syncing, ring = { StageRing(s, ring, it) }) { compact -> Stats(s, compact) }
        }
    }
}

/**
 * The card both ring widgets share: a ring, three figures, and the refresh button, arranged
 * for the space granted. [ring] is given the size it should be; [stats] is told whether it
 * is stacked under the ring, where it has to be compact.
 */
@Composable
private fun RingCard(
    syncing: Boolean,
    statsHeight: Dp = STATS_HEIGHT,
    ring: @Composable (Dp) -> Unit,
    stats: @Composable ColumnScope.(compact: Boolean) -> Unit,
) {
    val size = LocalSize.current
    val layout = ringLayout(size.width, size.height)
    // The ring takes the room there is, within what its bitmap stays sharp at. 24 dp is
    // the shell's padding, top and bottom.
    val room = when (layout) {
        RingLayout.STATS_BELOW -> minOf(size.width - 24.dp, size.height - 24.dp - statsHeight)
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
                ring(ringSize)
                Spacer(GlanceModifier.width(16.dp))
                Column(GlanceModifier.defaultWeight()) { stats(false) }
                // Keeps the figures' right edge clear of the refresh button above them.
                Spacer(GlanceModifier.width(20.dp))
            }
            RingLayout.STATS_BELOW -> Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            ) {
                ring(ringSize)
                Spacer(GlanceModifier.height(6.dp))
                stats(true)
            }
            RingLayout.RING_ONLY -> Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
            ) { ring(ringSize) }
        }
    }
}

/** A ring's middle: an icon, the figure, an optional unit, and a caption under them. */
@Composable
private fun RingCentre(icon: Int, ringSize: Dp, value: String?, unit: String, caption: String?) {
    val big = if (ringSize >= 100.dp) 26.sp else if (ringSize >= 72.dp) 20.sp else 14.sp
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The icon only where there is room beside a four-digit figure: in a ring of about
        // 115 dp it pushed "4,600" into the arc.
        if (ringSize >= 128.dp) {
            Glyph(icon, if (ringSize >= 100.dp) 18.dp else 14.dp)
            Spacer(GlanceModifier.width(4.dp))
        }
        Text(value ?: "—", style = TextStyle(color = TextStrong, fontSize = big, fontWeight = FontWeight.Bold))
        if (value != null && unit.isNotEmpty()) {
            Text(unit, style = TextStyle(color = TextStrong, fontSize = if (ringSize >= 100.dp) 14.sp else 11.sp))
        }
    }
    // A one-row cell leaves room inside the ring for the number and nothing else.
    if (ringSize >= 72.dp) {
        Text(
            if (value == null) empty() else caption.orEmpty(),
            style = TextStyle(color = TextMuted, fontSize = 11.sp),
        )
    }
}

@Composable
private fun StageRing(s: WidgetSnapshot, ring: Bitmap, ringSize: Dp) {
    Ring(ring, ringSize, "Last night's sleep stages") {
        RingCentre(R.drawable.ic_sym_bedtime, ringSize, ringHours(s), "h", ringCaption(s, roomy = ringSize >= 112.dp))
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
    ChargeStat(s, compact)
}

/** The ring's charge: the one figure that changes colour, amber and red on the brain's bands. */
@Composable
private fun ChargeStat(s: WidgetSnapshot, compact: Boolean) {
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
    unitTint: ColorProvider = TextMuted,
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
            Text(unit, style = TextStyle(color = unitTint, fontSize = 12.sp))
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

// ── today: steps as the ring, readiness beside it ────────────────────────────────────

/**
 * Today at a glance. The ring is today's steps towards the daily target (set on the You
 * tab). Beside it, the day's Readiness Score is the headline — it already folds in last
 * night, the heart and the recent activity — with one line for time asleep. The ring's
 * charge appears only when it is low, as a warning; otherwise it takes no room.
 *
 * Replaced the "Steps today" widget in place (same receiver, see [StepsWidgetReceiver]),
 * which showed distance in the ring and asleep / efficiency / charge beside it.
 */
class StepsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by WidgetBus.version.collectAsState()
            val s = remember(version) { WidgetStore.read(context) }
            val target = remember(version) { Goals.stepTarget(context) }
            val ring = remember(version) {
                progressRingBitmap(stepProgress(s.steps, target), BrandDark.primary, TrackColor)
            }
            val syncing = remember(version) { WidgetSync.isSyncing(context) }
            RingCard(
                syncing,
                // Stacked under the ring: three rows, and room for the charge line.
                statsHeight = if (chargeWarning(s.batteryPct, s.batteryStatus) != null) STATS_HEIGHT + 22.dp else STATS_HEIGHT,
                ring = { size ->
                    Ring(ring, size, "Today's steps towards the daily target") {
                        RingCentre(
                            R.drawable.ic_sym_directions_walk,
                            size,
                            s.steps?.let { stepsLabel(it) },
                            unit = "",
                            caption = stepsCaption(s.steps, target),
                        )
                    }
                },
            ) { compact -> TodayStats(s, compact) }
        }
    }
}

/** A score's word in green from Good up, amber below — the bands of `scoreLabel`. */
private fun scoreTint(score: Int?): ColorProvider = if (score != null && score >= 70) Good else Warn

/** Readiness as the headline (a row like the rest when [compact]), then time asleep and the Sleep Score, then the charge warning when there is one. */
@Composable
private fun TodayStats(s: WidgetSnapshot, compact: Boolean) {
    val r = s.readiness
    if (compact) {
        // Stacked under the ring there is no room for a headline: Readiness joins the
        // rows below it, so the ring keeps its size (2026-11-21, at the user's request).
        Stat(
            R.drawable.ic_sym_health_and_safety,
            "Readiness",
            r?.toString() ?: "—",
            unit = r?.let { SleepInterpretation.scoreLabel(it) }.orEmpty(),
            compact = true,
            unitTint = scoreTint(r),
        )
    } else {
        Text("Readiness", style = TextStyle(color = TextMuted, fontSize = 12.sp))
        Row(verticalAlignment = Alignment.Vertical.Bottom) {
            Text(r?.toString() ?: "—", style = TextStyle(color = TextStrong, fontSize = 34.sp, fontWeight = FontWeight.Bold))
            if (r != null) {
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    SleepInterpretation.scoreLabel(r),
                    style = TextStyle(color = scoreTint(r), fontSize = 13.sp),
                    modifier = GlanceModifier.padding(bottom = 5.dp),
                )
            }
        }
    }
    Spacer(GlanceModifier.height(if (compact) 2.dp else 6.dp))
    // An unscored night has only time in bed, and says so rather than passing it off.
    Stat(
        R.drawable.ic_sym_bedtime,
        if (s.asleepHours != null || s.sleepHours == null) "Asleep" else "In bed",
        sleepDuration(s) ?: "—",
        compact = compact,
    )
    Spacer(GlanceModifier.height(if (compact) 2.dp else 6.dp))
    val score = s.sleepScore
    Stat(
        R.drawable.ic_sym_bedtime,
        "Sleep score",
        score?.toString() ?: "—",
        unit = score?.let { SleepInterpretation.scoreLabel(it) }.orEmpty(),
        compact = compact,
        unitTint = scoreTint(score),
    )
    chargeWarning(s.batteryPct, s.batteryStatus)?.let { warning ->
        Spacer(GlanceModifier.height(if (compact) 2.dp else 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(R.drawable.ic_sym_battery_full, 14.dp, batteryTint(s.batteryStatus))
            Spacer(GlanceModifier.width(4.dp))
            Text(warning, style = TextStyle(color = batteryTint(s.batteryStatus), fontSize = 12.sp))
        }
    }
}

class StepsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}

// ── today: the three scores as rings ─────────────────────────────────────────────

/** The topics' colours on the dark card: Readiness teal, Sleep indigo, steps amber, as in the app. */
private val ReadinessArc = BrandDark.primary
private val SleepArc = Color(0xFF9AA5FF)
private val StepsArc = Color(0xFFFFC46B)

/**
 * Today's three figures side by side, each a ring in its topic's colour: Readiness, the
 * Sleep Score and steps towards the daily target. Under them (or beside them, when wide)
 * time asleep and distance; the charge only when it is low.
 *
 * Added 2026-11-21 next to [StepsWidget], not in its place: the user keeps both to choose
 * from in the widget picker.
 */
class ScoresWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val version by WidgetBus.version.collectAsState()
            val s = remember(version) { WidgetStore.read(context) }
            val target = remember(version) { Goals.stepTarget(context) }
            val rings = remember(version) {
                listOf(
                    progressRingBitmap((s.readiness ?: 0) / 100f, ReadinessArc, TrackColor),
                    progressRingBitmap((s.sleepScore ?: 0) / 100f, SleepArc, TrackColor),
                    progressRingBitmap(stepProgress(s.steps, target), StepsArc, TrackColor),
                )
            }
            val syncing = remember(version) { WidgetSync.isSyncing(context) }
            ScoresContent(s, rings, syncing)
        }
    }
}

@Composable
private fun ScoresContent(s: WidgetSnapshot, rings: List<Bitmap>, syncing: Boolean) {
    val size = LocalSize.current
    val layout = trioLayout(size.width.value, size.height.value)
    val warning = chargeWarning(s.batteryPct, s.batteryStatus)
    // Rings alone in a narrow cell take the whole width — their names are wider than the
    // rings, so the Steps ring ran under the button (2026-11-21). The tap still opens the app.
    val refresh = minOf(size.width, size.height) >= REFRESH_MIN_SIZE &&
        !(layout == TrioLayout.RINGS_ONLY && size.width < 260.dp)
    // What the rings may take: the width shared three ways, and the height left once the
    // header, the captions and (when stacked) the rows are taken out.
    val captions = 18.dp
    val ringSize = when (layout) {
        TrioLayout.ROWS_BELOW -> minOf(
            (size.width - 24.dp - 24.dp) / 3,
            size.height - 24.dp - 26.dp - captions - 12.dp - 48.dp - (if (warning != null) 22.dp else 0.dp),
        )
        // The rows keep 150 dp, enough for "Distance" and "8h 29m" on one line each.
        TrioLayout.ROWS_BESIDE -> minOf((size.width - 24.dp - 24.dp - 16.dp - 150.dp - 20.dp) / 3, size.height - 24.dp - captions)
        // With the refresh button, the row keeps clear of its corner.
        TrioLayout.RINGS_ONLY -> minOf((size.width - 24.dp - 24.dp - (if (refresh) 28.dp else 0.dp)) / 3, size.height - 24.dp - captions)
    }.coerceIn(32.dp, 96.dp)
    // Small rings sit closer: "Readiness" under them already spaces them out.
    val gap = if (ringSize < 60.dp) 6.dp else 12.dp
    val trio: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.Vertical.Top) {
            ScoreRing(rings[0], ringSize, s.readiness?.toString(), "Readiness", "Readiness score")
            Spacer(GlanceModifier.width(gap))
            ScoreRing(rings[1], ringSize, s.sleepScore?.toString(), "Sleep", "Sleep score")
            Spacer(GlanceModifier.width(gap))
            ScoreRing(rings[2], ringSize, s.steps?.let { stepsShort(it) }, "Steps", "Steps today")
        }
    }
    val rows: @Composable ColumnScope.() -> Unit = {
        Stat(
            R.drawable.ic_sym_bedtime,
            if (s.asleepHours != null || s.sleepHours == null) "Asleep" else "In bed",
            sleepDuration(s) ?: "—",
            compact = true,
        )
        Spacer(GlanceModifier.height(4.dp))
        Stat(R.drawable.ic_sym_straighten, "Distance", distanceLabel(s.distanceKm), unit = if (s.distanceKm != null) "km" else "", compact = true)
        warning?.let {
            Spacer(GlanceModifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_sym_battery_full, 14.dp, batteryTint(s.batteryStatus))
                Spacer(GlanceModifier.width(4.dp))
                Text(it, style = TextStyle(color = batteryTint(s.batteryStatus), fontSize = 12.sp))
            }
        }
    }
    Shell(corner = if (refresh) ({ RefreshButton(syncing) }) else null) {
        when (layout) {
            TrioLayout.ROWS_BELOW -> Column(GlanceModifier.fillMaxSize()) {
                // A header line, so the refresh button in the corner has a row of its own
                // and does not sit on the Steps ring.
                Text("Today", style = TextStyle(color = TextMuted, fontSize = 12.sp), modifier = GlanceModifier.padding(top = 4.dp))
                // The rings and rows together, centred in what is left: pushed apart, they
                // left a hole in the middle of a square cell.
                Column(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.Horizontal.CenterHorizontally) { trio() }
                    Spacer(GlanceModifier.height(14.dp))
                    rows()
                }
            }
            TrioLayout.ROWS_BESIDE -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                trio()
                Spacer(GlanceModifier.width(16.dp))
                Column(GlanceModifier.defaultWeight()) { rows() }
                Spacer(GlanceModifier.width(20.dp))
            }
            TrioLayout.RINGS_ONLY -> Row(
                GlanceModifier.fillMaxSize().padding(end = if (refresh) 28.dp else 0.dp),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) { trio() }
        }
    }
}

/** One of the three: a ring with its figure in the middle and its name underneath. */
@Composable
private fun ScoreRing(ring: Bitmap, size: Dp, value: String?, label: String, spoken: String) {
    Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Ring(ring, size, value?.let { "$spoken $it" } ?: "$spoken not available yet") {
            val big = if (size >= 72.dp) 20.sp else if (size >= 52.dp) 16.sp else 13.sp
            Text(value ?: "—", style = TextStyle(color = TextStrong, fontSize = big, fontWeight = FontWeight.Bold))
        }
        Spacer(GlanceModifier.height(4.dp))
        Text(label, style = TextStyle(color = TextMuted, fontSize = 11.sp))
    }
}

class ScoresWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScoresWidget()
}
