package app.drokpo.android.catalog

import app.drokpo.android.core.BlockedUser
import app.drokpo.android.core.model.AdCard
import app.drokpo.android.core.model.CommentCard
import app.drokpo.android.core.model.CommunityAddress
import app.drokpo.android.core.model.CommunityMember
import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.ContactPerson
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.FeedItem
import app.drokpo.android.core.model.LikedContent
import app.drokpo.android.core.model.NewsCard
import app.drokpo.android.core.model.Photo
import app.drokpo.android.core.model.Poll
import app.drokpo.android.core.model.PollOption
import app.drokpo.android.core.model.Preferences
import app.drokpo.android.core.model.Profile
import app.drokpo.android.core.model.SentMessage
import app.drokpo.android.core.model.Socials
import app.drokpo.android.core.model.SwipeEntry
import app.drokpo.android.features.chats.ChatMessage
import app.drokpo.android.features.chats.ChatStore
import app.drokpo.android.features.shared.sharing.ShareableContent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Debug-only fixture data for the catalog (CONTRACT.md §E). Realistic enough
 * that screenshots look like the real app: remote photos come from
 * picsum.photos (stable per seed), timestamps are relative to process start so
 * "2h ago" labels stay meaningful, and the shapes mirror what the FastAPI
 * backend actually returns. Never used outside the debug source set.
 *
 * Group-private fixtures belong in the group's own catalog file; additions
 * here go through `shared_change_requests`.
 */
object Fixtures {
    const val MY_UID = "fixture-me"

    // ---------------------------------------------------------------- helpers

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    /** Remote image URL for a seed (debug only). */
    fun imageUrl(seed: String, width: Int = 600, height: Int = 800): String =
        "https://picsum.photos/seed/$seed/$width/$height"

    fun photo(seed: String, order: Int = 0): Photo =
        Photo(storagePath = "fixtures/photos/$seed.jpg", order = order, url = imageUrl(seed))

    private fun photosFor(name: String, count: Int): List<Photo> =
        (1..count).map { photo("drokpo-$name-$it", order = it - 1) }

    private fun instantAgo(minutes: Long): Instant = now.minus(minutes, ChronoUnit.MINUTES)

    /** Always seconds + "±HH:MM" (OffsetDateTime.toString() drops ":00" seconds and writes "Z"). */
    private val isoWithOffset: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx", Locale.US)

    /** Backend-style ISO 8601 (`datetime.isoformat()` of a UTC timestamp, "+00:00" offset). */
    private fun isoAgo(minutes: Long): String = isoWithOffset.format(OffsetDateTime.ofInstant(instantAgo(minutes), ZoneOffset.UTC))

    /** An event time `days` from today at a local `hour`, with the device's offset. */
    private fun isoAhead(days: Long, hour: Int): String =
        isoWithOffset.format(LocalDate.now().plusDays(days).atTime(LocalTime.of(hour, 0)).atZone(ZoneId.systemDefault()))

    private const val HOUR = 60L
    private const val DAY = 24 * HOUR

    // ---------------------------------------------------------------- people

    val photos: List<Photo> = photosFor("tenzin", 3)

    /** The signed-in person: complete profile, every optional field set. */
    val profile: Profile = Profile(
        uid = MY_UID,
        displayName = "Tenzin Dolma",
        dob = "1996-04-12",
        gender = "female",
        bio = "Product designer in Toronto by way of Dharamshala. Always up for momos, long walks and a good playlist.",
        occupation = "Product designer",
        education = "Master's",
        region = "North America",
        languages = listOf("Tibetan", "English", "Hindi"),
        interests = listOf("Momo cooking", "Hiking", "Photography", "Music", "Language exchange"),
        answers = mapOf(
            "lookingFor" to "New friends",
            "teaChoice" to "Butter tea",
            "travelledTo" to "Dharamshala, Kathmandu, Lhasa, Zurich",
            "favoriteMusic" to "Tenzin Choegyal, Phurbu T Namgyal, Norah Jones",
            "perfectWeekend" to "A morning hike, then a momo party with friends.",
        ),
        socials = Socials(instagram = "tenzin.dolma", youtube = "TenzinDolmaDesigns", tiktok = "tenzin.dolma"),
        photos = photos,
        preferences = Preferences(ageMin = 24, ageMax = 38, distanceKm = 80),
        onboardingComplete = true,
        discoverable = true,
    )

