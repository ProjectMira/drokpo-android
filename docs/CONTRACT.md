# Drokpo Android — cross-package contract

This document is the **binding interface** between the foundation ("spine") and
the 11 feature groups that build the app in parallel. Read
[`ARCHITECTURE.md`](../ARCHITECTURE.md) first; this file refines it.

- **§A** is the spine API every group may call (the foundation implements it).
- **§B** lists, per group, the public entry points other code calls. Stub files are
  generated mechanically from §B — those signatures are binding.
- **§C** navigation model, **§D** environment/state, **§E** debug catalog,
  **§F** per-group behaviour checklists (the iOS details that are easy to miss).

iOS source of truth: `../drokpo-app/Drokpo` (SwiftUI). JSON field authority:
`../drokpo-backend/backend/app` (Pydantic).

---

## 0. Ground rules

### 0.1 What is binding

- Signatures in §A and §B are a contract. Owners **must not** rename, remove,
  reorder or retype parameters, or change a declaration's kind (class ↔ object,
  data class ↔ class). You **may** add parameters **with defaults** at the end.
  You may add members to classes.
- Everything marked *internal* is listed only so owners know their scope. Internal
  items may be Kotlin `internal` or `private` and can change freely.
- If you need a change to a §A/§B signature, or to anything outside your
  packages, report it in `shared_change_requests` (exact file and change). Do not
  edit someone else's file.

### 0.2 Naming

| iOS | Android | Meaning |
|---|---|---|
| `FooView` that is a tab root, a navigation destination or a flow | `FooScreen` | fills the space it is given; does **not** create its own modal container |
| `FooView` presented with `.sheet` and reused by other groups | `FooSheet` | **self-contained modal**: renders its own `DrokpoSheet` or `FullScreenCover` and takes `onDismissRequest`. The caller just does `if (x != null) FooSheet(x, onDismissRequest = { x = null })` |
| small reusable component (`CommunityPostContentView`, `AudioBubbleView`, `SwipeActionButtons`, `ShareButton`, `CommunityRow`, `PendingVerificationBanner`, `RecorderFailureRow`, `ProfileQuestionFields`) | same name as iOS | plain composable |

Every screen is split into `FooScreen(...)` (gets its ViewModel/state holder and
wires navigation callbacks) and an *internal* stateless `FooContent(state, onX…)`
that renders from plain data. `FooContent` must render with fixture data and no
network, Firebase or `AppGraph` access. The debug catalog (§E) depends on it.

### 0.3 Callback vocabulary

| Parameter | Meaning |
|---|---|
| `onBack: () -> Unit` | The leading top-bar button **and** any iOS `dismiss()` the screen does on its own, for example after a block or unmatch. The host decides whether that pops the NavHost or closes the sheet that contains it. |
| `navIcon: NavIcon` | What the leading button shows: `Back` (pushed), `Close` (root of a sheet or cover), `None` (tab root). See §A.12. |
| `onDismissRequest: () -> Unit` | Closes a self-contained `FooSheet`. |
| `onClose: () -> Unit` | Closes a full-screen cover host (`CommunitiesScreen`, `CommunityDirectoryCoverScreen`). |

### 0.4 State, async, errors, copy

- Observable state is `StateFlow`. Collect it with `collectAsStateWithLifecycle()`.
- Screen state lives in a `ViewModel` created with `viewModel { … }` (or
  `viewModel(key = …) { … }`). Scoping rules are in §D.3.
- Run async work with `viewModelScope.launch`. Fire-and-forget work that must
  outlive the screen goes on `AppGraph.appScope`.
- Errors: show iOS's alert as `ErrorAlert(message, onDismiss, title)` (§A.12).
  The message is `throwable.userMessage()` (core; equivalent to iOS
  `error.localizedDescription`, which yields `ApiError` detail text).
- Copy: use the iOS English strings **verbatim**, inline in Kotlin. Change copy
  only where it names iOS ("iOS will ask" → "Android will ask",
  "GoogleService-Info.plist" → "google-services.json").
- **Haptics:** the iOS app has none (no `sensoryFeedback` or `UIFeedbackGenerator`). Don't add any.
- **Icons:** SF Symbols map to `Icons.*` from material-icons-extended, using the
  ARCHITECTURE table plus the mappings in §F.

### 0.5 Models and API client (owned by the models/API agent, referenced here)

All response and request types live in `app.drokpo.android.core.model` with the
**same type and property names as `Models.swift`**: `GeoLocation`, `Preferences`,
`Socials`, `Photo`, `Profile`, `FeedCard`, `AdCard`, `FeedPage`, `FeedItem`,
`CommunitiesHomeResponse`, `LikedContent`, `LikedContentResponse`, `ContactPerson`,
`CommunityAddress`, `CommunityProfile`, `AccountResponse`, `CommunityOnboardingIn`,
`CommunityUpdate`, `CommunityPhotoConfirm`, `CommunityPhotoOrderUpdate`,
`PollOption`, `Poll`, `CommunityPostCard`, `CommunityPostIn`, `CommunityPostUpdate`,
`VoteIn`, `VoteResult`, `RsvpResult`, `NewsCard`, `CommunityListResponse`,
`CommunityPostsResponse`, `CommunityMember`, `CommunityMembersResponse`,
`CommentCard`, `CommentsResponse`, `RepliesResponse`, `CommentIn`, `CommentVoteIn`,
`CommentVoteResult`, `LastMessage`, `Match`, `SwipeEntry`, `SwipeResult`,
`SentMessage`, `TolerantList` (or equivalent), `OnboardingIn`, `PhotoConfirm`,
`PhotoOrderUpdate`, `ProfileUpdate`, `SwipeIn`, `SwipeAction`, `MessageIn`,
`FcmTokenIn`, `ReportIn`, `ContentEventIn`, `ProfileQuestion`, `Vocabulary`,
`EmptyResponse`. Computed Swift properties (`age`, `asFeedCard`, `displayAge`,
`isCommunity`, `displayPhotos`, `url`, `eventDate`, `publishedDate`,
`relativePublished`, `relativeCreated`, `isVerified`, `isPending`, `totalVotes`,
`percentage(for)`, `unread(for)`, `isMatched`, `isMatch`, `sentDate`, `authorPhoto`,
`audioURL`, `isTopLevel`, `isCommunityAuthor`, …) keep their names (Kotlin
casing, e.g. `audioUrl…`).

**Expected shapes of the Swift enums.** These are what feature code `when`s over. If
`core/model` ships something different, the model file wins and the
integrator updates this table.

```kotlin
sealed interface FeedItem { val id: String
    data class Person(val card: FeedCard) : FeedItem      // "person-{uid}"
    data class Ad(val ad: AdCard) : FeedItem              // "ad-{adId}"
    data class News(val item: NewsCard) : FeedItem        // "news-{newsId}"
    data class Post(val post: CommunityPostCard) : FeedItem // "post-{postId}"
}
sealed interface LikedContent { val id: String; val likedAt: String?
    data class News(val item: NewsCard, override val likedAt: String?) : LikedContent   // "liked-news-{id}"
    data class Post(val post: CommunityPostCard, override val likedAt: String?) : LikedContent // "liked-post-{id}"
}
// As shipped (integrator note): the constants are lowercase, matching the Swift raw values,
// so they serialize as-is. Write SwipeAction.like / .pass / .superlike.
enum class SwipeAction { like, pass, superlike }
```

`FeedCard`, `CommunityProfile`, `CommunityPostCard` and `NewsCard` are `@Serializable`, so nav
routes carry them as `DrokpoJson` strings. `Profile.asFeedCard` exists.

`core.ApiClient` mirrors `APIClient.shared`. As shipped it is a `class` (tests construct one
with a fake token provider and a MockWebServer URL) whose `companion object` forwards to a
shared default instance, so call sites read `ApiClient.get<T>(…)` exactly as if it were an
`object`. It has `suspend` `get / post / patch / put / delete`, with one reified type
argument for the decoded response. It throws `ApiError` (`NotAuthenticated`,
`Http(status, message)`, `InvalidResponse`) and never caches (no OkHttp `Cache`, and every
request sends `Cache-Control: no-cache`). The query is a `List<Pair<String, String>>`, not a
map: `listOf("limit" to "20")`. The shared `kotlinx.serialization.json.Json`
instance is **`app.drokpo.android.core.DrokpoJson`** (`core/Json.kt`).

### 0.6 Calling the API (one style for every group)

```kotlin
import app.drokpo.android.core.ApiClient
import app.drokpo.android.core.EmptyResponse
import app.drokpo.android.core.tryOrNull
import app.drokpo.android.core.userMessage

// GET with query parameters: a list of pairs, values already strings.
val page = ApiClient.get<FeedPage>("/api/feed", listOf("limit" to "20", "shape" to "items"))
// Tolerant lists (iOS `TolerantList`): one bad element doesn't fail the whole response.
val likes = ApiClient.get<TolerantList<SwipeEntry>>("/api/swipes", listOf("action" to "like")).items

// POST / PATCH / PUT: ONE type argument (the response); the body is any @Serializable instance.
ApiClient.post<EmptyResponse>("/api/profile/me/fcm-tokens", FcmTokenIn(token))
val result = ApiClient.post<SwipeResult>("/api/swipes/$uid", SwipeIn(SwipeAction.like))
ApiClient.post<EmptyResponse>("/api/matches/$matchId/read")          // no body
ApiClient.delete<EmptyResponse>("/api/profile/me/photos", listOf("storage_path" to path))

// Swift `try?` → tryOrNull { … } (coroutine cancellation still propagates).
tryOrNull { ApiClient.post<EmptyResponse>("/api/matches/$matchId/read") }

// Swift `error.localizedDescription` → throwable.userMessage() (ApiError detail text verbatim).
try { … } catch (e: CancellationException) { throw e } catch (e: Exception) { errorMessage = e.userMessage() }
```

- `EmptyResponse` accepts any JSON object (`{"ok": true}`, `{"uid": …}`), for endpoints whose
  body iOS ignores.
- Models are immutable `val`s. Update local copies with `copy(...)` (Swift mutates `var`s in place).
- URL helpers (`CommunityPostCard.url`, `NewsCard.url`, `CommentCard.audioURL`, …) return
  `android.net.Uri?`. Date helpers (`eventDate`, `publishedDate`, `sentDate`, …) return
  `java.time.Instant?`.
- Firestore maps (`DocumentSnapshot.data`): use the `Map<String, Any?>.firestoreString /
  firestoreInt / firestoreIntMap / firestoreInstant / firestoreStringList / firestoreMap`
  accessors in `core.model`. Android's SDK returns every integer as `Long` and timestamps as
  `Timestamp`, so a straight port of Swift's `as? Int` or `as? [String: Int]` silently yields null.

---

## A. Spine API (foundation-owned; everyone may call)

All of the following lives under `app/src/main/kotlin/app/drokpo/android/` and is owned
by the foundation. Feature groups call it but never edit it. The foundation must ship at
least stub versions of all of §A before the parallel feature phase starts.

### A.1 `core/AppGraph.kt` — reaching singletons

```kotlin
package app.drokpo.android.core

object AppGraph {
    /** Called first thing in DrokpoApplication.onCreate() (after FirebaseApp init). Idempotent. */
    fun init(app: Application)

    val app: Application
    /** App-lifetime scope: SupervisorJob() + Dispatchers.Main.immediate. For work that must
     *  outlive a screen (signOut, analytics events, token upload, fire-and-forget likes). */
    val appScope: CoroutineScope

    val prefs: AppPreferences
    val session: SessionStore
    val blocks: BlockStore
    val deepLinks: DeepLinkRouter
    val push: PushService
}
```

Stateless services are Kotlin `object`s in `core`: `ApiClient`, `AuthService`,
`PhotoUploader`, `MediaUploader`, `Safety`, `ContentEvents`, `AppConfig`. There
is no DI framework. ViewModels read `AppGraph.*` directly.

### A.2 `core/AppConfig.kt`

```kotlin
object AppConfig {
    val API_BASE_URL: String          // BuildConfig.API_BASE_URL = "https://drokpo-backend.web.app"
    val PRIVACY_POLICY_URL: String    // BuildConfig.PRIVACY_POLICY_URL
    val SHARE_HOST: String            // "drokpo-backend.web.app" (host of /s/{type}/{id} links)
    val hasFirebaseConfig: Boolean    // BuildConfig.HAS_FIREBASE_CONFIG
    /** "1.1 (37)" — iOS "\(CFBundleShortVersionString) (\(CFBundleVersion))". Both Settings screens. */
    val versionLabel: String
}
```

### A.3 `core/SessionStore.kt` — port of `SessionStore.swift`

```kotlin
enum class SessionState {
    Loading, SignedOut,
    /** Signed in, no users/{uid} or communities/{uid} doc yet — ask person vs community. */
    ChoosingAccountType,
    NeedsOnboarding, ActivePerson,
    NeedsCommunityOnboarding, ActiveCommunity,
    Failed,
}

enum class AccountType { Person, Community }

class SessionStore internal constructor(/* spine-private */) {
    val state: StateFlow<SessionState>             // starts Loading (SignedOut if !hasFirebaseConfig)
    val myProfile: StateFlow<Profile?>
    val myCommunity: StateFlow<CommunityProfile?>
    val lastError: StateFlow<String?>

    /** FirebaseAuth.currentUser?.uid, read live (null when !hasFirebaseConfig). Changes only together
     *  with `state`, so reading it during composition after collecting `state` is safe. */
    val uid: String?
    /** Email of the signed-in account (null for phone sign-in or providers that hide it). */
    val email: String?
    /** Phone number (phone sign-in only). */
    val phone: String?

    /** GET /api/account → route person / community / neither. Sets myProfile/myCommunity/state;
     *  on failure lastError = userMessage(), state = Failed. Calls AppGraph.push.enable() when the
     *  result is ActivePerson or ActiveCommunity. */
    suspend fun refreshAccount()
    /** Same as refreshAccount() — the name every screen calls to mean "my account may have changed". */
    suspend fun refreshProfile()
    /** Purely local: Person → NeedsOnboarding, Community → NeedsCommunityOnboarding. */
    fun chooseAccountType(type: AccountType)
    /** Fire-and-forget on appScope: AppGraph.push.unregister() (while the token is still valid),
     *  clear Credential Manager state, FirebaseAuth.signOut(). The auth listener then clears
     *  myProfile/myCommunity, calls AppGraph.blocks.reset() and sets SignedOut. */
    fun signOut()
}
```

Behaviour (spine): an `AuthStateListener` is registered in `init`. When the user becomes null it
does the sign-out reset; otherwise it calls `refreshAccount()`. The routing rule is
`accountType == "person"`: `ActivePerson` if `profile?.onboardingComplete ?: true`, else
`NeedsOnboarding`. `"community"` gives `ActiveCommunity` (no onboarding check). Anything
else gives `ChoosingAccountType`. `lastError` is **not** cleared on success (iOS parity).

### A.4 `core/AuthService.kt` — port of `AuthService.swift`

```kotlin
sealed class AuthServiceError(message: String) : Exception(message) {
    class MissingToken : AuthServiceError("Sign-in didn't return a valid token. Please try again.")
    class NoPresenter : AuthServiceError("Couldn't present the sign-in screen.")
    /** User backed out of the Google/Apple UI. Callers ignore it silently (no alert). */
    class Cancelled : AuthServiceError("Sign-in was cancelled.")
    /** Android-only (integrator addition): Credential Manager's NoCredentialException — no Google
     *  account on the device. Shown like any other error ("Sign-in failed" alert). */
    class NoGoogleAccount : AuthServiceError("No Google account is available on this device. Add one in Settings, or continue with phone.")
}

typealias PhoneResendToken = com.google.firebase.auth.PhoneAuthProvider.ForceResendingToken

sealed interface PhoneVerification {
    /** SMS sent — show the code step. Keep `resendToken` for "Resend code". */
    data class CodeSent(val verificationId: String, val resendToken: PhoneResendToken?) : PhoneVerification
    /** Android instant verification / SMS auto-retrieval already signed the user in. */
    data object AutoVerified : PhoneVerification
}

object AuthService {
    /** BuildConfig.APPLE_SIGN_IN_ENABLED — SignInScreen hides the Apple button when false. */
    val isAppleSignInEnabled: Boolean

    /** Credential Manager (GetSignInWithGoogleOption, serverClientId = default_web_client_id looked up
     *  at runtime via resources.getIdentifier — the resource only exists when google-services.json does)
     *  → GoogleAuthProvider credential → FirebaseAuth.signInWithCredential. */
    suspend fun signInWithGoogle(activity: Activity)

    /** Firebase OAuthProvider("apple.com"), scopes ["name"], startActivityForSignInWithProvider
     *  (checks pendingAuthResult first). Only called when isAppleSignInEnabled. */
    suspend fun signInWithApple(activity: Activity)

    /** PhoneAuthProvider.verifyPhoneNumber (60 s timeout). Resumes on the first of
     *  codeSent / verificationCompleted / verificationFailed (throws the FirebaseException).
     *  If auto-retrieval completes AFTER CodeSent was returned, the service signs in by itself —
     *  SessionStore's listener then routes away from SignInScreen. */
    suspend fun startPhoneVerification(
        activity: Activity,
        e164: String,
        resendToken: PhoneResendToken? = null,
    ): PhoneVerification

    suspend fun signInWithPhone(verificationId: String, code: String)
}
```

Account deletion needs no re-auth: iOS calls `DELETE /api/profile/me` or
`/api/communities/me`, the backend deletes the Firebase Auth user, and the app then
signs out. AuthService has nothing for Settings.

### A.5 `core/BlockStore.kt` — port of `BlockStore.swift`

```kotlin
@Serializable
data class BlockedUser(val uid: String, val displayName: String? = null, val blockedAt: Long /* epoch ms */) {
    val id: String get() = uid
}

/** Local record of who you've blocked — the backend has no list endpoint. */
class BlockStore internal constructor(prefs: AppPreferences) {
    val blocked: StateFlow<List<BlockedUser>>       // newest first, persisted (key "drokpo.blockedUsers")
    fun record(uid: String, displayName: String?)    // no-op if already present; inserts at index 0
    /** DELETE /api/blocks/{uid}, then forget the entry. Throws (entry kept on failure). */
    suspend fun unblock(user: BlockedUser)
    /** Sign-out reset — entries belong to the old account. */
    fun reset()
}
```

### A.6 `core/Safety.kt`, `core/ContentEvents.kt` — dedup of repeated iOS calls

