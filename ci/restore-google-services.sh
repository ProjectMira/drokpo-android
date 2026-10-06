#!/usr/bin/env bash
# Restores app/google-services.json from the GOOGLE_SERVICES_JSON repo secret.
#
# Usage (from the repo root):
#   GOOGLE_SERVICES_JSON=... ci/restore-google-services.sh [--required]
#
# Without --required (android-ci.yml) a missing secret is only a warning: the
# app module builds without the file and the app shows its "Firebase not
# configured" notice, so forks and fresh clones still get a green CI run.
# With --required (release.yml) it is an error — a store build without
# Firebase would be useless.
#
# The file is gitignored; get the real one with
#   firebase apps:sdkconfig ANDROID 1:246225694981:android:c84e277a94f02015ee076c \
#     --project drokpo-backend -o app/google-services.json
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-ProjectMira/drokpo-android}"
PACKAGE="app.drokpo.android"
FIREBASE_APP_ID="1:246225694981:android:c84e277a94f02015ee076c"
OUT="app/google-services.json"
FIX="Re-add it with the exact file bytes:  gh secret set GOOGLE_SERVICES_JSON --repo $REPO < $OUT   (download the file with: firebase apps:sdkconfig ANDROID $FIREBASE_APP_ID --project drokpo-backend -o $OUT)"

required=false
case "${1:-}" in
  --required) required=true ;;
  "") ;;
  *) echo "usage: $0 [--required]" >&2; exit 2 ;;
esac

if [ ! -d app ]; then
  echo "::error::Run $0 from the repository root (no app/ directory here)."
  exit 1
fi

raw="${GOOGLE_SERVICES_JSON:-}"
if [ -z "$raw" ]; then
  if [ "$required" = true ]; then
    echo "::error title=GOOGLE_SERVICES_JSON missing::Release builds need Firebase config, but the GOOGLE_SERVICES_JSON secret is not set (or is not available to this run — secrets are withheld from pull requests opened from forks). $FIX"
    exit 1
  fi
  echo "::warning title=GOOGLE_SERVICES_JSON missing::Building without app/google-services.json — the APK shows the 'Firebase not configured' notice instead of signing in. $FIX"
  exit 0
fi

# A JSON *object* — a JSON-encoded string (the whole file stored as one string
# literal) parses too, but isn't the file yet.
is_json() {
  printf '%s' "$1" | python3 -c 'import json, sys; sys.exit(0 if isinstance(json.load(sys.stdin), dict) else 1)' >/dev/null 2>&1
}

# Secrets get mangled in the same ways as the iOS GoogleService-Info.plist
# (see drokpo-app/.github/workflows/testflight.yml): pasted with CRLF endings,
# stored base64-wrapped, or saved from a .env / JSON-escaped CLI value with
# literal "\n" escape sequences. Normalise in order of increasing
# invasiveness and stop at the first form that parses, so a correct secret is
# written back byte-for-byte (minus carriage returns).
json="$(printf '%s' "$raw" | tr -d '\r')"
if ! is_json "$json"; then
  # base64 of the file (e.g. `base64 -i app/google-services.json | gh secret set ...`).
  decoded="$(printf '%s' "$raw" | tr -d '[:space:]' | openssl base64 -d -A 2>/dev/null | tr -d '\r' || true)"
  if is_json "$decoded"; then
    json="$decoded"
    echo "GOOGLE_SERVICES_JSON was base64-encoded; decoded it."
  fi
fi
if ! is_json "$json"; then
  # Literal escape sequences between tokens. google-services.json holds only
  # ids, keys and URLs — no string value legitimately contains "\n" — so
  # deleting them is safe.
  unescaped="$(printf '%s' "$json" | sed -e 's/\\r//g' -e 's/\\n//g' -e 's/\\t//g')"
  if ! is_json "$unescaped"; then
    # The whole file stored as one JSON string literal: "{\"project_info\":...}".
    unescaped="$(printf '%s' "$unescaped" | sed -e 's/^[[:space:]]*"//' -e 's/"[[:space:]]*$//' -e 's/\\"/"/g')"
  fi
  if is_json "$unescaped"; then
    json="$unescaped"
    echo "GOOGLE_SERVICES_JSON contained escape sequences; unescaped it."
  fi
fi

printf '%s\n' "$json" > "$OUT"

# Lint before the build depends on it — the google-services Gradle plugin's
# own error for a broken file is a long stack trace.
if ! err="$(python3 -m json.tool "$OUT" 2>&1 >/dev/null)"; then
  # Non-sensitive diagnostics only: the length and the parser's message, which
  # names a position but never echoes file content.
  echo "::error::GOOGLE_SERVICES_JSON is not valid JSON after normalisation (secret is ${#raw} chars; parser said: $(printf '%s' "$err" | tail -n 1)). $FIX"
  rm -f "$OUT"
  exit 1
fi

# A google-services.json for another app (e.g. a different Firebase project,
# or the old file before the Android app was registered) fails later with
# "No matching client found for package name". Catch it here, with the fix.
if ! summary="$(python3 - "$OUT" "$PACKAGE" <<'PY'
import json, sys
path, package = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
if not isinstance(data, dict) or "client" not in data:
    print("the JSON has no 'client' list — it is not a google-services.json")
    sys.exit(1)
names = []
for client in data.get("client") or []:
    info = client.get("client_info", {})
    name = info.get("android_client_info", {}).get("package_name")
    names.append(str(name))
    if name == package:
        project = data.get("project_info", {}).get("project_id", "?")
        print(f"project {project}, app {info.get('mobilesdk_app_id', '?')}")
        sys.exit(0)
print(f"it has no client for package {package} (found: {', '.join(names) or 'none'})")
sys.exit(1)
PY
)"; then
  echo "::error::GOOGLE_SERVICES_JSON is valid JSON but $summary. $FIX"
  rm -f "$OUT"
  exit 1
fi

echo "Restored $OUT ($summary)."
