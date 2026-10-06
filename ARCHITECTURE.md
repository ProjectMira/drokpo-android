# Drokpo Android — architecture & porting contract

Native Android port of the Drokpo iOS app (`../drokpo-app`, SwiftUI). The iOS
app is the **source of truth** for design, copy, flows and behaviour: every
screen, string, empty state, error message, confirmation dialog, colour and
navigation step should match it unless Android conventions make that wrong
(system back, Material components, runtime permissions, notification channels).

Backend: the same FastAPI + Firebase project (`../drokpo-backend`,
Firebase project `drokpo-backend`). Android talks to it exactly like iOS: REST
under `https://drokpo-backend.web.app/api/**` with a Firebase ID token as
bearer auth, plus direct Firestore listeners for chat and Firebase Storage for
media uploads. Pydantic schemas in `../drokpo-backend/backend/app` are the
authority on JSON field names when the Swift code is ambiguous.

## Stack

| Concern | iOS | Android |
|---|---|---|
| UI | SwiftUI | Jetpack Compose + Material 3 (dynamic colour OFF — brand colours) |
| State | `@Observable` classes | `ViewModel` + `StateFlow`, or plain state-holder classes with `mutableStateOf` for singletons |
| Navigation | `NavigationStack` per tab, `.sheet`, `.fullScreenCover` | One `NavHost` per tab (type-safe `@Serializable` routes), `ModalBottomSheet` for sheets, full-screen `Dialog` for covers |
| Tabs | `TabView` + `.badge` | `NavigationBar` + `BadgedBox` |
| Networking | `URLSession` + `JSONDecoder` | OkHttp + kotlinx.serialization (`core/ApiClient.kt`) |
| Images | `RemotePhotoView` | Coil 3 (`core/RemotePhoto.kt`) |
| Auth | FirebaseAuth, Sign in with Apple, GoogleSignIn, phone | FirebaseAuth, Credential Manager + Google ID, phone (`PhoneAuthProvider`), Apple via Firebase `OAuthProvider` (flagged off: `BuildConfig.APPLE_SIGN_IN_ENABLED`) |
| Push | APNs → FCM | FCM (`FirebaseMessagingService`) + notification channels + POST_NOTIFICATIONS runtime permission |
| Location | CoreLocation | Fused location provider + runtime permission |
| Photos | PhotosPicker | `ActivityResultContracts.PickVisualMedia` (system photo picker) |
| Audio | AVAudioRecorder / AVPlayer | `MediaRecorder` (AAC/m4a) / Media3 ExoPlayer |
| Web | `SafariView` | Custom Tabs (`androidx.browser`) |
| Persistence (`@AppStorage`) | UserDefaults | DataStore Preferences (`core/AppPreferences.kt`) |
| Share sheet | `ShareLink` / UIActivityViewController | `Intent.ACTION_SEND` chooser |
| Deep links | `drokpo://s/{type}/{id}` URL scheme | Same scheme via an intent filter on `MainActivity` |

No DI framework (the iOS app has none): app-wide singletons live in
`core/` as Kotlin `object`s or are created once in `DrokpoApplication` and
reached through `AppGraph`. ViewModels are created with
`viewModel { FooViewModel(...) }` factories.

## Package layout (`app/src/main/kotlin/app/drokpo/android/`)

```
DrokpoApplication.kt      Firebase init, AppGraph, notification channels
MainActivity.kt           single activity: splash, edge-to-edge, deep links, push taps
RootScreen.kt             session-state router (mirrors iOS RootView)
MainTabs.kt               5-tab shell (mirrors iOS MainTabView), TabReselectEffect
core/                     ports of Drokpo/Core/*.swift (models, API, session, auth, uploaders, push, …)
navigation/               SharedNavigation.kt: SharedRoute + sharedDestinations() — the profile /
                          community / members destinations every NavHost can push (CONTRACT §A.13)
ui/theme/                 Color.kt, Type.kt, Theme.kt, Brand.kt (iOS-style text styles + brand colours)
ui/components/            small generic widgets used everywhere (FlowRow wrappers, loading/empty/error states, grouped list rows, …)
features/<area>/          one package per iOS Features/<Area> folder
features/shared/<area>/   ports of Features/Shared/*
```

Feature packages map 1:1 to iOS folders:

