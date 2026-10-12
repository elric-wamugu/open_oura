package org.openoura.android.ui.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Short, transient outcomes — "Synced 293 events", "Profile saved" — shown as snackbars
 * over the bottom bar. Anything the user has to act on stays on the page instead.
 */
class AppMessages(private val host: SnackbarHostState, private val scope: CoroutineScope) {
    /** [long] keeps it up longer and adds a dismiss button, for sentences worth reading. */
    fun show(message: String, long: Boolean = false) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(
                message,
                withDismissAction = long,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }
}

val LocalAppMessages = staticCompositionLocalOf<AppMessages> {
    error("AppMessages is provided by OpenOuraApp")
}
