package app.drokpo.android.catalog

import androidx.compose.runtime.Composable

/**
 * One debug-catalog screen (CONTRACT.md §E). [id] is `"<group>.<screen>[.<state>]"`, lowercase with
 * dots, unique across the catalog; [content] renders full-screen inside DrokpoTheme with fixture
 * data only — no viewModel(), ApiClient, Firebase or AppGraph.session/deepLinks mutations.
 */
data class CatalogEntry(val id: String, val title: String, val content: @Composable () -> Unit) {
    /** The owning group: the id up to its first dot ("feed.deck.news" → "feed"). */
    val group: String get() = id.substringBefore('.')
}
