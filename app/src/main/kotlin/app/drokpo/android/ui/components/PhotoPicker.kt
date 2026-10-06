package app.drokpo.android.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * iOS `PhotosPicker` → the system photo picker (no permission needed),
 * CONTRACT.md §A.12. Returns the launch function; images only.
 *
 * [maxItems] == 1 → `PickVisualMedia`; > 1 → `PickMultipleVisualMedia(maxItems)`
 * (whose constructor throws for maxItems ≤ 1 — hidden here); ≤ 0 → launching
 * is a no-op. [onPicked] gets the chosen URIs; cancelling calls nothing, like iOS.
 */
@Composable
fun rememberPhotoPicker(maxItems: Int = 1, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) currentOnPicked(listOf(uri))
    }
    val multipleContract = remember(maxItems) {
        ActivityResultContracts.PickMultipleVisualMedia(maxItems.coerceAtLeast(2))
    }
    val multiple = rememberLauncherForActivityResult(multipleContract) { uris ->
        if (uris.isNotEmpty()) currentOnPicked(uris)
    }
    return remember(maxItems, single, multiple) {
        {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            when {
                maxItems <= 0 -> Unit
                maxItems == 1 -> single.launch(request)
                else -> multiple.launch(request)
            }
        }
    }
}