    /** Display name only — no photos, answers, socials or preferences. */
    val profileEmpty: Profile = Profile(uid = MY_UID, displayName = "Tenzin", onboardingComplete = true)

    /** A full person card: 3 photos, answers, socials, 12.4 km away. */
    val feedCard: FeedCard = FeedCard(
        uid = "u-pema",
        displayName = "Pema Lhamo",
        age = 27,
        dob = "1999-02-03",
        region = "Nepal",
        bio = "Thangka painter and weekend trekker. Looking for friends to explore Kathmandu's cafés with.",
        occupation = "Artist",
        education = "Bachelor's",
        languages = listOf("Tibetan", "Nepali", "English"),
        interests = listOf("Thangka painting", "Hiking", "Meditation", "Reading", "Travel"),
        answers = mapOf(
            "lookingFor" to "Friends first, then who knows",
            "teaChoice" to "Both, please",
            "favoriteMovies" to "Old Dog, Spirited Away",
            "perfectWeekend" to "Sketching at Boudha stupa, then dal bhat with friends.",
        ),
        socials = Socials(instagram = "pema.paints"),
        photos = photosFor("pema", 3),
        distanceKm = 12.4,
    )

    /** Name only — no photos, region or bio (tests every placeholder). */
    val feedCardMinimal: FeedCard = FeedCard(uid = "u-sonam", displayName = "Sonam")

    /** A community account served in the person-shaped card (kind = "community"). */
    val communityCard: FeedCard = FeedCard(
        uid = "c-tat",
        displayName = "Tibetan Association of Toronto",
        bio = "Community events, Losar celebrations and Tibetan language classes for all ages.",
        region = "Toronto, Canada",
        photos = listOf(photo("drokpo-tat-logo")),
        kind = "community",
    )

    private val karma = FeedCard(
        uid = "u-karma",
        displayName = "Karma Tsering",
        age = 31,
        dob = "1995-07-21",
        region = "India",
        bio = "Software engineer in Bengaluru. Basketball on Sundays, chess the rest of the week.",
        occupation = "Software engineer",
        education = "Bachelor's",
        languages = listOf("Tibetan", "Hindi", "English"),
        interests = listOf("Basketball", "Chess", "Gaming", "Movies"),
        answers = mapOf("lookingFor" to "New friends", "teaChoice" to "Chai"),
        socials = Socials(instagram = "karma.codes"),
        photos = photosFor("karma", 2),
        distanceKm = 3.1,
    )

    private val dechen = FeedCard(
        uid = "u-dechen",
        displayName = "Dechen Wangmo",
        age = 25,
        dob = "2001-01-09",
        region = "Europe",
        bio = "Nursing student in Zurich. I sing in the community choir and cook too much gyathuk.",
        occupation = "Nursing student",
        education = "Some college",
        languages = listOf("Tibetan", "German", "English"),
        interests = listOf("Singing", "Cooking", "Volunteering", "Dancing"),
        answers = mapOf("lookingFor" to "Community & events", "travelledTo" to "Paris, Dharamshala, Vienna"),
        photos = photosFor("dechen", 3),
        distanceKm = 640.0,
    )

    private val lobsang = FeedCard(
        uid = "u-lobsang",
        displayName = "Lobsang Gyatso",
        age = 34,
        dob = "1992-03-30",
        region = "Australia",
        bio = "Chef in Sydney. Ask me about the best momo dipping sauce.",
        occupation = "Chef",
        education = "Monastic education",
        languages = listOf("Tibetan", "English"),
        interests = listOf("Momo cooking", "Soccer", "Fitness"),
        answers = mapOf("teaChoice" to "Coffee person"),
        photos = photosFor("lobsang", 2),
        distanceKm = 25.0,
    )

    private val yangchen = FeedCard(
        uid = "u-yangchen",
        displayName = "Yangchen Dolkar",
        age = 29,
        dob = "1997-11-02",
        region = "Bhutan",
        bio = "Teacher in Thimphu. Books, board games and long conversations.",
        occupation = "Teacher",
        education = "Master's",
        languages = listOf("Tibetan", "English"),
        interests = listOf("Reading", "Board games", "Buddhism & philosophy"),
        photos = photosFor("yangchen", 3),
        distanceKm = 1.8,
    )

