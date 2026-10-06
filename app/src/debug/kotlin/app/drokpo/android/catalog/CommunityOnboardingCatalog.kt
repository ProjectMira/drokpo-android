package app.drokpo.android.catalog

import app.drokpo.android.features.communityonboarding.CommunityOnboardingFlowScreen

// Group 3 (communityonboarding) owns this file. Replace the starter entry with per-step content +
// fixtures (CONTRACT.md §E): the real flow screen reaches AppGraph.session.
val communityOnboardingCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("communityonboarding.flow", "Community onboarding flow (stub)") { CommunityOnboardingFlowScreen() },
)
