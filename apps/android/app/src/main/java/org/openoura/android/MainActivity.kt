package org.openoura.android

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.openoura.android.ui.theme.OpenOuraTheme
import uniffi.oura_core.coreVersion
import uniffi.oura_core.rmssd

private const val TAG = "OpenOura"

private data class CoreProbe(val version: String, val rmssd: String)

/**
 * Phase 0 proving harness: calls the shared Rust core over UniFFI and shows what came
 * back. `coreVersion()` proves the .so loads and the JNA bridge works; `rmssd()` proves a
 * real computation crosses the boundary with arguments and a return value.
 *
 * Replaced in Phase 1 by the real summary rendering.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val probe = CoreProbe(
            version = runCatching { coreVersion() }.getOrElse { "FFI FAILED: $it" },
            rmssd = runCatching { rmssd(listOf(800u, 820u, 810u, 850u, 830u)) }
                .map { "%.3f ms".format(it) }
                .getOrElse { "FFI FAILED: $it" },
        )
        Log.i(TAG, "core_version=${probe.version} rmssd=${probe.rmssd}")

        setContent {
            OpenOuraTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("open_oura", style = MaterialTheme.typography.headlineMedium)
                        Text("Rust core ${probe.version}", style = MaterialTheme.typography.bodyLarge)
                        Text("RMSSD probe: ${probe.rmssd}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
