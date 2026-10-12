package org.openoura.android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * One screen's chrome: its own top app bar over its content.
 *
 * The bottom bar, snackbars and the Sync button belong to the app-wide scaffold around
 * the navigation display, which already pads the bottom; so this one takes no window
 * insets of its own beyond what the top bar needs for the status bar.
 *
 * [large] gives a tab's root screen the Expressive flexible title that collapses into a
 * normal bar as the content scrolls; pushed screens use the compact bar with Back.
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    large: Boolean = false,
    onBack: (() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = if (large) {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    } else {
        TopAppBarDefaults.pinnedScrollBehavior()
    }
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    val nav: @Composable () -> Unit = { if (onBack != null) BackButton(onBack) }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            when {
                large -> LargeFlexibleTopAppBar(
                    title = { Text(title) },
                    subtitle = subtitle?.let { { Text(it) } },
                    navigationIcon = nav,
                    actions = actions,
                    colors = colors,
                    scrollBehavior = scroll,
                )
                subtitle != null -> TopAppBar(
                    title = titleContent ?: { Text(title) },
                    subtitle = { Text(subtitle) },
                    navigationIcon = nav,
                    actions = actions,
                    colors = colors,
                    scrollBehavior = scroll,
                )
                else -> TopAppBar(
                    title = titleContent ?: { Text(title) },
                    navigationIcon = nav,
                    actions = actions,
                    colors = colors,
                    scrollBehavior = scroll,
                )
            }
        },
        content = content,
    )
}
