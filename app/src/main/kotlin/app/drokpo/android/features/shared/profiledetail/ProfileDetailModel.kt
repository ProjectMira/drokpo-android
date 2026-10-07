package app.drokpo.android.features.shared.profiledetail

import androidx.lifecycle.ViewModel
import app.drokpo.android.core.AppGraph
import app.drokpo.android.core.Safety
import app.drokpo.android.core.model.FeedCard
import app.drokpo.android.core.model.ProfileQuestion
import app.drokpo.android.core.model.SwipeResult
import app.drokpo.android.core.model.Vocabulary
import app.drokpo.android.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** iOS ProfileDetailView's `@State` (minus the dialog flags, which are UI-local). */
internal data class ProfileDetailState(
    /** "Like back" in flight — the button is disabled meanwhile. */
    val isLiking: Boolean = false,
    /** Set once a LikedYou like-back matched: the bar flips to "Send message". */
    val localMatchId: String? = null,
    val showMatchAlert: Boolean = false,
    val errorMessage: String? = null,
    /** One-shot: a self-owned block succeeded, so the screen dismisses itself (iOS `dismiss()`). */
    val dismissAfterBlock: Boolean = false,
)

/**
 * The async parts of ProfileDetailView: LikedYou like-back → match, and the
 * screen's own report/block when the caller doesn't own safety. Dependencies
 * are constructor parameters with production defaults so JVM tests can fake them.
 */
internal class ProfileDetailModel(
    private val reportProfile: suspend (uid: String, reason: String) -> Unit =
        { uid, reason -> Safety.report(reportedUid = uid, reason = reason) },
    private val blockProfile: suspend (uid: String, displayName: String?) -> Unit =
        { uid, name -> Safety.block(uid, name) },
    private val openMessageThread: (matchId: String) -> Unit =
        { matchId -> AppGraph.deepLinks.handle(type = "message", matchId = matchId) },
    /**
     * Where the like-back / report / block requests run. iOS fires them in unstructured `Task`s
     * that keep going after the view is dismissed; on viewModelScope a quick back tap would cancel
     * a block mid-flight (the server blocks, BlockStore never records it). Null = AppGraph.appScope.
     */
    private val workScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileDetailState())
    val state: StateFlow<ProfileDetailState> = _state.asStateFlow()

    private val scope: CoroutineScope get() = workScope ?: AppGraph.appScope

    /**
     * iOS likeBack(_:): the caller (Likes) records the swipe and returns its
     * result; a match stores the matchId and raises "It's a match!".
     */
    fun likeBack(onLikeBack: suspend () -> SwipeResult?) {
        if (_state.value.isLiking) return
        _state.update { it.copy(isLiking = true) }
        scope.launch {
            val result = try {
                onLikeBack()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // iOS's closure can't throw (Likes reports its own errors and returns nil);
                // a Kotlin lambda can, so surface it rather than crash the scope.
                _state.update { it.copy(errorMessage = e.userMessage()) }
                null
            } finally {
                _state.update { it.copy(isLiking = false) }
            }
            if (result == null || !result.isMatch) return@launch
            _state.update {
                it.copy(localMatchId = result.matchId ?: result.match?.matchId, showMatchAlert = true)
            }
        }
    }

    /** "Say hi" / "Send message": hand the thread to the router (MainTabs → Chats → thread). */
    fun openThread(matchId: String? = null) {
        val id = matchId ?: _state.value.localMatchId ?: return
        openMessageThread(id)
    }

    /** Report through the API directly (callers that own cleanup pass onReport instead). iOS stays on the profile. */
    fun report(card: FeedCard, reason: String) {
        scope.launch {
            try {
                reportProfile(card.uid, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    /** POST /api/blocks/{uid} + BlockStore.record (Safety.block), then dismiss; nothing recorded on failure. */
    fun block(card: FeedCard) {
        scope.launch {
            try {
                blockProfile(card.uid, card.displayName)
                _state.update { it.copy(dismissAfterBlock = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.userMessage()) }
            }
        }
    }

    fun consumeDismiss() {
        _state.update { it.copy(dismissAfterBlock = false) }
    }

    fun dismissMatchAlert() {
        _state.update { it.copy(showMatchAlert = false) }
    }

    fun dismissError() {
        _state.update { it.copy(errorMessage = null) }
    }
}

/** One answered prompt on the detail card. */
internal data class AnsweredQuestion(val question: ProfileQuestion, val answer: String)

/**
 * The profile's prompt answers, in the vocabulary's order. Only known
 * question keys are shown, so retired questions vanish gracefully.
 * (Swift trims `.whitespaces` — spaces and tabs, not newlines — for the
 * emptiness check and shows the answer untrimmed.)
 */
internal fun answeredQuestions(card: FeedCard): List<AnsweredQuestion> =
    Vocabulary.questions.mapNotNull { question ->
        val answer = card.answers?.get(question.key) ?: return@mapNotNull null
        if (answer.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }.isEmpty()) {
            return@mapNotNull null
        }
        AnsweredQuestion(question, answer)
    }

/** "~{n} km away" — Swift `Int(distanceKm.rounded())` (half away from zero; distances are never negative). */
internal fun distanceLabel(distanceKm: Double): String = "~${distanceKm.roundToInt()} km away"

/** Own-profile preview (ProfileScreen) — no reporting/blocking yourself. */
internal fun isSelfProfile(card: FeedCard, myUid: String?): Boolean = card.uid == myUid || card.uid == "me"
