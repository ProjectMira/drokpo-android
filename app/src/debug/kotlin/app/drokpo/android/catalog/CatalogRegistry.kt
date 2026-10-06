package app.drokpo.android.catalog

/**
 * Every catalog entry, in display order: the shell first, then the 11 feature groups. Each group
 * owns its `<Group>Catalog.kt`; this aggregation is foundation-owned (CONTRACT.md §E).
 */
val allCatalogEntries: List<CatalogEntry> =
    shellCatalogEntries +
        authCatalogEntries +
        onboardingCatalogEntries +
        communityOnboardingCatalogEntries +
        feedCatalogEntries +
        likesCatalogEntries +
        chatsCatalogEntries +
        profileCatalogEntries +
        communitiesCatalogEntries +
        sharedCommunityCatalogEntries +
        commentsAudioCatalogEntries +
        sharingCatalogEntries

/** Ids that appear more than once — shown as a warning banner in the catalog list. */
internal val duplicateCatalogIds: List<String> =
    allCatalogEntries.groupBy { it.id }.filterValues { it.size > 1 }.keys.toList()
