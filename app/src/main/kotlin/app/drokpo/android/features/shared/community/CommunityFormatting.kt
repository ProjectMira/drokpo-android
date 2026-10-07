package app.drokpo.android.features.shared.community

import app.drokpo.android.core.model.CommunityPostCard
import app.drokpo.android.core.model.CommunityProfile
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale

// Pure copy/format helpers for the community page, post content and grid tiles — kept free of
// Compose and android.* so they're unit-testable on the JVM.

/** `"\(count) member\(count == 1 ? "" : "s")"`. */
internal fun memberCountLabel(count: Int): String = "$count member${if (count == 1) "" else "s"}"

/** `"\(poll.totalVotes) vote\(poll.totalVotes == 1 ? "" : "s")"`. */
internal fun voteCountLabel(total: Int): String = "$total vote${if (total == 1) "" else "s"}"

/** `"\(goingCount) going"`. */
internal fun goingLabel(count: Int): String = "$count going"

/**
 * `"\(Int((percentage * 100).rounded()))%"`. Swift's `rounded()` is schoolbook rounding (half away
 * from zero) — Kotlin's `round()` is half-even, so 12.5 would become 12 there; `Math.round` matches
 * Swift for the non-negative values a poll produces.
 */
internal fun pollPercentLabel(percentage: Double): String = "${Math.round(percentage * 100)}%"

/** `post.commentCount.map { $0 > 0 ? "\($0)" : "Comment" } ?? "Comment"`. */
internal fun commentButtonLabel(commentCount: Int?): String =
    commentCount?.takeIf { it > 0 }?.toString() ?: "Comment"

/** `post.ctaLabel?.isEmpty == false ? post.ctaLabel! : "Learn more"`. */
internal fun ctaLabel(post: CommunityPostCard): String = post.ctaLabel?.takeIf { it.isNotEmpty() } ?: "Learn more"

/**
 * Whether the post has a link the CTA can open — iOS `post.url != nil`. `CommunityPostCard.url` is
 * `linkUrl` parsed when non-blank (android.net.Uri.parse never fails), so this is the same test
 * without touching android.* (which is stubbed in JVM tests).
 */
internal fun hasLink(post: CommunityPostCard): Boolean = !post.linkUrl.isNullOrBlank()

/**
 * The website button's link — iOS `if let website = community?.website, let url = URL(string: website)`.
 * On iOS 17 (the deployment target) `URL(string:)` percent-encodes invalid characters instead of
 * failing, so only an empty string doesn't parse. Non-http(s) links are swapped for the backend
 * home page by the in-app browser, like SafariView.
 */
internal fun websiteUrl(community: CommunityProfile?): String? = community?.website?.takeIf { it.isNotEmpty() }

/**
 * A count-only label for non-members (member lists are members-only — see docs/COMMUNITIES.md), or
 * a link into the member list for the owner or a joined visitor.
 */
internal fun showsMembersLink(ownerMode: Boolean, community: CommunityProfile?): Boolean =
    ownerMode || community?.joined == true

/**
 * Owner sees "New post"; a visiting person sees Join/Joined; a visiting community account sees
 * neither — communities don't join communities.
 */
internal enum class PageAction { NewPost, Join, None }

internal fun pageAction(ownerMode: Boolean, isCommunityAccount: Boolean): PageAction = when {
    ownerMode -> PageAction.NewPost
    !isCommunityAccount -> PageAction.Join
    else -> PageAction.None
}

/** The grid tile's kind (icon + tint), from `post.kind`. */
internal enum class PostKind { Link, Poll, Event, Announcement }

internal fun postKind(kind: String?): PostKind = when (kind) {
    "link" -> PostKind.Link
    "poll" -> PostKind.Poll
    "event" -> PostKind.Event
    else -> PostKind.Announcement
}

/**
 * Port of `date.formatted(date: .abbreviated, time: .shortened)` — "Oct 12, 2026 at 6:00 PM" in
 * English. The date is the locale's medium date; the time is the locale's short time, switched to
 * 24-hour (or 12-hour) when [is24Hour] says the device clock disagrees with the locale default (iOS
 * honours the 24-Hour Time setting the same way). Null keeps the locale default.
 */
internal fun formatEventDate(
    instant: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean? = null,
): String {
    val dateTime = instant.atZone(zone)
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(dateTime)
    val localePattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null,
        FormatStyle.SHORT,
        IsoChronology.INSTANCE,
        locale,
    )
    val usesTwelveHour = localePattern.any { it == 'h' || it == 'K' }
    val timePattern = when {
        is24Hour == true && usesTwelveHour -> "HH:mm"
        is24Hour == false && !usesTwelveHour -> "h:mm a"
        else -> localePattern
    }
    val time = DateTimeFormatter.ofPattern(timePattern, locale).format(dateTime)
    val joiner = if (locale.language == Locale.ENGLISH.language) " at " else ", "
    return "$date$joiner$time"
}