```kotlin
object Safety {
    /** POST /api/reports {reportedUid, reason, note}. Throws. */
    suspend fun report(reportedUid: String, reason: String, note: String = "")
    /** POST /api/blocks/{uid}, then AppGraph.blocks.record(uid, displayName). Throws; nothing recorded on failure. */
    suspend fun block(uid: String, displayName: String?)
}

object ContentEvents {
    /** Fire-and-forget POST /api/{path}/events {"event": event} on appScope; all errors swallowed.
     *  path = "ads/{adId}" | "news/{newsId}" | "posts/{postId}". */
    fun send(path: String, event: String)
    fun click(path: String)        // send(path, "click")
    fun impression(path: String)   // send(path, "impression")
}
```

Groups may still call `ApiClient` directly. These helpers keep the endpoints in one place.

### A.7 `core/DeepLinkRouter.kt` — port of `DeepLinkRouter.swift`

```kotlin
class DeepLinkRouter internal constructor() {
    /** Push-tap payload. `type` = "match" | "message" | "like". */
    val pendingMatchId: StateFlow<String?>
    val pendingType: StateFlow<String?>
    /** Shared-content link waiting to be shown (chat-bubble tap, drokpo://s/… or https://…/s/… intent).
     *  Set by anyone (`pendingShare.value = dest`); consumed + cleared by MainTabs. */
    val pendingShare: MutableStateFlow<ShareDestination?>
    /** Set by MainTabs for a "like" push; consumed + cleared by LikesScreen (open "Liked you"). */
    val focusLikedYou: MutableStateFlow<Boolean>

    fun handle(type: String?, matchId: String?)   // sets both
    fun clear()                                    // clears both (not pendingShare/focusLikedYou)
}
```

`ShareDestination` is defined by group 11 (§B.11) in
`features.shared.sharing`. Core imports it.

### A.8 `core/PushService.kt` (+ `core/DrokpoMessagingService.kt`) — port of `PushService.swift`

```kotlin
class PushService internal constructor(app: Application) {
    /** Called every time the session becomes active (SessionStore) and once by MainTabs with the
     *  activity. With an activity, on API 33+, and POST_NOTIFICATIONS not granted and not yet asked
     *  (pref "drokpo.notificationsPrompted"), shows the system permission dialog once (iOS: the
     *  system prompt only shows once). When notifications are allowed, it fetches the FCM token and
     *  POSTs /api/profile/me/fcm-tokens {token} if it differs from the uploaded one. Never throws;
     *  failed uploads retry on the next enable() or token rotation. */
    fun enable(activity: Activity? = null)
    /** DELETE /api/profile/me/fcm-tokens?token=… (errors ignored). Call before signing out. */
    suspend fun unregister()

    // As shipped (spine-facing; MainActivity is the host — features don't call these):
    fun attachPermissionHost(host: NotificationPermissionHost)
    fun detachPermissionHost(host: NotificationPermissionHost)
    fun onPermissionHostResumed(host: NotificationPermissionHost)
    fun onNotificationPermissionResult(granted: Boolean)
    fun needsRuntimePermission(): Boolean   // API 33+, POST_NOTIFICATIONS not granted
    fun notificationsAllowed(): Boolean     // NotificationManagerCompat.areNotificationsEnabled()
}

/** Implemented by MainActivity, which registers the RequestPermission launcher before STARTED. */
interface NotificationPermissionHost { fun launchNotificationPermissionRequest() }
```

`enable()` without an activity can still prompt, through the attached MainActivity. If no
started activity exists, the prompt waits until MainActivity's next `onResume`. MainTabs'
`enable(activity)` call stays valid and is now just a harmless second trigger.

Spine-internal: `DrokpoMessagingService : FirebaseMessagingService` does three things.
- `onNewToken` passes the token to PushService (upload if signed in).
- `onMessageReceived` covers the app in the foreground. iOS shows banners in the
  foreground, so this posts a notification on the default channel with the payload's
  `notification.title/body`. Its PendingIntent targets `MainActivity` and carries the
  `type` and `matchId` extras.
- Background messages are shown by the system tray. A tap launches `MainActivity`
  with the data keys as intent extras.

Payloads from `functions/main.py`:
- match: `{type:"match", matchId}`
- like: `{type:"like"}`
- message: `{type:"message", matchId}`

### A.9 `core/PhotoUploader.kt`, `core/MediaUploader.kt`

```kotlin
sealed class PhotoUploaderError(message: String) : Exception(message) {
    class NotAuthenticated : PhotoUploaderError("You need to sign in again.")
    class InvalidImage : PhotoUploaderError("That photo couldn't be processed. Try a different one.")
}

object PhotoUploader {
    const val MAX_DIMENSION_PX = 1600
    const val JPEG_QUALITY = 80
    /** users/{uid}/photos/{uuid}.jpg (contentType image/jpeg) → returns the STORAGE PATH. */
    suspend fun upload(uri: Uri): String
    /** communities/{uid}/photos/{uuid}.jpg → storage path. */
    suspend fun uploadCommunityPhoto(uri: Uri): String
    suspend fun downloadUrl(storagePath: String): String
    /** Decode via ContentResolver, apply EXIF orientation, longest side ≤ 1600 PIXELS (the iOS comment:
     *  never upscale — the storage rule caps at 10 MB), JPEG q80. Throws InvalidImage. */
    suspend fun downscaledJpeg(uri: Uri): ByteArray
}

object MediaUploader {
    /** commentAudio/{uid}/{uuid}.m4a (contentType "audio/m4a") → STORAGE PATH (backend resolves the URL). */
    suspend fun uploadCommentAudio(file: File): String
    /** chatMedia/{uid}/{uuid}.jpg (downscaled like PhotoUploader) → DOWNLOAD URL. */
    suspend fun uploadChatPhoto(uri: Uri): String
    /** chatMedia/{uid}/{uuid}.m4a (contentType "audio/m4a") → DOWNLOAD URL. */
    suspend fun uploadChatAudio(file: File): String
}
```

Both throw `NotAuthenticated` when there is no current user. MediaUploader reuses the
`PhotoUploaderError` messages.

### A.10 `core/AppPreferences.kt` — every iOS `@AppStorage`/`UserDefaults` key

```kotlin
enum class AppearanceMode(val raw: String, val label: String) {
    System("system", "System"), Light("light", "Light"), Dark("dark", "Dark");
    /** null for System → follow the device. */
    fun isDark(systemIsDark: Boolean): Boolean
    companion object { fun fromRaw(raw: String?): AppearanceMode }  // unknown/null → System
}

class AppPreferences internal constructor(context: Context) {
    val appearance: StateFlow<AppearanceMode>
    /** True after the first DataStore read — MainActivity keeps the splash up until then (no theme flash). */
    val isLoaded: StateFlow<Boolean>
    fun setAppearance(mode: AppearanceMode)          // fire-and-forget write; the StateFlow updates immediately
    /** Same, but returns once the value is on disk. */
    suspend fun updateAppearance(mode: AppearanceMode)
}
```

| Key (DataStore `drokpo_prefs`) | Type | Default | iOS origin | Used by |
|---|---|---|---|---|
| `drokpo.appearance` | String (`AppearanceMode.raw`) | `"system"` | `@AppStorage` in DrokpoApp, SettingsView, CommunitySettingsView | MainActivity theme, Settings, CommunitySettings |
| `drokpo.blockedUsers` | String (JSON array of `BlockedUser`) | `[]` | `UserDefaults` in BlockStore | BlockStore (spine-internal) |
| `drokpo.notificationsPrompted` | Boolean | `false` | *Android-only*: iOS's "system prompt only shows once" | PushService (spine-internal) |
| `drokpo.locationPermissionRequested` | Boolean | `false` | *Android-only*: iOS's `.notDetermined` location status | features/onboarding `LocationFetcher` (`internal suspend fun locationPermissionRequestedNow()` / `setLocationPermissionRequested(value)`; set just before the first dialog, so a first-ever dialog dismissed with back or a tap outside isn't read as a permanent denial) |

Those are the only persisted keys in the iOS app (grep: `AppStorage`, `UserDefaults`). Feature
groups must not add DataStore keys. Request them instead.

### A.11 `core/RemotePhoto.kt` — port of `RemotePhotoView`/`PhotoBand`

```kotlin
/** Fills the caller's bounds (caller sets size/aspect and clips the shape). Loads photo.url when
 *  present, else PhotoUploader.downloadUrl(storagePath); memory+disk cache key = "storagePath|url"
 *  (just storagePath when there's no url — synthetic keys like ad-image-{id} must not pin a stale
 *  image in the persistent disk cache); 2 attempts
 *  1 s apart; cancellation isn't failure. Placeholders: photo == null → Person icon; loading → spinner;
 *  failed → BrokenImage icon, tap to retry. */
@Composable fun RemotePhotoView(photo: Photo?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop,
                                contentDescription: String? = null)
/** Same, for models that carry a bare storage path / URL pair (logos, post photos, comment authors). */
@Composable fun RemotePhotoView(storagePath: String?, url: String?, modifier: Modifier = Modifier,
                                contentScale: ContentScale = ContentScale.Crop, contentDescription: String? = null)

/** Fixed-aspect band (width × width/aspect) whose cropped photo can never affect layout. */
@Composable fun PhotoBand(photo: Photo?, modifier: Modifier = Modifier, aspect: Float = 16f / 9f)
@Composable fun PhotoBand(storagePath: String?, url: String?, modifier: Modifier = Modifier, aspect: Float = 16f / 9f)
```

To render a local picked image (`Uri`), use Coil's `AsyncImage(model = uri)` directly. Coil's
singleton loader comes from `newDrokpoImageLoader(context)` (core/RemotePhoto.kt), installed by
`DrokpoApplication : SingletonImageLoader.Factory`, so `AsyncImage` everywhere shares its caches.

### A.12 `ui/components/*` — generic widgets every group uses

```kotlin
package app.drokpo.android.ui.components

/** Leading top-bar button: Back = arrow (pushed), Close = X (root of a sheet/cover; iOS "Close",
 *  "Cancel", "Done" dismiss buttons), None = nothing (tab roots). */
enum class NavIcon { Back, Close, None }

/** iOS navigation bar. large=false → CenterAlignedTopAppBar (iOS .inline title);
 *  large=true → LargeTopAppBar (iOS large title). Drops the status-bar inset automatically inside a DrokpoSheet. */
@Composable fun DrokpoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navIcon: NavIcon = NavIcon.None,
    onNavIcon: () -> Unit = {},
    large: Boolean = false,
    containerColor: Color = DrokpoTheme.colors.background,      // as shipped: grouped screens pass groupedBackground
    scrollBehavior: TopAppBarScrollBehavior? = null,             // as shipped: large titles that collapse
    navigationIcon: (@Composable () -> Unit)? = null,            // as shipped: custom leading content, overrides navIcon
    actions: @Composable RowScope.() -> Unit = {},
)

/** iOS .sheet → ModalBottomSheet. skipPartiallyExpanded=true ≙ detents [.large] (iOS default);
 *  false ≙ [.medium, .large]. Provides LocalInsideSheet = true and a presentation-scoped
 *  ViewModelStoreOwner (§D.3). System back / swipe down → onDismissRequest. */
@Composable fun DrokpoSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    containerColor: Color = DrokpoTheme.colors.background,     // integrator addition (grabber strip included)
    content: @Composable ColumnScope.() -> Unit,
)
/** Integrator addition. Inside a DrokpoSheet: hide() with the slide-down animation, then the sheet's
 *  onDismissRequest (iOS dismiss()); repeated calls during one hide are ignored. Null outside a
 *  sheet and inside a FullScreenCover (which also resets LocalInsideSheet to false). Wire the
 *  sheet's own Close (X) to it: `onClose = LocalSheetDismiss.current ?: onDismissRequest`. */
val LocalSheetDismiss: ProvidableCompositionLocal<(() -> Unit)?>

/** iOS .fullScreenCover, and form sheets (Edit profile, Settings, Composer, Phone sign-in):
 *  full-screen Dialog (usePlatformDefaultWidth=false, decorFitsSystemWindows=false), edge-to-edge,
 *  presentation-scoped ViewModelStoreOwner. System back → onDismissRequest (unless an inner NavHost pops first). */
@Composable fun FullScreenCover(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

/** iOS .confirmationDialog. Bottom sheet listing items + a Cancel row. Tapping an item calls
 *  onDismissRequest() first, then item.onClick() (so chaining to a second ActionSheet works).
 *  title == null ≙ iOS titleVisibility hidden. */
data class ActionSheetItem(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)
@Composable fun ActionSheet(
    onDismissRequest: () -> Unit,
    items: List<ActionSheetItem>,
    title: String? = null,
    message: String? = null,
    cancelLabel: String = "Cancel",
)

/** iOS `.alert(title, isPresented: errorMessage != nil) { OK } message: { errorMessage }`.
 *  Renders nothing when message == null. */
@Composable fun ErrorAlert(message: String?, onDismiss: () -> Unit, title: String = "Something went wrong")

/** iOS ContentUnavailableView / custom empty VStacks (icon, headline, secondary text, optional button). */
@Composable fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    compact: Boolean = false,                    // as shipped: the Likes/Chats custom empties (48dp icon, headline title)
    action: (@Composable () -> Unit)? = null,
)

/** iOS Form/List(.insetGrouped) Section: secondary-grouped rounded card on the grouped background. */
@Composable fun GroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    footerContent: (@Composable () -> Unit)? = null,
    headerContent: (@Composable () -> Unit)? = null,           // as shipped
    separatorInset: Dp = GroupedDefaults.SeparatorInset,       // as shipped (16dp; rows with icons/avatars pass more)
    content: @Composable ColumnScope.() -> Unit,
)

/** System photo picker. maxItems == 1 → PickVisualMedia; > 1 → PickMultipleVisualMedia(maxItems)
 *  (which throws for maxItems ≤ 1 — this wrapper hides that pitfall); ≤ 0 → no-op. Returns the launch function. */
@Composable fun rememberPhotoPicker(maxItems: Int = 1, onPicked: (List<Uri>) -> Unit): () -> Unit

/** iOS SafariView: Custom Tab. Anything not http(s) is replaced by https://drokpo-backend.web.app
 *  (iOS guard against SFSafariViewController crashing on other schemes). */
fun Context.openInAppBrowser(url: String)
/** iOS UIApplication.openSettingsURLString → ACTION_APPLICATION_DETAILS_SETTINGS for this package. */
fun Context.openAppSettings()

/** Provides a fresh LocalViewModelStoreOwner for `content`, cleared when it leaves composition.
 *  Used by RootScreen per branch (MainTabs' branch = session scope), DrokpoSheet and FullScreenCover
 *  (presentation scope). */
@Composable fun ScopedViewModels(content: @Composable () -> Unit)

val LocalInsideSheet: ProvidableCompositionLocal<Boolean>   // default false
```

**Also shipped in `ui/components`.** Use these instead of hand-rolling (every one ports an iOS idiom):

| File | Widgets |
|---|---|
| `Bars.kt` | `BackButton`, `CloseButton`, `ActionBar` (bottom action bar on the bar background), `ListDivider(startIndent)`, `PlainSectionHeader` |
| `Buttons.kt` | `ControlSize { Small, Regular, Large }`, `ProminentButton` (`.borderedProminent`; text or slot overload, `loading`, `icon`, `tint`), `PrimaryButton` (full-width bottom call to action: Continue/Finish, Like back), `SecondaryButton` (`.bordered`: no `tint` = iOS without `.tint()`, accent label on the grey `secondaryFill`; an explicit `tint`, `.tint(.accentColor)` included, = translucent tinted fill; `spacing` sets the label gap), `PlainTextButton`, `DestructiveTextButton`, `ButtonMetrics` |
| `Grouped.kt` | `GroupedList` (LazyColumn on the grouped background), `GroupedForm` (scrolling Column), `GroupedRow`, `GroupedValueRow`, `GroupedNavigationRow`, `GroupedToggleRow` (text or label slot), `GroupedButtonRow` (destructive, loading), `GroupedExternalLinkRow`, `GroupedCheckmarkRow`, `GroupedPickerRow` (iOS `Picker(.menu)`), `GroupedTextField`, `drokpoSwitchColors()`, `GroupedDefaults` |
| `Chips.kt` | `FlowLayout` (iOS FlowLayout), `TagFlow`, `TagChip`, `TintedTag`, `OverlayTag` (badges on photos), `Chip` + `ChipBar` (pill filters) |
| `SegmentedPicker.kt` | `SegmentedPicker(options, selected, onSelect, label = …)`: iOS `Picker(.segmented)` look |
| `Avatar.kt` | `Avatar(photo, name, size = 56.dp)`: circular photo; initials on grey (or the person glyph) without one |
| `Badges.kt` | `CountBadge` (unread pill), `TabCountBadge` (BadgedBox badge, hidden at 0) |
| `Dialogs.kt` | `DrokpoAlert(title, onDismissRequest, message, confirmButton, dismissButton)` + `AlertButton`/`ActionRole` (iOS `.alert` with custom buttons, e.g. "It's a match!"); `ConfirmationSheet` + `ConfirmationAction` (an `ActionSheet` with roles and disabled items) |
| `States.kt` | `ErrorState(message, title, onRetry)` (inline error in place of content), `LoadingState` (centred spinner), `Spinner` (inline 20dp) |
| `TextFields.kt` | `RoundedTextField` (iOS `.roundedBorder` field: phone number, centred OTP via `textAlign`/`textStyle`) |
| `InAppBrowser.kt` | `rememberInAppBrowser(): (String) -> Unit` (Custom Tab coloured by the effective theme; prefer it inside composables), `openInAppBrowser(context, url, darkTheme)` |

The theme agent owns `ui/theme`. Names as shipped:
- `DrokpoTheme(darkTheme: Boolean = isSystemInDarkTheme(), dark: Boolean = darkTheme, content)`
  accepts either spelling; `dark` wins. It also keeps the system-bar icon colours in step with
  the flag.
- `DrokpoTheme.isDark` is the effective dark flag. Use it, never `isSystemInDarkTheme()`,
  because `AppearanceMode` can override the system.
- `DrokpoTheme.colors` (`DrokpoColors`): `accent`, `onAccent`, `brandRed`, `destructive`,
  `label`, `secondaryLabel`, `tertiaryLabel`, `quaternaryLabel`, `placeholderText`,
  `background`, `secondaryBackground`, `tertiaryBackground`, `groupedBackground`,
  `secondaryGroupedBackground`, `tertiaryGroupedBackground`, `bar`, `separator`,
  `opaqueSeparator`, `fill` (systemGray6-like), `fillSubtle`, `tertiaryFill`, `secondaryFill`
  (UIKit secondarySystemFill: untinted `.bordered` buttons), `systemGray`,
  `green`, `orange`, `yellow`, `onPhoto`, `photoScrimLight`, `photoScrim`, `photoScrimStrong`,
  `dimmingScrim`, `cardBackdrop`.
