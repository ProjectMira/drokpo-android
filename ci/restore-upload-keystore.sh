#!/usr/bin/env bash
# Restores the Google Play *upload* keystore from repo secrets and proves it
# can sign, before the release build depends on it.
#
# Inputs (env, from repo secrets — created by ci/setup-release-signing.sh):
#   DROKPO_UPLOAD_KEYSTORE_B64     base64 of the .jks
#   DROKPO_UPLOAD_STORE_PASSWORD   keystore password
#   DROKPO_UPLOAD_KEY_ALIAS        key alias inside it
#   DROKPO_UPLOAD_KEY_PASSWORD     key password (same as the store password for PKCS12)
# Outputs:
#   $GITHUB_ENV     DROKPO_UPLOAD_STORE_FILE=<restored .jks> (read by app/build.gradle.kts)
#   $GITHUB_OUTPUT  signed=true|false, sha1=…, sha256=… (upload certificate fingerprints)
#
# The passwords are deliberately NOT written to $GITHUB_ENV: the build step
# reads them straight from the secrets, so no other step (or third-party
# action) ever has them in its environment.
#
# No secrets at all → warning + signed=false: the workflow still builds and
# archives an unsigned bundle (useful before the key exists), it just can't go
# to Play. Some-but-not-all secrets → error, since that's always a mistake.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-ProjectMira/drokpo-android}"
SETUP_HINT="Run ci/setup-release-signing.sh on the machine that holds the upload key (it re-sets all four secrets on $REPO without regenerating an existing key)."
DEST_DIR="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
KEYSTORE="$DEST_DIR/drokpo-upload.jks"

emit() { # emit <file-var-name> <line>
  local target="${!1:-}"
  if [ -n "$target" ]; then printf '%s\n' "$2" >> "$target"; else printf '[%s] %s\n' "$1" "$2"; fi
}
fail() { echo "::error title=Upload keystore::$1 $SETUP_HINT"; rm -f "$KEYSTORE"; exit 1; }

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"; JARSIGNER="$JAVA_HOME/bin/jarsigner"
else
  KEYTOOL="$(command -v keytool)"; JARSIGNER="$(command -v jarsigner)"
fi

names=(DROKPO_UPLOAD_KEYSTORE_B64 DROKPO_UPLOAD_STORE_PASSWORD DROKPO_UPLOAD_KEY_ALIAS DROKPO_UPLOAD_KEY_PASSWORD)
missing=()
for name in "${names[@]}"; do
  [ -n "${!name:-}" ] || missing+=("$name")
done
if [ "${#missing[@]}" -eq "${#names[@]}" ]; then
  echo "::warning title=Unsigned build::No upload keystore secrets are set, so this run builds an UNSIGNED bundle and skips Google Play. $SETUP_HINT"
  emit GITHUB_OUTPUT "signed=false"
  exit 0
fi
if [ "${#missing[@]}" -gt 0 ]; then
  fail "Some upload keystore secrets are set but these are missing: ${missing[*]}."
fi

# Same normalisation as the other secrets: tolerate literal "\n" escapes,
# CRLF and line-wrapped base64 (`base64` wraps at 76 columns on Linux).
printf '%s' "$DROKPO_UPLOAD_KEYSTORE_B64" | sed -e 's/\\r//g' -e 's/\\n//g' | tr -d '[:space:]' \
  | openssl base64 -d -A > "$KEYSTORE" 2>/dev/null || true
chmod 600 "$KEYSTORE"
if [ ! -s "$KEYSTORE" ]; then
  fail "DROKPO_UPLOAD_KEYSTORE_B64 (${#DROKPO_UPLOAD_KEYSTORE_B64} chars) does not base64-decode to anything — it must be the base64 of the .jks file, e.g.  openssl base64 -A -in ~/.drokpo-android/drokpo-upload.jks | gh secret set DROKPO_UPLOAD_KEYSTORE_B64 --repo $REPO"
fi

