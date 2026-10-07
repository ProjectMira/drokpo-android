package app.drokpo.android.features.shared.sharing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Port of ShareButton: toolbar/inline share affordance that presents
 * [ShareSheet] for [content]. Must live under MainTabs' subtree (ShareSheet
 * needs [app.drokpo.android.features.chats.LocalChatStore]). The icon takes
 * the surrounding content colour — accent in a top bar's actions.
 * iOS `square.and.arrow.up` → the Android share glyph. (CONTRACT §B.11.)
 */
@Composable
fun ShareButton(content: ShareableContent, modifier: Modifier = Modifier) {
    var isPresented by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { isPresented = true }, modifier = modifier) {
        Icon(Icons.Filled.Share, contentDescription = "Share")
    }
    if (isPresented) {
        ShareSheet(content, onDismissRequest = { isPresented = false })
    }
}
