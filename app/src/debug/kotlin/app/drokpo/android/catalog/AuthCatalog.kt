package app.drokpo.android.catalog

import app.drokpo.android.features.auth.AccountTypeChoiceScreen
import app.drokpo.android.features.auth.SignInScreen

// Group 1 (auth) owns this file. Starter entries render the shell's stubs — replace them with
// SignInContent / PhoneSignInContent / AccountTypeChoiceContent + fixtures (CONTRACT.md §E): the
// real *Screen composables reach AuthService / AppGraph.session.
val authCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("auth.signin", "Sign in (stub)") { SignInScreen() },
    CatalogEntry("auth.accounttype", "Account type choice (stub)") { AccountTypeChoiceScreen() },
)
