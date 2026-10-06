#!/usr/bin/env python3
"""Google Play Developer API (androidpublisher v3) client for .github/workflows/release.yml.

    play_publish.py verify --package app.drokpo.android [--version-code N]
    play_publish.py upload --package app.drokpo.android --aab app-release.aab \
        [--mapping mapping.txt] --track internal --status completed \
        --release-name "1.1 (42) dev@abc1234" --version-code 42

`verify` is the Android twin of the iOS workflow's "Verify App Store Connect
credentials" step: it mints a token and opens + deletes an edit, so a wrong or
under-privileged service account fails in seconds with the fix spelled out,
instead of after a ten-minute R8 build. With --version-code it also checks the
number against every bundle Play already has, so a VERSION_CODE_OFFSET that's
too low (or a re-run of a run that already uploaded) is caught up front.

`upload` does the whole release in one edit: upload the AAB, attach the R8
mapping, put the version code on the track, commit.

Credentials come from the PLAY_SERVICE_ACCOUNT_JSON env var (the service
account's JSON key; raw, base64-wrapped or JSON-escaped are all accepted).
Standard library only, plus the `openssl` CLI for the RS256 signature — the
runner needs no pip installs and no third-party action ever sees the key.
PLAY_CHANGES_NOT_SENT_FOR_REVIEW=true commits with changesNotSentForReview
(needed once Play says so — see docs/RELEASE.md).
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

API_ROOT = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_ROOT = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
TOKEN_URI = "https://oauth2.googleapis.com/token"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
REPO = os.environ.get("GITHUB_REPOSITORY", "ProjectMira/drokpo-android")
DOCS = "docs/RELEASE.md"
SECRET_FIX = (
    f"gh secret set PLAY_SERVICE_ACCOUNT_JSON --repo {REPO} < play-service-account.json"
)


class PlayError(Exception):
    def __init__(self, code, message, reason=""):
        super().__init__(message)
        self.code = code
        self.message = message
        self.reason = reason

    @classmethod
    def from_body(cls, code, raw):
        try:
            err = json.loads(raw).get("error", {})
        except (ValueError, AttributeError):
            return cls(code, raw.decode("utf-8", "replace").strip()[:500] or f"HTTP {code}")
        if isinstance(err, str):  # OAuth token endpoint shape
            return cls(code, f"{err}: {json.loads(raw).get('error_description', '')}", err)
        reasons = [e.get("reason", "") for e in err.get("errors", []) if isinstance(e, dict)]
        return cls(code, err.get("message", f"HTTP {code}"), ",".join(filter(None, reasons)) or err.get("status", ""))


class Fatal(Exception):
    """An error already explained for humans; main() prints it as ::error."""

    def __init__(self, title, message):
        super().__init__(message)
        self.title = title


def gh_escape(text):
    return str(text).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def log(msg):
    print(msg, flush=True)


# ---------------------------------------------------------------- credentials


def load_service_account(raw):
    """Parses the secret in whichever form it was stored, mirroring the
    normalisation the iOS workflow applies to its .p8 key."""
    raw = (raw or "").strip()
    if not raw:
        raise Fatal("PLAY_SERVICE_ACCOUNT_JSON missing", f"The secret is empty. {SECRET_FIX}")
    candidates = [(raw, True)]
    try:
        candidates.append((base64.b64decode("".join(raw.split()), validate=True).decode("utf-8"), True))
    except (ValueError, UnicodeDecodeError):
        pass
    # Saved from a .env or a JSON-escaped CLI value: literal "\n" sequences
    # between tokens. Turning them into real newlines also puts raw newlines
    # inside private_key, which strict=False accepts; the PEM is rebuilt below.
    candidates.append((raw.replace("\\r", "").replace("\r", "").replace("\\n", "\n"), False))
    for text, strict in candidates:
        try:
            data = json.loads(text, strict=strict)
            if isinstance(data, str):  # the whole file stored as one JSON string
                data = json.loads(data, strict=False)
        except ValueError:
            continue
        if isinstance(data, dict):
            break
    else:
        raise Fatal(
            "PLAY_SERVICE_ACCOUNT_JSON unreadable",
            f"The secret ({len(raw)} chars) is not JSON, base64 JSON, or escaped JSON. "
            f"Store the service account's JSON key file exactly as downloaded: {SECRET_FIX}",
        )
    kind = data.get("type")
    if kind != "service_account" or not data.get("client_email") or not data.get("private_key"):
        raise Fatal(
            "PLAY_SERVICE_ACCOUNT_JSON is not a service-account key",
            f"The secret parses as JSON but is a {kind or 'non-credential'} file, not a service-account key "
            "(it needs type=service_account, client_email and private_key — an OAuth client secret or "
            f"google-services.json won't work). Create a JSON key for the Play service account ({DOCS}) and {SECRET_FIX}",
        )
    return data


def normalise_pem(text):
    """Rebuilds a canonical PEM: secrets often arrive with CRLFs, literal "\\n"
    escapes or newlines collapsed to spaces, all of which openssl rejects."""
    text = text.replace("\\r", "").replace("\r", "").replace("\\n", "\n")
    m = re.search(r"-----BEGIN ([A-Z ]*PRIVATE KEY)-----(.*?)-----END \1-----", text, re.S)
    if not m:
        raise Fatal(
            "Service account private key unreadable",
            f"private_key in PLAY_SERVICE_ACCOUNT_JSON has no PEM markers. {SECRET_FIX}",
        )
    label, body = m.group(1), re.sub(r"\s+", "", m.group(2))
    lines = [body[i:i + 64] for i in range(0, len(body), 64)]
    return f"-----BEGIN {label}-----\n" + "\n".join(lines) + f"\n-----END {label}-----\n"


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def sign_rs256(message, pem):
    keydir = tempfile.mkdtemp(prefix="play-key-")
    path = os.path.join(keydir, "key.pem")
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(pem)
        proc = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", path],
            input=message, capture_output=True, check=False,
        )
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
        os.rmdir(keydir)
    if proc.returncode != 0 or not proc.stdout:
        detail = (proc.stderr.decode("utf-8", "replace").strip().splitlines() or ["no output"])[-1]
        raise Fatal(
            "Service account private key unusable",
            f"openssl could not sign with private_key from PLAY_SERVICE_ACCOUNT_JSON ({detail}). "
            f"The key is truncated or corrupted — create a new JSON key and {SECRET_FIX}",
        )
    return proc.stdout


def mint_token(sa):
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    if sa.get("private_key_id"):
        header["kid"] = sa["private_key_id"]
    claims = {"iss": sa["client_email"], "scope": SCOPE, "aud": TOKEN_URI, "iat": now, "exp": now + 3600}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(claims).encode())}"
    jwt = f"{signing_input}.{b64url(sign_rs256(signing_input.encode('ascii'), normalise_pem(sa['private_key'])))}"
    form = urllib.parse.urlencode(
        {"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": jwt}
    ).encode()
    try:
        resp = http("POST", TOKEN_URI, data=form, content_type="application/x-www-form-urlencoded")
    except PlayError as e:
        hint = (
            "the key was deleted/rotated in Google Cloud, or the service account itself was deleted"
            if "invalid_grant" in e.message
            else "unexpected token endpoint response"
        )
        raise Fatal(
            "Google rejected the service account",
            f"Could not get an access token for {sa['client_email']} (HTTP {e.code}: {e.message}) — {hint}. "
            f"Create a new JSON key (IAM → Service accounts → Keys) and {SECRET_FIX}",
        ) from e
    token = resp.get("access_token")
    if not token:
        raise Fatal("Google rejected the service account", "Token endpoint returned no access_token.")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::add-mask::{token}", flush=True)
    return token


# ----------------------------------------------------------------------- HTTP


def http(method, url, token=None, body=None, data=None, content_type=None, timeout=120, attempts=3):
    headers = {"User-Agent": "drokpo-android-release"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    payload = None
    if body is not None:
        payload = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    elif data is not None:
        payload = data
        headers["Content-Type"] = content_type or "application/octet-stream"
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url, data=payload, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
            return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as e:
            try:
                err = PlayError.from_body(e.code, e.read())
            finally:
                e.close()
            if e.code >= 500 and attempt < attempts:
                log(f"HTTP {e.code} from {method} {urlparse_path(url)}; retrying ({attempt}/{attempts - 1})…")
                time.sleep(5 * attempt)
                continue
            raise err from None
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            if attempt < attempts:
                log(f"Network error on {method} {urlparse_path(url)} ({e}); retrying ({attempt}/{attempts - 1})…")
                time.sleep(5 * attempt)
                continue
            raise PlayError(0, f"network error: {e}") from None
    raise AssertionError("unreachable")


def urlparse_path(url):
    return urllib.parse.urlsplit(url).path


# -------------------------------------------------------------- explanations


def explain(e, step, package, sa_email, project_id, version_code=None):
    """Maps Play's error to the fix. Order matters: the specific messages are
    checked before the generic per-status advice."""
    msg = e.message
    low = msg.lower()
    title = f"Google Play: {step} failed (HTTP {e.code})"
    if "draft app" in low:
        return Fatal(
            "Google Play: app is still a draft",
            f"Play only accepts status=draft releases until the app has been published once ({msg}). "
            "Re-run this workflow from Actions → Release → Run workflow with status=draft, or set the repo "
            f"variable PLAY_RELEASE_STATUS=draft so pushes do the same, until the first release has been rolled "
            f"out from Play Console (then delete the variable). See {DOCS}.",
        )
    if "changesnotsentforreview" in low or "sent for review automatically" in low:
        return Fatal(
            "Google Play: changes need manual review submission",
            f"{msg} — set the repo variable PLAY_CHANGES_NOT_SENT_FOR_REVIEW=true, re-run, then press "
            f"'Send changes for review' in Play Console → Publishing overview. See {DOCS}.",
        )
    if "version code" in low and ("already been used" in low or "already used" in low):
        return Fatal(
            "Google Play: versionCode already used",
            f"{msg} Each upload needs a new versionCode. If this is a re-run of a run that already uploaded, "
            "start a NEW run instead (re-runs keep the same run number, hence the same versionCode); otherwise "
            f"raise VERSION_CODE_OFFSET in .github/workflows/release.yml. See {DOCS}.",
        )
    if "wrong key" in low or "signed with the wrong" in low or "certificate with fingerprint" in low:
        return Fatal(
            "Google Play: AAB signed with the wrong upload key",
            f"{msg} The DROKPO_UPLOAD_KEYSTORE_B64 secret holds a different key than the upload key registered in "
            "Play Console → Test and release → App integrity. Restore the original keystore's secrets "
            f"(ci/setup-release-signing.sh on the machine that has ~/.drokpo-android/), or request an upload key reset there. See {DOCS}.",
        )
    if ("debug" in low and "signed" in low) or "not signed" in low or "unsigned" in low:
        return Fatal(title, f"{msg} Only bundles signed with the upload key can be uploaded.")
    if "upgrade" in low or "shadowed" in low:
        return Fatal(
            title,
            f"{msg} The new versionCode{f' {version_code}' if version_code else ''} is not above a release "
            f"already active on the track — raise VERSION_CODE_OFFSET. See {DOCS}.",
        )
    if re.search(r"\bedit\b", low) and ("deleted" in low or "expired" in low or "conflict" in low or "not found" in low):
        return Fatal(
            title,
            f"{msg} Another Play edit was committed while this one was open (e.g. a main and a dev release at the "
            "same time, or someone editing in Play Console). Re-run this job — nothing was published.",
        )
    if e.code == 401:
        return Fatal(
            title,
            f"Google rejected the access token for {sa_email} ({msg}). Create a new JSON key and {SECRET_FIX}",
        )
    if e.code == 403 and ("has not been used in project" in low or "service_disabled" in low.replace(" ", "_")
                          or "accessnotconfigured" in e.reason.lower() or "is disabled" in low):
        return Fatal(
            "Google Play Android Developer API is disabled",
            f"{msg} Enable it for the service account's project: "
            f"gcloud services enable androidpublisher.googleapis.com --project {project_id or '<project>'} "
            "(or open the link above), wait a few minutes, then re-run.",
        )
    if e.code == 403:
        return Fatal(
            "Google Play: service account lacks access",
            f"{msg} — {sa_email} has no release permission for {package}. In Play Console → Users and permissions, "
            f"invite {sa_email}, add the app under App permissions and grant 'Release to testing tracks' (plus "
            "'Release to production…' for track=production). A fresh invite can take a while to take effect. "
            f"See {DOCS}.",
        )
    if e.code == 404:
        return Fatal(
            f"Google Play: {package} not found",
            f"{msg} — Play doesn't know {package} yet: create the app in Play Console and upload the FIRST AAB "
            "by hand (Test and release → Testing → Internal testing; the Play Developer API can neither create "
            "apps nor make the very first upload). Use the signed AAB from this workflow's 'drokpo-release-…' "
            f"artifact. If the app exists, check {sa_email} was invited for it. See {DOCS}.",
        )
    if e.code == 0:
        return Fatal(title, f"{msg} — could not reach Google; re-run the job.")
    return Fatal(title, msg)


# ----------------------------------------------------------------- commands


class Play:
    def __init__(self, package):
        self.sa = load_service_account(os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", ""))
        self.package = package
        self.token = mint_token(self.sa)
        self.edit_id = None

    def fail(self, e, step, version_code=None):
        return explain(e, step, self.package, self.sa["client_email"], self.sa.get("project_id"), version_code)

    def app_url(self, root=None):
        return f"{root or API_ROOT}/applications/{urllib.parse.quote(self.package)}"

    def edit_url(self, root=None):
        return f"{self.app_url(root)}/edits/{self.edit_id}"

    def call(self, step, method, url, version_code=None, **kw):
        try:
            return http(method, url, token=self.token, **kw)
        except PlayError as e:
            raise self.fail(e, step, version_code) from e

    def open_edit(self):
        self.edit_id = self.call("opening an edit", "POST", f"{self.app_url()}/edits", body={})["id"]

    def discard_edit(self):
        if not self.edit_id:
            return
        try:
            http("DELETE", self.edit_url(), token=self.token, attempts=2)
        except PlayError as e:
            if e.code != 404:  # already gone / committed
                log(f"(could not delete edit {self.edit_id}: HTTP {e.code} {e.message} — it expires on its own)")
        self.edit_id = None


def max_uploaded_version_code(play):
    bundles = play.call("listing bundles", "GET", f"{play.edit_url()}/bundles").get("bundles", [])
    apks = play.call("listing APKs", "GET", f"{play.edit_url()}/apks").get("apks", [])
    codes = [int(b["versionCode"]) for b in bundles + apks if "versionCode" in b]
    return max(codes) if codes else 0


def describe_tracks(play):
    tracks = play.call("listing tracks", "GET", f"{play.edit_url()}/tracks").get("tracks", [])
    for track in tracks:
        releases = track.get("releases") or []
        if not releases:
            continue
        parts = [
            f"{r.get('name') or '(unnamed)'} [{r.get('status')}] codes {','.join(r.get('versionCodes', [])) or '-'}"
            for r in releases
        ]
        log(f"  {track.get('track')}: " + "; ".join(parts))


def cmd_verify(args):
    play = Play(args.package)
    try:
        play.open_edit()
        log(f"Google Play credentials OK: {play.sa['client_email']} can open edits for {args.package}.")
        log("Current tracks:")
        describe_tracks(play)
        if args.version_code:
            highest = max_uploaded_version_code(play)
            if args.version_code <= highest:
                run = os.environ.get("GITHUB_RUN_NUMBER", "")
                need = f" (at least {highest + 1 - int(run)})" if run.isdigit() else ""
                raise Fatal(
                    "versionCode would collide",
                    f"This build would be versionCode {args.version_code}, but Play already has versionCode {highest}. "
                    "If this is a re-run of a run that already uploaded, start a NEW run instead (re-runs keep the "
                    "run number). Otherwise raise VERSION_CODE_OFFSET in .github/workflows/release.yml"
                    f"{need}. See {DOCS}.",
                )
            log(f"versionCode {args.version_code} is above the highest uploaded ({highest or 'none'}).")
    finally:
        play.discard_edit()


def cmd_upload(args):
    if not os.path.isfile(args.aab):
        raise Fatal("AAB missing", f"{args.aab} does not exist.")
    play = Play(args.package)
    committed = False
    try:
        play.open_edit()
        size_mb = os.path.getsize(args.aab) / 1e6
        log(f"Uploading {args.aab} ({size_mb:.1f} MB)…")
        with open(args.aab, "rb") as f:
            bundle = play.call(
                "uploading the AAB", "POST", f"{play.edit_url(UPLOAD_ROOT)}/bundles?uploadType=media",
                version_code=args.version_code,
                data=f.read(), content_type="application/octet-stream", timeout=1800, attempts=2,
            )
        code = int(bundle.get("versionCode", 0))
        if args.version_code and code != args.version_code:
            raise Fatal(
                "versionCode mismatch",
                f"Play read versionCode {code} from the AAB, expected {args.version_code}.",
            )
        log(f"Bundle accepted: versionCode {code}, sha256 {bundle.get('sha256', '?')}.")

        if args.mapping and os.path.isfile(args.mapping) and os.path.getsize(args.mapping) > 0:
            with open(args.mapping, "rb") as f:
                play.call(
                    "uploading the R8 mapping", "POST",
                    f"{play.edit_url(UPLOAD_ROOT)}/apks/{code}/deobfuscationFiles/proguard?uploadType=media",
                    data=f.read(), content_type="application/octet-stream", timeout=600, attempts=2,
                )
            log("R8 mapping attached (deobfuscated crash reports in Play Console / Android vitals).")
        else:
            log("No R8 mapping to attach.")

        release = {"versionCodes": [str(code)], "status": args.status}
        if args.release_name:
            release["name"] = args.release_name[:50]
        play.call(
            f"assigning the release to the {args.track} track", "PUT",
            f"{play.edit_url()}/tracks/{urllib.parse.quote(args.track)}",
            version_code=code, body={"track": args.track, "releases": [release]},
        )
        commit_url = f"{play.edit_url()}:commit"
        if os.environ.get("PLAY_CHANGES_NOT_SENT_FOR_REVIEW", "").lower() == "true":
            commit_url += "?changesNotSentForReview=true"
        play.call("committing the edit", "POST", commit_url, data=b"", content_type="application/json")
        committed = True
        log(f"Published '{release.get('name', code)}' to the {args.track} track (status {args.status}).")
    finally:
        if not committed:
            play.discard_edit()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    v = sub.add_parser("verify", help="check credentials (and versionCode headroom) without changing anything")
    v.add_argument("--package", required=True)
    v.add_argument("--version-code", type=int)
    u = sub.add_parser("upload", help="upload an AAB (+ mapping) and release it on a track")
    u.add_argument("--package", required=True)
    u.add_argument("--aab", required=True)
    u.add_argument("--mapping")
    u.add_argument("--track", required=True, choices=["internal", "alpha", "beta", "production"])
    u.add_argument("--status", required=True, choices=["completed", "draft"])
    u.add_argument("--release-name")
    u.add_argument("--version-code", type=int)
    args = parser.parse_args(argv)
    try:
        (cmd_verify if args.command == "verify" else cmd_upload)(args)
    except Fatal as e:
        print(f"::error title={gh_escape(e.title).replace(',', '%2C').replace(':', '%3A')}::{gh_escape(e)}", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
