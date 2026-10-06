package app.drokpo.android.features.communities

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.ui.theme.DrokpoTheme

/** Port of CommunityRow (logo 48 rounded 10, name + verified seal, "N member(s)").
 *  (CONTRACT §B.8 — stub: name + "CommunityRow — TODO".) */
@Composable
fun CommunityRow(community: CommunityProfile, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(community.name ?: "Community", style = DrokpoTheme.typography.headline, color = DrokpoTheme.colors.label)
        Text("CommunityRow — TODO", style = DrokpoTheme.typography.caption, color = DrokpoTheme.colors.secondaryLabel)
    }
}
