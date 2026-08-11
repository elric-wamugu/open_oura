package org.openoura.android

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openoura.android.ble.BlePermissions
import org.openoura.android.ble.RingSyncService
import org.openoura.android.ble.SyncPhase
import org.openoura.android.ble.describeSync
import org.openoura.android.ble.probeRing
import org.openoura.android.data.ProfileStore
import org.openoura.android.data.RingKeyStore
import org.openoura.android.data.SummaryRepository
import org.openoura.android.data.SummaryState
import org.openoura.android.ui.DayReportScreen
import org.openoura.android.ui.DaysBrowser
import org.openoura.android.ui.HomeScreen
import org.openoura.android.ui.ProfileScreen
import org.openoura.android.ui.RingKeyScreen
import org.openoura.android.ui.theme.OpenOuraTheme

class MainActivity : ComponentActivity() {

    private lateinit var repo: SummaryRepository
    private lateinit var profiles: ProfileStore
    private lateinit var ringKeys: RingKeyStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = SummaryRepository.get(applicationContext)
        profiles = ProfileStore(applicationContext)
        ringKeys = RingKeyStore(applicationContext)
        // Panel fold state, persisted the way the web keeps it in localStorage: collapsed
        // on a first run, but a choice to open it should survive restarting the app.
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)

        // Render the cached snapshot immediately; only compute when there is nothing
        // cached. A full recompute is an explicit user action (and, from Phase 4, a
        // post-sync step) rather than something that blocks every launch.
        lifecycleScope.launch { repo.load(refresh = false) }
        org.openoura.android.widget.WidgetUpdates.schedulePeriodic(applicationContext)

        setContent {
            OpenOuraTheme {
                val state by repo.state.collectAsState()
                var busy by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                // SAF picker: the user grants access to one file, so the app needs no
                // storage permission. "*/*" because a .db has no registered MIME type and
                // narrower filters hide it in the picker.
                val pickDatabase = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        busy = true
                        if (repo.importFrom(uri)) repo.recompute()
                        busy = false
                    }
                }

                // The stored key is never held in UI state — only its fingerprint, refreshed
                // explicitly after an import or removal so recomposition doesn't repeatedly
                // hit the Keystore.
                var ringKeyFp by remember { mutableStateOf(ringKeys.fingerprint()) }

                // A .key file is plain hex text, but has no registered MIME type — same
                // reason the database picker uses "*/*".
                val pickRingKey = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openInputStream(uri)?.use {
                                    it.readBytes().toString(Charsets.US_ASCII)
                                }
                            }.getOrNull()
                        }
                        if (text != null) ringKeys.save(text)
                        ringKeyFp = ringKeys.fingerprint()
                    }
                }

                // Which day report is open, and whether on the sleep or activity tab.
                var openDay by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
                var browsing by remember { mutableStateOf(false) }
                var editingProfile by remember { mutableStateOf(false) }
                var editingRingKey by remember { mutableStateOf(false) }

                // BLE connection test. Bluetooth permissions are requested on demand rather
                // than at launch: the app is fully usable on an imported database without
                // ever touching the radio, so asking up front would be asking for nothing.
                var probing by remember { mutableStateOf(false) }
                var probeStatus by remember { mutableStateOf<String?>(null) }
                fun runProbe() {
                    scope.launch {
                        probing = true
                        probeStatus = "Scanning…"
                        probeStatus = probeRing(applicationContext).summary()
                        probing = false
                    }
                }
                val askBlePermissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { granted ->
                    if (granted.values.all { it }) {
                        runProbe()
                    } else {
                        probeStatus = "Bluetooth permission denied — can't reach the ring."
                    }
                }

                // Ring sync. The service owns the work and publishes progress, so this
                // survives the screen going off and a returning Activity picks up the
                // current phase rather than showing a stale one.
                val syncPhase by RingSyncService.phase.collectAsState()
                LaunchedEffect(syncPhase) {
                    // A success is read at a glance; a failure has to be read properly, so
                    // it gets much longer before it clears itself.
                    when (syncPhase) {
                        is SyncPhase.Done -> {
                            delay(6_000)
                            RingSyncService.acknowledge()
                        }
                        is SyncPhase.Failed -> {
                            delay(20_000)
                            RingSyncService.acknowledge()
                        }
                        else -> Unit
                    }
                }
                val askSyncPermissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { granted ->
                    // POST_NOTIFICATIONS may be refused without blocking the sync — only
                    // the progress notification is lost — so the Bluetooth ones decide.
                    if (BlePermissions.granted(applicationContext)) {
                        RingSyncService.start(applicationContext)
                    }
                }
                fun startRingSync() {
                    if (BlePermissions.granted(applicationContext)) {
                        RingSyncService.start(applicationContext)
                    } else {
                        val wanted = BlePermissions.required() + notificationPermission()
                        askSyncPermissions.launch(wanted)
                    }
                }
                var batteryOpen by remember { mutableStateOf(prefs.getBoolean("fold_battery", false)) }

                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    val ready = state as? SummaryState.Ready
                    val sel = openDay
                    if (sel != null && ready != null) {
                        DayReportScreen(
                            summary = ready.summary,
                            ymd = sel.first,
                            sleepTab = sel.second,
                            onSelectTab = { sleep -> openDay = sel.first to sleep },
                            onBack = { openDay = null },
                            modifier = Modifier.padding(inner),
                        )
                    } else if (editingRingKey) {
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
                                if (BlePermissions.granted(applicationContext)) {
                                    runProbe()
                                } else {
                                    askBlePermissions.launch(BlePermissions.required())
                                }
                            },
                            // Back returns to Profile, which is where this was reached from.
                            onBack = { editingRingKey = false },
                            modifier = Modifier.padding(inner),
                        )
                    } else if (editingProfile) {
                        ProfileScreen(
                            initial = profiles.read(),
                            ringKeyFingerprint = ringKeyFp,
                            onSave = { p ->
                                profiles.write(p)
                                editingProfile = false
                                scope.launch { busy = true; repo.recompute(); busy = false }
                            },
                            onRingKey = { editingRingKey = true },
                            onBack = { editingProfile = false },
                            modifier = Modifier.padding(inner),
                        )
                    } else if (browsing && ready != null) {
                        DaysBrowser(
                            summary = ready.summary,
                            onOpenDay = { day, sleep -> browsing = false; openDay = day to sleep },
                            onBack = { browsing = false },
                            modifier = Modifier.padding(inner),
                        )
                    } else {
                        HomeScreen(
                            state = state,
                            busy = busy || syncPhase is SyncPhase.Running,
                            onRefresh = {
                                scope.launch {
                                    busy = true
                                    repo.recompute()
                                    busy = false
                                }
                            },
                            onSyncFromRing = { startRingSync() },
                            syncStatus = if (syncPhase is SyncPhase.Idle) {
                                null
                            } else {
                                describeSync(syncPhase)
                            },
                            syncProgress = (syncPhase as? SyncPhase.Running)?.progress,
                            syncing = syncPhase is SyncPhase.Running,
                            onImportDatabase = { pickDatabase.launch(arrayOf("*/*")) },
                            onOpenDay = { day, sleep -> openDay = day to sleep },
                            onBrowseDays = { browsing = true },
                            onProfile = { editingProfile = true },
                            batteryExpanded = batteryOpen,
                            onToggleBattery = {
                                batteryOpen = !batteryOpen
                                prefs.edit().putBoolean("fold_battery", batteryOpen).apply()
                            },
                            modifier = Modifier.padding(inner),
                        )
                    }
                }
            }
        }
    }
}

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
