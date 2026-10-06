package app.drokpo.android.features.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubScreen

/** Port of FeedView (Discover tab root). No NavHost needed — iOS FeedView pushes nothing; everything
 *  it shows is a sheet/cover (§C.2). (CONTRACT §B.4 — stub.) */
@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    StubScreen("FeedScreen", "Discover", modifier)
}