    /** 5 persons for decks and lists. */
    val feedCards: List<FeedCard> = listOf(feedCard, karma, dechen, lobsang, yangchen)

    // ---------------------------------------------------------------- ads & news

    val ad: AdCard = AdCard(
        adId = "ad-learn-tibetan",
        title = "Learn Tibetan online",
        body = "Live classes with native teachers — beginners welcome. Your first lesson is free.",
        linkUrl = "https://example.org/learn-tibetan",
        ctaLabel = "Book a class",
        photos = listOf(photo("drokpo-ad-class")),
    )

    val adNoImage: AdCard = AdCard(
        adId = "ad-momo-night",
        title = "Momo night at Lhasa Kitchen",
        body = "Every Thursday: all-you-can-eat momos and live music. Bring your friends.",
        linkUrl = "https://example.org/momo-night",
    )

    val news: NewsCard = NewsCard(
        newsId = "n-losar-toronto",
        title = "Toronto's Tibetan community prepares for its biggest Losar yet",
        gist = "Organisers expect more than two thousand guests at this year's celebration in Parkdale.",
        summary = "Preparations are under way for what organisers say will be the largest Losar celebration " +
            "the city has hosted. Volunteers have been rehearsing traditional dances for weeks, and local " +
            "restaurants are donating food for the community feast.\n\nThe event is free and open to everyone.",
        sourceUrl = "https://example.org/news/losar-toronto",
        sourceName = "Phayul",
        imageUrl = imageUrl("drokpo-news-losar", 800, 450),
        publishedAt = isoAgo(3 * HOUR),
    )

    /** No image; a bare-date publishedAt (some sources only expose a date). */
    val newsNoImage: NewsCard = NewsCard(
        newsId = "n-scholarship",
        title = "New scholarship opens for Tibetan students in Europe",
        gist = "Applications close at the end of the month; undergraduates and postgraduates can apply.",
        sourceUrl = "https://example.org/news/scholarship",
        sourceName = "Tibet.net",
        publishedAt = LocalDate.now().minusDays(1).toString(),
    )

    val newsList: List<NewsCard> = listOf(
        news,
        newsNoImage,
        NewsCard(
            newsId = "n-film-festival",
            title = "Tibetan film festival returns to Dharamshala",
            gist = "Twelve feature films and a youth short-film competition headline this year's programme.",
            summary = "The festival returns after a two-year break with screenings across McLeod Ganj.",
            sourceUrl = "https://example.org/news/film-festival",
            sourceName = "Tibet Sun",
            imageUrl = imageUrl("drokpo-news-film", 800, 450),
            publishedAt = isoAgo(2 * DAY),
        ),
        NewsCard(
            newsId = "n-language-app",
            title = "Volunteers build a Tibetan keyboard for every phone",
            gist = "The open-source project now supports Android and iOS.",
            sourceUrl = "https://example.org/news/keyboard",
            sourceName = "Voice of Tibet",
            imageUrl = imageUrl("drokpo-news-keyboard", 800, 450),
            publishedAt = isoAgo(9 * DAY),
        ),
    )

    // ---------------------------------------------------------------- community posts

    private const val TAT_ID = "c-tat"
    private const val TAT_NAME = "Tibetan Association of Toronto"
    private val tatLogo = imageUrl("drokpo-tat-logo")

    val announcementPost: CommunityPostCard = CommunityPostCard(
        postId = "p-announcement",
        communityId = TAT_ID,
        communityName = TAT_NAME,
        communityLogoUrl = tatLogo,
        kind = "announcement",
        title = "Tibetan language classes start next week",
        body = "Classes for children (Saturdays) and adults (Wednesday evenings) at the community centre. " +
            "All levels welcome — no registration needed for the first class.",
        imageUrl = imageUrl("drokpo-post-classes", 800, 450),
        active = true,
        commentCount = 4,
        createdAt = isoAgo(5 * HOUR),
    )