| iOS | Android package |
|---|---|
| Features/Auth | `features.auth` |
| Features/Onboarding | `features.onboarding` |
| Features/CommunityOnboarding | `features.communityonboarding` |
| Features/Feed | `features.feed` |
| Features/Likes | `features.likes` |
| Features/Chats | `features.chats` |
| Features/Communities | `features.communities` |
| Features/CommunityHome | `features.communityhome` |
| Features/Profile | `features.profile` |
| Features/Settings | `features.settings` |
| Features/Shared/Audio | `features.shared.audio` |
| Features/Shared/CommentsSheet | `features.shared.comments` |
| Features/Shared/CommunityPageView, CommunityPostContentView | `features.shared.community` |
| Features/Shared/NewsDetailSheet | `features.shared.news` |
| Features/Shared/ProfileDetailView | `features.shared.profiledetail` |
| Features/Shared/ProfileQuestionFields | `features.onboarding` (shared with profile edit) |
| Features/Shared/Sharing/* | `features.shared.sharing` |
| Features/Shared/SwipeActionButtons | `features.feed` |
| Features/Shared/FlowLayout, SafariView | `ui.components` (Compose `FlowRow`, Custom Tabs helper) |

## Ownership & parallel work protocol

> **Binding cross-package signatures live in [`docs/CONTRACT.md`](docs/CONTRACT.md)**: the spine
> API (§A), every feature group's public entry points (§B), navigation (§C), state scoping (§D),
> the debug catalog (§E) and per-group behaviour checklists (§F). Its §0.6 "Calling the API"
> sets the one style for REST calls, errors and models. Where this file and the contract
> disagree, the contract wins.

The app is built by several agents in parallel. **Each agent owns specific
directories and must not edit files outside them.** Shared files
(`build.gradle.kts`, `libs.versions.toml`, `AndroidManifest.xml`, `core/`,
`ui/`, `navigation/`, `RootScreen.kt`, `MainTabs.kt`, catalog registry) are owned by the
foundation; feature agents that need a change there **report it** in their
final output instead of editing (the integrator applies it). A feature may add
private helpers inside its own package rather than changing `core/`.

Stubs: the foundation creates a stub for every screen/component other
packages call, with its final public signature and a `TODO("…")`-free body
(a placeholder `Text`). Feature agents replace the stub bodies. **Public
signatures of stubs are a contract:** never rename/remove parameters or
change their types; you may add new parameters only if they have defaults.

### Workspace & build protocol (every agent)

- `MASTER=/Users/tashitsering/Desktop/Projects/drokpo/drokpo-android`
- Work in a private copy so parallel builds never see each other's half-written
  code: `rsync -a --delete --exclude '.gradle/' --exclude 'build/' --exclude '.kotlin/' "$MASTER/" "$WS/"`
  where `$WS` is the workspace path given in your task.
- Build with the Android Studio JDK:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
  then from `$WS`: `./gradlew :app:compileDebugKotlin --console=plain -q`
  (fast type-check) and `./gradlew :app:testDebugUnitTest --console=plain` for unit tests.
  Pipe output through `tail`/`grep -E 'e: |error:|warning:.*(deprecated|unused)'` — logs are long.
- Run `./gradlew --stop` is **forbidden** (it kills other agents' daemons).
- Deliver: when your work compiles, copy **only your owned directories** back:
  `rsync -a "$WS/<owned dir>/" "$MASTER/<owned dir>/"` (no `--delete` unless the dir is wholly yours).
- Never touch `$MASTER/app/google-services.json`, `app/debug.keystore`, or git state.

## Conventions

- Kotlin official style, 4-space indent, no wildcard imports. Comments explain
  *why* (port the iOS comments that carry intent — they document real bugs and
  product decisions).
- Every screen = `FooScreen(...)` (wires the ViewModel/state holder, navigation
  callbacks) + stateless `FooContent(state, onAction…)` that renders from plain
  data. `FooContent` must be renderable with fixture data, no network — the
  debug catalog and previews depend on it.
- User-facing copy: same English strings as iOS, inline in Kotlin (the iOS app
  has no localisation either).
- Errors: `ApiError` messages surface exactly like iOS (`error.localizedDescription`
  → `throwable.userMessage()` from `core`).
- Async: `viewModelScope.launch`; Firebase `Task`s via `kotlinx.coroutines.tasks.await()`.
  Snapshot listeners are registered/removed with lifecycle (`DisposableEffect`
  or ViewModel `onCleared`).
- Permissions: request with `rememberLauncherForActivityResult(RequestPermission)`,
  mirror iOS copy for rationale and "Open Settings" fallbacks.
- Edge-to-edge: use `Scaffold` insets; no hard-coded status-bar padding.
- Dark mode: honour `AppearanceMode` (system/light/dark) from `AppPreferences`.

## Design mapping (iOS → Compose)

Colours (from the iOS asset catalog):

| Token | Light | Dark | Use |
|---|---|---|---|
| `accent` (primary) | `#1877F2` | `#4599FF` | buttons, links, chat bubbles (mine), selected tab, toggles |
| `brandRed` | `#FF0000` | `#FF453A` | ONLY like/love: hearts, like button, LIKE stamp; also logo ground |
| iOS system grouped background | `#F2F2F7` | `#000000` | grouped lists / settings |
| secondary grouped (cards/rows) | `#FFFFFF` | `#1C1C1E` | |
| secondary label | `#3C3C43` @ 60% | `#EBEBF5` @ 60% | `.secondary` text |
| separator | `#3C3C43` @ 29% | `#545458` @ 60% | |
| system green / orange / yellow | `#34C759` / `#FF9500` / `#FFCC00` | `#30D158` / `#FF9F0A` / `#FFD60A` | status badges, pass/superlike |

Text styles (iOS Dynamic Type defaults, use `DrokpoTheme.typography`):
largeTitle 34/bold, title 28, title2 22, title3 20, headline 17/semibold,
body 17, callout 16, subheadline 15, footnote 13, caption 12, caption2 11.

Components:
- `List(.insetGrouped)` / `Form` → rounded grouped sections (`ui.components.GroupedSection`)
- `.borderedProminent` → filled `Button` (primary), `.bordered` → tonal/outlined
- `.navigationTitle` large → `LargeTopAppBar`/`TopAppBar`; inline → `CenterAlignedTopAppBar`
- `.toolbar` items → top app bar actions
- `.confirmationDialog` → `ModalBottomSheet` with actions or `AlertDialog`
- `.alert` → `AlertDialog`
- `ProgressView()` → `CircularProgressIndicator`
- `ContentUnavailableView` → `ui.components.EmptyState` (icon + title + message)
- `Picker(.segmented)` → `SingleChoiceSegmentedButtonRow`
- `.refreshable` → `PullToRefreshBox`
- `.swipeActions` → `SwipeToDismissBox` or long-press menu
- SF Symbols → closest `Icons.Filled/Rounded.*` from material-icons-extended
  (e.g. `heart.fill`→`Favorite`, `xmark`→`Close`, `star.fill`→`Star`,
  `bubble.left.and.bubble.right.fill`→`Forum`, `person.fill`→`Person`,
  `rectangle.stack.fill`→`Layers`/`ViewCarousel`, `person.3.fill`→`Groups`,
  `square.and.arrow.up`→`IosShare`/`Share`, `arrow.uturn.backward`→`Undo`,
  `mic.fill`→`Mic`, `photo`→`Image`, `ellipsis`→`MoreHoriz`, `flag`→`Flag`,
  `hand.raised`→`Block`, `checkmark.seal.fill`→`Verified`, `mappin`→`Place`).

## Debug catalog (visual QA without signing in)

`app/src/debug/kotlin/app/drokpo/android/catalog/` holds a debug-only
`CatalogActivity` listing every screen rendered with fixture data. Each feature
area contributes `catalog/<Area>Catalog.kt` exposing
`val <area>CatalogEntries: List<CatalogEntry>` (stateless `*Content`
composables + fixtures from `catalog/Fixtures.kt`). Launch one entry:

```
adb shell am start -n app.drokpo.android/.catalog.CatalogActivity                    # searchable list
adb shell am start -n app.drokpo.android/.catalog.CatalogActivity --es entry <id>    # one entry
adb shell am start -n app.drokpo.android/.catalog.CatalogActivity --es entry <id> --ez dark true
```

## Release & CI

See `README.md` and `.github/workflows/`. Version name tracks iOS
(`versionName` in `app/build.gradle.kts` ↔ `MARKETING_VERSION` in
`../drokpo-app/project.yml`); CI sets `versionCode` from the run number.
