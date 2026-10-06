package app.drokpo.android.features.communityhome

import androidx.compose.runtime.Composable
import app.drokpo.android.features.StubCover

/** Port of CommunityPostComposerView. Self-contained: renders its own FullScreenCover.
 *  On success: await onSaved(), then onDismissRequest(). (CONTRACT §B.8 — stub.) */
@Composable
fun CommunityPostComposerSheet(onSaved: suspend () -> Unit, onDismissRequest: () -> Unit) {
    StubCover("CommunityPostComposerSheet", "New post", onDismissRequest)
}
