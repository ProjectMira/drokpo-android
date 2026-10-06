package app.drokpo.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.semibold
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** SwiftUI `ButtonRole`: plain, `.cancel`, `.destructive`. */
enum class ActionRole { Default, Cancel, Destructive }

/** One button of a [ConfirmationSheet] (`Button("…", role:) { … }` inside `.confirmationDialog`). */
@Immutable
data class ConfirmationAction(
    val label: String,
    val role: ActionRole = ActionRole.Default,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * `.confirmationDialog(title, isPresented:, titleVisibility:) { … } message: { … }`
 * as a bottom action sheet: optional [title] and [message] (shown like iOS
 * `titleVisibility: .visible`), one row per action — accent text, system red
 * for [ActionRole.Destructive] — and a separate bold "Cancel" row. iOS always
 * adds Cancel; so does this (swipe-down / scrim tap also cancel).
 *
 * Show it conditionally: `if (showDelete) ConfirmationSheet(onDismissRequest = { showDelete = false }, …)`.
 * Tapping an action hides the sheet, then calls [onDismissRequest], then the
 * action — so an action may present the next dialog (Safety → Report → reasons).
 *
 * Example (Settings → Delete account):
 * ```
 * ConfirmationSheet(
 *     title = "Delete your account?",
 *     message = "Your profile, photos, likes, and matches will be permanently removed. This cannot be undone.",
 *     actions = listOf(ConfirmationAction("Delete everything", ActionRole.Destructive) { deleteAccount() }),
 *     onDismissRequest = { showDeleteConfirmation = false },
 * )
 * ```
 */
@Composable
fun ConfirmationSheet(
    onDismissRequest: () -> Unit,
    actions: List<ConfirmationAction>,
    title: String? = null,
    message: String? = null,
    cancelLabel: String = "Cancel",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // One action per presentation. The rows stay tappable while the sheet animates down, and a
    // second hide() cancels the first through the sheet's MutatorMutex — invokeOnCompletion runs
    // on cancellation too — so without this a double tap on "Delete everything" / "Block" / a
    // report reason would run the action twice. Plain (non-state) flag: it's only read in click
    // handlers. Re-armed after the action so a caller that keeps this sheet composed can reuse it.
    val handled = remember { AtomicBoolean(false) }
    val dismissThen: (() -> Unit) -> Unit = { then ->
        if (handled.compareAndSet(false, true)) {
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                onDismissRequest()
                then()
                handled.set(false)
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = DrokpoTheme.colors.secondaryGroupedBackground,
    ) {
        ConfirmationSheetContent(
            title = title,
            message = message,
            actions = actions.filter { it.role != ActionRole.Cancel },
            cancelLabel = actions.firstOrNull { it.role == ActionRole.Cancel }?.label ?: cancelLabel,
            onAction = { action -> dismissThen(action.onClick) },
            onCancel = {
                val cancel = actions.firstOrNull { it.role == ActionRole.Cancel }
                dismissThen { cancel?.onClick?.invoke() }
            },
        )
    }
}

/** One row of an [ActionSheet] (CONTRACT.md §A.12). */
@Immutable
data class ActionSheetItem(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * iOS `.confirmationDialog` as specified by CONTRACT.md §A.12: a bottom sheet
 * listing [items] plus a Cancel row. Tapping an item calls [onDismissRequest]
 * first, then `item.onClick()` (so chaining to a second ActionSheet works —
 * Safety → "Why are you reporting this profile?"). `title == null` ≙ iOS
 * `titleVisibility` hidden. Same rendering as [ConfirmationSheet].
 */
@Composable
fun ActionSheet(
    onDismissRequest: () -> Unit,
    items: List<ActionSheetItem>,
    title: String? = null,
    message: String? = null,
    cancelLabel: String = "Cancel",
) {
    ConfirmationSheet(
        onDismissRequest = onDismissRequest,
        actions = items.map { item ->
            ConfirmationAction(
                label = item.label,
                role = if (item.destructive) ActionRole.Destructive else ActionRole.Default,
                onClick = item.onClick,
            )
        },
        title = title,
        message = message,
        cancelLabel = cancelLabel,
    )
}

@Composable
private fun ConfirmationSheetContent(
    title: String?,
    message: String?,
    actions: List<ConfirmationAction>,
    cancelLabel: String,
    onAction: (ConfirmationAction) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (title != null || message != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (title != null) {
                    Text(title, style = typography.footnote.semibold(), color = colors.secondaryLabel, textAlign = TextAlign.Center)
                }
                if (message != null) {
                    Text(message, style = typography.footnote, color = colors.secondaryLabel, textAlign = TextAlign.Center)
                }
            }
            HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
        }
        actions.forEachIndexed { index, action ->
            if (index > 0) HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
            SheetRow(
                label = action.label,
                color = when {
                    !action.enabled -> colors.tertiaryLabel
                    action.role == ActionRole.Destructive -> colors.destructive
                    else -> colors.accent
                },
                bold = false,
                enabled = action.enabled,
                onClick = { onAction(action) },
            )
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(colors.groupedBackground),
        )
        SheetRow(label = cancelLabel, color = colors.accent, bold = true, enabled = true, onClick = onCancel)
    }
}

@Composable
private fun SheetRow(label: String, color: androidx.compose.ui.graphics.Color, bold: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = DrokpoTheme.typography.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

/** One `.alert` button. Any button dismisses the alert first, like iOS. */
@Immutable
data class AlertButton(
    val label: String,
    val role: ActionRole = ActionRole.Default,
    val onClick: () -> Unit = {},
)

/**
 * `.alert(title, isPresented:) { buttons } message: { Text(message) }`.
 * The [ActionRole.Cancel] button (if any) goes in the dismiss slot on the
 * left, semibold like iOS; the other goes on the right; destructive buttons
 * are system red.
 *
 * ```
 * // "It's a match!" — Say hi / Later
 * DrokpoAlert(
 *     title = "It's a match!",
 *     message = "You and $name liked each other.",
 *     onDismissRequest = { matched = null },
 *     confirmButton = AlertButton("Say hi") { openThread() },
 *     dismissButton = AlertButton("Later", ActionRole.Cancel),
 * )
 * ```
 */
@Composable
fun DrokpoAlert(
    title: String,
    onDismissRequest: () -> Unit,
    message: String? = null,
    confirmButton: AlertButton = AlertButton("OK", ActionRole.Cancel),
    dismissButton: AlertButton? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography

    @Composable
    fun AlertAction(button: AlertButton) {
        TextButton(
            onClick = {
                onDismissRequest()
                button.onClick()
            },
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (button.role == ActionRole.Destructive) colors.destructive else colors.accent,
            ),
        ) {
            Text(
                button.label,
                style = typography.body.copy(
                    fontWeight = if (button.role == ActionRole.Cancel) FontWeight.SemiBold else FontWeight.Normal,
                ),
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { AlertAction(confirmButton) },
        dismissButton = dismissButton?.let { { AlertAction(it) } },
        title = { Text(title, style = typography.headline, color = colors.label) },
        text = message?.let { { Text(it, style = typography.subheadline, color = colors.label) } },
        containerColor = DrokpoTheme.colors.let { if (it.isDark) it.tertiaryBackground else it.background },
    )
}

/**
 * The app's most common alert: `.alert("Something went wrong", isPresented:
 * errorMessage != nil) { OK } message: { errorMessage }`. Renders nothing
 * while [message] is null; [onDismiss] should clear it.
 */
@Composable
fun ErrorAlert(
    message: String?,
    onDismiss: () -> Unit,
    title: String = "Something went wrong",
) {
    if (message == null) return
    DrokpoAlert(title = title, message = message, onDismissRequest = onDismiss)
}

@DrokpoPreviews
@Composable
private fun ConfirmationSheetPreview() {
    DrokpoTheme {
        Box(Modifier.background(DrokpoTheme.colors.secondaryGroupedBackground)) {
            ConfirmationSheetContent(
                title = "Why are you reporting this profile?",
                message = null,
                actions = listOf(
                    ConfirmationAction("Fake profile", ActionRole.Destructive) {},
                    ConfirmationAction("Inappropriate photos", ActionRole.Destructive) {},
                    ConfirmationAction("Harassment", ActionRole.Destructive) {},
                ),
                cancelLabel = "Cancel",
                onAction = {},
                onCancel = {},
            )
        }
    }
}

@DrokpoPreviews
@Composable
private fun AlertPreview() {
    DrokpoTheme {
        DrokpoAlert(
            title = "It's a match!",
            message = "You and Pema liked each other.",
            onDismissRequest = {},
            confirmButton = AlertButton("Say hi"),
            dismissButton = AlertButton("Later", ActionRole.Cancel),
        )
    }
}
