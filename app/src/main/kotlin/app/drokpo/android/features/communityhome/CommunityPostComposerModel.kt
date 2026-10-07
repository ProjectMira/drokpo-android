package app.drokpo.android.features.communityhome

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.model.CommunityPostIn
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Poll posts carry 2–4 options (backend MIN_POLL_OPTIONS / MAX_POLL_OPTIONS). */
internal const val MIN_POLL_OPTIONS = 2
internal const val MAX_POLL_OPTIONS = 4

/** The composer's error when a picked image can't be read. */
internal const val PHOTO_LOAD_ERROR = "That photo couldn't be loaded — try picking another one."

/** The four post kinds; [raw] is the wire value (`CommunityPostIn.kind`). */
internal enum class PostKind(val raw: String, val label: String) {
    Announcement("announcement", "Announcement"),
    Link("link", "Link"),
    Poll("poll", "Poll"),
    Event("event", "Event"),
}

/**
 * A poll option being drafted. Identity-stable so removing a row can't
 * re-bind neighbours mid-update (the crash-prone indices+remove(at:) combo).
 */
internal data class PollOptionDraft(
    val id: String = UUID.randomUUID().toString(),
    val text: String = "",
)

/** Everything typed into the composer. Fields typed under one kind survive a kind switch. */
internal data class CommunityPostDraft(
    val kind: PostKind = PostKind.Announcement,
    val title: String = "",
    val body: String = "",
    /** Shared by link posts (required) and events (optional registration link). */
    val linkUrl: String = "",
    val ctaLabel: String = "",
    val pollOptions: List<PollOptionDraft> = listOf(PollOptionDraft(), PollOptionDraft()),
    val eventDate: Instant,
    val eventLocation: String = "",
    /** The picked photo (system photo picker content Uri), not yet uploaded. */
    val pickedImage: Uri? = null,
) {
    val filledPollOptions: List<String>
        get() = pollOptions.map { it.text.trimWhitespaces() }.filter { it.isNotEmpty() }

    val canRemovePollOption: Boolean get() = pollOptions.size > MIN_POLL_OPTIONS
    val canAddPollOption: Boolean get() = pollOptions.size < MAX_POLL_OPTIONS

    /** The kind-specific rules the backend enforces (title is checked by [canSave]). */
    fun isValid(now: Instant): Boolean {
        if (title.trimWhitespaces().isEmpty()) return false
        return when (kind) {
            PostKind.Announcement -> true
            PostKind.Link -> linkUrl.trimWhitespaces().startsWith("https://")
            PostKind.Poll -> {
                val filled = filledPollOptions
                filled.size >= MIN_POLL_OPTIONS && filled.toSet().size == filled.size
            }
            PostKind.Event -> {
                val trimmedLink = linkUrl.trimWhitespaces()
                val linkOk = trimmedLink.isEmpty() || trimmedLink.startsWith("https://")
                eventDate.isAfter(now) && linkOk
            }
        }
    }

    fun canSave(now: Instant, isSaving: Boolean): Boolean = !isSaving && isValid(now)

    fun addingPollOption(): CommunityPostDraft =
        if (canAddPollOption) copy(pollOptions = pollOptions + PollOptionDraft()) else this

    fun removingPollOption(id: String): CommunityPostDraft =
        if (canRemovePollOption) copy(pollOptions = pollOptions.filterNot { it.id == id }) else this

    fun updatingPollOption(id: String, text: String): CommunityPostDraft =
        copy(pollOptions = pollOptions.map { if (it.id == id) it.copy(text = text) else it })

    /** `POST /api/communities/me/posts` body, with the already-uploaded photo's storage path. */
    fun toPayload(photoStoragePath: String?): CommunityPostIn = CommunityPostIn(
        kind = kind.raw,
        title = title.trimWhitespaces(),
        // The Description field is hidden for polls — don't let a draft typed
        // under another kind leak into the poll.
        body = if (kind == PostKind.Poll) "" else body,
        photoStoragePath = photoStoragePath,
        linkUrl = when (kind) {
            PostKind.Link -> linkUrl.trimWhitespaces()
            PostKind.Event -> linkUrl.nonEmptyTrimmed()
            else -> null
        },
        ctaLabel = if (kind == PostKind.Link || kind == PostKind.Event) ctaLabel.nonEmptyTrimmed() else null,
        pollOptions = if (kind == PostKind.Poll) filledPollOptions else null,
        eventAt = if (kind == PostKind.Event) iso8601(eventDate) else null,
        location = if (kind == PostKind.Event) eventLocation.nonEmptyTrimmed() else null,
    )
}

