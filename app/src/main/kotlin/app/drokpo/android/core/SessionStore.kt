package app.drokpo.android.core

import android.util.Log
import app.drokpo.android.core.model.AccountResponse
import app.drokpo.android.core.model.CommunityProfile
import app.drokpo.android.core.model.Profile
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SessionState {
    Loading,
    SignedOut,

    /**
     * Signed in, no `users/{uid}` or `communities/{uid}` doc yet — the app
     * asks whether this account is a person or a community.
     */
    ChoosingAccountType,
    NeedsOnboarding,
    ActivePerson,
    NeedsCommunityOnboarding,
    ActiveCommunity,
    Failed,
}

enum class AccountType { Person, Community }

/**
 * Port of the iOS `SessionStore`: owns "who is signed in and which experience
 * do they get". A FirebaseAuth state listener drives it — signed out resets
 * everything, signed in fetches GET /api/account and routes.
 *
 * Created once by [AppGraph.init]; all state is mutated on the main thread.
 */
class SessionStore internal constructor(
    private val scope: CoroutineScope,
    /** False → never touch FirebaseAuth; the app shows the setup notice. */
    private val firebaseEnabled: Boolean = AppConfig.hasFirebaseConfig,
    private val fetchAccount: suspend () -> AccountResponse = { ApiClient.get<AccountResponse>("/api/account") },
    private val currentUid: () -> String? = { firebaseUser()?.uid },
    /** PushService.enable() — the session became active. */
    private val onActive: () -> Unit = { AppGraph.push.enable() },
    /** BlockStore.reset() — the entries belong to the old account. */
    private val onSignedOutReset: () -> Unit = { AppGraph.blocks.reset() },
    /** Push unregister + Credential Manager reset, while the auth session is still valid. */
    private val beforeSignOut: suspend () -> Unit = ::detachDevice,
    private val firebaseSignOut: () -> Unit = { FirebaseAuth.getInstance().signOut() },
) {
    private val _state = MutableStateFlow(if (firebaseEnabled) SessionState.Loading else SessionState.SignedOut)
    private val _myProfile = MutableStateFlow<Profile?>(null)
    private val _myCommunity = MutableStateFlow<CommunityProfile?>(null)
    private val _lastError = MutableStateFlow<String?>(null)

    /** Starts Loading (SignedOut when Firebase isn't configured). */
    val state: StateFlow<SessionState> = _state.asStateFlow()
    val myProfile: StateFlow<Profile?> = _myProfile.asStateFlow()
    val myCommunity: StateFlow<CommunityProfile?> = _myCommunity.asStateFlow()

    /** Last refresh failure (`userMessage()`); deliberately not cleared on success (iOS parity). */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Firebase Auth uid of the signed-in user, read live; used for Firestore
     * chat queries and unread counts. Null when Firebase isn't configured.
     */
    val uid: String?
        get() = if (firebaseEnabled) currentUid() else null

    /** Email of the signed-in account (null for providers that hide it, or for a phone sign-in). */
    val email: String?
        get() = if (firebaseEnabled) firebaseUser()?.email?.takeIf { it.isNotBlank() } else null

    /** Phone number of the signed-in account (only set for phone sign-in). */
    val phone: String?
        get() = if (firebaseEnabled) firebaseUser()?.phoneNumber?.takeIf { it.isNotBlank() } else null

    private var authListener: FirebaseAuth.AuthStateListener? = null

    /** Registers the auth-state listener (iOS does this in `init`). Idempotent. */
    internal fun start() {
        if (!firebaseEnabled || authListener != null) return
        val listener = FirebaseAuth.AuthStateListener { auth -> onAuthStateChanged(auth.currentUser?.uid) }
        authListener = listener
        // Fires once right away with the persisted user, then on every sign-in/out.
        FirebaseAuth.getInstance().addAuthStateListener(listener)
    }

    /** The listener body, separated so it can run without Firebase in tests. */
    internal fun onAuthStateChanged(signedInUid: String?) {
        if (signedInUid == null) {
            _myProfile.value = null
            _myCommunity.value = null
            onSignedOutReset()
            _state.value = SessionState.SignedOut
        } else {
            scope.launch { refreshAccount() }
        }
    }

    /**
     * The single call the app makes after sign-in (and after either
     * onboarding flow completes) to decide which experience to route into —
     * person, community, or neither yet.
     */
    suspend fun refreshAccount() {
        if (!firebaseEnabled) return
        // Nobody signed in: the listener has routed (or is about to route) to SignedOut.
        val requestedFor = currentUid() ?: return
        try {
            val account = fetchAccount()
            // Signed out (or switched account) while the request was in
            // flight: this answer belongs to a session that no longer exists.
            if (currentUid() != requestedFor) return
            val route = route(account)
            _myProfile.value = route.profile
            _myCommunity.value = route.community
            _state.value = route.state
            // Communities match/chat as themselves too, so they need "someone
            // liked you"/"new match"/"new message" pushes same as a person.
            if (route.state == SessionState.ActivePerson || route.state == SessionState.ActiveCommunity) {
                onActive()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (currentUid() != requestedFor) return
            _lastError.value = e.userMessage()
            _state.value = SessionState.Failed
        }
    }

    /**
     * Kept as the name every screen already calls to mean "my account state
     * may have changed, re-fetch it" — not person-specific despite the name,
     * now that there are two account types.
     */
    suspend fun refreshProfile() = refreshAccount()

    /**
     * Chosen on AccountTypeChoiceScreen, before either onboarding endpoint
     * has run. Purely local routing — the account only becomes real once the
     * matching onboarding flow submits.
     */
    fun chooseAccountType(type: AccountType) {
        _state.value = when (type) {
            AccountType.Person -> SessionState.NeedsOnboarding
            AccountType.Community -> SessionState.NeedsCommunityOnboarding
        }
    }

    /**
     * Detach this device from push notifications while the auth session is
     * still valid, then sign out. Fire-and-forget; the auth listener then
     * clears the profile, resets BlockStore and routes to SignedOut.
     */
    fun signOut() {
        if (!firebaseEnabled) return
        scope.launch {
            try {
                beforeSignOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Pre-sign-out cleanup failed", e)
            }
            try {
                firebaseSignOut()
            } catch (e: Exception) {
                // iOS: `try? Auth.auth().signOut()`.
                Log.w(TAG, "FirebaseAuth.signOut failed", e)
            }
        }
    }

    internal data class Route(val state: SessionState, val profile: Profile?, val community: CommunityProfile?)

    internal companion object {
        private const val TAG = "SessionStore"

        /**
         * An unreachable backend must not make "Sign out" hang for the full
         * HTTP timeout; the token DELETE is best effort anyway (iOS ignores
         * its errors too).
         */
        private const val DETACH_TIMEOUT_MS = 10_000L

        private fun firebaseUser() =
            try {
                FirebaseAuth.getInstance().currentUser
            } catch (e: IllegalStateException) {
                null
            }

        private suspend fun detachDevice() {
            withTimeoutOrNull(DETACH_TIMEOUT_MS) { AppGraph.push.unregister() }
            AuthService.clearCredentialState(AppGraph.app)
        }

        /**
         * GET /api/account → experience. `"person"`: active unless the
         * profile says onboarding isn't complete (a missing flag counts as
         * complete); `"community"`: active (no onboarding check); anything
         * else (`"none"`): ask person vs community.
         */
        fun route(account: AccountResponse): Route = when (account.accountType) {
            "person" -> Route(
                state = if (account.profile?.onboardingComplete ?: true) {
                    SessionState.ActivePerson
                } else {
                    SessionState.NeedsOnboarding
                },
                profile = account.profile,
                community = null,
            )
            "community" -> Route(SessionState.ActiveCommunity, profile = null, community = account.community)
            else -> Route(SessionState.ChoosingAccountType, profile = null, community = null)
        }
    }
}
