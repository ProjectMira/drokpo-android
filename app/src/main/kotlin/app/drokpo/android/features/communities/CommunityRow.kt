package app.drokpo.android.features.communities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.RemotePhotoView
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme

/** "1 member" / "N members" (iOS `"\(n) member\(n == 1 ? "" : "s")"`, nil counting as 0). */
internal fun memberCountLabel(count: Int?): String {
    val n = count ?: 0
    return if (n == 1) "$n member" else "$n members"
}

/**
 * Port of CommunityRow (CONTRACT §B.8): the community's logo (48, rounded 10),
 * its name with the verified seal, and "N member(s)". Used by the directory.
 */
@Composable
fun CommunityRow(community: CommunityProfile, modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemotePhotoView(
            photo = community.photos?.firstOrNull(),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    community.name ?: "Community",
                    style = typography.headline,
                    color = colors.label,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (community.isVerified) {
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = "Verified",
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                memberCountLabel(community.memberCount),
                style = typography.caption,
                color = colors.secondaryLabel,
            )
        }
    }
}

@DrokpoPreviews
@Composable
private fun CommunityRowPreview() {
    DrokpoTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CommunityRow(CommunityProfile(uid = "c1", name = "Tibetan Association of Toronto", verification = "verified", memberCount = 128))
            CommunityRow(CommunityProfile(uid = "c2", name = "Dharamshala Youth Circle", verification = "pending", memberCount = 1))
        }
    }
}