# 1. Store password + format. keytool's -storepass:env keeps the password out
#    of the process list.
if ! out="$("$KEYTOOL" -list -keystore "$KEYSTORE" -storepass:env DROKPO_UPLOAD_STORE_PASSWORD 2>&1)"; then
  case "$out" in
    *"password was incorrect"*|*"assword verification failed"*|*"failed to decrypt"*)
      fail "DROKPO_UPLOAD_STORE_PASSWORD does not open the keystore in DROKPO_UPLOAD_KEYSTORE_B64 (wrong password, or the two secrets come from different keystores)." ;;
    *"keystore format"*|*"not a keystore"*|*"Unrecognized"*|*"toDerInputStream"*|*"DerInputStream"*)
      fail "DROKPO_UPLOAD_KEYSTORE_B64 decodes to $(wc -c < "$KEYSTORE" | tr -d ' ') bytes that are not a Java keystore — the secret must hold the base64 of the .jks file." ;;
    *)
      fail "keytool could not read the keystore: $(tail -n 1 <<< "$out")." ;;
  esac
fi

# 2. Alias exists and holds a private key (not just a trusted certificate).
if ! out="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" -storepass:env DROKPO_UPLOAD_STORE_PASSWORD -alias "$DROKPO_UPLOAD_KEY_ALIAS" 2>&1)"; then
  listing="$("$KEYTOOL" -list -keystore "$KEYSTORE" -storepass:env DROKPO_UPLOAD_STORE_PASSWORD 2>/dev/null || true)"
  aliases="$(sed -nE 's/^([^,]+), .*(PrivateKeyEntry|trustedCertEntry).*/\1/p' <<< "$listing" | paste -sd, -)"
  fail "DROKPO_UPLOAD_KEY_ALIAS does not name an entry in the keystore (it contains: ${aliases:-no entries})."
fi
if ! grep -q 'PrivateKeyEntry' <<< "$out"; then
  fail "DROKPO_UPLOAD_KEY_ALIAS names a certificate-only entry; it must be the alias of the upload *private key*."
fi
sha1="$(sed -n 's/^[[:space:]]*SHA1:[[:space:]]*//p' <<< "$out" | sed -n 1p)"
sha256="$(sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' <<< "$out" | sed -n 1p)"

# 3. Key password. keytool -list never needs it, but the build does (AGP
#    calls KeyStore.getKey(alias, keyPassword)), so prove it by signing a
#    throwaway jar the same way — otherwise a wrong key password only shows up
#    as "Failed to read key … from store" after the whole R8 build.
probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
printf 'probe\n' > "$probe_dir/probe.txt"
(cd "$probe_dir" && python3 -m zipfile -c probe.jar probe.txt)
if ! out="$("$JARSIGNER" -keystore "$KEYSTORE" -storepass:env DROKPO_UPLOAD_STORE_PASSWORD \
      -keypass:env DROKPO_UPLOAD_KEY_PASSWORD "$probe_dir/probe.jar" "$DROKPO_UPLOAD_KEY_ALIAS" 2>&1)"; then
  case "$out" in
    # A wrong key password surfaces as "key associated with <alias> not a
    # private key" (jarsigner gets null back from KeyStore.getKey).
    *"not a private key"*|*"recover key"*|*"Cannot recover"*|*"password"*|*"padded"*)
      fail "DROKPO_UPLOAD_KEY_PASSWORD does not unlock the key '$DROKPO_UPLOAD_KEY_ALIAS' (for a PKCS12 keystore it must equal the store password)." ;;
    *)
      fail "jarsigner could not sign with the upload key: $(tail -n 1 <<< "$out")." ;;
  esac
fi

emit GITHUB_ENV "DROKPO_UPLOAD_STORE_FILE=$KEYSTORE"
emit GITHUB_OUTPUT "signed=true"
emit GITHUB_OUTPUT "sha1=$sha1"
emit GITHUB_OUTPUT "sha256=$sha256"
echo "Upload keystore restored and verified (alias '$DROKPO_UPLOAD_KEY_ALIAS')."
echo "Upload certificate SHA-1:   $sha1"
echo "Upload certificate SHA-256: $sha256"
