package app.drokpo.android.catalog

import app.drokpo.android.features.profile.ProfileScreen

// Group 7 (profile + settings) owns this file. Replace the starter entry with ProfileContent,
// EditProfile, Settings, BlockedUsers and SentMessages content + Fixtures.profile / blockedUsers /
// sentMessages (CONTRACT.md §E).
val profileCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("profile.screen", "Profile (stub)") { ProfileScreen() },
)
