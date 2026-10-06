package app.drokpo.android.features.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.drokpo.android.features.StubScreen

/** Port of AccountTypeChoiceView. Calls AppGraph.session.chooseAccountType(...) / signOut(). (CONTRACT §B.1 — stub.) */
@Composable
fun AccountTypeChoiceScreen(modifier: Modifier = Modifier) {
    StubScreen("AccountTypeChoiceScreen", "Welcome to Drokpo", modifier)
}
