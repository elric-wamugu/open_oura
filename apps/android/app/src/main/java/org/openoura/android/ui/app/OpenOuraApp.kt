package org.openoura.android.ui.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openoura.android.R
import org.openoura.android.ble.BlePermissions
import org.openoura.android.ble.RingSyncService
import org.openoura.android.ble.SyncPhase
import org.openoura.android.ble.describeSync
import org.openoura.android.ble.probeRing
import org.openoura.android.ble.runRingFeature
import org.openoura.android.data.ProfileStore
import org.openoura.android.data.RingKeyStore
import org.openoura.android.data.SummaryRepository
import org.openoura.android.data.SummaryState
import org.openoura.android.health.ExportResult
import org.openoura.android.health.HealthExport
import org.openoura.android.ui.DayReportScreen
import org.openoura.android.ui.DaysBrowser
import org.openoura.android.ui.HealthConnectScreen
import org.openoura.android.ui.HealthConnectState
import org.openoura.android.ui.Notice
import org.openoura.android.ui.RingKeyScreen
import org.openoura.android.ui.RingScreen
import org.openoura.android.ui.TodayScreen
import org.openoura.android.ui.YouScreen
import org.openoura.android.ui.components.ScreenScaffold
import org.openoura.android.ui.dayTitle
import org.openoura.android.ui.nav.DayReportRoute
import org.openoura.android.ui.nav.HealthConnectRoute
import org.openoura.android.ui.nav.HistoryRoute
import org.openoura.android.ui.nav.RingKeyRoute
import org.openoura.android.ui.nav.RingRoute
import org.openoura.android.ui.nav.Tab
import org.openoura.android.ui.nav.TodayRoute
import org.openoura.android.ui.nav.YouRoute
import org.openoura.android.ui.nav.rememberNavigator
import kotlin.math.roundToInt

/**
 * The whole app: four tabs over one navigation display, with the bottom bar, the Sync
 * button and snackbars in the scaffold around it.
 *
 * Navigation 3 builds a screen's entry once, when it is pushed, so anything an entry shows
 * is read through Compose state (delegated `by` properties, [rememberUpdatedState]) rather
 * than captured as a value — otherwise a screen would keep showing whatever was true at
 * the moment it opened.
 *
 * Long-running work (a Health Connect export, a capability write) runs on [work], the
 * Activity's scope, so navigating away from the screen that started it doesn't cancel it.
 */
