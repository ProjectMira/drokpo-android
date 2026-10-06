package app.drokpo.android.features.auth

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubPlaceholder

/** Port of SignInView. Needs LocalActivity for AuthService calls. (CONTRACT §B.1 — stub.) */
@Composable
fun SignInScreen(modifier: Modifier = Modifier) {
    StubPlaceholder("SignInScreen", modifier.fillMaxSize())
}
