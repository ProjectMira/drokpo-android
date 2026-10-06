package app.drokpo.android.features.shared.sharing

import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.drokpo.android.features.StubSheet
import app.drokpo.android.ui.components.PlainTextButton

/** Port of ShareSheetView: DrokpoSheet (skipPartiallyExpanded = false).
 *  (CONTRACT §B.11 — stub: only the "Outside Drokpo" system share works.) */
@Composable
fun ShareSheet(content: ShareableContent, onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    StubSheet("ShareSheet(${content.id})", "Share", onDismissRequest, skipPartiallyExpanded = false) {
        PlainTextButton(
            "Share via WhatsApp, Messages…",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content.messageText)
                    putExtra(Intent.EXTRA_SUBJECT, content.title)
                }
                context.startActivity(Intent.createChooser(send, null))
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        )
    }
}
