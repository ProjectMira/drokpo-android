# Drokpo for Android

[![Android CI](https://github.com/ProjectMira/drokpo-android/actions/workflows/android-ci.yml/badge.svg)](https://github.com/ProjectMira/drokpo-android/actions/workflows/android-ci.yml)
[![Release](https://github.com/ProjectMira/drokpo-android/actions/workflows/release.yml/badge.svg?branch=main)](https://github.com/ProjectMira/drokpo-android/actions/workflows/release.yml)

Native Android port of Drokpo, a simple Tinder-style app for the Tibetan community. It is
built with Kotlin and Jetpack Compose on top of the same Drokpo FastAPI backend and Firebase
project as the [iOS app](../drokpo-app). The iOS app is the source of truth for design,
copy and behaviour. This port follows it screen for screen.

## Requirements

- [Android Studio](https://developer.android.com/studio) (current stable). It bundles the
  JDK 21 that Gradle runs on. From a terminal, use it with
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Android SDK **Platform 37** (`platforms;android-37.0`) and **Build-Tools 37.0.0**.
  Install them from Android Studio → SDK Manager, or with
  `sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`. The app compiles against
  API 37, targets 36, and runs on API 26+.
- The `drokpo-backend` Firebase project with **Auth** (Google, phone and, optionally,
  Apple providers), **Firestore**, **Storage** and **Cloud Messaging**.

## Setup

1. Download the Firebase config for the Android app into `app/google-services.json`.
   The file is gitignored.

   ```sh
   firebase apps:sdkconfig ANDROID 1:246225694981:android:c84e277a94f02015ee076c \
     --project drokpo-backend -o app/google-services.json
   ```

   Without this file the project still builds. The app then shows a "Firebase not
   configured" notice instead of the sign-in screen, the way iOS behaves without its
   `GoogleService-Info.plist`.
2. Open the project folder in Android Studio and let Gradle sync. Android Studio writes
   `local.properties` with your SDK path.
3. The backend URL is `BuildConfig.API_BASE_URL` (`https://drokpo-backend.web.app`, whose
   `/api/**` rewrites to the drokpo-api Cloud Run service), set in `app/build.gradle.kts`.
4. Debug builds are signed with the committed `app/debug.keystore`. Its SHA-1 is registered
   in Firebase, so Google sign-in works on every machine and in CI without per-developer
   setup.

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full porting contract (stack mapping, package
layout, conventions and the iOS → Compose design mapping). In short:

- **Jetpack Compose + Material 3** with brand colours, `ViewModel` + `StateFlow`, and no DI
  framework (singletons in `core/`, reached through `AppGraph`). The iOS app has no DI
  either.
- `core/` ports `Drokpo/Core`: the API client (OkHttp + kotlinx.serialization, Firebase ID
  token as bearer auth), models, the session state machine, auth, uploaders, push and deep
  links.
- `features/<area>/` has one package per iOS `Features/<Area>` folder. Each screen is a
  `FooScreen` (wiring) plus a stateless `FooContent` that renders fixture data.
- Chat is real time. The match list and open threads use Firestore snapshot listeners
  directly. Everything else (membership, unmatching, read receipts, …) goes through the REST
  API.
- Root routing mirrors iOS `RootView`. Signed out → sign-in. Signed in without a profile →
  onboarding. Otherwise → the main tabs.

## Running

**Emulator:** create a device in Android Studio → Device Manager (a Google Play system image,
so Google sign-in and FCM work), then press Run. From a terminal:

```sh
./gradlew :app:installDebug
adb shell am start -n app.drokpo.android/.MainActivity
```

**Physical device:** enable Developer options → USB debugging (or Wireless debugging), connect
the device, and choose it in Android Studio's device picker. Phone-number sign-in, the camera,
microphone recording and push notifications all need a real device (or a Play emulator with
a signed-in Google account).

Deep links work like iOS:

```sh
adb shell am start -a android.intent.action.VIEW -d "drokpo://s/user/<uid>" app.drokpo.android
```

**Tests and lint** run the same tasks as CI:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s ci/tests      # the release tooling's own tests
```

### Debug screen catalog

Debug builds include `CatalogActivity`, which lists every screen rendered with fixture data,
with no sign-in or network. Use it for visual QA against the iOS app.

```sh
adb shell am start -n app.drokpo.android/.catalog.CatalogActivity                    # the list
adb shell am start -n app.drokpo.android/.catalog.CatalogActivity --es entry <id>    # one screen
```

Each feature area adds its entries in `app/src/debug/kotlin/app/drokpo/android/catalog/`.

## CI and releases

| Workflow | Trigger | Result |
|---|---|---|
| [Android CI](.github/workflows/android-ci.yml) | every push, every PR | unit tests, lint, debug APK artifact (`drokpo-debug-<branch>-<sha>`) |
| [Release](.github/workflows/release.yml) | push to `main` or `dev`, or Run workflow | signed AAB → **Google Play internal testing**, artifacts, tag `v<version>-android.<code>` |

This is the counterpart of the iOS TestFlight workflow. `versionName` tracks iOS
`MARKETING_VERSION`, and `versionCode` = run number + `VERSION_CODE_OFFSET`. Manual runs can
target the alpha, beta or production track, as a draft or completed release.

One-time setup (upload key, Play Console, service account, Firebase fingerprints, Sign in
with Apple) is in **[docs/RELEASE.md](docs/RELEASE.md)**. It starts with
`ci/setup-release-signing.sh`, which creates the upload key and sets the repo secrets.

Local signed release build (after running the setup script, which writes the gitignored
`keystore.properties`):

```sh
./gradlew :app:bundleRelease -Pdrokpo.versionCode=<n>
```

## Status

The port targets parity with iOS **1.1** (TestFlight build 36). Feature areas and where they
live:

| iOS feature | Android package |
|---|---|
| Sign in with Google / phone number (Apple: behind `APPLE_SIGN_IN_ENABLED`), person vs. community account choice | `features.auth` |
| Onboarding: profile questions, optional Instagram handle, photos, location | `features.onboarding` |
| Community onboarding and owner tools (profile editor, post composer, publish toggle, settings, pending-verification banner) | `features.communityonboarding`, `features.communityhome` |
| Discover deck: people, community posts, news and ad cards; undo · pass · like · share | `features.feed` |
| Likes: "You liked" (default) / "Liked you", like-back → match | `features.likes` |
| Real-time chat with matches: text, photos, voice notes, shared-link cards, read receipts | `features.chats` |
| Communities directory and members; Instagram-style community pages with a post grid | `features.communities`, `features.shared.community` |
| Post comments: text or voice, one level of replies, like/dislike, author/community delete | `features.shared.comments`, `features.shared.audio` |
| News detail, profile detail, sharing (send to a match, system share sheet, `drokpo://s/…` links) | `features.shared.news`, `features.shared.profiledetail`, `features.shared.sharing` |
| Profile editing incl. "Show me in Discover"; settings (appearance, blocked people, your activity, privacy policy, sign out, delete account); report / block everywhere | `features.profile`, `features.settings` |
| Push notifications (likes, matches, messages) with deep-link taps | `core` (FCM + notification channel) |

Intentional platform differences:

- Sign in with Apple is off until the Apple Services ID is configured (see
  [docs/RELEASE.md](docs/RELEASE.md#sign-in-with-apple-on-android)).
- Android uses the system back gesture, Material components, runtime permissions and
  notification channels where iOS has its own equivalents.
- Builds are distributed through Google Play internal testing instead of TestFlight.
