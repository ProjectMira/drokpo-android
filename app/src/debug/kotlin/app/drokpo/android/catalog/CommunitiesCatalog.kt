package app.drokpo.android.catalog

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.drokpo.android.core.AppearanceMode
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.features.communities.CommunitiesContent
import app.drokpo.android.features.communities.CommunitiesUiState
import app.drokpo.android.features.communities.CommunityDirectoryContent
import app.drokpo.android.features.communities.CommunityMembersContent
import app.drokpo.android.features.communities.CommunityRow
import app.drokpo.android.features.communities.DirectoryUiState
import app.drokpo.android.features.communities.DiscoverCommunityCard
import app.drokpo.android.features.communities.JoinedCommunityAvatar
import app.drokpo.android.features.communities.MembersUiState
import app.drokpo.android.features.communities.SponsoredFeedRow
import app.drokpo.android.features.communityhome.CommunityEditorFields
import app.drokpo.android.features.communityhome.CommunityEditorUiState
import app.drokpo.android.features.communityhome.CommunityPostComposerContent
import app.drokpo.android.features.communityhome.CommunityPostDraft
import app.drokpo.android.features.communityhome.CommunityProfileEditorContent
import app.drokpo.android.features.communityhome.CommunitySettingsContent
import app.drokpo.android.features.communityhome.CommunitySettingsUiState
import app.drokpo.android.features.communityhome.PHOTO_LOAD_ERROR
import app.drokpo.android.features.communityhome.PendingVerificationBanner
import app.drokpo.android.features.communityhome.PollOptionDraft
import app.drokpo.android.features.communityhome.PostKind
import app.drokpo.android.ui.components.DrokpoTopBar
import app.drokpo.android.ui.components.ListDivider
import app.drokpo.android.ui.components.NavIcon
import app.drokpo.android.ui.components.PlainSectionHeader
import app.drokpo.android.ui.theme.DrokpoTheme
import java.time.Instant
import java.time.temporal.ChronoUnit

// Group 8 (communities + communityhome). Every screen and meaningful state of
// CommunitiesScreen (home), the directory (pushed and as the community
// account's cover), members, CommunityRow and its sibling cells, the
// community profile editor, CommunitySettings, the post composer and the
// pending-verification banner — rendered through the stateless *Content
// composables with fixtures, no network or AppGraph (CONTRACT §E).

// ---------------------------------------------------------------- private fixtures

private val joinedCommunities: List<CommunityProfile> = Fixtures.communities.filter { it.joined == true }

private const val OFFLINE = "The Internet connection appears to be offline."

private val homeJoined = CommunitiesUiState(
    mine = joinedCommunities,
    items = Fixtures.communitiesHomeItems,
    isLoading = false,
    hasLoaded = true,
)

/** Six photos: the editor hides its "+" tile at the cap. */
private val communityFullPhotos: CommunityProfile = Fixtures.community.copy(
    photos = (1..6).map { Fixtures.photo("drokpo-tat-$it", order = it - 1) },
)

private val communityNoPhotos: CommunityProfile = Fixtures.communityPending.copy(photos = emptyList())

private fun editorState(
    community: CommunityProfile = Fixtures.community,
    fields: CommunityEditorFields = CommunityEditorFields.from(community),
    isSaving: Boolean = false,
    justSaved: Boolean = false,
    isWorking: Boolean = false,
    errorMessage: String? = null,
) = CommunityEditorUiState(
    community = community,
    fields = fields,
    isSaving = isSaving,
    justSaved = justSaved,
    isWorking = isWorking,
    errorMessage = errorMessage,
)

private val inThreeDays: Instant = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS)

private fun draft(
    kind: PostKind = PostKind.Announcement,
    title: String = "",
    body: String = "",
    linkUrl: String = "",
    ctaLabel: String = "",
    pollOptions: List<PollOptionDraft> = listOf(PollOptionDraft("o1"), PollOptionDraft("o2")),
    eventLocation: String = "",
    pickedImage: Uri? = null,
) = CommunityPostDraft(
    kind = kind,
    title = title,
    body = body,
    linkUrl = linkUrl,
    ctaLabel = ctaLabel,
    pollOptions = pollOptions,
    eventDate = inThreeDays,
    eventLocation = eventLocation,
    pickedImage = pickedImage,
)

// ---------------------------------------------------------------- renderers

