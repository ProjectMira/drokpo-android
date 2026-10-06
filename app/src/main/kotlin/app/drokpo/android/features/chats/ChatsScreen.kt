package app.drokpo.android.features.chats

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubScreen

/** Port of ChatsView (Chats tab root). Owns its NavHost (§C.4); consumes push deep links.
 *  (CONTRACT §B.6 — stub.) */
@Composable
fun ChatsScreen(modifier: Modifier = Modifier) {
    StubScreen("ChatsScreen", "Chats", modifier, large = true)
}