- `DrokpoTheme.typography` (`DrokpoTypography`): `largeTitle`, `title`, `title2`, `title3`,
  `headline`, `body`, `callout`, `subheadline`, `footnote`, `caption`, `caption2`. Weight
  helpers: `.bold()`, `.semibold()`, `.medium()`, `.monospacedDigit()`.
- `Brand.Red` / `Brand.Blue` are fixed artwork colours, `PhotoScrims.personCard / contentCard /
  noPhotoFallback` are card gradients, `DrokpoLogo(size = 96.dp)` is the logo, and
  `@DrokpoPreviews` gives light and dark previews.

### A.13 `navigation/SharedNavigation.kt` — destinations every NavHost can push

iOS pushes `ProfileDetailView`, `CommunityPageView` and `CommunityMembersView` from many
different stacks. Android registers them in every host through one builder, so the routes are
identical everywhere.

```kotlin
package app.drokpo.android.navigation

@Serializable sealed interface SharedRoute {
    /** ProfileDetailScreen, ProfileDetailContext.Plain. cardJson = DrokpoJson-encoded FeedCard. */
    @Serializable data class Profile(val cardJson: String) : SharedRoute
    /** CommunityPageScreen. previewJson = DrokpoJson-encoded CommunityProfile (directory/rail card). */
    @Serializable data class Community(val cid: String, val previewJson: String? = null, val ownerMode: Boolean = false) : SharedRoute
    /** CommunityMembersScreen. */
    @Serializable data class CommunityMembers(val cid: String) : SharedRoute
}

/** Push a member. card.isCommunity → openCommunity(card.uid) instead (the iOS
 *  `if card.isCommunity { CommunityPageView } else { ProfileDetailView }` pattern). */
fun NavController.openProfile(card: FeedCard)
fun NavController.openCommunity(cid: String, preview: CommunityProfile? = null)
fun NavController.openCommunityMembers(cid: String)

/** Registers the three SharedRoute destinations (ProfileDetailScreen, CommunityPageScreen,
 *  CommunityMembersScreen) wired to the helpers above. onCloseHost = how to close the sheet/cover
 *  that contains this NavHost (null for tab roots). */
fun NavGraphBuilder.sharedDestinations(navController: NavHostController, onCloseHost: (() -> Unit)? = null)

/** Pop; if this is the host's start destination, onCloseHost?.invoke(). Use it as every destination's onBack. */
fun NavHostController.backOrClose(onCloseHost: (() -> Unit)?)

/** Back if `entry` has something below it on the back stack; else Close if onCloseHost != null; else None. */
fun NavHostController.navIconFor(entry: NavBackStackEntry, onCloseHost: (() -> Unit)?): NavIcon

/** NavHost whose graph is just sharedDestinations, starting at `start`. MainTabs uses it for the
 *  community account's Communities tab: SharedNavHost(SharedRoute.Community(myCid, ownerMode = true),
 *  navController = nav). Pass navController (integrator addition) when the caller needs it,
 *  e.g. for a tab reselect. */
@Composable fun SharedNavHost(
    start: SharedRoute,
    modifier: Modifier = Modifier,
    onCloseHost: (() -> Unit)? = null,
    navController: NavHostController = rememberNavController(),
)
```

Implementation notes (as shipped):
- `backOrClose` checks `previousBackStackEntry` before popping. `popBackStack()` at the start
  destination succeeds and leaves the NavHost empty, which shows a blank sheet.
- `navIconFor` uses public API only, because `NavController.currentBackStack` is
  library-restricted and fails lint with RestrictedApi. It decides "is this the root
  entry?" once, while the entry is on top, and caches the answer in the entry's
  `SavedStateHandle`. Don't call `currentBackStack` in feature code either.

### A.14 `RootScreen.kt`, `MainTabs.kt`, `MainActivity.kt`

```kotlin
package app.drokpo.android

/** Port of RootView. Each branch below is wrapped in its own ScopedViewModels (§D.3).
 *  !AppConfig.hasFirebaseConfig → SetupNoticeScreen(); else Crossfade on state:
 *  Loading → spinner; SignedOut → SignInScreen(); ChoosingAccountType → AccountTypeChoiceScreen();
 *  NeedsOnboarding → OnboardingFlowScreen(); ActivePerson/ActiveCommunity → MainTabs() (same
 *  crossfade key, so MainTabs isn't recreated between them); NeedsCommunityOnboarding →
 *  CommunityOnboardingFlowScreen(); Failed → "Couldn't load your profile." + lastError (footnote,
 *  secondary) + "Retry" (filled → refreshProfile()) + "Sign out" (text). */
@Composable fun RootScreen()

/** Gear icon, "Firebase not configured", "Add google-services.json to app/ and rebuild." */
@Composable fun SetupNoticeScreen()

enum class MainTab { Discover, Likes, Communities, Chats, Profile }

/** Port of MainTabView — see §C.1 and §F.0. */
@Composable fun MainTabs()

/** Runs onReselect when the user taps the already-selected tab (iOS: tapping the active tab pops its
 *  NavigationStack to root). Tab roots that own a NavHost pop to their start destination. */
@Composable fun TabReselectEffect(tab: MainTab, onReselect: () -> Unit)
```

`MainActivity` handles `onCreate` and `onNewIntent` in this order. iOS gives share links
precedence.
1. `intent.data` parsed by `ShareDestination.parse(uri)` is non-null → `AppGraph.deepLinks.pendingShare.value = it`.
2. Else, extras `type` and/or `matchId` present → `AppGraph.deepLinks.handle(type, matchId)`.

It applies `DrokpoTheme(dark = prefs.appearance.isDark(systemDark))`, keeps the splash
screen up until `prefs.isLoaded`, and calls `enableEdgeToEdge` with light or dark system bar
styles that follow the effective theme.

As shipped: `installSplashScreen()` runs before `super.onCreate` (the manifest points
MainActivity at `Theme.Drokpo.Starting`). The two routing steps above live in the internal
`DeepLinkRouter.handleLaunch(dataString, type, matchId)`. A handled intent has its data and
extras stripped, and intents flagged `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` are ignored, so
Recents never replays a followed link. The launch mode is `singleTask`, so later links arrive
through `onNewIntent`. MainActivity also implements `NotificationPermissionHost` (§A.8).

**Tab reselect idiom** (§C.2). Feature tab roots with a NavHost use:

```kotlin
import androidx.navigation.NavGraph.Companion.findStartDestination
TabReselectEffect(MainTab.Likes) { nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false) }
```

Tab roots without a NavHost (Feed, Profile, CommunityProfileEditor) may use it for
scroll-to-top. Outside MainTabs (e.g. in the catalog) it is a no-op.

### A.15 Manifest & infra the foundation must add

- Permissions: `INTERNET`, `POST_NOTIFICATIONS`, `ACCESS_COARSE_LOCATION`,
  `ACCESS_FINE_LOCATION`, `RECORD_AUDIO`. The photo picker needs none.
- Add `android:configChanges` to `MainActivity`: `orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|uiMode|density|fontScale|fontWeightAdjustment|grammaticalGender|locale|layoutDirection|mcc|mnc|touchscreen|navigation|colorMode`.
  Compose handles these in place, so presentation-scoped ViewModels survive. Keep the list
  complete: ScopedViewModels' stores live in `remember`, so any change left out recreates the
  Activity and clears them.
- `MainActivity` intent filters:
  - `VIEW` + `BROWSABLE` + `DEFAULT`, scheme `drokpo`, host `s`.
  - `VIEW`, `autoVerify="true"`, scheme `https`, host `drokpo-backend.web.app`, `pathPrefix="/s/"`.
    This needs `/.well-known/assetlinks.json` on Hosting; see open issues.
- Declare `DrokpoMessagingService` (`com.google.firebase.MESSAGING_EVENT`). Add one notification
  channel, `drokpo_default` "Notifications", created in `DrokpoApplication`. Point the
  `com.google.firebase.messaging.default_notification_channel_id` meta-data at it. Use a
  monochrome small icon.
- `DrokpoApplication.onCreate`: `FirebaseApp.initializeApp` (only if `hasFirebaseConfig`), then
  `AppGraph.init(this)`, then the `drokpo_default` channel (`PushNotifications.createChannels`).
  `DrokpoApplication` is also Coil's `SingletonImageLoader.Factory` (§A.11).
- Also shipped:
  - `android:allowBackup="false"`, `android:fullBackupContent="false"` (API 26–30) and
    `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+). Nothing goes to
    cloud backup or device transfer.
  - `<queries>` for the Custom Tabs service and for https `VIEW` + `BROWSABLE`. The latter is
    the browser fallback when no Custom Tabs provider exists.
  - `default_notification_icon` = `@drawable/ic_stat_notification` and
    `default_notification_color` = `@color/notification_accent` meta-data. The foreground
    builder uses the same icon and colour.
  - MainActivity is `singleTask`, `screenOrientation="portrait"` (iOS is portrait-only),
    `windowSoftInputMode="adjustResize"`, with theme `Theme.Drokpo.Starting` (splash).
  - The debug manifest adds the exported `.catalog.CatalogActivity`.

### A.16 Spine stubs that §B depends on

The foundation also generates the §B stubs. Each stub has the final signature and a
placeholder body: a `Text("<Name> — TODO")` or a trivial non-UI implementation that
returns null, empty or false. Stubs must compile against §A, so `ShareDestination`,
`ChatStore` and the `LocalChatStore` stubs must exist before `DeepLinkRouter` and
`MainTabs` compile.

---

## B. Feature groups — public entry points (binding)

Format per group: **package/files**, **public API**, **ports**, **callers**,
**presents**, **internal scope**. "Callers" are all call sites outside the group.

### B.1 `auth` — `features/auth`

```kotlin
package app.drokpo.android.features.auth

/** Port of SignInView. Needs LocalActivity for AuthService calls. */
@Composable fun SignInScreen(modifier: Modifier = Modifier)

/** Port of AccountTypeChoiceView. Calls AppGraph.session.chooseAccountType(...) / signOut(). */
@Composable fun AccountTypeChoiceScreen(modifier: Modifier = Modifier)
```

- **Callers:** `RootScreen` (SignedOut, ChoosingAccountType).
- **Presents:** `PhoneSignInScreen` in a `FullScreenCover`, plus ErrorAlerts.
- **Internal:** `PhoneSignInScreen(onDismiss: () -> Unit)` (port of PhoneSignInView) and
  `PhoneSignInModel` (VM: step, country code, cooldown). Also `SignInContent`,
  `PhoneSignInContent`, `AccountTypeChoiceContent`, the `CountryCode` list, and the
  Firebase error → friendly message mapping.

### B.2 `onboarding` — `features/onboarding`

```kotlin
package app.drokpo.android.features.onboarding

/** Port of OnboardingFlow (+ OnboardingModel). */
@Composable fun OnboardingFlowScreen(modifier: Modifier = Modifier)

/** Port of Shared/ProfileQuestionFields: one GroupedSection(header = question.label) per
 *  Vocabulary.questions entry, stacked in a Column (NOT lazy — place inside a scrolling Column or a
 *  single LazyColumn item). choice → dropdown with "Skip" ("" value) + options, label hidden;
 *  text → 1–4 line field with the question's placeholder. Empty answers are stripped by the caller. */
@Composable fun ProfileQuestionFields(
    answers: Map<String, String>,
    onAnswerChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
)

/** Port of LocationFetcher (one-shot location). Obtain with rememberLocationFetcher(). */
class LocationFetcher internal constructor(/* group-private: context, permission launcher */) {
    /** Location permission denied with no further prompting possible (iOS .denied/.restricted) —
     *  only system Settings can change it. Valid after a requestLocation() call. */
    val isDenied: Boolean
    /** Requests ACCESS_COARSE_LOCATION + ACCESS_FINE_LOCATION if not granted (system dialog only — no
     *  custom "Allow" button, App Review 5.1.1(iv) rationale), then one current fix (Fused, balanced
     *  power, ~10 s timeout). Returns null on denial, failure or timeout. Never throws. */
    suspend fun requestLocation(): GeoLocation?
}

/** Registers the permission launcher; must be called unconditionally in composition. */
@Composable fun rememberLocationFetcher(): LocationFetcher
```

- **Callers:** `RootScreen` (NeedsOnboarding) calls `OnboardingFlowScreen`. Group 7's
  EditProfileScreen calls `ProfileQuestionFields`, and calls `rememberLocationFetcher`
  for "Update my location".
- **Internal:** `OnboardingModel` (VM; `advance(locationFetcher)`), step composables
  (Basics, Details, AboutYou, Socials, Location, Photos), `MultiSelectRow`, and `OnboardingContent`.

### B.3 `communityonboarding` — `features/communityonboarding`

```kotlin
package app.drokpo.android.features.communityonboarding

/** Port of CommunityOnboardingFlow (+ CommunityOnboardingModel). */
@Composable fun CommunityOnboardingFlowScreen(modifier: Modifier = Modifier)
```

- **Callers:** `RootScreen` (NeedsCommunityOnboarding).
- **Internal:** `CommunityOnboardingModel` (VM) and steps (Basics, Contact,
  ContactPerson, Address, Photos).

### B.4 `feed` — `features/feed`

```kotlin
package app.drokpo.android.features.feed

/** Port of FeedView (Discover tab root). No NavHost needed — iOS FeedView pushes nothing; everything
 *  it shows is a sheet/cover (§C.2). */
@Composable fun FeedScreen(modifier: Modifier = Modifier)

/** Port of Shared/SwipeActionButtons: undo · pass · like · share. undo/share hidden when their
 *  callback is null and rendered at 0.72× size; disabled → 40 % opacity. */
@Composable fun SwipeActionButtons(
    onPass: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    onUndo: (() -> Unit)? = null,
    undoDisabled: Boolean = false,
    onShare: (() -> Unit)? = null,
    shareDisabled: Boolean = false,
)

object SwipeActionButtonsDefaults {
    /** Space deck cards keep free at the bottom so text never sits under the overlaid buttons. */
    val DeckClearance: Dp   // 112.dp
}
```

- **Callers:** `MainTabs` (Discover tab) calls `FeedScreen`. Group 11's ProfileDetailScreen
  calls `SwipeActionButtons(onPass, onLike)` in the Discover context.
- **Presents:**
  - ProfileDetailScreen in a DrokpoSheet
  - `ShareSheet`, `NewsDetailSheet`, `CommunityPostDetailSheet`
  - `FullScreenCover` holding `CommunitiesScreen` or `CommunityDirectoryCoverScreen`
  - `PendingVerificationBanner`
  - the in-app browser
  - MatchOverlay, ActionSheets and ErrorAlert
- **Internal:**
  - `FeedModel` (VM) and `DeckItem` (Profile, Ad, News, Post; ids
    `profile-{uid}`, `ad-{id}`, `news-{id}`, `post-{id}`)
  - `CardView`, `AdCardView`, `NewsCardView`, `CommunityPostCardView` (deck-only)
  - `SwipeableWrapper`, `SwipeableCard`/`SwipeableAdCard`/`SwipeableNewsCard`/`SwipeableCommunityPostCard`
  - `MatchOverlay`, `FeedContent`

### B.5 `likes` — `features/likes`

```kotlin
package app.drokpo.android.features.likes

/** Port of LikesView (Likes tab root). Owns its NavHost (§C.3). */
@Composable fun LikesScreen(modifier: Modifier = Modifier)
```

- **Callers:** `MainTabs` (Likes tab).
- **Presents:** ProfileDetailScreen (LikedYou context, private route) and shared routes
  (Profile, Community, CommunityMembers). Also the private `LikedPostDetailScreen`,
  `CommentsSheet`, `ShareButton`, the browser and alerts.
- **Internal:** `LikesModel` (VM: received, given, likedContent, filters), `LikeRow`,
  `LikedNewsRow`, `LikedPostRow`, `LikedPostDetailScreen`, the `Direction` and
  `GivenFilter` enums, `GivenEntry`, `LikesContent`, and routes `LikesRoute.Home`,
  `LikesRoute.LikedYouProfile(cardJson)`, `LikesRoute.SavedPost(postJson)`.

### B.6 `chats` — `features/chats`

```kotlin
package app.drokpo.android.features.chats

/** Port of ChatStore. A ViewModel created by MainTabs in the session scope (§D.3) and provided via
 *  LocalChatStore. The constructor must not touch Firebase (the catalog instantiates it). */
class ChatStore : ViewModel() {
    data class Entry(
        val matchId: String,
        val otherUid: String,
        val otherUser: FeedCard? = null,
        val lastMessageText: String? = null,
        val lastMessageSenderId: String? = null,
        val unread: Int = 0,
        val sortDate: Instant = Instant.EPOCH,     // java.time.Instant
    ) {
        val id: String get() = matchId
        val hasMessages: Boolean get() = lastMessageText != null
    }

    val entries: StateFlow<List<Entry>>            // sorted by sortDate desc
    val isLoading: StateFlow<Boolean>              // true until first snapshot + profile join
    val errorMessage: StateFlow<String?>
    val totalUnread: StateFlow<Int>                // sum of unread → Chats tab badge
    val newMatches: StateFlow<List<Entry>>         // !hasMessages
    val conversations: StateFlow<List<Entry>>      // hasMessages

    /** Attach the Firestore listener for `uid`. No-op when already started for the same uid. */
    fun start(uid: String)
    /** Remove the listener and reset all state (also called from onCleared()). */
    fun stop()
    /** Optimistic local unread reset; the thread POSTs /matches/{id}/read. */
    fun clearUnread(matchId: String)
    /** iOS `chats.errorMessage = …` (ChatsView unmatch failure) and alert dismissal (null). */
    fun setError(message: String?)
}

/** Provided by MainTabs (and by CatalogActivity with an idle ChatStore()). Reading it elsewhere throws. */
val LocalChatStore: ProvidableCompositionLocal<ChatStore>

/** Port of ChatMessage (one Firestore message doc). */
data class ChatMessage(
    val id: String,
    val senderId: String,
    val text: String,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDurationSec: Int? = null,
    val createdAt: Instant,
) {
    val hasMedia: Boolean get() = imageUrl != null || audioUrl != null
}

