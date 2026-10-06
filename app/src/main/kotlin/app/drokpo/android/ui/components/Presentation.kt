package app.drokpo.android.ui.components

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/**
 * True inside a [DrokpoSheet]. [DrokpoTopBar] reads it to drop the status-bar
 * inset (a sheet never sits under the status bar). CONTRACT.md §A.12.
 */
val LocalInsideSheet: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * Provides a fresh `LocalViewModelStoreOwner` for [content] and clears its
 * ViewModels when [content] leaves composition — the lifetime of iOS `@State`
 * in a presented view (CONTRACT.md §D.3). Used by MainTabs (session scope),
 * [DrokpoSheet] and [FullScreenCover] (presentation scope); onboarding flows
 * wrap themselves so a sign-out / sign-in starts them fresh.
 *
 * MainActivity declares `configChanges` for everything Compose handles in
 * place, so the scope is not torn down by rotation / dark-mode switches.
 * The owner offers the default (no-arg / initializer) factory only: create
 * models with `viewModel { FooViewModel(…) }`; `createSavedStateHandle()` is
 * not available in this scope.
 */
@Composable
fun ScopedViewModels(content: @Composable () -> Unit) {
    val owner = remember { ScopedViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

private class ScopedViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}

/**
 * iOS `.sheet` → ModalBottomSheet (CONTRACT.md §A.12).
 * [skipPartiallyExpanded] = true ≙ detents `[.large]` (iOS default, no drag
 * handle, like iOS); false ≙ `[.medium, .large]` (half height first, with a
 * grabber). Provides [LocalInsideSheet] = true and a presentation-scoped
 * ViewModel owner ([ScopedViewModels]). System back / swipe down / scrim tap →
 * [onDismissRequest].
 *
 * Put a `DrokpoTopBar` (navIcon = Close) at the top of [content] for iOS's
 * in-sheet NavigationStack toolbar.
 */
@Composable
fun DrokpoSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = DrokpoTheme.colors.background,
        contentColor = DrokpoTheme.colors.label,
        dragHandle = if (skipPartiallyExpanded) null else ({ BottomSheetDefaults.DragHandle() }),
    ) {
        val scope = this
        CompositionLocalProvider(LocalInsideSheet provides true) {
            ScopedViewModels { scope.content() }
        }
    }
}

/**
 * iOS `.fullScreenCover`, and the form sheets the contract maps to it (Edit
 * profile, Settings, Composer, Phone sign-in): a full-screen, edge-to-edge
 * Dialog on the system background with a presentation-scoped ViewModel owner
 * (CONTRACT.md §A.12). System back → [onDismissRequest] (unless an inner
 * NavHost pops first). Content handles its own insets — a `Scaffold` with a
 * `DrokpoTopBar` does; add `imePadding()` around text-entry content.
 */
@Composable
fun FullScreenCover(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val view = LocalView.current
        val dark = DrokpoTheme.isDark
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            // The dialog has its own window: keep its bar icons readable and
            // let the IME resize content instead of covering text fields.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.setDimAmount(0f)
        }
        ScopedViewModels {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(DrokpoTheme.colors.background),
            ) {
                content()
            }
        }
    }
}

@DrokpoPreviews
@Composable
private fun PresentationPreview() {
    // Sheets/dialogs open their own windows; preview the in-sheet top bar look.
    DrokpoTheme {
        CompositionLocalProvider(LocalInsideSheet provides true) {
            Box(Modifier.background(DrokpoTheme.colors.background)) {
                DrokpoTopBar(title = "Comments", navIcon = NavIcon.Close)
            }
        }
        Text("Sheet content", color = DrokpoTheme.colors.secondaryLabel)
    }
}
