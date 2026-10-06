package app.drokpo.android.features.shared.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/** Port of CommunityPostContentView. Each callback null ⇒ that affordance hidden/disabled:
 *  onVote (poll taps), onRsvp (Join / Can't come), onOpenLink (CTA, also needs post.url),
 *  onOpenComments (comment button), onOpenCommunity (header tap → community page; iOS always a
 *  NavigationLink when communityId != null — every caller should pass it).
 *  (CONTRACT §B.9 — stub: community name, title, body.) */
@Composable
fun CommunityPostContentView(
    post: CommunityPostCard,
    modifier: Modifier = Modifier,
    onVote: ((optionId: String) -> Unit)? = null,
    onRsvp: ((going: Boolean) -> Unit)? = null,
    onOpenLink: (() -> Unit)? = null,
    onOpenComments: (() -> Unit)? = null,
    onOpenCommunity: ((cid: String) -> Unit)? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(post.communityName ?: "Community", style = typography.subheadline.bold(), color = colors.label)
        post.title?.let { Text(it, style = typography.headline, color = colors.label) }
        post.body?.takeIf { it.isNotEmpty() }?.let { Text(it, style = typography.subheadline, color = colors.secondaryLabel) }
        Text("CommunityPostContentView (${post.kind ?: "announcement"}) — TODO", style = typography.caption2, color = colors.tertiaryLabel)
    }
}
