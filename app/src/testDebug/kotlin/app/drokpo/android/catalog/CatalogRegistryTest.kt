package app.drokpo.android.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT.md §E rules for the debug catalog: ids are unique, lowercase and dotted, and start
 * with their group's prefix, and every group contributes entries to [allCatalogEntries].
 * It also prints every id (one per line, `CATALOG_ID <id>`) into the test report, so the
 * emulator smoke test can launch each entry.
 */
class CatalogRegistryTest {
    private val groups = listOf(
        "shell", "auth", "onboarding", "communityonboarding", "feed", "likes", "chats",
        "profile", "communities", "sharedcommunity", "commentsaudio", "sharing",
    )

    @Test
    fun idsAreUniqueAndWellFormed() {
        val ids = allCatalogEntries.map { it.id }
        ids.forEach { println("CATALOG_ID $it") }
        assertEquals("duplicate ids: $duplicateCatalogIds", emptyList<String>(), duplicateCatalogIds)
        val malformed = ids.filterNot { ID_PATTERN.matches(it) }
        assertTrue("malformed ids: $malformed", malformed.isEmpty())
        val unknownGroup = allCatalogEntries.filter { it.group !in groups }.map { it.id }
        assertTrue("ids outside the known groups: $unknownGroup", unknownGroup.isEmpty())
    }

    @Test
    fun everyGroupContributesEntries() {
        val present = allCatalogEntries.map { it.group }.toSet()
        val missing = groups.filterNot { it in present }
        assertTrue("groups without catalog entries: $missing", missing.isEmpty())
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(\\.[a-z0-9-]+)+")
    }
}
