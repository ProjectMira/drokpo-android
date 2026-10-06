#!/usr/bin/env bash
# One-time setup of the Google Play *upload* key for Drokpo Android — the
# Android counterpart of the iOS distribution certificate in
# drokpo-app/ci/README-signing.md. Run it yourself, from a clone of the repo:
#
#   ci/setup-release-signing.sh [--no-firebase] [--no-github] [--repo OWNER/NAME]
#
# It is idempotent and NEVER replaces an existing key: once Play has seen a
# bundle signed with this key, every later upload must use the same one
# (a lost key means a support-ticket upload-key reset in Play Console).
#
#  1. Creates ~/.drokpo-android/drokpo-upload.jks (PKCS12, RSA 4096, random
#     password) and ~/.drokpo-android/keystore.properties (chmod 600) — or
#     reuses them if they already exist.
#  2. Writes the repo's gitignored keystore.properties, so
#     `./gradlew :app:bundleRelease` signs locally too.
#  3. Prints the upload certificate's SHA-1 / SHA-256 and registers both with
#     the Firebase Android app (Google sign-in and phone auth check them), then
#     refreshes app/google-services.json from Firebase.
#  4. Sets the release workflow's secrets on GitHub with `gh secret set … < file`
#     (values never echoed or passed as arguments): DROKPO_UPLOAD_KEYSTORE_B64,
#     DROKPO_UPLOAD_STORE_PASSWORD, DROKPO_UPLOAD_KEY_ALIAS,
#     DROKPO_UPLOAD_KEY_PASSWORD, GOOGLE_SERVICES_JSON.
#
# Needs: a JDK's keytool (JAVA_HOME or Android Studio's bundled JBR), openssl,
# and — unless skipped — `gh` (logged in, admin on the repo) and `firebase`
# (logged in with access to drokpo-backend).
# Back up ~/.drokpo-android/ (e.g. in a password manager) afterwards.
set -euo pipefail

REPO="ProjectMira/drokpo-android"
FIREBASE_PROJECT="drokpo-backend"
FIREBASE_APP_ID="1:246225694981:android:c84e277a94f02015ee076c"
KEY_ALIAS="upload"
SIGNING_DIR="$HOME/.drokpo-android"
KEYSTORE="$SIGNING_DIR/drokpo-upload.jks"
CREDS="$SIGNING_DIR/keystore.properties"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_PROPS="$ROOT/keystore.properties"
GS_JSON="$ROOT/app/google-services.json"

[ -f "$ROOT/app/build.gradle.kts" ] || { echo "error: $ROOT doesn't look like the drokpo-android repo." >&2; exit 1; }

do_firebase=true
do_github=true
while [ "$#" -gt 0 ]; do
  case "$1" in
    --no-firebase) do_firebase=false ;;
    --no-github) do_github=false ;;
    --repo) REPO="${2:?--repo needs OWNER/NAME}"; shift ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

step() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
umask 077

# ------------------------------------------------------------------ tools

find_keytool() {
  local candidate
  for candidate in \
    "${JAVA_HOME:+$JAVA_HOME/bin/keytool}" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
    "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
    "/opt/android-studio/jbr/bin/keytool" \
    "$HOME/android-studio/jbr/bin/keytool"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then echo "$candidate"; return; fi
  done
  # Last resort; on macOS /usr/bin/keytool is a stub that fails without a JDK.
  if command -v keytool >/dev/null && keytool -help >/dev/null 2>&1; then command -v keytool; return; fi
  die "keytool not found — install Android Studio (it bundles a JDK) or set JAVA_HOME."
}
KEYTOOL="$(find_keytool)"
command -v openssl >/dev/null || die "openssl not found."

if [ "$do_github" = true ]; then
  command -v gh >/dev/null || die "gh (GitHub CLI) not found — install it (brew install gh) or pass --no-github."
  gh auth status --hostname github.com >/dev/null 2>&1 || die "gh is not logged in — run: gh auth login"
  gh repo view "$REPO" --json name >/dev/null 2>&1 \
    || die "Can't see $REPO with gh — create the repo first, or pass --repo OWNER/NAME."
