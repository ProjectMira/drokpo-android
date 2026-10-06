package app.drokpo.android.catalog

import app.drokpo.android.features.likes.LikesScreen

// Group 5 (likes) owns this file. Replace the starter entry with LikesContent states (You liked /
// Liked you, filters, empty, loading) + Fixtures.receivedLikes / givenLikes / likedContent.
val likesCatalogEntries: List<CatalogEntry> = listOf(
    CatalogEntry("likes.screen", "Likes (stub)") { LikesScreen() },
)
