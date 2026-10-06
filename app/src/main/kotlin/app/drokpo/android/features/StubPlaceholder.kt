package app.drokpo.android.features

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.components.DrokpoSheet
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.FullScreenCover
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.theme.DrokpoTheme

// Placeholder bodies for entry points whose owning feature group hasn't ported
// them yet (CONTRACT.md §A.16). Each stub keeps its final public signature and
// renders one of these, so the shell runs end to end and every host can open
// and close what it presents. Feature groups replace their stub bodies; delete
// this file once nothing references it.

/** Centred "<Name> — TODO" label. The caller sizes it. */
@Composable
internal fun StubPlaceholder(name: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "$name — TODO",
            style = DrokpoTheme.typography.subheadline,
            color = DrokpoTheme.colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** A screen stub: the iOS navigation title in a [DrokpoTopBar] over a [StubPlaceholder]. */
@Composable
internal fun StubScreen(
    name: String,
    title: String,
    modifier: Modifier = Modifier,
    navIcon: NavIcon = NavIcon.None,
    onNavIcon: () -> Unit = {},
    large: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        topBar = { DrokpoTopBar(title, navIcon = navIcon, onNavIcon = onNavIcon, large = large) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        StubPlaceholder(name, Modifier.padding(padding).fillMaxSize())
    }
}

/** A self-contained `FooSheet` stub: a [DrokpoSheet] with a Close button. */
@Composable
internal fun StubSheet(
    name: String,
    title: String,
    onDismissRequest: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    DrokpoSheet(onDismissRequest = onDismissRequest, skipPartiallyExpanded = skipPartiallyExpanded) {
        DrokpoTopBar(title, navIcon = NavIcon.Close, onNavIcon = onDismissRequest)
        StubPlaceholder(name, Modifier.fillMaxWidth().height(if (skipPartiallyExpanded) 480.dp else 280.dp))
        extra()
    }
}

/** A self-contained full-screen (`.fullScreenCover` / form sheet) stub with a Close button. */
@Composable
internal fun StubCover(name: String, title: String, onDismissRequest: () -> Unit) {
    FullScreenCover(onDismissRequest = onDismissRequest) {
        StubScreen(name, title, navIcon = NavIcon.Close, onNavIcon = onDismissRequest)
    }
}
