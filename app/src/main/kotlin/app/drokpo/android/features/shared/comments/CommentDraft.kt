package app.drokpo.android.features.shared.comments

import java.io.File

/** What the composer is about to send — exactly one of text or audio. (CONTRACT §B.10.) */
sealed interface CommentDraft {
    data class Text(val text: String) : CommentDraft
    data class Audio(val file: File, val seconds: Int) : CommentDraft
}