@Composable
private fun Home(state: CommunitiesUiState) {
    CommunitiesContent(
        state = state,
        onClose = {},
        onOpenDirectory = {},
        onOpenCommunity = { _, _ -> },
        onRefresh = {},
        onVote = { _, _ -> },
        onRsvp = { _, _ -> },
        onOpenPostLink = {},
        onOpenAd = {},
        onOpenComments = {},
        onDismissError = {},
    )
}

@Composable
private fun Directory(state: DirectoryUiState, navIcon: NavIcon = NavIcon.Back) {
    CommunityDirectoryContent(
        state = state,
        navIcon = navIcon,
        onBack = {},
        onOpenCommunity = { _, _ -> },
        onToggleJoin = {},
        onRefresh = {},
        onDismissError = {},
    )
}

@Composable
private fun Members(state: MembersUiState) {
    CommunityMembersContent(state = state, onBack = {}, onRefresh = {}, onDismissError = {})
}

@Composable
private fun Editor(state: CommunityEditorUiState) {
    CommunityProfileEditorContent(
        state = state,
        onFieldsChange = {},
        onSave = {},
        onOpenSettings = {},
        onAddPhoto = {},
        onDeletePhoto = {},
        onRefresh = {},
        onDismissError = {},
    )
}

@Composable
private fun Settings(
    state: CommunitySettingsUiState = CommunitySettingsUiState(),
    signedInAs: String = "hello@tibetan-toronto.example.org",
    confirmingDelete: Boolean = false,
) {
    CommunitySettingsContent(
        state = state,
        appearance = AppearanceMode.System,
        signedInAs = signedInAs,
        versionLabel = "1.1 (37)",
        onClose = {},
        onAppearanceChange = {},
        onOpenPrivacyPolicy = {},
        onSignOut = {},
        onDeleteConfirmed = {},
        onDismissError = {},
        initiallyConfirmingDelete = confirmingDelete,
    )
}

@Composable
private fun Composer(
    draft: CommunityPostDraft,
    isSaving: Boolean = false,
    canSave: Boolean = draft.canSave(Instant.now(), isSaving),
    errorMessage: String? = null,
) {
    CommunityPostComposerContent(
        draft = draft,
        isSaving = isSaving,
        canSave = canSave,
        errorMessage = errorMessage,
        earliestEventDate = { Instant.now() },
        onDraftChange = {},
        onEventDateChange = {},
        onChoosePhoto = {},
        onRemovePhoto = {},
        onPost = {},
        onCancel = {},
        onDismissError = {},
    )
}

