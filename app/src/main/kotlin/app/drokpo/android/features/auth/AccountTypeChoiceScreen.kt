package app.drokpo.android.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.drokpo.android.core.AccountType
import app.drokpo.android.core.AppGraph
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.PlainTextButton
import app.drokpo.android.ui.theme.DrokpoLogo
import app.drokpo.android.ui.theme.DrokpoPreviews
import app.drokpo.android.ui.theme.DrokpoTheme
import app.drokpo.android.ui.theme.bold

/**
 * Port of AccountTypeChoiceView. Shown once, right after first sign-in,
 * before either onboarding endpoint has been called — purely local routing
 * (SessionStore.chooseAccountType). The account only becomes real once the
 * matching onboarding flow submits.
 */
@Composable
fun AccountTypeChoiceScreen(modifier: Modifier = Modifier) {
    val session = AppGraph.session
    // The choice re-routes RootScreen through a crossfade, during which this
    // screen is still on screen and tappable: take the first choice only, so a
    // quick second tap on the other card can't flip the account type.
    var chosen by remember { mutableStateOf(false) }
    AccountTypeChoiceContent(
        onChoose = { type ->
            if (!chosen) {
                chosen = true
                session.chooseAccountType(type)
            }
        },
        onSignOut = session::signOut,
        modifier = modifier,
    )
}

/**
 * Stateless body of [AccountTypeChoiceScreen]: a bar with only a trailing
 * "Sign out", then (VStack spacing 20) Spacer, the 72dp logo + "Welcome to
 * Drokpo" + question, the two choice cards (20dp sides, 16dp apart), and two
 * Spacers — so the block sits a third of the way down.
 */
@Composable
internal fun AccountTypeChoiceContent(
    onChoose: (AccountType) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            DrokpoTopBar(
                title = "",
                actions = { PlainTextButton("Sign out", onClick = onSignOut) },
            )
        },
        containerColor = colors.background,
        contentColor = colors.label,
    ) { padding ->
        FillViewportColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.weight(1f))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DrokpoLogo(size = 72.dp)
                Text(
                    "Welcome to Drokpo",
                    style = typography.title2.bold(),
                    color = colors.label,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "How will you be using Drokpo?",
                    style = typography.subheadline,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ChoiceCard(
                    icon = Icons.Filled.Person,
                    title = "I'm here to make friends",
                    subtitle = "Build a personal profile, swipe to meet people nearby and across the diaspora.",
                    onClick = { onChoose(AccountType.Person) },
                )
                ChoiceCard(
                    icon = Icons.Filled.Business,
                    title = "Register a community or organization",
                    subtitle = "Share announcements, events, and polls with the community.",
                    footnote = "Community accounts are reviewed before they appear publicly.",
                    onClick = { onChoose(AccountType.Community) },
                )
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * iOS `choiceCard`: top-aligned row — tinted icon (title2, 32 wide), title
 * (headline) / subtitle (subheadline, secondary) / optional footnote (caption,
 * tertiary), then a tertiary chevron — padded 16 on a 14-rounded
 * `.quaternary.opacity(0.5)` fill.
 */
@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    footnote: String? = null,
) {
    val colors = DrokpoTheme.colors
    val typography = DrokpoTheme.typography
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.quaternaryLabel.copy(alpha = colors.quaternaryLabel.alpha * 0.5f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.TopCenter) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = typography.headline, color = colors.label)
            Text(subtitle, style = typography.subheadline, color = colors.secondaryLabel)
            if (footnote != null) {
                Text(footnote, style = typography.caption, color = colors.tertiaryLabel)
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = colors.tertiaryLabel,
            // Centred on the title's first line (headline line height 22).
            modifier = Modifier
                .padding(top = 3.dp)
                .size(16.dp),
        )
    }
}

@DrokpoPreviews
@Composable
private fun AccountTypeChoicePreview() {
    DrokpoTheme {
        AccountTypeChoiceContent(onChoose = {}, onSignOut = {})
    }
}
