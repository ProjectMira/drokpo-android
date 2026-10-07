package app.drokpo.android.features.shared.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drokpo.android.core.PhotoBand
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.features.shared.sharing.ShareButton
import app.drokpo.android.features.shared.sharing.ShareableContent
import app.drokpo.android.ui.components.DrokpoSheet
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.LocalInsideSheet
import app.drokpo.android.ui.components.LocalSheetDismiss
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.ProminentButton
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Tap-through detail for a news card: full summary, source attribution, and a button to open the
 * source article in the in-app browser. Used as a sheet from the Discover deck;
 * ShareDestinationSheet reuses [NewsDetailContent] for stories arriving via share links. Port of
 * NewsDetailSheet (CONTRACT §B.9): DrokpoSheet (iOS `[.large]`), title "News", Close, Share.
 * [onReadFullStory] is the caller's (it records the click, closes this sheet and opens the link).
 */
@Composable
fun NewsDetailSheet(item: NewsCard, onReadFullStory: () -> Unit, onDismissRequest: () -> Unit) {
    DrokpoSheet(onDismissRequest = onDismissRequest, skipPartiallyExpanded = true) {
        NewsDetailSheetContent(
            item = item,
            onReadFullStory = onReadFullStory,
            onClose = LocalSheetDismiss.current ?: onDismissRequest,
        )
    }
}

/** The sheet's page: top bar over the scrolling [NewsDetailContent]. */
@Composable
internal fun NewsDetailSheetContent(
    item: NewsCard,
    onReadFullStory: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val insideSheet = LocalInsideSheet.current
    Column(
        modifier
            .fillMaxSize()
            .background(DrokpoTheme.colors.background),
    ) {
        DrokpoTopBar(
            title = "News",
            navIcon = NavIcon.Close,
            onNavIcon = onClose,
            actions = { ShareButton(ShareableContent.News(item)) },
        )
        NewsDetailContent(
            item = item,
            onReadFullStory = onReadFullStory,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .then(if (insideSheet) Modifier else Modifier.navigationBarsPadding()),
        )
    }
}

/**
 * The scrollable body of a news story's detail view (scrolling is the CALLER's job — wrap it in
 * `verticalScroll`). The image is a PhotoBand — a fixed-aspect, clipped container — because an
 * unclipped scaled-to-fill photo once inflated the whole sheet wider than the screen.
 */
@Composable
fun NewsDetailContent(item: NewsCard, onReadFullStory: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(
        modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item.displayPhotos.firstOrNull()?.let { photo ->
            PhotoBand(
                photo = photo,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.sourceName?.takeIf { it.isNotEmpty() }?.let { sourceName ->
                Text(sourceName.uppercase(), style = typography.caption.bold(), color = colors.secondaryLabel)
            }
            item.relativePublished?.let { relative ->
                Text("· $relative", style = typography.caption, color = colors.tertiaryLabel)
            }
        }
        Text(item.title ?: "—", style = typography.title2.bold(), color = colors.label)
        Text(
            newsBody(item),
            // `.lineSpacing(5)`: five extra points between lines.
            style = typography.body.copy(lineHeight = typography.body.lineHeight.value.plus(5).sp),
            color = colors.label,
        )
        ProminentButton(
            onClick = onReadFullStory,
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Text("Read the full story", style = typography.headline, textAlign = TextAlign.Center)
        }
    }
}

/** `item.summary?.isEmpty == false ? item.summary! : item.gist ?? ""`. */
internal fun newsBody(item: NewsCard): String = item.summary?.takeIf { it.isNotEmpty() } ?: item.gist ?: ""