    val linkPost: CommunityPostCard = CommunityPostCard(
        postId = "p-link",
        communityId = TAT_ID,
        communityName = TAT_NAME,
        communityLogoUrl = tatLogo,
        kind = "link",
        title = "Volunteer for the Losar festival",
        body = "We need 40 volunteers for set-up, food stalls and the children's corner.",
        linkUrl = "https://example.org/volunteer",
        ctaLabel = "Sign up",
        active = true,
        commentCount = 0,
        createdAt = isoAgo(1 * DAY),
    )

    private val pollOptions = listOf(
        PollOption(id = "opt-sat", label = "Saturday afternoon"),
        PollOption(id = "opt-sun", label = "Sunday morning"),
        PollOption(id = "opt-fri", label = "Friday evening"),
    )

    /** Others have voted; I haven't (no percentages shown yet). */
    val pollPost: CommunityPostCard = CommunityPostCard(
        postId = "p-poll",
        communityId = TAT_ID,
        communityName = TAT_NAME,
        communityLogoUrl = tatLogo,
        kind = "poll",
        title = "When should we hold the summer picnic?",
        body = "",
        poll = Poll(options = pollOptions, counts = mapOf("opt-sat" to 14, "opt-sun" to 9, "opt-fri" to 3)),
        active = true,
        commentCount = 7,
        createdAt = isoAgo(2 * DAY),
    )

    /** Same poll after I voted Sunday. */
    val pollPostVoted: CommunityPostCard = pollPost.copy(
        postId = "p-poll-voted",
        poll = Poll(options = pollOptions, counts = mapOf("opt-sat" to 14, "opt-sun" to 10, "opt-fri" to 3)),
        myVote = "opt-sun",
    )

    /** Future event, not going yet, 12 attendees, with a registration link. */
    val eventPost: CommunityPostCard = CommunityPostCard(
        postId = "p-event",
        communityId = TAT_ID,
        communityName = TAT_NAME,
        communityLogoUrl = tatLogo,
        kind = "event",
        title = "Losar party 2027",
        body = "Dances, a momo feast and a raffle for the youth football club. Bring the whole family!",
        imageUrl = imageUrl("drokpo-post-losar", 800, 450),
        linkUrl = "https://example.org/losar-tickets",
        ctaLabel = "Get tickets",
        active = true,
        eventAt = isoAhead(days = 5, hour = 18),
        location = "Tibetan Canadian Cultural Centre, Etobicoke",
        attendeeCount = 12,
        myRsvp = false,
        commentCount = 2,
        createdAt = isoAgo(3 * DAY),
    )

    val eventPostGoing: CommunityPostCard = eventPost.copy(postId = "p-event-going", myRsvp = true, attendeeCount = 13)

    /** Owner view only: active = false. */
    val unpublishedPost: CommunityPostCard = CommunityPostCard(
        postId = "p-unpublished",
        communityId = TAT_ID,
        communityName = TAT_NAME,
        communityLogoUrl = tatLogo,
        kind = "announcement",
        title = "Draft: annual general meeting",
        body = "Agenda to follow.",
        active = false,
        commentCount = 0,
        createdAt = isoAgo(30),
    )

    val posts: List<CommunityPostCard> = listOf(
        announcementPost, linkPost, pollPost, pollPostVoted, eventPost, eventPostGoing, unpublishedPost,
    )

    // ---------------------------------------------------------------- communities

    /** Verified, joined, 128 members, every contact/address/social field, 2 photos. */
    val community: CommunityProfile = CommunityProfile(
        uid = TAT_ID,
        name = TAT_NAME,
        description = "Bringing Toronto's Tibetan community together since 1999 — Losar and Saga Dawa " +
            "celebrations, language classes, youth sports and support for newcomers.",
        website = "https://example.org/tibetan-toronto",
        phone = "+1 416 555 0134",
        email = "hello@tibetan-toronto.example.org",
        contactPerson = ContactPerson(
            name = "Tashi Namgyal",
            role = "Coordinator",
            phone = "+1 416 555 0199",
            email = "tashi@tibetan-toronto.example.org",
        ),
        address = CommunityAddress(
            line1 = "40 Titan Rd",
            city = "Toronto",
            state = "Ontario",
            country = "Canada",
            postalCode = "M8Z 2J8",
        ),
        socials = Socials(instagram = "tibetantoronto", youtube = "TibetanToronto", tiktok = "tibetantoronto", facebook = "tibetantoronto"),
        photos = listOf(photo("drokpo-tat-logo", 0), photo("drokpo-tat-hall", 1)),
        verification = "verified",
        memberCount = 128,
        joined = true,
    )