/** Port of ChatsView (Chats tab root). Owns its NavHost (§C.4); consumes push deep links. */
@Composable fun ChatsScreen(modifier: Modifier = Modifier)
```

- **Callers:**
  - `MainTabs`: `viewModel<ChatStore>()`, then `start(uid)`, the `totalUnread` badge, providing `LocalChatStore`, and `ChatsScreen`.
  - Group 11's ShareSheet reads `LocalChatStore.current.entries`.
  - `catalog/Fixtures.kt` builds `ChatStore.Entry` and `ChatMessage`.
- **Presents:** `ChatThreadScreen` (private route) plus the shared routes (Profile,
  Community, Members). Also `ChatImageViewer` in a FullScreenCover and ActionSheets. Through
  the router it triggers `ShareDestinationSheet` (hosted by MainTabs).
- **Internal:**
  - `ChatThreadScreen(matchId: String, onBack: () -> Unit, onOpenProfile: (FeedCard) -> Unit)` and `ChatThreadModel`
  - `ChatInputBar`, `ChatDraft` (Text, Photo(uri), Voice(file, seconds)), `ChatImageViewer`
  - `ChatsContent`, `ChatThreadContent`
  - routes `ChatsRoute.List`, `ChatsRoute.Thread(matchId)`

### B.7 `profile` + `settings` — `features/profile`, `features/settings`

```kotlin
package app.drokpo.android.features.profile

/** Port of ProfileView (person Profile tab root). */
@Composable fun ProfileScreen(modifier: Modifier = Modifier)
```

- **Callers:** `MainTabs` (Profile tab when ActivePerson).
- **Presents:**
  - `EditProfileScreen` in a FullScreenCover
  - `SettingsScreen` in a FullScreenCover with its own NavHost (Settings → BlockedUsers, SentMessages)
  - preview: ProfileDetailScreen(card = profile.asFeedCard, title = "Preview", navIcon = Close) in a DrokpoSheet
- **Internal:**
  - `features.profile`: `ProfileModel`, `EditProfileScreen(profile: Profile, onSaved: suspend () -> Unit, onDismiss: () -> Unit)`, `EditProfileModel`, `RangeSliderRow`, photo reorder
  - `features.settings`: `SettingsScreen(onDismiss: () -> Unit)`, `BlockedUsersScreen(onBack: () -> Unit)`, `SentMessagesScreen(onBack: () -> Unit)`
  - the `*Content` composables
- `AppearanceMode` lives in core (§A.10), not here.

### B.8 `communities` + `communityhome` — `features/communities`, `features/communityhome`

```kotlin
package app.drokpo.android.features.communities

/** Port of CommunitiesView — a person's community browsing, presented by FeedScreen inside a
 *  FullScreenCover. Owns its NavHost (Home → Directory / SharedRoute.*). "Close" → onClose. */
@Composable fun CommunitiesScreen(onClose: () -> Unit, modifier: Modifier = Modifier)

/** A community account's browse cover (iOS FeedView: NavigationStack { CommunityDirectoryView } +
 *  "Close"). Owns its NavHost (Directory(navIcon = Close) → SharedRoute.*). */
@Composable fun CommunityDirectoryCoverScreen(onClose: () -> Unit, modifier: Modifier = Modifier)

/** Port of CommunityMembersView (always pushed → Back icon). Registered by sharedDestinations. */
@Composable fun CommunityMembersScreen(cid: String, onBack: () -> Unit, modifier: Modifier = Modifier)

/** Port of CommunityRow (logo 48 rounded 10, name + verified seal, "N member(s)"). */
@Composable fun CommunityRow(community: CommunityProfile, modifier: Modifier = Modifier)
```

```kotlin
package app.drokpo.android.features.communityhome

/** Port of CommunityProfileEditorView (community account's Profile tab root). */
@Composable fun CommunityProfileEditorScreen(modifier: Modifier = Modifier)

/** Port of CommunityPostComposerView. Self-contained: renders its own FullScreenCover.
 *  On success: await onSaved(), then onDismissRequest(). */
@Composable fun CommunityPostComposerSheet(onSaved: suspend () -> Unit, onDismissRequest: () -> Unit)

/** Port of PendingVerificationBanner ("Awaiting verification" orange card). */
@Composable fun PendingVerificationBanner(modifier: Modifier = Modifier)
```

- **Callers:**
  - FeedScreen (group 4): `CommunitiesScreen`, `CommunityDirectoryCoverScreen`, `PendingVerificationBanner`.
  - CommunityPageScreen (group 9): `CommunityPostComposerSheet`, `PendingVerificationBanner`.
  - `navigation/SharedNavigation.kt` (spine): `CommunityMembersScreen`.
  - `MainTabs`: `CommunityProfileEditorScreen`.
- **Presents:** CommunityPageScreen through shared routes, `CommentsSheet`, the browser,
  `CommunitySettingsScreen` in a FullScreenCover, and alerts.
- **Internal:** `CommunitiesModel`, `CommunityDirectoryScreen(onBack, onOpenCommunity:
  (cid: String, preview: CommunityProfile) -> Unit, navIcon: NavIcon)`, `DirectoryModel`,
  `JoinedCommunityAvatar`, `DiscoverCommunityCard`, `SponsoredFeedRow`,
  `CommunitySettingsScreen(onDismiss)`, `CommunityProfileEditorModel`, `PostKind`,
  `PollOptionDraft`, and the routes.

### B.9 `sharedcommunity` — `features/shared/community`, `features/shared/news`

```kotlin
package app.drokpo.android.features.shared.community

/** Port of CommunityPageView (Instagram-style page; visitor or ownerMode). Registered by
 *  sharedDestinations; also the community tab root via SharedNavHost(ownerMode = true, navIcon None).
 *  onBack = leading button AND iOS dismiss() after blocking. preview = directory/rail data shown
 *  immediately (ignored in ownerMode, which reads AppGraph.session.myCommunity). */
@Composable fun CommunityPageScreen(
    cid: String,
    onBack: () -> Unit,
    onOpenMembers: (cid: String) -> Unit,
    modifier: Modifier = Modifier,
    preview: CommunityProfile? = null,
    ownerMode: Boolean = false,
    navIcon: NavIcon = NavIcon.Back,
)

/** Port of CommunityPostContentView. Each callback null ⇒ that affordance hidden/disabled:
 *  onVote (poll taps), onRsvp (Join / Can't come), onOpenLink (CTA, also needs post.url),
 *  onOpenComments (comment button), onOpenCommunity (header tap → community page; iOS always a
 *  NavigationLink when communityId != null — every caller should pass it). */
@Composable fun CommunityPostContentView(
    post: CommunityPostCard,
    modifier: Modifier = Modifier,
    onVote: ((optionId: String) -> Unit)? = null,
    onRsvp: ((going: Boolean) -> Unit)? = null,
    onOpenLink: (() -> Unit)? = null,
    onOpenComments: (() -> Unit)? = null,
    onOpenCommunity: ((cid: String) -> Unit)? = null,
)

/** Port of CommunityPostDetailSheet. Self-contained DrokpoSheet with its own NavHost (start = the post;
 *  header → SharedRoute.Community inside the sheet, like iOS's own NavigationStack); "Close" +
 *  ShareButton(.Post) in its top bar; hosts CommentsSheet. The caller owns vote/RSVP network calls
 *  and passes the updated `post` back in. allowPartialHeight = iOS detents [.medium, .large] (Feed). */
@Composable fun CommunityPostDetailSheet(
    post: CommunityPostCard,
    onVote: ((optionId: String) -> Unit)?,
    onRsvp: ((going: Boolean) -> Unit)?,
    onOpenLink: (() -> Unit)?,
    onDismissRequest: () -> Unit,
    ownerMode: Boolean = false,
    onTogglePublish: (() -> Unit)? = null,
    allowPartialHeight: Boolean = false,
)
```

```kotlin
package app.drokpo.android.features.shared.news

/** Port of NewsDetailSheet: DrokpoSheet (skipPartiallyExpanded = true), title "News", Close, ShareButton(.News). */
@Composable fun NewsDetailSheet(item: NewsCard, onReadFullStory: () -> Unit, onDismissRequest: () -> Unit)

/** Port of NewsDetailContent (scrollable body is the CALLER's job — wrap in verticalScroll). */
@Composable fun NewsDetailContent(item: NewsCard, onReadFullStory: () -> Unit, modifier: Modifier = Modifier)
```

- **Callers:**
  - `CommunityPageScreen` ← `navigation/SharedNavigation.kt` (spine), which serves Likes,
    Chats, CommunitiesScreen, the directory cover, ShareDestinationSheet and the community tab.
  - `CommunityPostContentView` ← CommunitiesScreen (8), LikedPostDetailScreen (5),
    SharedPostView in ShareDestinationSheet (11), and this group's own detail sheet.
  - `CommunityPostDetailSheet` ← FeedScreen (4) and this group's own page.
  - `NewsDetailSheet` ← FeedScreen (4).
  - `NewsDetailContent` ← ShareDestinationSheet (11).
- **Presents:** CommunityPostComposerSheet (8), CommunityPostDetailSheet, CommentsSheet (10),
  ShareButton (11), PendingVerificationBanner (8), the browser, ActionSheets and alerts.
- **Internal:** `CommunityPageModel` (VM: header, posts, pagination, joinGeneration),
  `PostTile`, `EventDetailsView`, `PollOptionsView`, `CommunityPageContent`.

### B.10 `commentsaudio` — `features/shared/comments`, `features/shared/audio`

```kotlin
package app.drokpo.android.features.shared.comments

/** Port of CommentsSheet (+ CommentsModel). Self-contained DrokpoSheet (skipPartiallyExpanded = false,
 *  iOS [.medium, .large]); title "Comments", Close. Builds CommentsModel(post.postId,
 *  postOwnerCid = post.communityId, myUid = AppGraph.session.uid) fresh per presentation. */
@Composable fun CommentsSheet(post: CommunityPostCard, onDismissRequest: () -> Unit)

/** What the composer is about to send — exactly one of text or audio. */
sealed interface CommentDraft {
    data class Text(val text: String) : CommentDraft
    data class Audio(val file: File, val seconds: Int) : CommentDraft
}
```

```kotlin
package app.drokpo.android.features.shared.audio

data class RecordedClip(val file: File, val seconds: Int)

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val elapsedSeconds: Int) : RecorderState
    data class Failed(val message: String) : RecorderState
}

/** Port of AudioRecorder: MediaRecorder → cacheDir/{uuid}.m4a (MPEG_4 / AAC, 44.1 kHz, mono).
 *  One instance per composer/input bar (rememberAudioRecorder). */
class AudioRecorder internal constructor(/* group-private: context, permission launcher */) {
    val state: StateFlow<RecorderState>
    /** Requests RECORD_AUDIO if needed, then records; auto-stops at maxSeconds and calls onAutoStop. */
    fun start(maxSeconds: Int, onAutoStop: (RecordedClip) -> Unit)
    /** Stops and returns the clip (seconds = max(1, rounded elapsed)), or null if not recording. */
    fun stop(): RecordedClip?
    /** Stops and deletes the file. */
    fun cancel()
    /** Failed → Idle, so a denied mic never locks the user out of typing. */
    fun dismissFailure()
}

/** Registers the RECORD_AUDIO launcher and releases the recorder on dispose. */
@Composable fun rememberAudioRecorder(): AudioRecorder

/** Port of AudioPlaybackCenter: one clip at a time app-wide (Media3 ExoPlayer on AppGraph.app). */
object AudioPlaybackCenter {
    val playingId: StateFlow<String?>
    val progress: StateFlow<Float>               // 0..1 of the playing clip, updated ~100 ms
    /** Same id as playing → stop (pause gesture); otherwise stop current and play `url`
     *  (https → downloaded once to cacheDir/voice-cache/{hash}.m4a; file:// → played directly). Silent on failure. */
    fun play(id: String, url: String)
    fun stop()
}

/** Port of AudioBubbleView. url: https download URL or file:// URI (draft preview). */
@Composable fun AudioBubbleView(
    id: String,
    url: String,
    durationSec: Int,
    modifier: Modifier = Modifier,
    isOnTintBackground: Boolean = false,
)

/** Port of RecorderFailureRow: message, "Settings" link (openAppSettings) when the mic permission is
 *  denied, dismiss X (a11y "Dismiss"). */
@Composable fun RecorderFailureRow(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier)
```

- **Callers:**
  - `CommentsSheet` ← CommunitiesScreen (8), LikedPostDetailScreen (5),
    CommunityPostDetailSheet (9), SharedPostView (11).
  - ChatInputBar in group 6 uses `AudioRecorder`/`rememberAudioRecorder`,
    `AudioBubbleView`, `RecorderFailureRow` and `RecordedClip`.
  - Chat bubbles in group 6 use `AudioBubbleView`.
- **Internal:** `CommentsModel` (VM), `CommentRow`, `ReplyRow`, `ReplyRowActions`,
  `CommentHeader`, `CommentBody`, `VoteButtons`, `CommentComposerBar`, `CommentsContent`.

### B.11 `sharing` + `profiledetail` — `features/shared/sharing`, `features/shared/profiledetail`

```kotlin
package app.drokpo.android.features.shared.sharing

/** Port of ShareableContent. */
sealed interface ShareableContent {
    data class Profile(val card: FeedCard) : ShareableContent
    data class Community(val cid: String, val name: String?) : ShareableContent
    data class Post(val post: CommunityPostCard) : ShareableContent
    data class News(val news: NewsCard) : ShareableContent

    /** "user" | "community" | "post" | "news" (a Profile whose card.isCommunity → "community"). */
    val pathType: String
    val contentId: String
    /** Profile: displayName ?: "A Drokpo member"; Community: name ?: "A community on Drokpo";
     *  Post: title ?: communityName ?: "A community post"; News: title ?: "A news story". */
    val title: String
    /** "${AppConfig.API_BASE_URL}/s/$pathType/$contentId" */
    val webUrl: String
    /** "$title\n$webUrl" — what lands in a chat. */
    val messageText: String
    /** "$pathType-$contentId" */
    val id: String
}

/** Port of ShareDestination (a parsed incoming share link). */
@Serializable
sealed interface ShareDestination {
    @Serializable data class User(val uid: String) : ShareDestination
    @Serializable data class Community(val cid: String) : ShareDestination
    @Serializable data class Post(val postId: String) : ShareDestination
    @Serializable data class News(val newsId: String) : ShareDestination

    /** "user-{id}" | "community-{id}" | "post-{id}" | "news-{id}" */
    val id: String

    companion object {
        /** type ∈ user/community/post/news and id non-empty, else null. */
        fun make(type: String, id: String): ShareDestination?
        /** drokpo://s/{type}/{id} ("s" is the host) or https://drokpo-backend.web.app/s/{type}/{id};
         *  anything else → null. Implement with java.net.URI (unit-testable; android.net.Uri is stubbed in JVM tests). */
        fun parse(url: String): ShareDestination?
        fun parse(uri: Uri): ShareDestination?     // = parse(uri.toString())
    }
}

/** Port of SharedLinkMessage — a chat message carrying a share link. */
data class SharedLinkMessage(val destination: ShareDestination, val caption: String?) {
    /** "a profile" | "a community" | "a community post" | "a news story" */
    val kindLabel: String
    /** AccountCircle | Groups | Campaign | Newspaper */
    val icon: ImageVector
    companion object {
        /** Lines split on \n and trimmed; the first line that parses as a ShareDestination is the link;
         *  caption = the remaining non-empty lines joined with \n (null if none). Null when no link line. */
        fun from(text: String): SharedLinkMessage?
    }
}

/** Port of ChatMessageSender — direct Firestore write to matches/{matchId}/messages with EXPLICIT nulls:
 *  {senderId, text, imageUrl: null, audioUrl: null, audioDurationSec: null, createdAt: serverTimestamp, readAt: null}. */
object ChatMessageSender {
    suspend fun sendText(text: String, matchId: String, senderId: String)
}

/** Port of ShareButton: IconButton (Share icon, a11y "Share") that presents ShareSheet. Must be under MainTabs (LocalChatStore). */
@Composable fun ShareButton(content: ShareableContent, modifier: Modifier = Modifier)

/** Port of ShareSheetView: DrokpoSheet (skipPartiallyExpanded = false). */
@Composable fun ShareSheet(content: ShareableContent, onDismissRequest: () -> Unit)

/** Port of ShareDestinationView: DrokpoSheet (skipPartiallyExpanded = true) with its own NavHost
 *  (loader start route + sharedDestinations(onCloseHost = onDismissRequest)). */
@Composable fun ShareDestinationSheet(destination: ShareDestination, onDismissRequest: () -> Unit)
```

```kotlin
package app.drokpo.android.features.shared.profiledetail

/** Port of ProfileDetailContext. */
sealed interface ProfileDetailContext {
    data object Plain : ProfileDetailContext
    /** "Liked you" entry, not matched yet: "Like back", which flips to "Send message" once it matches. */
    class LikedYou(val onLikeBack: suspend () -> SwipeResult?) : ProfileDetailContext
    /** Expanded card from the Discover deck: pass/like bar. */
    class Discover(val onLike: () -> Unit, val onPass: () -> Unit) : ProfileDetailContext
}

/** Port of ProfileDetailView. onReport/onBlock: caller-owned safety (Discover — the card must also
 *  leave the deck); when null the screen calls Safety.report itself (and stays, like iOS report()) or
 *  Safety.block itself and then onBack() (iOS block() → dismiss()).
 *  title overrides the top-bar title (ProfileScreen preview passes "Preview"). */