/** Plain-background screen with a title, for the stateless cells. */
@Composable
private fun CellsScreen(title: String, content: @Composable () -> Unit) {
    Scaffold(
        topBar = { DrokpoTopBar(title, navIcon = NavIcon.Back) },
        containerColor = DrokpoTheme.colors.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------- entries

val communitiesCatalogEntries: List<CatalogEntry> = listOf(
    // CommunitiesScreen (person's browse cover) — home
    CatalogEntry("communities.home.joined", "Communities — joined rail + feed (posts, ad)") { Home(homeJoined) },
    CatalogEntry("communities.home.discover", "Communities — nothing joined: communities to discover") {
        Home(CommunitiesUiState(discover = Fixtures.communities, isLoading = false, hasLoaded = true))
    },
    CatalogEntry("communities.home.discover.empty", "Communities — nothing joined, nothing to discover") {
        Home(CommunitiesUiState(isLoading = false, hasLoaded = true))
    },
    CatalogEntry("communities.home.feed.empty", "Communities — joined, no posts yet") {
        Home(homeJoined.copy(items = emptyList()))
    },
    CatalogEntry("communities.home.ad.only", "Communities — feed with sponsored cards (with and without image)") {
        Home(homeJoined.copy(items = listOf(FeedItem.Ad(Fixtures.ad), FeedItem.Ad(Fixtures.adNoImage))))
    },
    CatalogEntry("communities.home.loading", "Communities — first load") { Home(CommunitiesUiState()) },
    CatalogEntry("communities.home.refreshing", "Communities — pull-to-refresh in progress") {
        Home(homeJoined.copy(isLoading = true, isRefreshing = true))
    },
    CatalogEntry("communities.home.error", "Communities — load failed (alert)") {
        Home(CommunitiesUiState(isLoading = false, errorMessage = OFFLINE))
    },

    // Directory ("Discover communities")
    CatalogEntry("communities.directory", "Discover communities — pushed (Back)") {
        Directory(DirectoryUiState(communities = Fixtures.communities, isLoading = false, hasLoaded = true))
    },
    CatalogEntry("communities.directory.cover", "Discover communities — community account's cover (Close)") {
        Directory(DirectoryUiState(communities = Fixtures.communities, isLoading = false, hasLoaded = true), NavIcon.Close)
    },
    CatalogEntry("communities.directory.joining", "Discover communities — a join in flight (all buttons disabled)") {
        Directory(
            DirectoryUiState(
                communities = Fixtures.communities,
                isLoading = false,
                hasLoaded = true,
                workingCid = Fixtures.communities[2].id,
            ),
        )
    },
    CatalogEntry("communities.directory.empty", "Discover communities — empty") {
        Directory(DirectoryUiState(isLoading = false, hasLoaded = true))
    },
    CatalogEntry("communities.directory.loading", "Discover communities — first load") { Directory(DirectoryUiState()) },
    CatalogEntry("communities.directory.error", "Discover communities — join failed (alert)") {
        Directory(
            DirectoryUiState(
                communities = Fixtures.communities,
                isLoading = false,
                hasLoaded = true,
                errorMessage = "Only person accounts can join communities.",
            ),
        )
    },

    // Members
    CatalogEntry("communities.members", "Members") {
        Members(MembersUiState(members = Fixtures.members, isLoading = false))
    },
    CatalogEntry("communities.members.empty", "Members — empty") { Members(MembersUiState(isLoading = false)) },
    CatalogEntry("communities.members.loading", "Members — loading") { Members(MembersUiState()) },
    CatalogEntry("communities.members.error", "Members — not a member (alert)") {
        Members(MembersUiState(isLoading = false, errorMessage = "Only members can view this community's member list"))
    },

    // Cells
    CatalogEntry("communities.rows", "CommunityRow — all fixtures") {
        CellsScreen("CommunityRow") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Fixtures.communities.forEach { CommunityRow(it) }
                CommunityRow(CommunityProfile(uid = "c-unnamed", memberCount = null))
                CommunityRow(
                    CommunityProfile(
                        uid = "c-long",
                        name = "The Tibetan Cultural and Educational Association of Greater Vancouver",
                        verification = "verified",
                        memberCount = 1,
                    ),
                )
            }
        }
    },
    CatalogEntry("communities.cells", "Rail avatars, discover cards, sponsored rows") {
        CellsScreen("Community cells") {
            PlainSectionHeader("Joined rail")
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                joinedCommunities.forEach { JoinedCommunityAvatar(it) }
                JoinedCommunityAvatar(CommunityProfile(uid = "c-noname"))
            }
            PlainSectionHeader("Communities to discover")
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Fixtures.communities.takeLast(2).forEach { DiscoverCommunityCard(it) }
            }
            PlainSectionHeader("Sponsored")
            SponsoredFeedRow(Fixtures.ad, onOpen = {}, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            ListDivider()
            SponsoredFeedRow(Fixtures.adNoImage, onOpen = {}, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    },
    CatalogEntry("communities.banner", "PendingVerificationBanner") {
        CellsScreen("Discover") { PendingVerificationBanner() }
    },

    // Community profile editor (community account's Profile tab)
    CatalogEntry("communities.editor.verified", "Community editor — verified, all fields") { Editor(editorState()) },
    CatalogEntry("communities.editor.pending", "Community editor — pending (banner), few fields") {
        Editor(editorState(community = Fixtures.communityPending))
    },
    CatalogEntry("communities.editor.nophotos", "Community editor — no photos yet") {
        Editor(editorState(community = communityNoPhotos))
    },
    CatalogEntry("communities.editor.photos.full", "Community editor — 6 photos (no + tile)") {
        Editor(editorState(community = communityFullPhotos))
    },
    CatalogEntry("communities.editor.blocker", "Community editor — dirty with a save blocker (red footer, Save disabled)") {
        Editor(editorState(fields = CommunityEditorFields.from(Fixtures.community).copy(name = "T")))
    },
    CatalogEntry("communities.editor.blocker.website", "Community editor — website blocker") {
        Editor(
            editorState(fields = CommunityEditorFields.from(Fixtures.community).copy(website = "http://example.org")),
        )
    },
    CatalogEntry("communities.editor.saving", "Community editor — saving") {
        Editor(editorState(fields = CommunityEditorFields.from(Fixtures.community).copy(phone = "+1 416 555 0000"), isSaving = true))
    },
    CatalogEntry("communities.editor.saved", "Community editor — Saved ✓ (2 s)") {
        Editor(editorState(isSaving = true, justSaved = true))
    },
    CatalogEntry("communities.editor.working", "Community editor — photo upload in flight") {
        Editor(editorState(isWorking = true))
    },
    CatalogEntry("communities.editor.error", "Community editor — error alert") {
        Editor(editorState(errorMessage = "That photo couldn't be processed. Try a different one."))
    },

    // Community settings
    CatalogEntry("communities.settings", "Community settings") { Settings() },
    CatalogEntry("communities.settings.phone", "Community settings — phone sign-in") { Settings(signedInAs = "+1 416 555 0134") },
    CatalogEntry("communities.settings.confirm", "Community settings — delete confirmation") { Settings(confirmingDelete = true) },
    CatalogEntry("communities.settings.deleting", "Community settings — deleting") {
        Settings(state = CommunitySettingsUiState(isDeleting = true))
    },
    CatalogEntry("communities.settings.error", "Community settings — couldn't delete (alert)") {
        Settings(state = CommunitySettingsUiState(errorMessage = OFFLINE))
    },

    // Post composer
    CatalogEntry("communities.composer.announcement", "New post — announcement, empty (Post disabled)") {
        Composer(draft())
    },
    CatalogEntry("communities.composer.announcement.filled", "New post — announcement, filled") {
        Composer(
            draft(
                title = "Saga Dawa prayers this Sunday",
                body = "Join us at the centre from 9am. Butter lamps and tsampa provided.",
            ),
        )
    },
    CatalogEntry("communities.composer.link", "New post — link") {
        Composer(
            draft(
                kind = PostKind.Link,
                title = "Volunteer for the Losar festival",
                body = "We need 40 volunteers.",
                linkUrl = "https://example.org/volunteer",
                ctaLabel = "Sign up",
            ),
        )
    },
    CatalogEntry("communities.composer.link.invalid", "New post — link without https (Post disabled)") {
        Composer(draft(kind = PostKind.Link, title = "Volunteer", linkUrl = "example.org"))
    },
    CatalogEntry("communities.composer.poll", "New post — poll, 3 options (remove + add)") {
        Composer(
            draft(
                kind = PostKind.Poll,
                title = "When should we hold the summer picnic?",
                pollOptions = listOf(
                    PollOptionDraft("o1", "Saturday afternoon"),
                    PollOptionDraft("o2", "Sunday morning"),
                    PollOptionDraft("o3", ""),
                ),
            ),
        )
    },
    CatalogEntry("communities.composer.poll.full", "New post — poll, 4 options (no Add option)") {
        Composer(
            draft(
                kind = PostKind.Poll,
                title = "Pick a venue",
                pollOptions = (1..4).map { PollOptionDraft("o$it", "Venue $it") },
            ),
        )
    },
    CatalogEntry("communities.composer.poll.duplicate", "New post — poll with duplicate options (Post disabled)") {
        Composer(
            draft(
                kind = PostKind.Poll,
                title = "Tea or coffee?",
                pollOptions = listOf(PollOptionDraft("o1", "Tea"), PollOptionDraft("o2", "Tea")),
            ),
        )
    },
    CatalogEntry("communities.composer.event", "New post — event") {
        Composer(
            draft(
                kind = PostKind.Event,
                title = "Losar party 2027",
                body = "Dances, a momo feast and a raffle.",
                eventLocation = "Tibetan Canadian Cultural Centre",
                linkUrl = "https://example.org/losar-tickets",
                ctaLabel = "Get tickets",
            ),
        )
    },
    CatalogEntry("communities.composer.photo", "New post — photo chosen") {
        Composer(
            draft(
                title = "Our new hall",
                pickedImage = Fixtures.imageUrl("drokpo-composer-photo", 1200, 800).toUri(),
            ),
        )
    },
    CatalogEntry("communities.composer.saving", "New post — posting") {
        Composer(draft(title = "Saga Dawa prayers this Sunday"), isSaving = true)
    },
    CatalogEntry("communities.composer.error", "New post — couldn't load the photo (alert)") {
        Composer(draft(title = "Our new hall"), errorMessage = PHOTO_LOAD_ERROR)
    },
)
