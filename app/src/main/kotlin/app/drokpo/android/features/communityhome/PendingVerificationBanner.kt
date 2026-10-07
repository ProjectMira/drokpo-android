package app.drokpo.android.features.communityhome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Port of PendingVerificationBanner (CONTRACT §B.8). Shown across every
 * community-account screen while `verification != "verified"`. Registration is
 * open — the community can post and appear in Discover right away — so this
 * is purely informational about what verification adds: the checkmark seal,
 * and (per the Discover deck) the ability to like people.
 *
 * Carries its own margins (16dp horizontal, 4dp top) like the iOS view.
 */
@Composable
fun PendingVerificationBanner(modifier: Modifier = Modifier) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Row(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp)
            .fillMaxWidth()
            .background(colors.orange.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Outlined.Schedule,
            contentDescription = null,
            tint = colors.orange,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Awaiting verification", style = typography.subheadline.bold(), color = colors.label)
            Text(
                "You can post and appear in Discover right away. The verified badge — and liking people " +
                    "from the deck — unlock once your community is approved.",
                style = typography.caption,
                color = colors.secondaryLabel,
            )
        }
    }
}

@DrokpoPreviews
@Composable
private fun PendingVerificationBannerPreview() {
    DrokpoTheme { PendingVerificationBanner() }
}