@Composable fun ProfileDetailScreen(
    card: FeedCard,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    context: ProfileDetailContext = ProfileDetailContext.Plain,
    onReport: ((reason: String) -> Unit)? = null,
    onBlock: (() -> Unit)? = null,
    navIcon: NavIcon = NavIcon.Back,
    title: String? = null,
)
```

- **Callers:**
  - `ShareableContent`: FeedScreen (4), LikedPostDetailScreen (5), CommunityPageScreen (9),
    CommunityPostDetailSheet (9), NewsDetailSheet (9), and this group itself.
  - `ShareDestination`: MainActivity, DeepLinkRouter and MainTabs (spine); ChatThreadScreen (6)
    through `SharedLinkMessage.from`.
  - `ShareButton`: groups 5 and 9, and this group.
  - `ShareSheet`: FeedScreen (4) and ShareButton.
  - `ShareDestinationSheet`: MainTabs.
  - `ProfileDetailScreen`: `navigation/SharedNavigation.kt` (spine, Plain), LikesScreen (5,
    LikedYou), FeedScreen (4, Discover, navIcon = Close, onReport/onBlock), and ProfileScreen
    (7, title = "Preview", navIcon = Close).
  - `ProfileDetailContext`: groups 4 and 5.
- **Internal:** `ShareSheetContent`, `ShareModel`, `SharedUserLoader`, `SharedPostLoader`,
  `SharedPostView`, `SharedNewsLoader`, `SharedContentUnavailable`, `ProfileDetailContent`,
  and the system share intent.

---

## C. Navigation model

### C.1 Shell

- `RootScreen` is a `when` on `SessionState`, not a NavHost.
- `MainTabs` = `Scaffold(bottomBar = NavigationBar, contentWindowInsets = WindowInsets(0))`, composed
  inside RootScreen's `ScopedViewModels` for its branch (the session scope, §D.3). Its content is
  `Box(Modifier.padding(p).consumeWindowInsets(p))` wrapping the selected tab.
- MainTabs composes **only the selected tab**, inside
  `rememberSaveableStateHolder().SaveableStateProvider(tab.name)`. Each tab's NavHost back stack
  (`rememberNavController` is saveable) and its tab-scoped ViewModels (session scope, §D.3)
  survive tab switches.
- Re-entering a tab re-runs its `LaunchedEffect`s, which matches iOS `.onAppear` on tab switch.
- The selected tab is `rememberSaveable`.

Tabs, in iOS order:

| Tab | Label | Icon | Shown | Root composable |
|---|---|---|---|---|
| Discover | "Discover" | `Icons.Filled.ViewCarousel` (rectangle.stack.fill) | always | `FeedScreen()` |
| Likes | "Likes" | `Icons.Filled.Favorite` | always | `LikesScreen()` |
| Communities | "Communities" | `Icons.Filled.Groups` | **ActiveCommunity only** | MainTabs' private `CommunityTabHost`: `SharedNavHost(SharedRoute.Community(myCid, ownerMode = true), navController = nav)` + `TabReselectEffect` pop-to-start, keyed by `myCid` |
| Chats | "Chats" | `Icons.Filled.Forum` | always; `BadgedBox` with `totalUnread` (hidden at 0) | `ChatsScreen()` |
| Profile | "Profile" | `Icons.Filled.Person` | always | ActiveCommunity → `CommunityProfileEditorScreen()`, else `ProfileScreen()` |

`myCid = session.myCommunity?.uid ?: session.uid ?: ""`.

Each tab root renders its own `Scaffold(topBar = DrokpoTopBar(...))` with default insets. The
outer scaffold consumes only the bottom bar.

### C.2 Hosts and their routes

Every host that can push anything owns a `NavHost` with type-safe `@Serializable` routes, and
calls `sharedDestinations(navController, onCloseHost)` (§A.13).

| Host (owner) | Container | Start route | Own routes | + shared |
|---|---|---|---|---|
| FeedScreen (4) | tab root, no NavHost | — | — | — |
| LikesScreen (5) | tab root | `LikesRoute.Home` | `LikedYouProfile(cardJson)`, `SavedPost(postJson)` | yes |
| ChatsScreen (6) | tab root | `ChatsRoute.List` | `Thread(matchId)` | yes |
| ProfileScreen (7) | tab root, no NavHost | — | — | — |
| SettingsScreen (7) | FullScreenCover | `Settings` | `BlockedUsers`, `SentMessages` | no |
| Community tab (spine) | tab root | `SharedRoute.Community(myCid, ownerMode=true)` | — | yes (via `SharedNavHost`) |
| CommunityProfileEditorScreen (8) | tab root, no NavHost | — | — | — |
| CommunitiesScreen (8) | FullScreenCover (from Feed) | `Home` | `Directory` | yes, `onCloseHost = onClose` |
| CommunityDirectoryCoverScreen (8) | FullScreenCover (from Feed) | `Directory` | — | yes, `onCloseHost = onClose` |
| CommunityPostDetailSheet (9) | DrokpoSheet | `PostDetail` | — | yes, `onCloseHost = onDismissRequest` |
| ShareDestinationSheet (11) | DrokpoSheet (from MainTabs) | loader for the destination (community → `SharedRoute.Community` directly) | `UserLoader`, `PostLoader`, `NewsLoader` | yes, `onCloseHost = onDismissRequest` |

Rules:
- A destination's `onBack` is `navController.backOrClose(onCloseHost)`, and its `navIcon` is
  `navController.navIconFor(entry, onCloseHost)`.
- System back pops the NavHost first. At a host's start destination it closes the sheet or cover.
  Tab roots fall through to the Activity (exit).
- `TabReselectEffect(MainTab.X) { nav.popBackStack(nav.graph.findStartDestination().id, inclusive = false) }`
  (import `androidx.navigation.NavGraph.Companion.findStartDestination`) goes in LikesScreen,
  ChatsScreen and the community tab (spine). Tab roots without a NavHost (Feed, Profile,
  CommunityProfileEditor) may use it for scroll-to-top.
- Route args are primitives or JSON strings (`DrokpoJson.encodeToString(...)`). Never put
  lambdas in routes. Host-specific behaviour, such as Likes' `LikedYou(onLikeBack)`, lives in that
  host's own route, and that route's composable gets the host's ViewModel.

### C.3 Every iOS presentation and its Compose mapping

| # | iOS (file) | Compose | Hosted by |
|---|---|---|---|
| 1 | MainTabView `.sheet(item: sharedDestination) ShareDestinationView` | `ShareDestinationSheet(dest, onDismissRequest)` | MainTabs |
| 2 | SignInView `.sheet PhoneSignInView` | `FullScreenCover { PhoneSignInScreen(onDismiss) }` | auth (1) |
| 3 | FeedView `.sheet(expandedCard) NavigationStack{ProfileDetailView(.discover)} + Close` | `DrokpoSheet(skipPartiallyExpanded = true) { ProfileDetailScreen(card, onBack = close, context = Discover(…), onReport, onBlock, navIcon = Close) }` | FeedScreen |
| 4 | FeedView `.sheet(shareContent) ShareSheetView` | `ShareSheet(content, …)` | FeedScreen |
| 5 | FeedView / Likes / Communities / CommunityPage / SharedPost / SharedNews `.sheet(urlToOpen) SafariView` | `context.openInAppBrowser(url)`. There is no sheet state, and no iOS pendingURL dance: dismiss the detail sheet, then open the tab | caller |
| 6 | FeedView `.sheet(expandedNews) NewsDetailSheet` | `NewsDetailSheet(item, onReadFullStory, onDismissRequest)` | FeedScreen |
| 7 | FeedView `.sheet(expandedPost) CommunityPostDetailSheet` + inner alert | `CommunityPostDetailSheet(…, allowPartialHeight = true)`. Feed's `ErrorAlert` is composed after the sheet, so its window stacks on top | FeedScreen |
| 8 | FeedView `.fullScreenCover(showCommunityBrowse)` | `FullScreenCover { if (ActiveCommunity) CommunityDirectoryCoverScreen(onClose) else CommunitiesScreen(onClose) }` | FeedScreen |
| 9 | FeedView `.overlay MatchOverlay` | in-screen `Box` overlay | feed (4) |
| 10 | SwipeableCard `.confirmationDialog("Safety")` → "Why are you reporting this profile?" | two chained `ActionSheet`s | feed (4) |
| 11 | LikesView `NavigationLink` → ProfileDetailView(.likedYou / plain), CommunityPageView, LikedPostDetailView | `LikesRoute.LikedYouProfile`, `nav.openProfile(card)`, `nav.openCommunity`, `LikesRoute.SavedPost` | LikesScreen |
| 12 | LikedPostDetailView `.sheet CommentsSheet` + toolbar `ShareButton` | `CommentsSheet`, `ShareButton(ShareableContent.Post)` | likes (5) |
| 13 | LikesView `.alert("It's a match!")` | `AlertDialog` ("Say hi" / "Later") | likes (5) |
| 14 | ChatsView `NavigationStack(path:)` → ChatThreadView | `ChatsRoute.Thread(matchId)`, pushed by a deep link too | ChatsScreen |
| 15 | ChatThreadView toolbar avatar `NavigationLink` → profile/community | `onOpenProfile(card)` → `nav.openProfile(card)` | ChatsScreen |
| 16 | ChatThreadView toolbar `Menu` (Unmatch/Block/Report) | top-bar overflow `DropdownMenu` → `ActionSheet(title, message)` confirmations | chats (6) |
| 17 | ChatThreadView `.fullScreenCover ChatImageViewer` | `FullScreenCover` (black) | chats (6) |
| 18 | ProfileView `.sheet EditProfileView` | `FullScreenCover { EditProfileScreen(…) }` | profile (7) |
| 19 | ProfileView `.sheet SettingsView` (own NavigationStack → Blocked users / Messages you've sent) | `FullScreenCover { SettingsScreen(onDismiss) }` with internal NavHost | profile (7) |
| 20 | ProfileView `.sheet` Preview (`ProfileDetailView` + "Done") | `DrokpoSheet { ProfileDetailScreen(profile.asFeedCard, onBack = close, title = "Preview", navIcon = Close) }` | profile (7) |
| 21 | SettingsView / CommunitySettingsView `.confirmationDialog` delete | `ActionSheet(title, message, [Delete everything (destructive)])` | 7 / 8 |
| 22 | CommunitiesView (own NavigationStack) → CommunityDirectoryView, CommunityPageView(preview) | internal NavHost: `Directory`, `nav.openCommunity(cid, preview)` | CommunitiesScreen |
| 23 | CommunitiesView `.sheet CommentsSheet` | `CommentsSheet(post, …)` | communities (8) |
| 24 | CommunityPageView `.sheet CommunityPostComposerView` | `CommunityPostComposerSheet(onSaved = { reload }, onDismissRequest)` | CommunityPageScreen |
| 25 | CommunityPageView `.sheet(selectedPost) CommunityPostDetailSheet(ownerMode)` | `CommunityPostDetailSheet(post, …, ownerMode, onTogglePublish)` | CommunityPageScreen |
| 26 | CommunityPageView memberCount `NavigationLink` → CommunityMembersView | `onOpenMembers(cid)` → host `nav.openCommunityMembers(cid)` | host NavHost |
| 27 | CommunityPageView toolbar `Menu` Report/Block (visitor), `+` (owner), `ShareButton` | top-bar actions + `DropdownMenu` → `ActionSheet`s | shared community (9) |
| 28 | CommunityPostContentView header `NavigationLink` → CommunityPageView | `onOpenCommunity(cid)` → caller's `nav.openCommunity(cid)` | each caller |
| 29 | CommunityPostDetailSheet own NavigationStack + `.sheet CommentsSheet` | internal NavHost + `CommentsSheet` | shared community (9) |
| 30 | CommunityProfileEditorView `.sheet CommunitySettingsView` | `FullScreenCover { CommunitySettingsScreen(onDismiss) }` | communityhome (8) |
| 31 | ShareButton `.sheet ShareSheetView` | `ShareSheet(content, …)` | sharing (11) |
| 32 | ShareDestinationView own NavigationStack (CommunityPageView / ProfileDetailView / SharedPostView / news) | `ShareDestinationSheet` internal NavHost | sharing (11) |
| 33 | SharedPostView `.sheet CommentsSheet` | `CommentsSheet` | sharing (11) |
| 34 | ProfileDetailView toolbar `ShareButton` + `Menu` → confirmationDialogs, alerts | top-bar actions, `ActionSheet`s, `AlertDialog`s | profiledetail (11) |
| 35 | Onboarding / CommunityOnboarding / AccountTypeChoice / PhoneSignIn: `NavigationStack` used only for its toolbar | `Scaffold` + `DrokpoTopBar`, no NavHost | 1–3 |
| 36 | All `.alert("Something went wrong" / "Couldn't …")` | `ErrorAlert(message, onDismiss, title)` | the screen |
| 37 | `Picker(.segmented)` / `Picker(.menu)` / `DatePicker` / `Toggle` / `.refreshable` / `.swipeActions` / `.contextMenu` | `SingleChoiceSegmentedButtonRow` / `ExposedDropdownMenuBox` / `DatePickerDialog` (+ `TimePicker` for events) / `Switch` / `PullToRefreshBox` / `SwipeToDismissBox` or long-press menu / long-press `DropdownMenu` | the screen |

### C.4 Deep links & push taps (end to end)

1. **Push tap.** `MainActivity` reads the intent extras (`type`, `matchId`) and calls
   `deepLinks.handle(type, matchId)`. This works on a cold start or via `onNewIntent`, and it
   works signed out too: state waits until MainTabs exists.
2. **MainTabs**, on first composition and whenever `pendingMatchId` or `pendingType` changes,
   returns if both are null. Otherwise:
   - `type == "like"`: select Likes, set `focusLikedYou.value = true`, then `clear()`.
   - Anything else: select Chats and **don't clear**.
3. **ChatsScreen**, on composition and when `pendingMatchId` changes: if either value is set
   and `type == "message"` with a non-null `matchId`, navigate to `ChatsRoute.Thread(matchId)`.
   It **always** calls `clear()`. Pushing before ChatStore delivers the match is fine: the
   thread looks the entry up by id.
4. **LikesScreen**, on composition and when `focusLikedYou` changes: if true, set it back to
   false and select the "Liked you" segment.
5. **"Say hi" / "Send message"** (Likes match alert, ProfileDetail LikedYou) call
   `deepLinks.handle("message", matchId)`, which runs steps 2–3.
6. **Share links.** `MainActivity` handles `drokpo://s/{type}/{id}` and `https://drokpo-backend.web.app/s/…`,
   and a chat bubble tap sets `pendingShare.value = dest`. MainTabs observes `pendingShare`: when
   non-null it copies the value into local state, clears the router, and shows
   `ShareDestinationSheet`.

---

## D. Environment & state sharing

### D.1 iOS `@Environment` and singletons in Compose

| iOS | Android | Provided by | Consumers |
|---|---|---|---|
| `@Environment(SessionStore.self)` | `AppGraph.session` (StateFlows) | DrokpoApplication | every screen/VM that needs uid, profile, community or state |
| `@Environment(ChatStore.self)`, created by MainTabView | `LocalChatStore.current`: a ViewModel in MainTabs' session scope, started with `LaunchedEffect(uid) { chats.start(uid) }`, stopped in `onCleared()` when the session scope is cleared | MainTabs (also CatalogActivity, with an idle `ChatStore()`) | ChatsScreen, ChatThreadScreen, ShareSheet |
| `DeepLinkRouter.shared` | `AppGraph.deepLinks` | DrokpoApplication | MainActivity, MainTabs, ChatsScreen, LikesScreen, ProfileDetailScreen, ChatThreadScreen (shared-link bubble), LikesScreen "Say hi" |
| `BlockStore.shared` | `AppGraph.blocks` (writes usually via `Safety.block`) | DrokpoApplication | BlockedUsersScreen, Safety |
| `AudioPlaybackCenter.shared` | `object AudioPlaybackCenter` | group 10 | AudioBubbleView |
| `@AppStorage("drokpo.appearance")` | `AppGraph.prefs.appearance` / `setAppearance` | DrokpoApplication | MainActivity, SettingsScreen, CommunitySettingsScreen |
| `PushService.shared` | `AppGraph.push` | DrokpoApplication | SessionStore, MainTabs |
| `@Environment(\.dismiss)` | `onBack` / `onDismissRequest` / `onClose` callbacks | the host | everyone |
| `@Environment(\.colorScheme)` (Apple button style) | the theme's dark flag (ui.theme) | DrokpoTheme | SignInScreen |

`LocalChatStore` is the **only** CompositionLocal a feature group defines. Spine-owned
locals: `LocalInsideSheet` (ui.components) and the tab-reselect plumbing behind
`TabReselectEffect`.

### D.2 Reading session state in UI

```kotlin
val state by AppGraph.session.state.collectAsStateWithLifecycle()
val community by AppGraph.session.myCommunity.collectAsStateWithLifecycle()
val isUnverifiedCommunity = state == SessionState.ActiveCommunity && community?.isVerified != true
```

ViewModels read `AppGraph.session.uid` when they start. Screens pass `uid` into `*Content`
as plain data, for example to decide "isMine".

### D.3 ViewModel scoping (mirrors the lifetime of iOS `@State`)

| Where `viewModel()` is called | Scope | iOS equivalent |
|---|---|---|
| A NavHost destination | that back-stack entry; cleared when popped | `@State` of a pushed view |
| A tab root that isn't a NavHost destination (FeedScreen, ProfileScreen, CommunityProfileEditorScreen) and MainTabs itself | **session scope**: the `ScopedViewModels` that RootScreen puts around its MainTabs branch, cleared when MainTabs leaves composition (sign-out, account switch). Survives tab switches | TabView children's `@State` |
| Inside `DrokpoSheet` / `FullScreenCover` (outside any inner NavHost) | **presentation scope**, cleared when dismissed. A fresh model on every presentation (iOS CommentsSheet rebuilds its model each time) | sheet `@State` |
| RootScreen-level screens (SignIn, AccountTypeChoice, both onboarding flows) | **branch scope**: RootScreen wraps each `when` branch in its own `ScopedViewModels` (keyed by branch), so leaving a state (sign-out, onboarding done) clears its VMs. MainTabs' branch scope *is* the session scope above | `@State` in RootView children |

Key ViewModels with `viewModel(key = …)` whenever one host can show several instances. For
example, key `CommunityPageModel` by `cid` and `ownerMode`.

`.task { }` (runs once) maps to `init {}` in the VM or a `loadedOnce` guard. `.onAppear { }`
(runs again after a pop or tab switch) maps to `LaunchedEffect(Unit)` in the destination's
composable.

---

## E. Debug catalog contract

```kotlin
package app.drokpo.android.catalog          // app/src/debug/kotlin/app/drokpo/android/catalog/

data class CatalogEntry(val id: String, val title: String, val content: @Composable () -> Unit) {
    /** As shipped: the owning group, i.e. the id up to its first dot ("feed.deck.news" → "feed"). The list groups by it. */
    val group: String
}
```

**Foundation-owned:**
- `CatalogEntry.kt`
- `CatalogActivity.kt`: a searchable list of all entries, or `--es entry <id>` to render one
  full screen. `--ez dark true|false` forces the theme; the list also has a light/dark toggle.
  It wraps every entry in `DrokpoTheme` + `CompositionLocalProvider(LocalChatStore provides
  remember { ChatStore() })` (never started).