/** iOS `ISO8601DateFormatter().string(from:)`: UTC, whole seconds, e.g. `2026-10-06T12:00:00Z`. */
internal fun iso8601(instant: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

// region Event date pickers
//
// The Material DatePicker works in UTC-midnight millis for the chosen calendar
// day, while the event date is an Instant shown in the device zone. These keep
// the two apart, so a day never shifts by one for users east or west of UTC.

/** [date]'s local calendar day in [zone], as the DatePicker's UTC-midnight millis. */
internal fun pickerMillisFor(date: Instant, zone: ZoneId): Long =
    date.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The calendar day the DatePicker's UTC-midnight [utcMillis] stands for. */
internal fun pickedDay(utcMillis: Long): LocalDate =
    Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()

/** [date] moved to the picked day, keeping its local time of day in [zone]. */
internal fun withPickedDay(date: Instant, utcMillis: Long, zone: ZoneId): Instant =
    pickedDay(utcMillis).atTime(date.atZone(zone).toLocalTime()).atZone(zone).toInstant()

/** [date] at [hour]:[minute] local time in [zone], keeping its local day. */
internal fun withPickedTime(date: Instant, hour: Int, minute: Int, zone: ZoneId): Instant =
    date.atZone(zone).toLocalDate().atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()

/** iOS `DatePicker(in: Date()...)`: no day before [today] (the device's local day) is selectable. */
internal fun isSelectableDay(utcMillis: Long, today: LocalDate): Boolean = !pickedDay(utcMillis).isBefore(today)

// endregion

/**
 * The composer for all four post kinds — announcement, link, poll, event.
 * Posting is open to any registered community, verified or not — see
 * PendingVerificationBanner for what verification actually unlocks.
 *
 * Snapshot state (not a StateFlow) for the same reason as the profile editor:
 * text fields must see their own edits synchronously.
 */
internal class CommunityPostComposerModel(
    private val api: CommunityAccountApi = RemoteCommunityAccountApi,
    private val now: () -> Instant = Instant::now,
    /** Whether a picked image can be decoded (iOS: `loadTransferable` + `UIImage(data:)`). */
    private val canLoadImage: suspend (Uri) -> Boolean = ::canDecodeImage,
) : ViewModel() {
    var draft by mutableStateOf(CommunityPostDraft(eventDate = now().plusSeconds(3_600)))
        private set
    var isSaving by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Set once the presentation is gone, so a save finishing afterwards doesn't dismiss a newer one. */
    private var isCleared = false

    val canSave: Boolean get() = draft.canSave(now(), isSaving)

    /** The earliest selectable event time (iOS `DatePicker(in: Date()...)`). */
    fun earliestEventDate(): Instant = now()

    fun updateDraft(newDraft: CommunityPostDraft) {
        draft = newDraft
    }

    /** Event date from the pickers, clamped to "now" like the iOS DatePicker's range. */
    fun setEventDate(date: Instant) {
        val earliest = now()
        draft = draft.copy(eventDate = if (date.isBefore(earliest)) earliest else date)
    }

    fun onPhotoPicked(uri: Uri) {
        viewModelScope.launch {
            if (canLoadImage(uri)) {
                draft = draft.copy(pickedImage = uri)
            } else {
                errorMessage = PHOTO_LOAD_ERROR
            }
        }
    }

    fun removePhoto() {
        draft = draft.copy(pickedImage = null)
    }

    /**
     * Upload the photo first (→ `photoStoragePath`), then create the post, then
     * `onSaved()` and dismiss. Like the iOS Task, it finishes even if the
     * composer is closed meanwhile — the post and the caller's reload still
     * happen — but only an open composer dismisses itself.
     */
    fun save(onSaved: suspend () -> Unit, onDone: () -> Unit) {
        val image = draft.pickedImage
        save(upload = image?.let { { api.uploadCommunityPhoto(it) } }, onSaved = onSaved, onDone = onDone)
    }

    internal fun save(upload: (suspend () -> String)?, onSaved: suspend () -> Unit, onDone: () -> Unit) {
        if (!canSave) return
        isSaving = true
        val snapshot = draft
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    val photoStoragePath = upload?.invoke()
                    api.createPost(snapshot.toPayload(photoStoragePath))
                    onSaved()
                    if (!isCleared) onDone()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorMessage = e.userMessage()
                } finally {
                    isSaving = false
                }
            }
        }
    }

    fun dismissError() {
        errorMessage = null
    }

    override fun onCleared() {
        isCleared = true
    }
}

/** Decodes just the bounds — enough to know the picker handed back a readable image. */
private suspend fun canDecodeImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
    try {
        AppGraph.app.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, options)
            options.outWidth > 0 && options.outHeight > 0
        } ?: false
    } catch (e: Exception) {
        false
    }
}
