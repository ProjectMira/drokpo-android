# Releasing Drokpo Android

The Android twin of the iOS TestFlight pipeline (`drokpo-app/.github/workflows/testflight.yml`,
`drokpo-app/ci/README-signing.md`): every push to `main` or `dev` becomes a signed build on
the Google Play **internal testing** track, the same way every iOS push to `main` becomes a
TestFlight build.

| Workflow | Runs on | Does |
|---|---|---|
| [`android-ci.yml`](../.github/workflows/android-ci.yml) | every push, every PR, manual | unit tests, Android lint, debug APK artifact (+ test/lint reports) |
| [`release.yml`](../.github/workflows/release.yml) | push to `main`/`dev` (not docs-only pushes), manual | signed AAB + APK → Google Play (internal by default), artifacts, git tag `v<versionName>-android.<versionCode>` |

The release job checks every credential before the ten-minute R8 build, and each check
fails with an `::error` that says how to fix it. That is the same "fail fast with the fix"
approach the iOS workflow uses for its `.p8` key. Pieces:

| Piece | What it guards against |
|---|---|
| `ci/restore-google-services.sh` | secret stored base64-wrapped, with CRLFs, `\n` escapes or as a JSON string; a `google-services.json` for the wrong package |
| `ci/restore-upload-keystore.sh` | half-set secrets, a base64 secret that isn't a keystore, wrong store password, wrong alias, wrong key password (proved by signing a probe jar, exactly as AGP will) |
| `ci/play_publish.py verify` | bad or revoked service-account key, API not enabled, account not invited, app not created / no first upload, a versionCode Play already has |
| `ci/verify-release-outputs.sh` | the `-Pdrokpo.versionCode` override not reaching the binary; a silently **unsigned** bundle (`app/build.gradle.kts` falls back to unsigned when the keystore file is missing) |
| `ci/play_publish.py upload` | maps Play's errors (draft app, duplicate versionCode, wrong upload key, review required, concurrent edit) to the fix |

`ci/tests/` runs the publisher against an offline fake Google endpoint (`python3 -m unittest
discover -s ci/tests`). CI runs these tests too.

---

## Secrets and variables

Repo → Settings → Secrets and variables → Actions.

| Secret | Contents | Set by |
|---|---|---|
| `GOOGLE_SERVICES_JSON` | `app/google-services.json`. Optional for CI, **required** for releases | `ci/setup-release-signing.sh` |
| `DROKPO_UPLOAD_KEYSTORE_B64` | base64 of the upload keystore | `ci/setup-release-signing.sh` |
| `DROKPO_UPLOAD_STORE_PASSWORD` | keystore password | `ci/setup-release-signing.sh` |
| `DROKPO_UPLOAD_KEY_ALIAS` | key alias (`upload`) | `ci/setup-release-signing.sh` |
| `DROKPO_UPLOAD_KEY_PASSWORD` | key password (equals the store password for PKCS12) | `ci/setup-release-signing.sh` |
| `PLAY_SERVICE_ACCOUNT_JSON` | JSON key of the Play publishing service account | you (step 6 below) |

| Variable (optional) | Effect |
|---|---|
| `PLAY_RELEASE_STATUS` | `draft` makes **push** releases drafts. Needed until the app's first release has been rolled out (see "Draft apps" below). Delete it afterwards. |
| `PLAY_CHANGES_NOT_SENT_FOR_REVIEW` | `true` commits with `changesNotSentForReview`. Set it only when Play asks for it, then press "Send changes for review" in Play Console yourself. |

What happens when secrets are missing:

| Missing | Release run |
|---|---|
| keystore secrets | builds **unsigned**, uploads artifacts, `::warning`, skips Play |
| `PLAY_SERVICE_ACCOUNT_JSON` | builds **signed**, uploads artifacts, `::warning`, skips Play |
| `GOOGLE_SERVICES_JSON` | fails. A store build without Firebase can't sign anyone in. (`android-ci.yml` only warns.) |

---

## One-time setup

### 1. GitHub repo

Create `ProjectMira/drokpo-android` (public, default branch `main`), push, and create the
`dev` branch for pre-release work. Settings → Actions → General → Workflow permissions can
stay at "Read repository contents". `release.yml` asks for `contents: write` itself, to push
tags.

### 2. Upload key (run on your machine; it creates real credentials)

