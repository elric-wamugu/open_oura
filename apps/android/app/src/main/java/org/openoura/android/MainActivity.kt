package org.openoura.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.openoura.android.data.ProfileStore
import org.openoura.android.data.SummaryRepository
import org.openoura.android.data.SummaryState
import org.openoura.android.ui.DayReportScreen
import org.openoura.android.ui.DaysBrowser
import org.openoura.android.ui.HomeScreen
import org.openoura.android.ui.ProfileScreen
import org.openoura.android.ui.theme.OpenOuraTheme

class MainActivity : ComponentActivity() {

    private lateinit var repo: SummaryRepository
    private lateinit var profiles: ProfileStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = SummaryRepository(applicationContext)
        profiles = ProfileStore(applicationContext)

        // Render the cached snapshot immediately; only compute when there is nothing
        // cached. A full recompute is an explicit user action (and, from Phase 4, a
        // post-sync step) rather than something that blocks every launch.
        lifecycleScope.launch { repo.load(refresh = false) }

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

                // Which day report is open, and whether on the sleep or activity tab.
                var openDay by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
                var browsing by remember { mutableStateOf(false) }
                var editingProfile by remember { mutableStateOf(false) }

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
                    } else if (editingProfile) {
                        ProfileScreen(
                            initial = profiles.read(),
                            onSave = { p ->
                                profiles.write(p)
                                editingProfile = false
                                scope.launch { busy = true; repo.recompute(); busy = false }
                            },
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
                            busy = busy,
                            onRefresh = {
                                scope.launch {
                                    busy = true
                                    repo.recompute()
                                    busy = false
                                }
                            },
                            onImportDatabase = { pickDatabase.launch(arrayOf("*/*")) },
                            onOpenDay = { day, sleep -> openDay = day to sleep },
                            onBrowseDays = { browsing = true },
                            onProfile = { editingProfile = true },
                            modifier = Modifier.padding(inner),
                        )
                    }
                }
            }
        }
    }
}
