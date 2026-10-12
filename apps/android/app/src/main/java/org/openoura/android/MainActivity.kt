package org.openoura.android

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.openoura.android.data.ProfileStore
import org.openoura.android.data.RingKeyStore
import org.openoura.android.data.SummaryRepository
import org.openoura.android.sync.AutoSync
import org.openoura.android.sync.SyncTrigger
import org.openoura.android.ui.app.OpenOuraApp
import org.openoura.android.ui.theme.OpenOuraTheme
import org.openoura.android.ui.theme.ThemePrefs
import org.openoura.android.widget.WidgetUpdates

class MainActivity : ComponentActivity() {

    /**
     * Opening the app is the opportunistic sync trigger, and it belongs here rather than in
     * [onCreate].
     *
     * In onCreate it only fired on a cold start. Returning to an Activity the system had
     * kept alive — which is most of the time someone opens this app — created nothing at
     * all; verified on 2026-09-18 by bringing the existing task to the front and watching
     * the log stay empty, then force-stopping and seeing it fire at once.
     *
     * [AutoSync.decide] still applies the 3-hour floor, so this costs an enqueue per
     * resume, not a sync.
     */
    override fun onResume() {
        super.onResume()
        AutoSync.requestSync(applicationContext, SyncTrigger.APP_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent navigation bar in both themes: the app's own bottom bar sits behind
        // it, and with 3-button navigation the default would lay a grey scrim over it.
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false

        val repo = SummaryRepository.get(applicationContext)
        // Render the cached snapshot immediately; only compute when there is nothing
        // cached. A full recompute is an explicit user action or a post-sync step rather
        // than something that blocks every launch.
        lifecycleScope.launch { repo.load(refresh = false) }
        WidgetUpdates.schedulePeriodic(applicationContext)
        AutoSync.schedulePeriodic(applicationContext)

        setContent {
            var dynamic by remember { mutableStateOf(ThemePrefs.dynamicColor(applicationContext)) }
            OpenOuraTheme(dynamicColor = dynamic) {
                OpenOuraApp(
                    repo = repo,
                    profiles = ProfileStore(applicationContext),
                    ringKeys = RingKeyStore(applicationContext),
                    work = lifecycleScope,
                    dynamicColor = dynamic,
                    onDynamicColor = { on ->
                        dynamic = on
                        ThemePrefs.setDynamicColor(applicationContext, on)
                        // The widgets read the same setting; redraw them to match.
                        lifecycleScope.launch { WidgetUpdates.refreshAll(applicationContext) }
                    },
                )
            }
        }
    }
}
