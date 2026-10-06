package app.drokpo.android.features.shared.sharing

import androidx.compose.runtime.Composable
import app.drokpo.android.features.StubSheet

/** Port of ShareDestinationView: DrokpoSheet (skipPartiallyExpanded = true) with its own NavHost
 *  (loader start route + sharedDestinations(onCloseHost = onDismissRequest)). (CONTRACT §B.11 — stub.) */
@Composable
fun ShareDestinationSheet(destination: ShareDestination, onDismissRequest: () -> Unit) {
    StubSheet("ShareDestinationSheet(${destination.id})", "Shared", onDismissRequest)
}