fi
if [ "$do_firebase" = true ]; then
  command -v firebase >/dev/null || die "firebase CLI not found — npm i -g firebase-tools, or pass --no-firebase."
fi

prop() { sed -n "s/^$1=//p" "$CREDS" | sed -n 1p; }

# ---------------------------------------------------------------- keystore

step "Upload keystore"
mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR"
if [ -f "$KEYSTORE" ]; then
  [ -f "$CREDS" ] || die "$KEYSTORE exists but $CREDS (its passwords) does not. Restore it from your backup — refusing to touch the key."
  echo "Reusing the existing key at $KEYSTORE (this script never replaces it)."
elif [ -f "$CREDS" ]; then
  die "$CREDS exists but $KEYSTORE is missing. Restore the .jks from your backup — a NEW key would not match the upload key Play already knows."
else
  password="$(openssl rand -hex 24)"
  DROKPO_KS_PASSWORD="$password" "$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Drokpo Android upload key, O=Drokpo" \
    -storepass:env DROKPO_KS_PASSWORD -keypass:env DROKPO_KS_PASSWORD >/dev/null 2>&1 \
    || die "keytool -genkeypair failed."
  chmod 600 "$KEYSTORE"
  # PKCS12 has a single password, so the key password equals the store password.
  {
    echo "# Drokpo Android upload key — created $(date -u +%Y-%m-%dT%H:%M:%SZ) by ci/setup-release-signing.sh."
    echo "# Back this file up together with drokpo-upload.jks; neither can be regenerated."
    echo "storeFile=$KEYSTORE"
    echo "storePassword=$password"
    echo "keyAlias=$KEY_ALIAS"
    echo "keyPassword=$password"
  } > "$CREDS"
  chmod 600 "$CREDS"
  unset password
  echo "Created $KEYSTORE and $CREDS."
fi

store_password="$(prop storePassword)"
key_alias="$(prop keyAlias)"
key_password="$(prop keyPassword)"
[ -n "$store_password" ] && [ -n "$key_alias" ] && [ -n "$key_password" ] \
  || die "$CREDS is missing storePassword/keyAlias/keyPassword."
listing="$(DROKPO_KS_PASSWORD="$store_password" "$KEYTOOL" -list -v -keystore "$KEYSTORE" \
  -storepass:env DROKPO_KS_PASSWORD -alias "$key_alias" 2>&1)" \
  || die "The passwords in $CREDS don't open $KEYSTORE (or alias '$key_alias' is missing)."
sha1="$(sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' <<< "$listing" | sed -n 1p)"
sha256="$(sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' <<< "$listing" | sed -n 1p)"
[ -n "$sha1" ] && [ -n "$sha256" ] || die "Could not read the certificate fingerprints from keytool."

# --------------------------------------------------- local release builds

step "Repo keystore.properties (local release builds)"
if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
   && ! git -C "$ROOT" check-ignore -q "$REPO_PROPS"; then
  die "$REPO_PROPS is not gitignored — refusing to write passwords where git could commit them."
fi
{
  echo "# Local release signing for ./gradlew :app:bundleRelease — gitignored, never commit."
  echo "# Written by ci/setup-release-signing.sh from $CREDS."
  echo "storeFile=$KEYSTORE"
  echo "storePassword=$store_password"
  echo "keyAlias=$key_alias"
  echo "keyPassword=$key_password"
} > "$work/keystore.properties"
if [ -f "$REPO_PROPS" ] && cmp -s "$work/keystore.properties" "$REPO_PROPS"; then
  echo "$REPO_PROPS is already up to date."
else
  cp "$work/keystore.properties" "$REPO_PROPS"
  chmod 600 "$REPO_PROPS"
  echo "Wrote $REPO_PROPS."
fi

step "Upload certificate fingerprints"
echo "SHA-1:   $sha1"
echo "SHA-256: $sha256"