@Composable
fun OpenOuraApp(
    repo: SummaryRepository,
    profiles: ProfileStore,
    ringKeys: RingKeyStore,
    work: CoroutineScope,
    dynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val prefs = remember { app.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val navigator = rememberNavigator()
    val snackbar = remember { SnackbarHostState() }
    val messages = remember(snackbar, scope) { AppMessages(snackbar, scope) }
    val dynamic by rememberUpdatedState(dynamicColor)
    val setDynamic by rememberUpdatedState(onDynamicColor)

    val state by repo.state.collectAsState()
    var busy by remember { mutableStateOf(false) }

    // SAF picker: the user grants access to one file, so the app needs no storage
    // permission. "*/*" because a .db has no registered MIME type and narrower filters
    // hide it in the picker.
    val pickDatabase = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            if (repo.importFrom(uri)) repo.recompute()
            busy = false
        }
    }

    // The stored key is never held in UI state — only its fingerprint, refreshed
    // explicitly after an import or removal so recomposition doesn't keep hitting the
    // Keystore.
    var ringKeyFp by remember { mutableStateOf(ringKeys.fingerprint()) }

    // A .key file is plain hex text, but has no registered MIME type — same reason the
    // database picker uses "*/*".
    val pickRingKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.US_ASCII) }
                }.getOrNull()
            }
            if (text != null) ringKeys.save(text)
            ringKeyFp = ringKeys.fingerprint()
        }
    }

    var healthState by remember { mutableStateOf(HealthConnectState.UNSUPPORTED) }
    var healthBusy by remember { mutableStateOf(false) }
    var healthAuto by remember { mutableStateOf(HealthExport.autoExport(app)) }

    // Health Connect's own permission flow: its UI, its grant, and the result comes back
    // as the set it actually gave rather than a yes/no.
    val askHealth = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        healthState = if (granted.containsAll(HealthExport.PERMISSIONS)) {
            HealthConnectState.READY
        } else {
            messages.show("Not all the write permissions were granted.")
            HealthConnectState.NEEDS_PERMISSION
        }
    }

    // BLE connection test. Bluetooth permissions are requested on demand rather than at
    // launch: the app is fully usable on an imported database without ever touching the
    // radio, so asking up front would be asking for nothing.
    var probing by remember { mutableStateOf(false) }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    fun runProbe() {
        scope.launch {
            probing = true
            probeStatus = "Scanning…"
            probeStatus = probeRing(app).summary()
            probing = false
        }
    }
    val askBlePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) runProbe() else probeStatus = "Bluetooth permission denied — can't reach the ring."
    }

    // Ring sync. The service owns the work and publishes progress, so this survives the
    // screen going off and a returning Activity picks up the current phase.
    val syncPhase by RingSyncService.phase.collectAsState()
    LaunchedEffect(syncPhase) {
        when (val p = syncPhase) {
            // A success is read at a glance, so it becomes a snackbar straight away; a
            // failure has to be read properly, so its card stays much longer.
            is SyncPhase.Done -> {
                messages.show(describeSync(p))
                RingSyncService.acknowledge()
            }
            is SyncPhase.Failed -> {
                delay(20_000)
                RingSyncService.acknowledge()
            }
            else -> Unit
        }
    }
    val askSyncPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // POST_NOTIFICATIONS may be refused without blocking the sync — only the progress
        // notification is lost — so the Bluetooth ones decide.
        if (BlePermissions.granted(app)) RingSyncService.start(app)
    }
    fun startRingSync() {
        if (RingSyncService.isRunning) return
        if (BlePermissions.granted(app)) {
            RingSyncService.start(app)
        } else {
            askSyncPermissions.launch(BlePermissions.required() + notificationPermission())
        }
    }

    // Panel fold state, persisted the way the web keeps it in localStorage: collapsed on
    // a first run, but a choice to open it survives restarting the app.
    var batteryOpen by remember { mutableStateOf(prefs.getBoolean("fold_battery", false)) }
    var deviceOpen by remember { mutableStateOf(prefs.getBoolean("fold_device", false)) }
    // Which capability is being written to the ring. Held here rather than in the panel
    // because the work outlives the panel; its outcome is reported in a snackbar.
    var capabilityBusy by remember { mutableStateOf<String?>(null) }

    val provider = entryProvider<NavKey> {
        entry<TodayRoute>(metadata = TabRootMotion) {
            TodayScreen(
                state = state,
                busy = busy,
                syncPhase = syncPhase,
                onSync = ::startRingSync,
                onDismissSync = { RingSyncService.acknowledge() },
                onRetrySummary = { scope.launch { busy = true; repo.recompute(); busy = false } },
                onImportDatabase = { pickDatabase.launch(arrayOf("*/*")) },
                onOpenDay = { day, sleep -> navigator.push(DayReportRoute(day, sleep, Tab.TODAY)) },
                onBrowseDays = { navigator.select(Tab.HISTORY) },
                onOpenRing = { navigator.select(Tab.RING) },
            )
        }
        entry<HistoryRoute>(metadata = TabRootMotion) {
            val summary = (state as? SummaryState.Ready)?.summary
            ScreenScaffold(
                title = "History",
                subtitle = summary?.let { "${it.days.size} days" },
                large = true,
            ) { padding ->
                if (summary != null) {
                    DaysBrowser(
                        summary = summary,
                        onOpenDay = { day, sleep -> navigator.push(DayReportRoute(day, sleep, Tab.HISTORY)) },
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding).padding(16.dp)) {
                        Notice("No days to show yet. Sync with the ring, or import a database.")
                    }
                }
            }
        }
        entry<RingRoute>(metadata = TabRootMotion) {
            // A stat call, but not one to repeat every frame: re-read when the summary
            // changes, which is exactly when a sync or an import has grown the file.
            val dbBytes = remember(state) { repo.databaseBytes() }
            RingScreen(
                databaseBytes = dbBytes,
                state = state,
                syncPhase = syncPhase,
                onSync = ::startRingSync,
                onDismissSync = { RingSyncService.acknowledge() },
                batteryExpanded = batteryOpen,
                onToggleBattery = {
                    batteryOpen = !batteryOpen
                    prefs.edit { putBoolean("fold_battery", batteryOpen) }
                },
                deviceExpanded = deviceOpen,
                onToggleDevice = {
                    deviceOpen = !deviceOpen
                    prefs.edit { putBoolean("fold_device", deviceOpen) }
                },
                busyCapability = capabilityBusy,
                onToggleCapability = { cap ->
                    // The ring holds one connection; a drain already has it.
                    if (RingSyncService.isRunning) {
                        messages.show("A sync is running — try again once it finishes.")
                    } else if (capabilityBusy == null) {
                        capabilityBusy = cap.feature
                        work.launch {
                            val phase = withContext(Dispatchers.IO) { runRingFeature(app, cap.feature, !cap.on) }
                            val outcome = when (phase) {
                                // The "wear the ring" half only makes sense one way round:
                                // nothing starts appearing because a capability was
                                // switched off.
                                is SyncPhase.Done -> if (cap.on) {
                                    "${cap.name} turned off. The ring stops recording it; history already synced stays."
                                } else {
                                    "${cap.name} turned on. Wear the ring; its events appear on the next sync."
                                }
                                is SyncPhase.Failed -> phase.message
                                else -> null
                            }
                            outcome?.let { messages.show(it, long = true) }
                            capabilityBusy = null
                            // The Rust side rewrote feature_modes.json, which the summary
                            // reads — so the switches only move once we recompute over it.
                            if (phase is SyncPhase.Done) repo.recompute()
                        }
                    }
                },
            )
        }
        entry<YouRoute>(metadata = TabRootMotion) {
            val profile = remember { profiles.read() }
            YouScreen(
                profile = profile,
                ringKeyFingerprint = ringKeyFp,
                onSaveProfile = { p ->
                    profiles.write(p)
                    messages.show("Profile saved. Recomputing your figures…")
                    scope.launch { busy = true; repo.recompute(); busy = false }
                },
                onRingKey = { navigator.push(RingKeyRoute) },
                onHealthConnect = { navigator.push(HealthConnectRoute) },
                onImportDatabase = { pickDatabase.launch(arrayOf("*/*")) },
                dynamicColor = dynamic,
                onDynamicColor = { setDynamic(it) },
            )
        }
        entry<DayReportRoute> { key ->
            // Which half is showing is the screen's own state, not a navigation step.
            var sleepTab by rememberSaveable { mutableStateOf(key.sleep) }
            val summary = (state as? SummaryState.Ready)?.summary
            ScreenScaffold(title = dayTitle(key.ymd), onBack = navigator::back) { padding ->
                if (summary != null) {
                    DayReportScreen(
                        summary = summary,
                        ymd = key.ymd,
                        sleepTab = sleepTab,
                        onSelectTab = { sleepTab = it },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
        entry<RingKeyRoute> {
            ScreenScaffold(title = "Ring key", onBack = navigator::back) { padding ->
                RingKeyScreen(
                    fingerprint = ringKeyFp,
                    onSave = { text ->
                        val ok = ringKeys.save(text)
                        ringKeyFp = ringKeys.fingerprint()
                        ok
                    },
                    onImportFile = { pickRingKey.launch(arrayOf("*/*")) },
                    onRemove = {
                        ringKeys.clear()
                        ringKeyFp = null
                    },
                    probing = probing,
                    probeStatus = probeStatus,
                    onTestConnection = {
                        if (BlePermissions.granted(app)) runProbe() else askBlePermissions.launch(BlePermissions.required())
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
        entry<HealthConnectRoute> {
            // Re-read whenever the screen opens: the user can revoke these from system
            // settings while the app sits in the background.
            LaunchedEffect(Unit) {
                healthState = when (HealthExport.status(app)) {
                    HealthConnectClient.SDK_UNAVAILABLE -> HealthConnectState.UNSUPPORTED
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectState.NEEDS_UPDATE
                    else -> if (HealthExport.hasPermissions(app)) HealthConnectState.READY else HealthConnectState.NEEDS_PERMISSION
                }
            }
            val ready = state as? SummaryState.Ready
            ScreenScaffold(title = "Health Connect", onBack = navigator::back) { padding ->
                HealthConnectScreen(
                    state = healthState,
                    nights = ready?.summary?.nights?.size ?: 0,
                    days = ready?.summary?.activityDaily?.size ?: 0,
                    busy = healthBusy,
                    autoExport = healthAuto,
                    onAutoExport = {
                        healthAuto = it
                        HealthExport.setAutoExport(app, it)
                    },
                    onGrant = { askHealth.launch(HealthExport.PERMISSIONS) },
                    onExport = {
                        val summary = (state as? SummaryState.Ready)?.summary
                        if (summary == null) {
                            messages.show("No summary computed yet — sync first.")
                        } else if (!healthBusy) {
                            healthBusy = true
                            work.launch {
                                val outcome = when (val res = HealthExport.export(app, summary)) {
                                    is ExportResult.Wrote ->
                                        "Exported ${res.total} records — ${res.sleep} nights, " +
                                            "${res.vitals} nightly vitals, ${res.activity} daily totals."
                                    ExportResult.NoPermission -> {
                                        healthState = HealthConnectState.NEEDS_PERMISSION
                                        "Health Connect revoked the write permissions."
                                    }
                                    ExportResult.Unavailable -> "Health Connect is not available."
                                    is ExportResult.Failed -> res.message
                                }
                                messages.show(outcome, long = true)
                                healthBusy = false
                            }
                        }
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    // One decorated entry list per tab, so a tab's screens keep their state (scroll
    // position, typed text) while another tab is showing.
    val entriesByTab = Tab.entries.associateWith { tab ->
        rememberDecoratedNavEntries(
            backStack = navigator.stack(tab),
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = provider,
        )
    }
    val today = entriesByTab.getValue(Tab.TODAY)
    val visible = if (navigator.tab == Tab.TODAY) today else today + entriesByTab.getValue(navigator.tab)

    // The Sync button folds to its icon while content scrolls down and opens again on the
    // way back up, so it never sits on top of what is being read.
    var fabExpanded by remember { mutableStateOf(true) }
    val fabScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -1f) fabExpanded = false else if (available.y > 1f) fabExpanded = true
                return Offset.Zero
            }
        }
    }
    val showFab = navigator.atTabRoot && (navigator.tab == Tab.TODAY || navigator.tab == Tab.RING)
    val motion = MaterialTheme.motionScheme

    CompositionLocalProvider(LocalAppMessages provides messages) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                ShortNavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = navigator.tab == tab
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { navigator.select(tab) },
                            icon = {
                                Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null)
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = showFab,
                    enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                    exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.defaultEffectsSpec()),
                ) {
                    SyncFab(syncPhase, expanded = fabExpanded, onClick = ::startRingSync)
                }
            },
        ) { inner ->
            NavDisplay(
                entries = visible,
                onBack = navigator::back,
                transitionSpec = pushMotion(motion),
                popTransitionSpec = popMotion(motion),
                predictivePopTransitionSpec = { popMotion(motion)() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = inner.calculateBottomPadding())
                    .nestedScroll(fabScroll),
            )
        }
    }
}

@Composable
private fun SyncFab(phase: SyncPhase, expanded: Boolean, onClick: () -> Unit) {
    val running = phase as? SyncPhase.Running
    ExtendedFloatingActionButton(
        text = {
            Text(
                when {
                    running == null -> "Sync"
                    running.progress != null -> "Syncing ${(running.progress * 100).roundToInt()}%"
                    else -> "Syncing…"
                },
            )
        },
        icon = {
            if (running != null) {
                LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current)
            } else {
                Icon(painterResource(R.drawable.ic_sym_sync), contentDescription = null)
            }
        },
        onClick = { if (running == null) onClick() },
        expanded = expanded || running != null,
    )
}

// Pushed screens move along the X axis with the motion scheme's springs; tab roots fade
// through, since switching tabs is a jump sideways rather than a step deeper.

private fun pushMotion(m: MotionScheme): AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
    (slideInHorizontally(m.defaultSpatialSpec<IntOffset>()) { it / 5 } + fadeIn(m.defaultEffectsSpec())) togetherWith
        (slideOutHorizontally(m.defaultSpatialSpec<IntOffset>()) { -it / 5 } + fadeOut(m.defaultEffectsSpec()))
}

private fun popMotion(m: MotionScheme): AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
    (slideInHorizontally(m.defaultSpatialSpec<IntOffset>()) { -it / 5 } + fadeIn(m.defaultEffectsSpec())) togetherWith
        (slideOutHorizontally(m.defaultSpatialSpec<IntOffset>()) { it / 5 } + fadeOut(m.defaultEffectsSpec()))
}

private val FadeThrough: AnimatedContentTransitionScope<Scene<*>>.() -> ContentTransform = {
    val m = MotionScheme.expressive()
    (fadeIn(m.defaultEffectsSpec()) + scaleIn(m.defaultSpatialSpec(), initialScale = 0.96f)) togetherWith
        fadeOut(m.fastEffectsSpec())
}

private val TabRootMotion: Map<String, Any> =
    NavDisplay.transitionSpec(FadeThrough) +
        NavDisplay.popTransitionSpec(FadeThrough) +
        NavDisplay.predictivePopTransitionSpec { _ -> FadeThrough() }

/**
 * POST_NOTIFICATIONS only exists from API 33. Requesting it below that throws, and the
 * sync's progress notification is the only thing it gates.
 */
private fun notificationPermission(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }
