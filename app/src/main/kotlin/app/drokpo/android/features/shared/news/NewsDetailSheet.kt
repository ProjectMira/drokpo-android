package app.drokpo.android.features.shared.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.StubSheet
import app.drokpo.android.ui.components.PrimaryButton
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/** Port of NewsDetailSheet: DrokpoSheet (skipPartiallyExpanded = true), title "News", Close, ShareButton(.News).
 *  (CONTRACT §B.9 — stub.) */
@Composable
fun NewsDetailSheet(item: NewsCard, onReadFullStory: () -> Unit, onDismissRequest: () -> Unit) {
    StubSheet("NewsDetailSheet(${item.newsId})", "News", onDismissRequest)
}

/** Port of NewsDetailContent (scrollable body is the CALLER's job — wrap in verticalScroll).
 *  (CONTRACT §B.9 — stub: title, body, "Read the full story".) */
@Composable
fun NewsDetailContent(item: NewsCard, onReadFullStory: () -> Unit, modifier: Modifier = Modifier) {
    val typography = DrokpoTheme.typography
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item.title?.let { Text(it, style = typography.title2.bold(), color = DrokpoTheme.colors.label) }
        val body = item.summary?.takeIf { it.isNotEmpty() } ?: item.gist
        body?.let { Text(it, style = typography.body, color = DrokpoTheme.colors.label) }
        Text("NewsDetailContent — TODO", style = typography.caption2, color = DrokpoTheme.colors.tertiaryLabel)
        PrimaryButton("Read the full story", onClick = onReadFullStory, modifier = Modifier.fillMaxWidth())
    }
}