    /** Awaiting review, not joined, a single member. */
    val communityPending: CommunityProfile = CommunityProfile(
        uid = "c-dsala-youth",
        name = "Dharamshala Youth Circle",
        description = "Weekly meet-ups, hikes and volunteering for young Tibetans in McLeod Ganj.",
        email = "hello@dsala-youth.example.org",
        contactPerson = ContactPerson(name = "Norbu"),
        address = CommunityAddress(city = "Dharamshala", country = "India"),
        photos = listOf(photo("drokpo-dsala-logo")),
        verification = "pending",
        memberCount = 1,
        joined = false,
    )

    val communities: List<CommunityProfile> = listOf(
        community,
        communityPending,
        CommunityProfile(
            uid = "c-zurich",
            name = "Tibeter Gemeinschaft Zürich",
            description = "Tibetan community of Zurich — culture, language and mutual support.",
            address = CommunityAddress(city = "Zurich", country = "Switzerland"),
            photos = listOf(photo("drokpo-zurich-logo")),
            verification = "verified",
            memberCount = 342,
            joined = false,
        ),
        CommunityProfile(
            uid = "c-nyc-football",
            name = "NYC Tibetan Football Club",
            description = "Sunday pick-up games in Queens. All skill levels.",
            address = CommunityAddress(city = "New York", country = "USA"),
            photos = listOf(photo("drokpo-nycfc-logo")),
            verification = "verified",
            memberCount = 57,
            joined = true,
        ),
        CommunityProfile(
            uid = "c-sydney-dharma",
            name = "Sydney Dharma Group",
            description = "Meditation and study evenings every Tuesday.",
            address = CommunityAddress(city = "Sydney", country = "Australia"),
            verification = "verified",
            memberCount = 0,
            joined = false,
        ),
    )

    val members: List<CommunityMember> = listOf(
        CommunityMember(uid = "u-pema", displayName = "Pema Lhamo", photo = photo("drokpo-pema-1"), region = "Nepal"),
        CommunityMember(uid = "u-karma", displayName = "Karma Tsering", photo = photo("drokpo-karma-1"), region = "India"),
        CommunityMember(uid = "u-dechen", displayName = "Dechen Wangmo", photo = photo("drokpo-dechen-1"), region = "Europe"),
        CommunityMember(uid = "u-lobsang", displayName = "Lobsang Gyatso", photo = photo("drokpo-lobsang-1"), region = "Australia"),
        CommunityMember(uid = "u-yangchen", displayName = "Yangchen Dolkar", region = "Bhutan"),
        CommunityMember(uid = "u-anon"),
    )

    // ---------------------------------------------------------------- comments

    /** Public sample clip — only fetched when a bubble's play button is tapped. */
    private const val SAMPLE_AUDIO_URL = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"

    /** Text, audio, community author, a thread with replies (+ my "like"), a dislike, and one of mine. */
    val comments: List<CommentCard> = listOf(
        CommentCard(
            commentId = "cm-1",
            authorUid = "u-karma",
            authorKind = "person",
            authorName = "Karma Tsering",
            authorPhotoUrl = imageUrl("drokpo-karma-1"),
            text = "Can adults who are complete beginners join the Wednesday class?",
            replyCount = 2,
            likeCount = 5,
            dislikeCount = 0,
            myVote = "like",
            createdAt = isoAgo(45),
        ),
        CommentCard(
            commentId = "cm-2",
            authorUid = "u-dechen",
            authorKind = "person",
            authorName = "Dechen Wangmo",
            authorPhotoUrl = imageUrl("drokpo-dechen-1"),
            audioUrl = SAMPLE_AUDIO_URL,
            audioDurationSec = 14,
            replyCount = 0,
            likeCount = 2,
            createdAt = isoAgo(2 * HOUR),
        ),
        CommentCard(
            commentId = "cm-3",
            authorUid = TAT_ID,
            authorKind = "community",
            authorName = TAT_NAME,
            authorPhotoUrl = tatLogo,
            text = "Reminder: the first class is free and books are provided. See you there!",
            replyCount = 0,
            likeCount = 11,
            createdAt = isoAgo(3 * HOUR),
        ),
        CommentCard(
            commentId = "cm-4",
            authorUid = "u-lobsang",
            authorKind = "person",
            authorName = "Lobsang Gyatso",
            text = "Wish there was a Sydney chapter of this 🙏",
            replyCount = 0,
            likeCount = 0,
            dislikeCount = 1,
            myVote = "dislike",
            createdAt = isoAgo(1 * DAY),
        ),
        CommentCard(
            commentId = "cm-5",
            authorUid = MY_UID,
            authorKind = "person",
            authorName = "Tenzin Dolma",
            authorPhotoUrl = imageUrl("drokpo-tenzin-1"),
            text = "I'll bring my cousin along on Wednesday.",
            replyCount = 0,
            createdAt = isoAgo(10),
        ),
    )

    /** The thread under comments[0]. */
    val replies: List<CommentCard> = listOf(
        CommentCard(
            commentId = "cm-1-r1",
            authorUid = TAT_ID,
            authorKind = "community",
            authorName = TAT_NAME,
            authorPhotoUrl = tatLogo,
            text = "Yes! The Wednesday class starts from the alphabet.",
            parentId = "cm-1",
            likeCount = 3,
            createdAt = isoAgo(40),
        ),
        CommentCard(
            commentId = "cm-1-r2",
            authorUid = "u-pema",
            authorKind = "person",
            authorName = "Pema Lhamo",
            authorPhotoUrl = imageUrl("drokpo-pema-1"),
            audioUrl = SAMPLE_AUDIO_URL,
            audioDurationSec = 6,
            parentId = "cm-1",
            createdAt = isoAgo(20),
        ),
    )

    // ---------------------------------------------------------------- likes

    /** People (and one community) who liked me — all unmatched. */
    val receivedLikes: List<SwipeEntry> = listOf(
        SwipeEntry(uid = yangchen.uid, action = "like", createdAt = isoAgo(25), otherUser = yangchen),
        SwipeEntry(uid = communityCard.uid, action = "like", createdAt = isoAgo(4 * HOUR), otherUser = communityCard),
        SwipeEntry(uid = dechen.uid, action = "like", createdAt = isoAgo(1 * DAY), otherUser = dechen),
        SwipeEntry(uid = feedCardMinimal.uid, action = "like", createdAt = isoAgo(3 * DAY), otherUser = feedCardMinimal),
    )

    /** People I liked (unmatched). */
    val givenLikes: List<SwipeEntry> = listOf(
        SwipeEntry(uid = feedCard.uid, action = "like", createdAt = isoAgo(50), otherUser = feedCard),
        SwipeEntry(uid = lobsang.uid, action = "like", createdAt = isoAgo(6 * HOUR), otherUser = lobsang),
        SwipeEntry(uid = karma.uid, action = "like", createdAt = isoAgo(4 * DAY), otherUser = karma),
    )

    /** Saved news and posts, interleaving with [givenLikes] by time. */
    val likedContent: List<LikedContent> = listOf(
        LikedContent.News(news, likedAt = isoAgo(30)),
        LikedContent.Post(eventPost, likedAt = isoAgo(2 * HOUR)),
        LikedContent.News(newsList[2], likedAt = isoAgo(2 * DAY)),
        LikedContent.Post(linkPost, likedAt = isoAgo(5 * DAY)),
        LikedContent.Post(pollPostVoted, likedAt = null),
    )

    /** GET /api/communities/home items: posts with one sponsored card. */
    val communitiesHomeItems: List<FeedItem> = listOf(
        FeedItem.Post(announcementPost),
        FeedItem.Post(pollPost),
        FeedItem.Ad(ad),
        FeedItem.Post(eventPost),
        FeedItem.Post(linkPost),
    )

    // ---------------------------------------------------------------- chats

