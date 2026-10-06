#!/usr/bin/env bash
# Checks what `:app:bundleRelease :app:assembleRelease` actually produced,
# before anything is archived or uploaded:
#   - the AAB and APK carry the versionCode/versionName CI asked for (the iOS
#     pipeline once shipped literal defaults because an override never reached
#     the binary — TestFlight then rejected the "duplicate" build);
#   - when signing was expected, both are signed by the upload key restored
#     from the secrets (app/build.gradle.kts silently falls back to unsigned if
#     the keystore file is missing, so "BUILD SUCCESSFUL" alone proves nothing).
#
# Usage: ci/verify-release-outputs.sh <versionCode> <versionName> <signed:true|false> [<upload cert SHA-256>]
# Outputs ($GITHUB_OUTPUT): aab=…, apk=…, mapping=… (repo-relative paths)
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <versionCode> <versionName> <signed:true|false> [<upload cert SHA-256>]" >&2
  exit 2
fi
want_code="$1"; want_name="$2"; signed="$3"; want_sha256="${4:-}"

AAB="app/build/outputs/bundle/release/app-release.aab"
MAPPING="app/build/outputs/mapping/release/mapping.txt"
if [ "$signed" = true ]; then APK="app/build/outputs/apk/release/app-release.apk"
else APK="app/build/outputs/apk/release/app-release-unsigned.apk"; fi

emit() { if [ -n "${GITHUB_OUTPUT:-}" ]; then printf '%s\n' "$1" >> "$GITHUB_OUTPUT"; else printf '[GITHUB_OUTPUT] %s\n' "$1"; fi; }
fail() { echo "::error title=Release outputs::$1"; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f local.properties ]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' local.properties)"
fi
build_tools="$sdk/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-37.0.0}"
AAPT2="$build_tools/aapt2"; APKSIGNER="$build_tools/apksigner"
[ -x "$AAPT2" ] || fail "aapt2 not found at $AAPT2 (set ANDROID_HOME / ANDROID_BUILD_TOOLS_VERSION)."
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jarsigner" ]; then
  JARSIGNER="$JAVA_HOME/bin/jarsigner"; KEYTOOL="$JAVA_HOME/bin/keytool"
else
  JARSIGNER="$(command -v jarsigner)"; KEYTOOL="$(command -v keytool)"
fi

[ -s "$AAB" ] || fail "$AAB was not produced."
[ -s "$APK" ] || fail "$APK was not produced (signed=$signed)."

badging_field() { # badging_field <badging output> <field>
  sed -n "1s/.* $2='\([^']*\)'.*/\1/p" <<< "$1"
}
check_version() { # check_version <label> <apk>
  local badging code name
  badging="$("$AAPT2" dump badging "$2" 2>&1)" || fail "aapt2 could not read the $1: $(tail -n 1 <<< "$badging")"
  code="$(badging_field "$badging" versionCode)"; name="$(badging_field "$badging" versionName)"
  [ "$code" = "$want_code" ] || fail "$1 has versionCode '$code', expected $want_code — the -Pdrokpo.versionCode override did not reach the binary (check app/build.gradle.kts)."
  [ "$name" = "$want_name" ] || fail "$1 has versionName '$name', expected $want_name."
  echo "$1: versionCode $code, versionName $name"
}

check_version "APK" "$APK"

# aapt2 can't open an .aab directly, but the base module's manifest and
# resource table are the same protobuf files a "proto APK" holds, just at
# different paths — repackage those two and aapt2 reads them fine.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
python3 - "$AAB" "$work/base-proto.apk" <<'PY'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as aab, zipfile.ZipFile(dst, "w") as apk:
    apk.writestr("AndroidManifest.xml", aab.read("base/manifest/AndroidManifest.xml"))
    apk.writestr("resources.pb", aab.read("base/resources.pb"))
PY
check_version "AAB" "$work/base-proto.apk"

norm() { tr -d ':[:space:]' <<< "$1" | tr '[:upper:]' '[:lower:]'; }

if [ "$signed" = true ]; then
  # jarsigner -verify exits 0 even for an UNSIGNED jar, so match its verdict.
  verdict="$("$JARSIGNER" -verify "$AAB" 2>&1 || true)"
  grep -q '^jar verified' <<< "$verdict" || fail "$AAB is not signed (jarsigner: $(grep -E 'jar|error' <<< "$verdict" | sed -n 1p))."
  certs="$("$KEYTOOL" -printcert -jarfile "$AAB" 2>&1 || true)"
  aab_sha256="$(sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' <<< "$certs" | sed -n 1p)"
  [ -n "$aab_sha256" ] || fail "keytool could not read the signer of $AAB: $(tail -n 1 <<< "$certs")"
  certs="$("$APKSIGNER" verify --print-certs "$APK" 2>&1 || true)"
  # apksigner prints "Signer #1 certificate …" (older) or "V2 Signer: certificate …" (newer).
  apk_sha256="$(sed -nE 's/^(Signer #1|V[0-9.]+ Signer:) certificate SHA-256 digest: //p' <<< "$certs" | sed -n 1p)"
  [ -n "$apk_sha256" ] || fail "$APK does not verify with apksigner: $(grep -v '^WARNING' <<< "$certs" | tail -n 1)"
  if [ -n "$want_sha256" ]; then
    [ "$(norm "$aab_sha256")" = "$(norm "$want_sha256")" ] || fail "$AAB is signed by $aab_sha256, not the upload key $want_sha256."
    [ "$(norm "$apk_sha256")" = "$(norm "$want_sha256")" ] || fail "$APK is signed by $apk_sha256, not the upload key $want_sha256."
  fi
  echo "AAB and APK signed by the upload key (SHA-256 $aab_sha256)."
else
  echo "Unsigned build (no upload keystore) — fine for artifacts, not uploadable to Google Play."
fi

if [ -s "$MAPPING" ]; then
  emit "mapping=$MAPPING"
else
  echo "::warning::$MAPPING is missing — Play crash reports for this build won't be deobfuscated."
  emit "mapping="
fi
emit "aab=$AAB"
emit "apk=$APK"