# ---------------------------------------------------------------- Firebase

if [ "$do_firebase" = true ]; then
  step "Firebase Android app $FIREBASE_APP_ID ($FIREBASE_PROJECT)"
  existing="$(firebase apps:android:sha:list "$FIREBASE_APP_ID" --project "$FIREBASE_PROJECT" --json 2>&1)" \
    || die "firebase apps:android:sha:list failed — run 'firebase login' (with an account that can edit $FIREBASE_PROJECT). Output: $(tail -n 1 <<< "$existing")"
  existing="$(tr -d ':' <<< "$existing" | tr '[:upper:]' '[:lower:]')"
  for fingerprint in "$sha1" "$sha256"; do
    hash="$(tr -d ':' <<< "$fingerprint" | tr '[:upper:]' '[:lower:]')"
    if grep -q "$hash" <<< "$existing"; then
      echo "Already registered: $fingerprint"
    else
      firebase apps:android:sha:create "$FIREBASE_APP_ID" "$hash" --project "$FIREBASE_PROJECT" >/dev/null \
        || die "Could not register $fingerprint with Firebase."
      echo "Registered: $fingerprint"
    fi
  done

  # New fingerprints change the OAuth client list inside google-services.json,
  # so refresh the local copy (and, below, the CI secret) from Firebase.
  firebase apps:sdkconfig ANDROID "$FIREBASE_APP_ID" --project "$FIREBASE_PROJECT" \
    -o "$work/google-services.json" >/dev/null \
    || die "firebase apps:sdkconfig failed."
  python3 -m json.tool "$work/google-services.json" >/dev/null 2>&1 \
    || die "Firebase returned an invalid google-services.json."
  if [ -f "$GS_JSON" ] && cmp -s "$work/google-services.json" "$GS_JSON"; then
    echo "$GS_JSON is up to date."
  else
    cp "$work/google-services.json" "$GS_JSON"
    chmod 644 "$GS_JSON"
    echo "Updated $GS_JSON from Firebase."
  fi
fi

# ------------------------------------------------------------------ GitHub

if [ "$do_github" = true ]; then
  step "GitHub Actions secrets on $REPO"
  set_secret() { # set_secret NAME FILE — the value travels via stdin only
    gh secret set "$1" --repo "$REPO" < "$2" >/dev/null || die "gh secret set $1 failed (need admin on $REPO)."
    echo "Set $1"
  }
  openssl base64 -A -in "$KEYSTORE" > "$work/keystore.b64"
  printf '%s' "$store_password" > "$work/store-password"
  printf '%s' "$key_alias" > "$work/key-alias"
  printf '%s' "$key_password" > "$work/key-password"
  set_secret DROKPO_UPLOAD_KEYSTORE_B64 "$work/keystore.b64"
  set_secret DROKPO_UPLOAD_STORE_PASSWORD "$work/store-password"
  set_secret DROKPO_UPLOAD_KEY_ALIAS "$work/key-alias"
  set_secret DROKPO_UPLOAD_KEY_PASSWORD "$work/key-password"
  if [ -f "$GS_JSON" ]; then
    set_secret GOOGLE_SERVICES_JSON "$GS_JSON"
  else
    echo "Skipped GOOGLE_SERVICES_JSON: $GS_JSON doesn't exist (run without --no-firebase, or download it with"
    echo "  firebase apps:sdkconfig ANDROID $FIREBASE_APP_ID --project $FIREBASE_PROJECT -o app/google-services.json)."
  fi
fi

unset store_password key_password
step "Done"
cat <<EOF
Upload key: $KEYSTORE (alias '$key_alias'); passwords in $CREDS.
BACK UP $SIGNING_DIR now — Play only accepts bundles signed with this key.

Next (docs/RELEASE.md): create the app in Play Console, upload the first
signed AAB from the Release workflow's artifact by hand, add
PLAY_SERVICE_ACCOUNT_JSON, then register the Play *app signing* key's
SHA-1/SHA-256 with Firebase too.
EOF