```sh
ci/setup-release-signing.sh          # --no-firebase / --no-github to skip those parts
```

The script needs `gh auth login` and `firebase login`. It is idempotent and **refuses to
replace an existing key**. It does the following:

- creates `~/.drokpo-android/drokpo-upload.jks` (PKCS12, RSA 4096, random password) and
  `~/.drokpo-android/keystore.properties` (chmod 600);
- writes the repo's gitignored `keystore.properties`, so local `./gradlew :app:bundleRelease`
  is signed too;
- prints the upload certificate's SHA-1/SHA-256 and registers both with the Firebase Android
  app `1:246225694981:android:c84e277a94f02015ee076c`, then refreshes
  `app/google-services.json`;
- sets the five secrets above with `gh secret set … < file`. Values are never echoed or put
  on a command line.

**Back up `~/.drokpo-android/`** (for example in a password manager). Once Play has seen
this key, it accepts uploads signed only with it. Losing the key means requesting an
upload-key reset (step 9).

### 3. Create the app in Play Console

[Play Console](https://play.google.com/console) → **Create app**: name *Drokpo*, default
language, App, Free. Then work through the Dashboard's "Set up your app" tasks: privacy
policy `https://drokpo-backend.web.app/privacy.html`, app access (reviewers need a test
login, as with App Store review), ads, content rating, target audience, data safety. Internal
testing doesn't need all of them, but the first closed, open or production release does.

The package name `app.drokpo.android` is fixed by the first upload and can never change.

### 4. Play App Signing

New apps are enrolled automatically. When you create the first release, keep **"Use a
Google-generated key"** (Test and release → App integrity shows it later). Google holds the
*app signing key* that devices see. Our keystore is only the *upload key* that proves a
bundle came from us. Because of this split, Firebase needs both keys' fingerprints (step 8).

### 5. First upload — by hand (the API can't do it)

The Play Developer API can neither create an app nor accept its very first bundle. Until
one bundle has been uploaded in the Console, every API call returns **404 "Package not
found"**. So:

1. With the keystore secrets set and **no** `PLAY_SERVICE_ACCOUNT_JSON` yet, run
   Actions → **Release** → Run workflow (or push to `dev`). The run builds signed, skips Play
   with a warning, and uploads the artifact `drokpo-release-<version>-<code>`.
2. Download it and unzip it to get `drokpo-<version>-<code>.aab`.
3. Play Console → Test and release → Testing → **Internal testing** → Create new release →
   upload that AAB → name it → **Save, review, roll out**.

Using CI's own artifact means CI numbering (`run_number + VERSION_CODE_OFFSET`) can never
collide with this upload, so the offset stays `0`. If you upload a locally built bundle
instead, raise `VERSION_CODE_OFFSET` above its versionCode.

### 6. Testers

Internal testing → **Testers** → create an email list (up to 100 Google accounts) → save →
copy the **opt-in link** and send it to testers. They accept, then install from Play.
Internal-testing builds are available within minutes and skip review.

### 7. Service account for CI uploads

In any Google Cloud project you own. `drokpo-backend` is fine. Use the gcloud CLI account,
not ADC, which points at the wrong account on this machine.

```sh
gcloud services enable androidpublisher.googleapis.com --project drokpo-backend
gcloud iam service-accounts create play-publisher \
  --display-name "Google Play publisher (GitHub Actions)" --project drokpo-backend
gcloud iam service-accounts keys create play-service-account.json \
  --iam-account play-publisher@drokpo-backend.iam.gserviceaccount.com
```

The service account needs **no** Cloud IAM roles. Its permissions live in Play Console. If
the org policy `iam.disableServiceAccountKeyCreation` blocks key creation, create the key
in a project without that policy.

Play Console → **Users and permissions** → Invite new users → the service account's email.
Under **App permissions**, add *Drokpo* and grant:

- **Release to testing tracks**: internal, alpha (closed) and beta (open);
- **Release to production, exclude devices, and use Play App Signing**: only if CI should
  ever run `track=production`;
- **View app information (read-only)**.

Leave account permissions empty. Then:

```sh
gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo ProjectMira/drokpo-android < play-service-account.json
rm play-service-account.json   # or keep it only in a password manager
```

Push to `dev`. The "Verify Google Play credentials" step should print `credentials OK` and
list the current tracks. A fresh invite can take a while to take effect. If you get a 403
right after inviting, retry later.

### 8. Register the Play **app signing** key with Firebase

Devices see the app signing key, not our upload key. Without this step, Google sign-in and
phone auth fail on Play-installed builds even though they work on CI artifacts:

- Google sign-in fails with *DEVELOPER_ERROR* / "No credentials available";
- phone auth falls back to reCAPTCHA or fails app verification.

Play Console → Test and release → **App integrity** → App signing → copy the *App signing
key certificate* SHA-1 and SHA-256, then:

```sh
firebase apps:android:sha:create 1:246225694981:android:c84e277a94f02015ee076c <SHA-1>   --project drokpo-backend
firebase apps:android:sha:create 1:246225694981:android:c84e277a94f02015ee076c <SHA-256> --project drokpo-backend
firebase apps:sdkconfig ANDROID 1:246225694981:android:c84e277a94f02015ee076c --project drokpo-backend -o /tmp/gs.json \
  && mv /tmp/gs.json app/google-services.json \
  && gh secret set GOOGLE_SERVICES_JSON --repo ProjectMira/drokpo-android < app/google-services.json
```

Fingerprints Firebase should end up with:

| Key | Covers | Registered by |
|---|---|---|
| debug (`app/debug.keystore`, SHA-1 `06:96:42:71:E4:CF:25:75:FF:01:5B:61:D8:C4:6D:BA:13:F6:15:79`) | debug builds, CI debug APKs | already done |
| upload key | release APK/AAB artifacts sideloaded from CI | `ci/setup-release-signing.sh` |
| Play app signing key | everything installed from Google Play | you, here |

For phone auth on Android, Firebase verifies the app with Play Integrity, which uses the
SHA-256. Also make sure the **Phone** provider is enabled (Authentication → Sign-in method),
as for iOS.

If share links should open the app (`https://drokpo-backend.web.app/s/…`, verified App
Links), the same SHA-256 values must also be in
`drokpo-backend/public/.well-known/assetlinks.json`. That file doesn't exist yet.

### 9. Lost upload key / rotation

Play Console → App integrity → **Request upload key reset**. Then generate a new key: move
`~/.drokpo-android/` aside, re-run `ci/setup-release-signing.sh`, and upload the `.pem` that
Play asks for. Register the new upload fingerprints with Firebase. The app signing key,
which users see, never changes.

---

## Day to day

- **Push to `dev`** → internal testing, release name like `1.1 (42) dev@abc1234`.
- **Push to `main`** → internal testing, `1.1 (43) main@def5678`.

  Both branches share the internal track, so the newest push wins, and the release name
  tells testers which branch they're on. Docs-only pushes (`**.md`, `docs/**`) don't
  release.
- **Promote** a tested build in Play Console (internal → closed/open/production → "Promote
  release"), or run the workflow manually with `track` = `alpha`, `beta` or `production`.
  `production` + `completed` is a 100% rollout. Use the Console when you want a staged
  rollout.
- Each successful upload pushes the tag `v<versionName>-android.<versionCode>`, for example
  `v1.1-android.42`. iOS uses `v<version>-build.<n>`.
- The Release run's **artifacts** (AAB, APK, R8 mapping) are kept for 90 days. The mapping is
  also attached to the Play release, so Play Console / Android vitals show deobfuscated
  crashes.

### Version numbers

- **versionName** = `versionName` in `app/build.gradle.kts`. Keep it in step with
  `MARKETING_VERSION` in `drokpo-app/project.yml` (both `1.1` today), and bump both when a
  new release cycle starts, so testers and reviewers see the same version on both platforms.
- **versionCode** = `GITHUB_RUN_NUMBER + VERSION_CODE_OFFSET` (`release.yml` `env`). Run
  numbers are per workflow, so `main` and `dev` never reuse a number. The local default is 1.
  - Raise `VERSION_CODE_OFFSET` only when `run_number` would restart below a versionCode
    Play already has. That happens if `release.yml` is renamed or recreated, the repo moves,
    or a higher versionCode was uploaded by hand. Set it so that `run_number + offset` is
    above the highest code in Play Console → **App bundle explorer**. The verify step checks
    this and tells you the minimum.
  - **Re-running** a run keeps its run number, so it keeps its versionCode. Re-running a run
    that already uploaded fails with "versionCode already used". Start a new run instead
    (Run workflow, or push). Re-running a run that failed *before* the upload is fine.

### Draft apps: use `status=draft`

Until an app has had a release rolled out, Play treats it as a **draft app** and rejects
`completed` releases with *"Only releases with status draft may be created on draft app."*
Rolling out the manual first upload (step 5) normally ends this state. If you hit the error
anyway (for example, the first release was saved but not rolled out):

- run the workflow manually with **status = draft**, or
- set the repo variable `PLAY_RELEASE_STATUS=draft` so pushes do the same;

then roll the draft out from Play Console. Once the app is out of draft, delete the
variable.

---

## Sign in with Apple on Android

Android has no native Apple sign-in. Firebase runs Apple's web OAuth flow, and Apple has to
know about that web flow. iOS keeps working unchanged: it uses the bundle id
`app.drokpo.ios` natively. One-time setup:

1. [Apple Developer → Identifiers](https://developer.apple.com/account/resources/identifiers/list)
   → **+** → **Services IDs**, for example `app.drokpo.signin`. Enable *Sign in with Apple*
   → Configure:
   - primary App ID: `app.drokpo.ios`;
   - domain: `drokpo-backend.firebaseapp.com`;
   - return URL: `https://drokpo-backend.firebaseapp.com/__/auth/handler`.
2. Keys → **+** → enable *Sign in with Apple* (same primary App ID) → download the `.p8`.
   Note the Key ID. The Team ID is `W66Z24ZPWQ`.
3. Firebase console → Authentication → Sign-in method → **Apple** → fill in *Services ID*,
   and under "OAuth code flow configuration" the Team ID, Key ID and private key.
4. Optional: Apple's private email relay. Register `noreply@drokpo-backend.firebaseapp.com`
   (or your sender) under Certificates, Identifiers & Profiles → More → *Sign in with Apple
   for Email Communication*, so "Hide my email" users get mail.
5. In `app/build.gradle.kts`, flip
   `buildConfigField("boolean", "APPLE_SIGN_IN_ENABLED", "true")` and push. The button
   appears on the sign-in screen.

## Push notifications (FCM)

Android needs nothing extra. iOS needs an APNs key uploaded to Firebase; Android delivers
FCM directly. `google-services.json` plus `firebase-messaging` is the whole client setup,
and the backend's Cloud Functions already send through FCM HTTP v1 for the same
`drokpo-backend` project. The app registers its token with the backend just like iOS does.
On Android 13+, the app asks for the `POST_NOTIFICATIONS` runtime permission.

---

## Troubleshooting

| Error (in the failing step) | Fix |
|---|---|
| `GOOGLE_SERVICES_JSON … no client for package app.drokpo.android` | wrong Firebase app. Re-download with `firebase apps:sdkconfig ANDROID 1:246225694981:android:c84e277a94f02015ee076c --project drokpo-backend -o app/google-services.json` |
| `DROKPO_UPLOAD_STORE_PASSWORD does not open the keystore` / `…KEY_ALIAS does not name an entry` / `…KEY_PASSWORD does not unlock` | the secrets come from different keystores. Re-run `ci/setup-release-signing.sh` on the machine that holds `~/.drokpo-android/` |
| `Could not get an access token … invalid_grant` | the service-account key was deleted or rotated. Create a new key and update `PLAY_SERVICE_ACCOUNT_JSON` |
| `Google Play Android Developer API is disabled` | `gcloud services enable androidpublisher.googleapis.com --project <project>` |
| `service account lacks access` (403) | invite it in Play Console → Users and permissions (step 7) |
| `app.drokpo.android not found` (404) | create the app and make the first upload by hand (steps 3 and 5) |
| `versionCode would collide` / `already used` | a re-run (start a new run), or raise `VERSION_CODE_OFFSET` |
| `AAB signed with the wrong upload key` | the secrets hold a different key than the one Play registered. Restore the original, or request an upload-key reset |
| `app is still a draft` | `status=draft` / `PLAY_RELEASE_STATUS=draft` (see above) |
| `changes need manual review submission` | set `PLAY_CHANGES_NOT_SENT_FOR_REVIEW=true`, re-run, then send for review in the Console |
| `Another Play edit was committed while this one was open` | a `main` and a `dev` release, or a Console edit, overlapped. Re-run; nothing was published |
| sdkmanager can't install `platforms;android-37.0` | the runner image's cmdline-tools are too old. The setup step updates them once and retries. If it still fails, the API level isn't available on GitHub's runner yet |
