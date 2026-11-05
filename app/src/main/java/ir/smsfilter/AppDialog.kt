package ir.smsfilter

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider

/** The app's text-size choice, already multiplied by the system font scale. */
internal val LocalAppDensity = compositionLocalOf { Density(1f, 1f) }

/**
 * Every AlertDialog goes through here.
 *
 * A dialog is its own window: Compose gives it the window's density, so a page that honoured the
 * system font scale still showed dialogs at the default size. Re-applying the app density to the
 * dialog's content is the only place that can be fixed - the dialog root resets LocalDensity.
 */
@Composable
internal fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val density = LocalAppDensity.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Scaled(density, title) },
        text = { Scaled(density, text) },
        confirmButton = { Scaled(density, confirmButton) },
        dismissButton = dismissButton?.let { button -> { Scaled(density, button) } }
    )
}

@Composable
private fun Scaled(density: Density, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides density) { content() }
}