    /** 2 new matches + 3 conversations (one with 2 unread, one whose last message is mine, one community). */
    val chatEntries: List<ChatStore.Entry> = listOf(
        ChatStore.Entry(
            matchId = "m-pema",
            otherUid = feedCard.uid,
            otherUser = feedCard,
            lastMessageText = "See you at the Losar party! 🎉",
            lastMessageSenderId = feedCard.uid,
            unread = 2,
            sortDate = instantAgo(5),
        ),
        ChatStore.Entry(
            matchId = "m-yangchen",
            otherUid = yangchen.uid,
            otherUser = yangchen,
            sortDate = instantAgo(20),
        ),
        ChatStore.Entry(
            matchId = "m-karma",
            otherUid = karma.uid,
            otherUser = karma,
            lastMessageText = "Sounds good, tashi delek!",
            lastMessageSenderId = MY_UID,
            unread = 0,
            sortDate = instantAgo(2 * HOUR),
        ),
        ChatStore.Entry(
            matchId = "m-lobsang",
            otherUid = lobsang.uid,
            otherUser = lobsang,
            sortDate = instantAgo(3 * HOUR),
        ),
        ChatStore.Entry(
            matchId = "m-tat",
            otherUid = communityCard.uid,
            otherUser = communityCard,
            lastMessageText = "Thanks for joining — our next event is on Saturday.",
            lastMessageSenderId = communityCard.uid,
            unread = 0,
            sortDate = instantAgo(2 * DAY),
        ),
    )

    /** The thread with Pema across two days: both senders, text, photo, voice and a shared link. */
    val chatMessages: List<ChatMessage> = run {
        val pema = feedCard.uid
        val yesterday = instantAgo(1 * DAY)
        listOf(
            ChatMessage("msg-1", pema, "Tashi delek! I saw you like photography too 🙂", createdAt = yesterday.minus(3, ChronoUnit.HOURS)),
            ChatMessage("msg-2", MY_UID, "Tashi delek! Yes — mostly street photography around Toronto.", createdAt = yesterday.minus(170, ChronoUnit.MINUTES)),
            ChatMessage("msg-3", MY_UID, "What do you paint?", createdAt = yesterday.minus(169, ChronoUnit.MINUTES)),
            ChatMessage(
                "msg-4",
                pema,
                "📷 Photo",
                imageUrl = imageUrl("drokpo-chat-thangka"),
                createdAt = yesterday.minus(150, ChronoUnit.MINUTES),
            ),
            ChatMessage("msg-5", pema, "Thangkas! This one took me four months.", createdAt = yesterday.minus(149, ChronoUnit.MINUTES)),
            ChatMessage(
                "msg-6",
                MY_UID,
                "🎤 Voice message",
                audioUrl = SAMPLE_AUDIO_URL,
                audioDurationSec = 9,
                createdAt = instantAgo(50),
            ),
            ChatMessage("msg-7", pema, ShareableContent.Post(eventPost).messageText, createdAt = instantAgo(12)),
            ChatMessage("msg-8", MY_UID, "I'm going! Want to go together?", createdAt = instantAgo(8)),
            ChatMessage("msg-9", pema, "See you at the Losar party! 🎉", createdAt = instantAgo(5)),
        )
    }

    // ---------------------------------------------------------------- settings

    val sentMessages: List<SentMessage> = listOf(
        SentMessage(messageId = "msg-8", matchId = "m-pema", senderId = MY_UID, text = "I'm going! Want to go together?", createdAt = isoAgo(8)),
        SentMessage(messageId = "s-2", matchId = "m-karma", senderId = MY_UID, text = "Sounds good, tashi delek!", createdAt = isoAgo(2 * HOUR)),
        SentMessage(
            messageId = "s-3",
            matchId = "m-karma",
            senderId = MY_UID,
            text = "Are you playing basketball this Sunday? A few of us are going to the court near Parkdale " +
                "around 10 and could use one more player — bring water, it's going to be hot.",
            createdAt = isoAgo(1 * DAY + 3 * HOUR),
        ),
        SentMessage(messageId = "s-4", matchId = "m-tat", senderId = MY_UID, text = "Thank you for the warm welcome!", createdAt = isoAgo(3 * DAY)),
    )

    val blockedUsers: List<BlockedUser> = listOf(
        BlockedUser(uid = "u-blocked-1", displayName = "Jigme", blockedAt = instantAgo(2 * DAY).toEpochMilli()),
        BlockedUser(uid = "u-blocked-2", displayName = "Spam Promotions", blockedAt = instantAgo(20 * DAY).toEpochMilli()),
        BlockedUser(uid = "u-blocked-3", displayName = null, blockedAt = instantAgo(90 * DAY).toEpochMilli()),
    )
}