- `CatalogRegistry.kt`: `val allCatalogEntries = shellCatalogEntries + authCatalogEntries + …`
  (also `duplicateCatalogIds`, which the list shows as a warning banner).
- `ShellCatalog.kt` / `val shellCatalogEntries` (integrator note: renamed from
  SpineCatalog/spineCatalogEntries, the names the foundation task used): RootScreen's
  Loading / Failed / SetupNotice, the MainTabs chrome (person, community, Chats badge), and a
  fixtures overview. Ids are `shell.*`.
- `Fixtures.kt`

**Group-owned:** one file per group. Its stub is created by the foundation as `emptyList()`,
and the group replaces it.

| Group | File | Exposes |
|---|---|---|
| 1 | `AuthCatalog.kt` | `val authCatalogEntries: List<CatalogEntry>` |
| 2 | `OnboardingCatalog.kt` | `val onboardingCatalogEntries` |
| 3 | `CommunityOnboardingCatalog.kt` | `val communityOnboardingCatalogEntries` |
| 4 | `FeedCatalog.kt` | `val feedCatalogEntries` |
| 5 | `LikesCatalog.kt` | `val likesCatalogEntries` |
| 6 | `ChatsCatalog.kt` | `val chatsCatalogEntries` |
| 7 | `ProfileCatalog.kt` | `val profileCatalogEntries` |
| 8 | `CommunitiesCatalog.kt` | `val communitiesCatalogEntries` |
| 9 | `SharedCommunityCatalog.kt` | `val sharedCommunityCatalogEntries` |
| 10 | `CommentsAudioCatalog.kt` | `val commentsAudioCatalogEntries` |
| 11 | `SharingCatalog.kt` | `val sharingCatalogEntries` |

Rules:
- Entry ids are `"<group>.<screen>[.<state>]"`, lowercase with dots, e.g. `feed.deck.news`,
  `likes.received.empty`. They must be unique.
- Entries render `*Content` composables (internal is fine; same module) with `Fixtures.*` and
  no-op callbacks. Never call `viewModel()`, `ApiClient`, Firebase, or `AppGraph.session` /
  `AppGraph.deepLinks` mutations from an entry. Sheets: render the sheet's *content*
  full-screen, not the `ModalBottomSheet`.
- Cover at least: every screen's main state, its empty state, its loading state and its error
  state, plus the variants named in §F.
- Groups may declare private fixtures inside their own catalog file. Additions to the shared
  `Fixtures.kt` go through `shared_change_requests`.

`Fixtures.kt` (foundation) provides the following. Photo fixtures use
`url = "https://picsum.photos/seed/<seed>/600/800"` (debug only). Build other sizes with
`Fixtures.imageUrl(seed, width = 600, height = 800)`, e.g. for ad, news and post images.

```kotlin
object Fixtures {
    const val MY_UID = "fixture-me"
    fun imageUrl(seed: String, width: Int = 600, height: Int = 800): String
    fun photo(seed: String, order: Int = 0): Photo
    val photos: List<Photo>                          // 3
    val profile: Profile                             // complete person, uid = MY_UID, answers, socials, preferences, discoverable = true
    val profileEmpty: Profile                        // displayName only; no photos/answers
    val feedCard: FeedCard                           // full person, 3 photos, answers, distanceKm = 12.4
    val feedCardMinimal: FeedCard                    // name only, no photos
    val communityCard: FeedCard                      // kind = "community"
    val feedCards: List<FeedCard>                    // 5 persons
    val ad: AdCard; val adNoImage: AdCard
    val news: NewsCard; val newsNoImage: NewsCard; val newsList: List<NewsCard>
    val announcementPost: CommunityPostCard; val linkPost: CommunityPostCard
    val pollPost: CommunityPostCard                  // not voted
    val pollPostVoted: CommunityPostCard             // myVote set, counts
    val eventPost: CommunityPostCard                 // future eventAt, myRsvp = false, attendeeCount 12
    val eventPostGoing: CommunityPostCard            // myRsvp = true
    val unpublishedPost: CommunityPostCard           // active = false
    val posts: List<CommunityPostCard>               // all of the above
    val community: CommunityProfile                  // verified, joined = true, memberCount 128, full contact/address/socials, 2 photos
    val communityPending: CommunityProfile           // verification = "pending", joined = false, memberCount 1
    val communities: List<CommunityProfile>          // 5, mixed
    val members: List<CommunityMember>               // 6
    val comments: List<CommentCard>                  // text, audio, community author, replyCount > 0, myVote = "like"
    val replies: List<CommentCard>                   // parentId = comments[0].commentId
    val receivedLikes: List<SwipeEntry>              // persons + one community, unmatched
    val givenLikes: List<SwipeEntry>
    val likedContent: List<LikedContent>             // news + posts with likedAt
    val communitiesHomeItems: List<FeedItem>         // posts + one ad
    val chatEntries: List<ChatStore.Entry>           // 2 new matches + 3 conversations (one unread = 2, one last message mine)
    val chatMessages: List<ChatMessage>              // 2 days, both senders, text/photo/audio/shared-link
    val sentMessages: List<SentMessage>
    val blockedUsers: List<BlockedUser>
}
```

---

## F. Behaviour checklists (what's easy to miss)

Strings in quotes are verbatim iOS copy. "→" means "does / calls".

### F.0 Spine (foundation)

- **RootView Failed:** "Couldn't load your profile.", then `lastError` (footnote, secondary),
  then "Retry" (filled) → `refreshProfile()`, then "Sign out". The state change is animated
  (Crossfade).
- **SetupNotice** shows when `!hasFirebaseConfig`. SessionStore must then never touch FirebaseAuth.
- **MainTabs:**
  - Start `ChatStore` with the current uid on first composition.
  - Call `push.enable(activity)` once (Android 13 permission).
  - Route push deep links (§C.4), consume `pendingShare`, and badge Chats with `totalUnread`.
  - The Communities tab exists only for ActiveCommunity; the Profile tab content depends on
    account type.
