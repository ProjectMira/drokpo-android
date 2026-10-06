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
 * Port of ShareButton: toolbar/inline share affordance that presents ShareSheet for `content`.
 * Must live under MainTabs (ShareSheet needs LocalChatStore). (CONTRACT §B.11.)
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