- **Sign-out:** `push.unregister()` happens **before** `FirebaseAuth.signOut()`. Then clear
  Credential Manager state (`clearCredentialState`), reset BlockStore, and clear the session
  ViewModel scope (drops ChatStore's listener).
- **Push** in the foreground shows a heads-up notification (iOS shows banners in the
  foreground). A tap goes through intent extras. Background taps arrive as launcher-intent extras.
- **Google sign-in:** look up `default_web_client_id` at runtime, never as `R.string`, since it
  doesn't exist without google-services.json. Map `GetCredentialCancellationException` to `AuthServiceError.Cancelled`.
- **RemotePhotoView:** retry on tap after failure; a cancelled load is never shown as failed; a
  null photo shows the Person placeholder.
- **openInAppBrowser** scheme guard; no crash on `mailto:` or other junk schemes.
- **Keyboard in sheets:** `DrokpoSheet` and `FullScreenCover` must let content apply
  `Modifier.imePadding()`, so bottom composers (CommentsSheet, ChatInputBar, forms) stay above the IME.
  Use `adjustResize` plus edge-to-edge, and never hard-code bottom insets.
- **API:** never cache. iOS once replayed a cached 404 for `GET /profile/me` and trapped new
  users in onboarding, so set OkHttp `cache = null` and `Cache-Control: no-cache`.

### F.1 auth

- **SignInScreen:**
  - Logo (96dp), "Drokpo" (largeTitle bold), "Make friends and find your people in the Tibetan
    community" (subheadline, secondary, centred).
  - Bottom stack (24dp horizontal, 32dp bottom), 50dp-tall buttons:
    - Apple: "Sign in with Apple", black in light / white in dark, **only if `AuthService.isAppleSignInEnabled`**.
    - "Sign in with Google": bordered, G icon.
    - "Continue with phone": bordered, `Icons.Filled.Phone`.
    - Caption: "You must be 18 or older to use Drokpo."
  - While signing in: everything disabled, centred spinner.
  - Errors → `ErrorAlert(title = "Sign-in failed")`. `AuthServiceError.Cancelled` is silent.
  - On success do **nothing**: the SessionStore listener routes.
- **PhoneSignInScreen** (FullScreenCover):
  - Title "Phone number" (inline), X = Cancel.
  - Country menu shows "India (+91)", "Nepal (+977)", "Bhutan (+975)", "US / Canada (+1)",
    "Switzerland (+41)", "France (+33)", "Germany (+49)", "United Kingdom (+44)",
    "Australia (+61)", "Other…". India is the default. The chip shows the dial code, or "Other".
  - "Other…" reveals a "+xx" field (phone keyboard, 64dp wide).
  - Number field "Phone number" (phone keyboard).
  - "Continue" (filled) is enabled iff not working, dial code starts with "+" and is longer
    than 1, and the number has at least 6 digits. `e164 = dialCode + digitsOnly`.
- **Code step:**
  - "Enter the 6-digit code sent to {e164}".
  - Field "123456": number pad, SMS one-time-code autofill, centred, title2 monospaced. Keep
    digits only, max 6, and **auto-verify at 6 digits**.
  - "Resend in {n}s" / "Resend code" (bordered), disabled during the cooldown or while working.
  - The 30 s cooldown restarts on every send and is cancelled when the screen is dismissed.
    Resend passes the `resendToken`.
  - `AutoVerified` (or a later auto sign-in) leaves the screen through the session change.
  - Spinner overlay while working.
- **Phone errors** → `ErrorAlert(title = "Couldn't sign in")`:
  - `ERROR_INVALID_PHONE_NUMBER` → "That doesn't look like a valid phone number."
  - `ERROR_MISSING_PHONE_NUMBER` → "Enter a phone number first."
  - `FirebaseTooManyRequestsException` / quota → "Too many attempts right now — try again in a bit."
  - `ERROR_INVALID_VERIFICATION_CODE` → "That code isn't right. Check and try again."
  - `ERROR_SESSION_EXPIRED` / `ERROR_INVALID_VERIFICATION_ID` → "This code expired — request a new one."
  - Anything else → `userMessage()`.
- **AccountTypeChoiceScreen:**
  - Logo (72), "Welcome to Drokpo" (title2 bold), "How will you be using Drokpo?".
  - Card 1: Person icon, "I'm here to make friends", "Build a personal profile, swipe to meet
    people nearby and across the diaspora." → `chooseAccountType(Person)`.
  - Card 2: `Icons.Filled.Business`, "Register a community or organization", "Share
    announcements, events, and polls with the community.", footnote "Community accounts are
    reviewed before they appear publicly." → `chooseAccountType(Community)`.
  - Cards are 14dp-rounded on a quaternary 50 % fill, with a chevron.
  - Top-bar text action "Sign out".

### F.2 onboarding

- **Shell:**
  - Title "Create profile" (inline). Linear progress = (step + 1) / 6.
  - Bottom button "Continue" ("Finish" on photos), 50dp, white spinner while submitting.
    Disabled unless `canAdvance` and not submitting.
  - Leading "Back" (not on Basics; disabled while submitting) → previous step.
    **System back does the same when step > Basics.**
  - Trailing "Sign out".
  - `ErrorAlert` ("Something went wrong").
- **canAdvance:**
  - Basics: trimmed name non-empty and gender chosen.
  - Details: region chosen and at least one language.
  - AboutYou: always.
  - Socials: terms accepted.
  - Location: always.
  - Photos: at least one photo picked.
- **Basics:**
  - Section "About you": "Your name".
  - "Date of birth": max = today − 18 years, default = today − 25 years.
  - "I am" picker: "Select" + "Male" / "Female". Values are stored lowercase "male" / "female".
- **Details:**
  - "Where are you from?": Region picker, "Select" + `Vocabulary.regions`.
  - "Languages you speak" and "Interests": checkmark multi-select rows over the full vocabularies.
  - "About me": "A few words about yourself…", 3–6 lines.
- **AboutYou:**
  - Section "Work & study": "Occupation or current job" and Education picker ("Select" + levels).
  - Footer "All of this is optional — answer what you like. It helps people find things in
    common with you."
  - Then `ProfileQuestionFields`.
- **Socials:**
  - Section "Instagram": "@" prefix and "your_handle" field (no autocap or autocorrect).
  - Footer "Optional — adding your Instagram helps new friends see you're a real person."
  - Switch with the text "I confirm I am 18 or older and agree to treat other members with
    respect. Abusive or fake profiles are removed." (footnote).
- **Location:**
  - Big location icon, outline until saved and then filled. "Share your location" (title2 bold).
  - Body: "Drokpo uses your location to show you people nearby. When you tap Continue, Android
    will ask whether to share it. If you don't, we'll use the center of your region instead."
  - Show "Location saved" (check) once set.
  - **Continue goes straight into the system permission dialog.** No custom allow button.
- **Continue on Location:**
  1. If no location yet: `requestLocation()`, with the spinner shown.
  2. `POST /api/onboarding` with `OnboardingIn`:
     - `displayName` trimmed, `dob` as yyyy-MM-dd in UTC, `gender` null if empty.
     - `bio` raw, `occupation` trimmed, `education`, `region`, and languages/interests as lists.
     - `answers` trimmed, empty ones dropped.
     - `socials.instagram` trimmed with "@" removed (null if empty).
     - `location` = fix ?: `Vocabulary.regionCoordinates[region]` ?: (0, 0).
     - `preferences = Preferences()`.
  3. On success the step becomes Photos.
- **Photos:**
  - "Add photos", "Add 1–6 photos. The first one is your main photo."
  - Adaptive grid of 100×133 tiles, 10dp corners. Each tile has an X remove; a "+" tile
    shows while fewer than 6 (`rememberPhotoPicker(maxItems = 6 − count)`).
- **Finish:**
  - Upload in pick order, **resuming from the first unconfirmed photo** after a mid-batch
    failure. Track a confirmed count; never re-upload confirmed photos.
  - For each photo: `PhotoUploader.upload(uri)`, then `POST /api/onboarding/photos/confirm
    {storagePath, order: index}`.
  - Then `POST /api/onboarding/complete`, then `session.refreshProfile()`.
- **LocationFetcher.isDenied** drives EditProfile's "Open Settings" link.

### F.3 communityonboarding

- **Shell:**
  - Title "Register your community". Progress over 5 steps.
  - Back / Sign out / Continue / Finish as in F.2, including system back.
- **Basics:**
  - Section "About your community": "Organization or community name", "Description" (4–8 lines).
  - Footer "This is what members see first — say who you are and what you do."
  - Advance needs both fields non-empty after trimming.
- **Contact:**
  - Section "Contact info": "Website (https://…)" (URL keyboard, no autocap), "Phone",
    "Email (required)".
  - Footer "Email is required — verification updates about your community are sent there."
  - Section "Social media": Instagram, YouTube, TikTok, Facebook rows with the title on the left
    and a right-aligned "handle" field.
  - Footer "All optional — add whichever accounts you use."
  - Advance iff the trimmed email contains "@".
- **Contact person:**
  - Section "Person to contact": "Name", "Role (e.g. Coordinator)", "Phone", "Email".
  - Footer "Who should members or Drokpo reach out to with questions? Name is required."
- **Address:**
  - Section "Address": "Street address", "City", "State / province", "Country", "Postal code".
  - Footer "City and country are required."
- **Leaving Address:**
  - First time: `POST /api/communities/onboarding` (`CommunityOnboardingIn`; optionals
    `nonEmpty` → null; `contactPerson.name` and city/country trimmed).
  - **If already created** (the user went Back): `PATCH /api/communities/me` (`CommunityUpdate`,
    every field trimmed, cleared optionals sent as ""). Never re-POST, because that 409s.
- **Photos:**
  - "Add a logo and photos", "Optional — the first photo becomes your logo. You can add or
    change these later." Same grid as F.2.
  - Load failures: "One photo couldn't be loaded — try picking it again." /
    "{n} photos couldn't be loaded — try picking them again."
  - Finish uploads each photo not yet confirmed, tracked **by identity (Uri), not position**.
    Each upload is `uploadCommunityPhoto`, then `POST /api/communities/me/photos
    {storagePath, order: index}`.
  - Then `refreshProfile()`. Finishing with zero photos is allowed.

### F.4 feed

- **Top bar and states:**
  - Title "Discover" (inline). Trailing action `Groups` icon (a11y "Browse communities") opens
    the cover (§C.3 #8).
  - `PendingVerificationBanner` above the deck when ActiveCommunity and not verified.
  - Spinner only for the **initial** load (`loadInitial` runs only if the deck is empty).
  - Empty state: `Icons.Filled.AutoAwesome` (sparkles), "No one new right now", "Check back
    later, or widen your preferences in your profile.", button "Refresh" (bordered) → `fetchMore()`.
- **Fetch:**
  - `GET /api/feed?limit=20&shape=items`.
  - If `items` is null: legacy mixing, with one content card after every 3 profiles, cycling
    ad → news → post, skipping queues that are empty or already in the deck.
  - Dedupe against deck ids, `swipedUids` (this session) and `likedContentIds` (this session).
  - The `isFetching` guard prevents concurrent fetches.
  - **Refill when `deck.size <= 3`** after any swipe.
- **Deck rendering:**
  - Top 3 cards; card i is scaled by 1 − 0.03·i and offset 10dp·i downward.
  - Only the top card drags. Threshold 110dp; rotation = offsetX / 18 degrees; flies off to ±600.
  - Stamps: like label in brandRed rotated −15° top-left, visible when offset > 40;
    "PASS" in accent rotated +15° top-right, visible when offset < −40.
  - Like labels per card: profile "LIKE", ad "VISIT", news "SAVE", post "SAVE", event "JOIN".
  - Spring animation, 0.3 s.
- **Buttons overlay** (`SwipeActionButtons`, 20dp bottom padding):
  - Undo: orange; disabled when there is nothing to undo.
  - Pass: accent. Like: brandRed.
  - Share: disabled for ads and for an empty deck.
  - Pass and like **route to whatever card is on top**.
- **Profile swipe:**
  - Remove the card optimistically and add the uid to `swipedUids`. `lastSwipedProfile` = card.
  - `POST /api/swipes/{uid}` `{action}`.
  - If the action isn't pass and the result is a match: show MatchOverlay, and **clear undo**
    if this was the last swipe (a match can't be undone).
  - Error → alert.
- **Undo:** clear `lastSwipedProfile` immediately, then `DELETE /api/swipes/{uid}`. On success,
  remove the uid from `swipedUids` and re-insert the card at the top. On error, alert.
- **Ad:** a right swipe opens `linkUrl` in-app and sends a click event (`ads/{id}`). Either
  direction just removes the card; no swipe is recorded. The ad's CTA (top card only) is the
  same as a right swipe.
- **News:**
  - Right swipe **saves**: `PUT /api/news/{id}/like`, fire-and-forget, plus add to `likedContentIds`.
  - The top card's arrow button opens the source in-app plus a click event. The card stays.
  - Tapping the card opens `NewsDetailSheet`. "Read the full story" sends a click, closes the
    sheet, then opens the browser.
- **Post:**
  - Right swipe saves (`PUT /api/posts/{id}/like`). For an **event** it also RSVPs going.
  - Link CTA (top card) opens the link plus a click event.
  - An event's "Swipe right to join" affordance is the same as a right swipe.
  - Tapping opens the detail sheet, where vote and RSVP update **both the deck entry and the
    open sheet**. "Read"/link: click, close the sheet, open the browser.
- **Impressions:** the first time each ad, news or post card becomes the top card this session,
  send `ContentEvents.impression("ads|news|posts/{id}")`. Re-check whenever the top card changes.
- **Profile card:**
  - Tapping the outer 30 % thirds flips photos; the centre or the info block expands
    (top card only).
  - Photo progress capsules (3dp) when there is more than one photo.
  - Name (title bold) + age (title2) + `chevron.up.circle` when expandable, then a `…` button.
  - Region with `Place`, languages joined with " · ", bio (2 lines).
  - Gradient stops: clear at 0.40, black 45 % at 0.62, black 88 % at 1.0.
  - Text keeps `DeckClearance` bottom padding.
- **Profile card "…":**
  - First ActionSheet (no title): "Report" (destructive), "Block" (destructive).
  - Report → second ActionSheet "Why are you reporting this profile?" listing
    `Vocabulary.reportReasons`, all destructive → `reportAndRemove` (`Safety.report(uid, reason)`).
  - Block → `blockAndRemove` (`Safety.block`), with **no confirmation** on the deck (iOS parity).
  - Both remove the card immediately.
- **Expanded profile sheet** (ProfileDetail, Discover context):
  - Like and pass close the sheet, then swipe.
  - Report and block close the sheet, then remove the card.
- **Share:** a profile card shares `ShareableContent.Profile`, except a community card, which
  shares `Community(uid, displayName)`. News and posts share their own content type.
- **MatchOverlay:** black 75 % scrim, "It's a match!" (largeTitle bold white), 140dp circular
  photo, "You and {name ?: "they"} like each other.", "Keep swiping" (filled). Tapping anywhere
  dismisses.
- **Ad card:**
  - "Sponsored" badge (caption bold on a black 55 % capsule).
  - With an image: dark backdrop (white 0.07) and a 16:9 `PhotoBand` 52dp from the top.
    Without one: an accent 85 % → brandRed 75 % diagonal gradient.
  - Gradient stops: clear at 0.30, black 55 % at 0.55, black 94 % at 1.0.
  - Title (title2 bold, 3 lines), body (3 lines). The CTA uses `ctaLabel`, or "Learn more"
    when blank, at 44dp, and appears on the top card only.
- **News card:**
  - "News" badge. Top-right `arrow.up.right.circle` (top card only).
  - Source name uppercased + "· {relativePublished}". Title, then gist (3 lines).
  - Hint "Swipe right to save · arrow to read" with a hand icon (top card only).
- **Post card:**
  - "Community" badge. Top-right chevron when expandable. Community name uppercased, then the title.
  - Event: date with a calendar icon (abbreviated date + short time). Then the body (3 lines).
  - Event footer: "{n} going" plus, on the top card, "You're going ✓ — tap for details" or
    "Swipe right to join".
  - Poll: "Tap to vote" with a bar-chart icon.
  - Link: CTA button.
- **Errors:** "Something went wrong". Vote/RSVP failures while the post sheet is up must be
  visible above the sheet.

### F.5 likes

- **Layout:**
  - Title "Likes" (large).
  - Segmented control "You liked" (**default, left**) | "Liked you".
  - Pill filters, only under "You liked": "All", "Friends", "Communities", "News". The selected
    pill is bold on accent 18 %; unselected pills use the systemGray6 fill.
- **Loading:**
  - Full spinner **only when all three lists are empty**. Otherwise reload silently **on every
    appearance** (tab select, pop back) and on pull-to-refresh.
  - The three requests run in parallel: `GET /api/swipes/received?action=like`,
    `GET /api/swipes?action=like`, `GET /api/likes/content`.
  - **Drop matched entries** (`matchId != null`) from both people lists; matched people are in Chats.
- **"You liked" merge:** people (`createdAt`) and saved content (`likedAt`), filtered by the
  pill, sorted by ISO string descending. Entries with no timestamp go last.
- **Empty states** (filtered):
  - Received: heart outline, "No likes yet", "Likes you receive will show up here."
  - Given: paper plane, "Nothing saved yet", "People, news, and community posts you like in
    Discover will show up here."
- **Received row:**
  - 56dp circle photo, name (headline) + age (secondary).
  - Community card: "Community" capsule (caption2 bold, accent on accent 15 %). Otherwise region.
  - Heart button (brandRed on a quaternary circle) likes back.
- **Row like-back:** `POST /api/swipes/{uid}` `{like}`, then remove the row from `received`.
  If it matched, `AlertDialog`:
  - Title "It's a match!", message "You and {name} liked each other." (note: "liked", while
    the deck says "like").
  - "Say hi" → `deepLinks.handle("message", matchId ?: match?.matchId)`. "Later" cancels.
- **Received tap:** a community opens the community page. Anyone else opens
  `LikesRoute.LikedYouProfile` with `LikedYou(onLikeBack = { vm.likeBack(card) })`. That
  callback removes the row and returns the result **without** setting Likes' own match alert,
  because ProfileDetail shows its own.
- **Given person row:** no heart. Tapping opens `nav.openProfile(card)`.
- **Saved news row:**
  - 72×54 thumbnail with 8dp corners, source (caption2 bold, uppercased, secondary), title
    (2 lines), `arrow.up.right`.
  - Tap → open the source in-app plus `ContentEvents.click("news/{id}")`.
  - Swipe or long-press "Remove" → `DELETE /api/news/{id}/like`, then remove locally.
- **Saved post row:** kind icon (link → `Link`, poll → `BarChart`, event → `CalendarMonth`,
  otherwise `Campaign`), community name caption, title. Tapping pushes `SavedPost`. "Remove" →
  `DELETE /api/posts/{id}/like`.
- **LikedPostDetailScreen:**
  - Read-only `CommunityPostContentView` (no vote or RSVP). The link opens in-app plus
    `click("posts/{id}")`. Comments open `CommentsSheet`. The header opens the community page.
  - Title is communityName ?: "Saved post". Top bar has `ShareButton(Post)`.
- `focusLikedYou` is consumed on composition and on change (§C.4). Error → "Something went wrong".

### F.6 chats

- **ChatStore:**
  - Firestore `matches` where `users` array-contains uid **and** `status == "active"`.
  - Entry fields:
    - `otherUid` is the user in `users` who isn't me.
    - `lastMessage.text` / `senderId`.
    - `unread` = `unreadCount[uid]`.
    - `sortDate` = `lastMessage.createdAt` ?: `createdAt` ?: EPOCH. `Match.fromFirestore(id, data)`
      already reads all of these (`LastMessage.createdAt`, `Match.sortDate`).
  - Sort descending.
  - If any profile is missing, `GET /api/matches` (tolerant list) and join `otherUser` by uid.
    `isLoading` stays true until that's done.
  - A listener error sets `errorMessage` and `isLoading = false`.
- **ChatsScreen:**
  - Title "Chats" (large). Spinner when loading and empty.
  - Empty: `Forum` outline, "No chats yet", "When you and someone else like each other, you
    can start chatting here."
  - Section "New matches": horizontal row of 64dp avatars with the name (caption, 1 line), 72dp wide.
  - Conversation rows: 56dp avatar, name (headline), last message (1 line, prefixed "You: " when
    the sender is me), unread count pill (white on accent) when > 0.
  - Swipe "Unmatch" (destructive) with **no confirmation** in the list → `POST
    /api/matches/{id}/unmatch`. The listener drops the row. Errors go to `chats.setError`.
- **Deep link:** see §C.4 step 3, including `clear()` even for "match".
- **Thread top bar:**
  - Title: `otherUser.displayName ?: "Chat"` (inline).
  - Trailing: a 32dp avatar → `onOpenProfile(other)`, **only once the entry is loaded**.
  - Overflow (only when the entry exists): "Unmatch" (destructive), "Block" (destructive), "Report".
- **Thread confirmations:**
  - "Unmatch?", message "You'll no longer see each other or be able to message.", [Unmatch]
    → POST unmatch, then `onBack()`.
  - "Block this person?", message "They won't be able to message you, and you'll be unmatched.",
    [Block] → `Safety.block(otherUid, name)`, then `onBack()`.
  - "Report this person": reason list with **non-destructive** items → `Safety.report(otherUid, reason)`.
- **Messages:**
  - `matches/{id}/messages` ordered by `createdAt`, `limitToLast(100)`.
  - Read `createdAt` with `ServerTimestampBehavior.ESTIMATE` so our own sends don't jump.
  - Skip docs without `senderId` or `text`. A missing `createdAt` falls back to now.
  - Remove the listener when the screen leaves.
- **markRead:**
  - Skip unless (entry.unread > 0) **or** the last message isn't mine.
  - Skip if the last message id equals the last one marked.
  - Otherwise remember that id, call `chats.clearUnread(matchId)`, then `POST
    /api/matches/{id}/read` (errors ignored).
- **Bubbles:**
  - Mine: right-aligned, accent background, white text. Theirs: left-aligned, quaternary
    background. 48dp minimum gap on the far side. 18dp corners.
  - Photo: 220×220. Tapping opens `ChatImageViewer` (black background, fit, X button; tap anywhere closes).
  - Audio: `AudioBubbleView(isOnTintBackground = mine)`.
  - Shared link (`SharedLinkMessage.from(text)`): card with "Shared {kindLabel}" + icon
    (caption bold), caption (body bold), "Tap to view" (caption2). Tap →
    `deepLinks.pendingShare.value = destination`.
- **Separators and times:**
  - Day separator chip above the first message of each day: "Today", "Yesterday", or
    "MMM d, yyyy".
  - Time caption (short time) after the last message of a same-sender run, after a gap of more
    than 10 min, and after the last message overall. Aligned to the sender's side.
- **Scrolling:** auto-scroll to the bottom on appear and on every change.
- **Input bar:**
  - Photo icon → picker → **sends immediately** (no preview). A load failure shows "That photo
    couldn't be loaded — try picking another one."
  - Text field "Message…": capsule, 1–4 lines.
  - Mic when the text is blank, otherwise the send arrow (30dp).
  - Recording: red dot, "{n}s / 120s", cancel X, stop (red). Max is **120 s**.
  - Preview: audio bubble + trash (deletes the file) + send.
  - `RecorderFailureRow` when the recorder failed. Spinner overlay while sending.
- **Send:** a Firestore `add` with **explicit null keys**: `imageUrl`, `audioUrl`,
  `audioDurationSec`, `readAt`, plus `createdAt = serverTimestamp()` and `senderId`.
  - Text: trimmed, must be non-empty.
  - Photo: `uploadChatPhoto`, `text = "📷 Photo"`, `imageUrl`.
  - Voice: `uploadChatAudio`, `text = "🎤 Voice message"`, `audioUrl`, `audioDurationSec`.
  - Errors → alert.

### F.7 profile + settings

- **ProfileScreen top bar:** title "Profile" (large). Leading gear opens Settings. Trailing:
  eye (disabled without a profile) opens the preview, and "Edit".
- **Photos section:**
  - Horizontal 90×120 tiles with 10dp corners. Each has an X delete (**no confirmation**); the
    first has a "Primary" badge. A "+" tile appears when there are fewer than 6.
  - Footer "Drag to reorder — your first photo is the one people see on your card."
- **Reorder:**
  - Long-press drag reorders an optimistic local list.
  - On drop, if the order changed: `PATCH /api/profile/me/photos/order {storagePaths}`, then refresh.
  - On failure: alert, then refresh (revert).
  - A session refresh **mid-drag must not clobber** the local order.
- **Photo add/delete:**
  - Add: `PhotoUploader.upload`, then `POST /api/profile/me/photos {storagePath, order: count}`,
    then refresh.
  - Delete: `DELETE /api/profile/me/photos?storage_path=…`, then refresh.
  - Spinner overlay. An unreadable image shows `PhotoUploaderError.InvalidImage`'s message.
- **"About" section:** rows Name, Age, Region, Languages (joined ", "), Interests, Occupation,
  Education, each showing "—" when empty; then the bio text.
- **"Prompts" section:** answered prompts as label (caption, secondary) + answer. With none:
  "Answer a few prompts in Edit — they're the easiest way to start conversations."
- **"Socials" section:** Instagram "@x" (or "—"). YouTube only if set. TikTok "@x" only if set.
- **"Discovery preferences" section:**
  - Switch "Show me in Discover", optimistic, disabled while saving → `PATCH /api/profile/me
    {discoverable}`, then refresh.
  - Rows "Age range" "{min}–{max}" and "Distance" "{n} km".
  - Footer: "Your profile can appear in other people's swipe deck." or "Your profile is hidden —
    nobody can find you by swiping. Your matches and chats keep working."
- **"Account" section:** row "Email" (`session.email`, "—" for phone users). Footer "The
  account you're signed in with. Only you can see this."
- Pull-to-refresh → `refreshProfile()`.
- **Edit profile layout:**
  - Title "Edit profile". X = Cancel. "Save" is disabled while saving or when the trimmed name is blank.
  - Section "About": Name; Bio (3–6 lines); Gender ("Not set" + Male/Female); "Birthday" (max
    today − 18y; default from dob, else today); "Occupation"; Education ("Not set" + levels);
    Region ("Not set" + regions).
  - **Legacy values:** a stored region or education not in the vocabulary is prepended to the
    options, so the picker isn't blank.
- **Edit profile location:**
  - Section "Location": button "Update my location" with an inline spinner.
  - Footer default: "Your location decides who shows up in your feed. Update it after you move
    or travel."
  - After a fix: "Location updated — save to apply."
  - Denied: "Location access is off for Drokpo. You can turn it on in Settings — until then
    your feed uses your saved location." plus an "Open Settings" link.
  - Failure: "Couldn't get your location right now. Try again in a moment."
- **Edit profile remaining sections:**
  - "Socials": Instagram, YouTube, TikTok rows with right-aligned "handle" fields.
  - "Languages" and "Interests": checkmark rows.
  - `ProfileQuestionFields`.
  - "Discovery preferences": "Age: {a}–{b}" with a two-thumb range of 18–99 (minimum gap 1),
    and "Distance: {n} km" with a slider of 5–500 in steps of 5.
- **Edit profile save:**
  - `PATCH /api/profile/me` with the full `ProfileUpdate`:
    - name trimmed, gender "" → null, answers trimmed (empty dropped)
    - instagram and tiktok trimmed with "@" stripped, youtube trimmed
    - `location` only if updated
  - Success: `onSaved()` (refresh), then dismiss. Error → "Couldn't save".
- **Settings sections:**
  - Title "Settings" (inline). X = Done.
  - "Appearance": segmented "System" / "Light" / "Dark" → `prefs.setAppearance`.
  - "About": "Privacy policy" (`arrow.up.right`) → open `PRIVACY_POLICY_URL`; "Version" →
    `versionLabel`.
  - "Privacy & activity": "Blocked users" and "Messages you've sent", each pushed.
- **Settings account:**
  - "Sign out" dismisses, then calls `signOut()`.
  - "Delete account" (red) → ActionSheet "Delete your account?", message "Your profile,
    photos, likes, and matches will be permanently removed. This cannot be undone.", item
    "Delete everything" (destructive) → `DELETE /api/profile/me`, then dismiss and `signOut()`.
  - Spinner while deleting. Error → "Couldn't delete account".
- **Blocked users:**
  - Title "Blocked users".
  - Empty: `Block` icon, "No blocked users", "People you block from the feed will show up here."
  - Rows: `displayName ?: "Member"` + blocked date (long date style), and an "Unblock" button
    (bordered, every button disabled while any unblock runs).
  - Error → "Couldn't unblock".
- **Sent messages:**
  - Title "Sent messages". `GET /api/messages/sent?limit=100` on start and on pull-to-refresh.
  - An error replaces the list with secondary text. Spinner overlay while loading.
  - Empty: paper plane, "Nothing sent yet", "Messages you send in your chats will show up here."
  - Rows: text (3 lines) + relative date ("2 hours ago", "yesterday").

### F.8 communities + communityhome

- **CommunitiesScreen (cover):**
  - Title "Communities" (large). X = Close. Trailing `AddCircle` (a11y "Discover
    communities") pushes Directory. Plain list.
  - No joined communities and not loading:
    - Section "Communities to discover": horizontal 120×90 cards with name and "N member(s)".
    - With nothing to suggest: "No communities to discover yet."
    - Footer "Join a community to see its posts in your feed here."
  - Otherwise a rail of joined communities: 64dp circle and name, 72dp wide.
  - Feed section header "From your communities" (only when joined).
  - Feed empty: "Posts from your communities will show up here."
  - Posts render as `CommunityPostContentView` with vote (polls), RSVP (events), link, comments
    and `onOpenCommunity`. Ads render as a sponsored row: "Sponsored" (caption2 bold,
    secondary), PhotoBand with 12dp corners, title, body (3 lines), and a filled CTA (or "Learn
    more"). Person and news items are skipped.
- **CommunitiesScreen loading:**
  - `GET /api/communities/home`. If you have joined nothing, also `GET /api/communities?limit=20`.
  - **Reload on every reappearance after the first load**, so join/leave on a pushed page is
    reflected.
  - Pull-to-refresh. Spinner overlay only while loading with nothing to show.
- **CommunitiesScreen actions:**
  - A link opens in-app plus `click("posts/{id}")` or `click("ads/{id}")`.
  - Vote and RSVP update the item in place.
  - Errors → alert.
- **Directory:**
  - Title "Discover communities" (inline). `GET /api/communities?limit=50`. Reload on
    reappearance. Pull-to-refresh.
  - Empty: `Groups`, "No communities yet", "Communities will show up here as they're created."
  - Each row is `CommunityRow` plus a small "Join"/"Joined" capsule button ("Joined" in
    secondary tint) with an inline spinner. All buttons are disabled while one join runs.
  - Join: POST or DELETE `/api/communities/{cid}/join`, then flip `joined` and change
    `memberCount` by ±1 (minimum 0).
  - Tapping a row opens `nav.openCommunity(cid, preview = community)`.
- **CommunityRow** member label: "{n} member" or "{n} members".
- **Members:**
  - Title "Members". `GET /api/communities/{cid}/members?limit=50`.
  - Empty: "No members yet", "Members will show up here once people join."
  - Rows: 44dp circle, `displayName ?: "Member"` (subheadline bold), region (caption).
- **Community editor layout:**
  - Title "Community" (inline). Leading gear opens CommunitySettings.
  - Trailing "Save": disabled while `saveBlocker` is set; a spinner while saving; then "Saved"
    with a check in green for **2 s**.
  - `PendingVerificationBanner` at the top when not verified.
  - "Photos": no reorder; X delete; "+" when fewer than 6. Footer "The first photo is used as
    your logo across the app."
  - "About": Name, Description (3–8 lines). Footer: the save blocker in **red**, only while dirty.
  - "Contact info": "Website (https://…)", "Phone", "Email".
  - "Social media": 4 handle rows.
  - "Person to contact": Name, Role, Phone, Email.
  - "Address": 5 fields.
  - "Status": "Verification" (Verified in green / Pending in orange) and "Members" count.
- **Community editor save blockers:**
  - "Name must be 2–80 characters."
  - "Description can't be empty."
  - "Website must start with https://." (only when the website is non-empty)
- **Community editor sync:**
  - Fill the fields from `session.myCommunity` on start.
  - Re-sync whenever the session community changes, **only when not dirty**, so photo
    add/delete never wipes typed edits.
  - Pull-to-refresh → `refreshProfile()`.
- **Community editor save:** `PATCH /api/communities/me`.
  - Clearable optionals are sent as "" when emptied: website, phone, contact role/phone/email,
    line1, state, postalCode, all socials.
  - Never-clearable fields are omitted when empty: email, contact name, city, country.
  - Then refresh and reload the fields.
- **Community editor photos:** `uploadCommunityPhoto`, then `POST /api/communities/me/photos
  {storagePath, order: count}`. Delete: `DELETE /api/communities/me/photos?storage_path=…`.
- **CommunitySettings:**
  - Title "Settings". X close (iOS has no Done button; Android needs a close).
  - "Appearance" and "About" as in F.7.
  - "Account": "Signed in as" `email ?: phone ?: "—"`, "Sign out", and "Delete community" (red).
  - Delete → "Delete this community?", message "Your community's profile, photos, and posts
    will be permanently removed. This cannot be undone.", item "Delete everything" →
    `DELETE /api/communities/me`, then `signOut()`.
  - Error → "Couldn't delete community". No blocked-users or sent-messages section.
- **Composer layout:**
  - Title "New post". X = Cancel. "Post" (a spinner while saving).
  - Section "Post type": segmented Announcement / Link / Poll / Event.
  - Main section header: "Question" for a poll, "Event" for an event, otherwise "Post".
  - Title field placeholder: "Ask a question", "Event name", or "Title".
  - "Description" (3–8 lines), hidden for polls.
- **Composer per-kind sections:**
  - Event: "Event details" with "Date & time" (≥ now; default now + 1 h) and "Location (optional)".
  - Link: section "Link" with "https://…" and `Button label (optional, e.g. "Register")`.
    Footer "Members open this in-app when they swipe right on your card."
  - Event: section "Registration link" with "https://… (optional)" and the button label field.
    Footer "Optional. Members open this in-app when they swipe right on your card."
  - Poll: section "Options" with "Poll option" rows. A red minus appears when there are more
    than 2. "Add option" appears when there are fewer than 4. Footer "2–4 options. Members can
    change their vote any time." Rows have stable ids.
- **Composer photo:**
  - Section "Photo (optional)": preview (max 160dp tall), "Remove photo" (destructive), and
    "Choose photo" / "Replace photo".
  - Load failure: "That photo couldn't be loaded — try picking another one."
- **Composer canSave:** the trimmed title is non-empty and not saving, and:
  - Link: link starts with "https://".
  - Poll: at least 2 filled options, all unique.
  - Event: date in the future and the link either empty or starting with "https://".
- **Composer payload** (`POST /api/communities/me/posts`):
  - Upload the photo first (`uploadCommunityPhoto` → `photoStoragePath`).
  - `body` is "" for a poll.
  - `linkUrl`: trimmed for a link; non-empty-or-null for an event; otherwise null.
  - `ctaLabel` (link/event): non-empty-or-null.
  - `pollOptions`: the filled options.
  - `eventAt`: ISO-8601 UTC, e.g. `2026-10-06T12:00:00Z`.
  - `location` (event): non-empty-or-null.
  - Then `onSaved()`, then dismiss. Error → "Couldn't post".
- **PendingVerificationBanner:** `Schedule` icon (clock.badge.exclamationmark) in orange,
  "Awaiting verification" (subheadline bold), and "You can post and appear in Discover right
  away. The verified badge — and liking people from the deck — unlock once your community is
  approved." (caption, secondary). 10dp padding, orange 12 % fill, 10dp corners, horizontal
  margin, 4dp top.

### F.9 sharedcommunity + news

- **Owner vs visitor:**
  - Owner mode reads the header from `session.myCommunity`. Never `GET /api/communities/{cid}`:
    it 404s for an unverified community.
  - Visitor: show `preview` immediately, then `GET /api/communities/{cid}`.
  - **joinGeneration guard:** if a join or leave landed while that GET was in flight, keep the
    local `joined` and `memberCount`.
- **Header:**
  - 88dp circular logo. Name (title3 bold) + verified seal.
  - "{n} member(s)": a link to the members list for the owner or a joined visitor, otherwise
    plain text.
  - Description (subheadline).
  - Action row:
    - Owner: "New post" (filled, `AddCircle`) opens the composer.
    - Visiting person: "Join" (filled) or "Joined" (bordered), with an inline spinner.
    - Visiting community account: no join button (communities don't join communities).
    - "Website" (bordered, globe) opens in-app, only if the URL parses.
- **Top bar:**
  - Title: community name ?: "Community" (inline).
  - `ShareButton(Community(cid, name))`.
  - Owner: `+` opens the composer.
  - Visitor: overflow (a11y "Report or block") with "Report" and "Block", both destructive.
- **Report:** ActionSheet "Why are you reporting this community?", destructive reasons →
  `Safety.report(cid, reason)`.
- **Block:** ActionSheet "Block {name ?: "this community"}?", message "You won't see this
  community or its posts." → `Safety.block(cid, name)`, then `onBack()`.
- **Owner extras:** `PendingVerificationBanner` under the header when unverified. Unpublished
  posts get an "Unpublished" badge (orange capsule, white caption2 bold) and 55 % opacity.
- **Posts:**
  - `GET /api/communities/{cid}/posts?limit=30`.
  - When the **last tile appears**, `…&before={lastPostId}`. Failures are silent; stop when a
    page comes back empty. Spinner below the grid.
  - Pull-to-refresh reloads everything. The header spinner overlay shows only while loading
    with no community.
- **Grid:**
  - 3 columns with 2dp spacing, square tiles.
  - Photo tiles: the kind icon bottom-left.
  - Placeholder tiles: kind tint at 15 % (link blue, poll purple, event green, otherwise
    accent), icon, title (caption2 bold, 3 lines).
- **Empty grid:** "No posts yet" (grid icon). Owner: "Share an announcement, link, poll, or
  event." Visitor: "Check back soon."
- **Tapping a tile** opens `CommunityPostDetailSheet(ownerMode)`:
  - Vote and RSVP update both the grid and the open sheet.
  - The link closes the sheet, then opens the browser.
  - Publish toggle: `PATCH /api/communities/me/posts/{id} {active: !active}`, then close the
    sheet and reload.
- **Join toggle:** POST or DELETE `/api/communities/{cid}/join`, then `joined` and memberCount
  ±1 (minimum 0), and bump `joinGeneration`.
- After the composer saves, reload.
- **CommunityPostContentView header:** 28dp circular logo, community name (subheadline bold, or
  "Community"), and a small chevron when `communityId` is set. The header is tappable (calls
  `onOpenCommunity`).
- **CommunityPostContentView body:**
  - PhotoBand with 12dp corners. Title (headline).
  - Event: date with calendar icon, location with place icon, "{n} going".
    RSVP: "Can't come" (bordered) or "Join" (filled), only when `onRsvp` is set.
  - Body (subheadline, secondary).
  - Poll: option rows 40dp tall with 8dp corners. After voting they fill to the percentage
    (mine in accent 35 %, others in secondary 20 %) and show a check on mine plus "NN%".
    Tapping is disabled when `onVote` is null or the option is already my vote. Shows
    "{n} vote(s)" when > 0.
  - Link CTA (filled): `ctaLabel`, or "Learn more".
  - Comment button: `ChatBubbleOutline` + count, or "Comment" when 0 or null.
- **PostDetailSheet:**
  - Title `communityName ?: "Community post"`. X = Close. `ShareButton(Post)`.
  - Owner controls below a divider: "Unpublished — only you can see this" (`VisibilityOff`,
    orange) when inactive, and "Republish" (green, bordered) / "Unpublish" (orange, bordered).
  - Comments open `CommentsSheet`.
- **NewsDetail:**
  - PhotoBand with 14dp corners. Source (caption bold, uppercased, secondary) + "· {relative}"
    (tertiary).
  - Title (title2 bold). Body: `summary` when non-empty, else `gist`; body style with extra
    line spacing.
  - "Read the full story": filled, 46dp, full width.
  - 20dp padding.

### F.10 comments + audio

- **Sheet and load:**
  - Title "Comments" (inline). X = Close.
  - `GET /api/posts/{id}/comments`, newest first.
  - Pagination when the last row appears: `?before={lastCommentId}`. Silent on failure;
    stop on an empty page.
  - Spinner on first load. Pull-to-refresh.
  - Empty: `Forum` outline, "No comments yet", "Be the first to say something."
- **Comment row:**
  - 32dp avatar (26 for replies), author (subheadline bold) + verified seal for community
    authors, relative time (caption2).
  - Body is text, or `AudioBubbleView` for audio. Indent 40dp (34 for replies).
  - Actions: "Reply" (caption bold, secondary), then thumbs up/down. The active vote is filled
    and in accent; counts show when > 0.
- **Voting:** tapping the active vote clears it (`DELETE …/vote`). Otherwise `PUT …/vote
  {value: "like" | "dislike"}`. Update counts and `myVote` from the response.
- **Replies:**
  - "View {n} reply" / "View {n} replies" lazy-loads `GET …/comments/{id}/replies` once
    (spinner while loading).
  - **There's no collapse button once expanded** (iOS parity).
  - Reply target chip: "Replying to {name}" with an X.
- **Delete:**
  - Allowed for the author and for the post's owning community (`myUid == post.communityId`).
  - Offered by swipe or long-press "Delete" → `DELETE …/comments/{id}`.
  - Deleting a reply decrements the parent's `replyCount`. Deleting a top-level comment drops
    its thread.
- **Safety** (never on your own comments):
  - Long-press "Report or block…" or swipe "Report" (orange) → ActionSheet "Comment by
    {name ?: "member"}" with "Report…" and "Block {name ?: "member"}", both destructive.
  - "Report…" → second sheet "Why are you reporting this comment?" with destructive reasons →
    `Safety.report(authorUid, reason, note = "Comment {commentId} on post {postId}")`.
  - Block → `Safety.block(authorUid, authorName)`, then reload (the backend hides blocked authors).
- **Composer:**
  - "Add a comment…": capsule, 1–4 lines. A mic button records with a **60 s** max:
    "{n}s / 60s", cancel, stop.
  - Preview: audio bubble + trash.
  - The send arrow shows **only when there is something to send**. A spinner replaces it while
    sending.
  - Submit: text is trimmed. Audio is `uploadCommentAudio` (path), then `POST
    …/comments {audioStoragePath, audioDurationSec, parentId}`.
  - Insert the created comment at the **top** (top-level) or **append** it to its thread
    (`replyCount + 1`, thread expanded). Clear the composer and the reply target only on success.
- **AudioRecorder:**
  - Permission denied → `Failed("Microphone access is off. You can turn it on in Settings.")`.
  - Recorder setup failure → "Couldn't access the microphone." or "Couldn't start recording."
  - The timer ticks every 1 s. At the max it auto-stops and calls `onAutoStop`.
  - `stop()` returns at least 1 s. `cancel()` deletes the file.
  - Release the recorder on dispose.
- **AudioPlaybackCenter:**
  - Starting one clip stops any other. Tapping the playing clip stops it.
  - Don't take exclusive audio focus (iOS `mixWithOthers`): `handleAudioFocus = false`.
  - Progress updates every 100 ms. On completion → `stop()`. Failures are silent.
- **AudioBubbleView:**
  - Play/pause circle (title2), a 4dp progress capsule (minimum 3 %), and a "m:ss" label
    (remaining while playing, else total), caption2 monospaced, 36dp wide.
  - Minimum width 140dp. The tint variant is white-on-accent.

### F.11 sharing + profiledetail

- **ShareSheet:**
  - Title "Share" (inline). X = Close.
  - Section "Send in a chat": with no entries, "Match with someone first to share inside Drokpo."
  - Rows: 44dp avatar, name, and a "Send" button (filled, small, disabled while sending) that
    becomes "Sent" with a check in green.
  - Send → `ChatMessageSender.sendText(content.messageText, matchId, uid)`. Error → alert.
  - Section "Outside Drokpo": "Share via WhatsApp, Messages…" (share icon) → `ACTION_SEND`
    `text/plain`, `EXTRA_TEXT = messageText`, `EXTRA_SUBJECT = title`, through
    `Intent.createChooser`.
- **ShareDestinationSheet:**
  - X = Close.
  - `Community(cid)` → `SharedRoute.Community(cid)`.
  - `User(uid)` → `GET /api/users/{uid}` (FeedCard). A community card opens the community page;
    anyone else gets ProfileDetail (Plain).
  - `Post(id)` → `GET /api/posts/{id}`, shown as SharedPostView:
    - Title `communityName ?: "Community post"`. `ShareButton(Post)`.
    - Live vote and RSVP update local state. The link opens in-app.
    - Comments open `CommentsSheet`. The header opens the community.
  - `News(id)` → `GET /api/news/{id}`, shown as `NewsDetailContent`: title "News",
    `ShareButton(News)`, Read → open plus `click("news/{id}")`.
  - Loading → spinner.
  - Any failure → "Content unavailable" (`Link` icon), "It may have been removed, or isn't
    available to you."
- **ProfileDetail layout:**
  - Photo pager: `HorizontalPager`, 420dp tall, 16dp corners, page dots.
  - Name (title bold) + age (title2).
  - Labelled rows in secondary text:
    - region (place icon)
    - "~{n} km away" (location icon), from `distanceKm`
    - occupation (`Work`)
    - education (`School`)
    - languages joined ", " (`Language`)
    - "@{instagram}" (`CameraAlt`)
  - Bio.
  - "Interests" label (sparkles) above a `FlowRow` of footnote chips on quaternary capsules.
  - Answered prompts, in vocabulary order, for known keys only: cards with the label (caption,
    secondary) and the answer, 12dp padding, 12dp corners, quaternary 50 % fill.
  - Bottom padding 72dp when there's an action bar.
- **ProfileDetail top bar:**
  - Title: `title ?: displayName ?: "Profile"`.
  - `ShareButton`: a community card shares `Community(uid, displayName)`, anyone else `Profile(card)`.
  - Overflow (a11y "Report or block") with "Report" and "Block", **hidden when isSelf**
    (`card.uid == session.uid || card.uid == "me"`).
- **ProfileDetail safety:**
  - Report → "Why are you reporting this profile?", destructive reasons.
  - Block → "Block {name ?: "this member"}?", message "You won't see each other anywhere in Drokpo.".
  - Both use the caller's `onReport` / `onBlock` when given. Otherwise `Safety.report` (the
    screen stays open, as iOS `report()` never dismisses) / `Safety.block`, then `onBack()`.
- **ProfileDetail action bar** (bottom bar on the bar background):
  - Plain: none.
  - Discover: `SwipeActionButtons(onPass, onLike)` with 12dp vertical padding.
  - LikedYou: "Like back" (heart, brandRed, large, disabled while liking) → `onLikeBack()`.
- **After a LikedYou match:**
  - Store the matchId. AlertDialog "It's a match!", "You and {name ?: "they"} liked each
    other.", "Say hi" → `deepLinks.handle("message", matchId)`, "Later".
  - The button becomes "Send message" (`Chat` icon, filled) and does the same handle call.

---

## G. Reference stubs (signature check)

Every §A/§B signature above, plus a file exercising the documented cross-group call sites, was
compiled as throwaway stubs against placeholder models (`./gradlew :app:compileDebugKotlin` →
BUILD SUCCESSFUL). The stubs live in the contract agent's workspace:

`/private/tmp/claude-501/-Users-tashitsering-Desktop-Projects-drokpo/67fe116d-1d9d-4aa3-b609-9385065f3799/scratchpad/ws/contract/app/src/main/kotlin/app/drokpo/android/`

| File | Covers |
|---|---|
| `core/Spine.kt` | §A.1–A.11 |
| `ui/components/Components.kt` | §A.12 |
| `navigation/SharedNavigation.kt` | §A.13, a working implementation of the builder and helpers |
| `RootAndTabs.kt` | §A.14 shape only |
| `features/**/…Stubs.kt` | §B |
| `ContractUsageCheck.kt` | call-site check |

The foundation may copy them as a starting point. Don't copy `core/model/PlaceholderModels.kt`,
which is scratch only; the real models come from the models agent. Don't copy
`ContractUsageCheck.kt` either.

**Integration status (foundation integrator, 2026-10-07).** MASTER now contains the real
spine (§A), the models/API, the theme, the shell, `navigation/SharedNavigation.kt` and every §B
stub. MASTER is the reference from here on; the scratch workspace above is historical. Checks:
- `ContractUsageCheck.kt` compiled against MASTER without changes, except for the
  `DrokpoJson` import, which lives in `core`.
- Every declaration name in the reference `*Stubs.kt` exists in MASTER's feature packages.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` is green, with 0 lint errors.

Deviations from the original text are marked "as shipped" or "integrator" above. They are in
§0.5 (SwipeAction casing, ApiClient class/companion, query type), §0.6 (new), §A.4
(NoGoogleAccount), §A.8, §A.10, §A.11, §A.12, §A.13, §A.14, §A.15, §C.1, §C.2 and §E.
