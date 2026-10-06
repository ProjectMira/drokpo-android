"""Offline tests for ci/play_publish.py against a fake Google endpoint.

    python3 -m unittest discover -s ci/tests -v

Covers the secret normalisation, the JWT the token request carries (its
RS256 signature is checked with openssl against the throwaway key), the
verify/upload request sequence, and the actionable messages for the Play
errors people actually hit during setup.
"""

import base64
import contextlib
import importlib.util
import io
import json
import os
import subprocess
import tempfile
import threading
import unittest
import unittest.mock
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("play_publish", os.path.join(HERE, "..", "play_publish.py"))
pp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pp)

PKG = "app.drokpo.android"
APP = f"/androidpublisher/v3/applications/{PKG}"
UPLOAD_APP = f"/upload/androidpublisher/v3/applications/{PKG}"


def b64url_decode(part):
    return base64.urlsafe_b64decode(part + "=" * (-len(part) % 4))


class FakeGoogle(BaseHTTPRequestHandler):
    """Records every request; `routes` maps (method, path) → (status, body),
    `queued` holds one-shot responses served first (for retry tests)."""

    routes = {}
    queued = {}
    calls = []
    public_key_path = None

    def log_message(self, *args):  # keep test output clean
        pass

    def _handle(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        path = urllib.parse.urlsplit(self.path).path
        query = urllib.parse.urlsplit(self.path).query
        type(self).calls.append((self.command, path, query, body, dict(self.headers)))
        if path == "/token":
            form = urllib.parse.parse_qs(body.decode())
            jwt = form["assertion"][0]
            header, claims, sig = jwt.split(".")
            with tempfile.NamedTemporaryFile() as sigf, tempfile.NamedTemporaryFile() as msgf:
                sigf.write(b64url_decode(sig)); sigf.flush()
                msgf.write(f"{header}.{claims}".encode()); msgf.flush()
                ok = subprocess.run(
                    ["openssl", "dgst", "-sha256", "-verify", self.public_key_path, "-signature", sigf.name, msgf.name],
                    capture_output=True,
                ).returncode == 0
            if not ok:
                return self._send(400, {"error": "invalid_grant", "error_description": "Invalid JWT Signature."})
            return self._send(*self.routes.get(("POST", "/token"), (200, {"access_token": "ya29.fake"})))
        queue = self.queued.get((self.command, path))
        if queue:
            status, payload = queue.pop(0)
        else:
            status, payload = self.routes.get((self.command, path), (404, {"error": {"code": 404, "message": f"no route {path}"}}))
        if self.headers.get("Authorization") != "Bearer ya29.fake":
            status, payload = 401, {"error": {"code": 401, "message": "Request had invalid authentication credentials."}}
        self._send(status, payload)

    def _send(self, status, payload):
        raw = json.dumps(payload).encode() if payload is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    do_GET = do_POST = do_PUT = do_DELETE = _handle


class PlayPublishTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        key = os.path.join(cls.tmp.name, "key.pem")
        pub = os.path.join(cls.tmp.name, "pub.pem")
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-out", key,
                        "-pkeyopt", "rsa_keygen_bits:2048"], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", key, "-pubout", "-out", pub], check=True, capture_output=True)
        with open(key) as f:
            cls.pem = f.read()
        cls.sa = {
            "type": "service_account", "project_id": "drokpo-play", "private_key_id": "abc123",
            "private_key": cls.pem, "client_email": "play-publisher@drokpo-play.iam.gserviceaccount.com",
        }
        FakeGoogle.public_key_path = pub
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeGoogle)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{cls.server.server_port}"
        pp.API_ROOT = f"{base}/androidpublisher/v3"
        pp.UPLOAD_ROOT = f"{base}/upload/androidpublisher/v3"
        pp.TOKEN_URI = f"{base}/token"
        pp.time.sleep = lambda s: None  # no backoff waits in tests

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.tmp.cleanup()

    def setUp(self):
        FakeGoogle.calls = []
        FakeGoogle.queued = {}
        FakeGoogle.routes = {
            ("POST", f"{APP}/edits"): (200, {"id": "e1", "expiryTimeSeconds": "9999999999"}),
            ("DELETE", f"{APP}/edits/e1"): (204, None),
            ("GET", f"{APP}/edits/e1/tracks"): (200, {"tracks": [{"track": "internal", "releases": [
                {"name": "1.1 (5) main@0000000", "status": "completed", "versionCodes": ["5"]}]}]}),
            ("GET", f"{APP}/edits/e1/bundles"): (200, {"bundles": [{"versionCode": 5}, {"versionCode": 3}]}),
            ("GET", f"{APP}/edits/e1/apks"): (200, {}),
            ("POST", f"{UPLOAD_APP}/edits/e1/bundles"): (200, {"versionCode": 42, "sha256": "f00d"}),
            ("POST", f"{UPLOAD_APP}/edits/e1/apks/42/deobfuscationFiles/proguard"): (200, {}),
            ("PUT", f"{APP}/edits/e1/tracks/internal"): (200, {}),
            ("POST", f"{APP}/edits/e1:commit"): (200, {"id": "e1"}),
        }
        self.env = {"PLAY_SERVICE_ACCOUNT_JSON": json.dumps(self.sa, indent=2), "GITHUB_RUN_NUMBER": "40"}

    def run_main(self, *argv, env=None):
        out = io.StringIO()
        with unittest.mock.patch.dict(os.environ, env or self.env), contextlib.redirect_stdout(out):
            code = pp.main(list(argv))
        return code, out.getvalue()

    def paths(self):
        return [(m, p) for m, p, *_ in FakeGoogle.calls]

    # ------------------------------------------------------------ secrets

    def test_secret_forms_all_load(self):
        raw = json.dumps(self.sa, indent=2)
        forms = {
            "raw": raw,
            "crlf": raw.replace("\n", "\r\n"),
            "base64": base64.b64encode(raw.encode()).decode(),
            "base64 wrapped": "\n".join(base64.b64encode(raw.encode()).decode()[i:i + 76] for i in range(0, 4000, 76)),
            "escaped newlines": raw.replace("\n", "\\n"),
            "json string": json.dumps(raw),
        }
        for name, form in forms.items():
            with self.subTest(name):
                sa = pp.load_service_account(form)
                self.assertEqual(sa["client_email"], self.sa["client_email"])
                # Every form must still yield a key openssl accepts.
                self.assertTrue(pp.sign_rs256(b"x", pp.normalise_pem(sa["private_key"])))

    def test_flattened_pem_is_rebuilt(self):
        flat = self.pem.replace("\n", " ")
        self.assertTrue(pp.sign_rs256(b"x", pp.normalise_pem(flat)))

    def test_wrong_secret_kinds_are_explained(self):
        for blob, needle in [
            ("not json at all", "not JSON"),
            (json.dumps({"project_info": {}, "client": []}), "not a service-account key"),
            (json.dumps({"installed": {"client_id": "x"}}), "not a service-account key"),
        ]:
            with self.subTest(needle):
                with self.assertRaises(pp.Fatal) as ctx:
                    pp.load_service_account(blob)
                self.assertIn(needle, str(ctx.exception))

    # ------------------------------------------------------------- verify

    def test_verify_ok_checks_headroom_and_deletes_edit(self):
        code, out = self.run_main("verify", "--package", PKG, "--version-code", "42")
        self.assertEqual(code, 0, out)
        self.assertIn("credentials OK", out)
        self.assertIn("1.1 (5) main@0000000 [completed] codes 5", out)
        self.assertEqual(self.paths()[-1], ("DELETE", f"{APP}/edits/e1"))
        token_call = FakeGoogle.calls[0]
        claims = json.loads(b64url_decode(urllib.parse.parse_qs(token_call[3].decode())["assertion"][0].split(".")[1]))
        self.assertEqual(claims["scope"], "https://www.googleapis.com/auth/androidpublisher")
        self.assertEqual(claims["iss"], self.sa["client_email"])

    def test_verify_catches_version_code_collision(self):
        # A re-run of run 5 (offset 0) after it already uploaded versionCode 5.
        code, out = self.run_main("verify", "--package", PKG, "--version-code", "5",
                                  env=dict(self.env, GITHUB_RUN_NUMBER="5"))
        self.assertEqual(code, 1)
        self.assertIn("versionCode would collide", out)
        self.assertIn("start a NEW run", out)
        self.assertIn("(at least 1)", out)
        self.assertEqual(self.paths()[-1], ("DELETE", f"{APP}/edits/e1"))  # edit still discarded

    def test_verify_404_means_app_missing_or_no_first_upload(self):
        FakeGoogle.routes[("POST", f"{APP}/edits")] = (404, {"error": {"code": 404, "message": f"Package not found: {PKG}."}})
        code, out = self.run_main("verify", "--package", PKG)
        self.assertEqual(code, 1)
        self.assertIn("upload the FIRST AAB", out)

    def test_verify_403_permission(self):
        FakeGoogle.routes[("POST", f"{APP}/edits")] = (403, {"error": {"code": 403, "message": "The caller does not have permission"}})
        code, out = self.run_main("verify", "--package", PKG)
        self.assertEqual(code, 1)
        self.assertIn("Users and permissions", out)

    def test_verify_403_api_disabled(self):
        FakeGoogle.routes[("POST", f"{APP}/edits")] = (403, {"error": {
            "code": 403, "status": "PERMISSION_DENIED",
            "message": "Google Play Android Developer API has not been used in project 123 before or it is disabled."}})
        code, out = self.run_main("verify", "--package", PKG)
        self.assertEqual(code, 1)
        self.assertIn("gcloud services enable androidpublisher.googleapis.com --project drokpo-play", out)

    def test_bad_signature_is_explained(self):
        other = subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
                               check=True, capture_output=True, text=True).stdout
        env = dict(self.env, PLAY_SERVICE_ACCOUNT_JSON=json.dumps(dict(self.sa, private_key=other)))
        code, out = self.run_main("verify", "--package", PKG, env=env)
        self.assertEqual(code, 1)
        self.assertIn("deleted/rotated", out)

    def test_server_errors_are_retried(self):
        FakeGoogle.queued[("POST", f"{APP}/edits")] = [(503, {"error": {"code": 503, "message": "backend"}})]
        code, out = self.run_main("verify", "--package", PKG)
        self.assertEqual(code, 0, out)
        self.assertIn("retrying", out)
        self.assertEqual(self.paths().count(("POST", f"{APP}/edits")), 2)

    # ------------------------------------------------------------- upload

    def make_files(self):
        aab = os.path.join(self.tmp.name, "app-release.aab")
        mapping = os.path.join(self.tmp.name, "mapping.txt")
        with open(aab, "wb") as f:
            f.write(b"PK\x03\x04 fake bundle")
        with open(mapping, "w") as f:
            f.write("a.b.C -> x:\n")
        return aab, mapping

    def test_upload_full_sequence(self):
        aab, mapping = self.make_files()
        code, out = self.run_main("upload", "--package", PKG, "--aab", aab, "--mapping", mapping,
                                  "--track", "internal", "--status", "completed",
                                  "--release-name", "1.1 (42) dev@abc1234", "--version-code", "42")
        self.assertEqual(code, 0, out)
        self.assertEqual([p for p in self.paths() if p[1] != "/token"], [
            ("POST", f"{APP}/edits"),
            ("POST", f"{UPLOAD_APP}/edits/e1/bundles"),
            ("POST", f"{UPLOAD_APP}/edits/e1/apks/42/deobfuscationFiles/proguard"),
            ("PUT", f"{APP}/edits/e1/tracks/internal"),
            ("POST", f"{APP}/edits/e1:commit"),
        ])
        track_body = json.loads(next(c[3] for c in FakeGoogle.calls if c[0] == "PUT"))
        self.assertEqual(track_body, {"track": "internal", "releases": [
            {"versionCodes": ["42"], "status": "completed", "name": "1.1 (42) dev@abc1234"}]})
        upload = next(c for c in FakeGoogle.calls if c[1].endswith("/bundles") and c[0] == "POST")
        self.assertEqual(upload[2], "uploadType=media")
        self.assertEqual(upload[3], b"PK\x03\x04 fake bundle")

    def test_upload_draft_app_error_points_to_status_draft(self):
        aab, mapping = self.make_files()
        FakeGoogle.routes[("PUT", f"{APP}/edits/e1/tracks/internal")] = (400, {"error": {
            "code": 400, "message": "Only releases with status draft may be created on draft app."}})
        code, out = self.run_main("upload", "--package", PKG, "--aab", aab, "--mapping", mapping,
                                  "--track", "internal", "--status", "completed", "--version-code", "42")
        self.assertEqual(code, 1)
        self.assertIn("status=draft", out)
        self.assertIn("PLAY_RELEASE_STATUS=draft", out)
        self.assertEqual(self.paths()[-1], ("DELETE", f"{APP}/edits/e1"))  # uncommitted edit discarded

    def test_upload_duplicate_version_code(self):
        aab, mapping = self.make_files()
        FakeGoogle.routes[("POST", f"{UPLOAD_APP}/edits/e1/bundles")] = (403, {"error": {
            "code": 403, "message": "APK specifies a version code that has already been used."}})
        code, out = self.run_main("upload", "--package", PKG, "--aab", aab, "--track", "internal",
                                  "--status", "completed", "--version-code", "42")
        self.assertEqual(code, 1)
        self.assertIn("start a NEW run", out)

    def test_upload_changes_not_sent_for_review(self):
        aab, mapping = self.make_files()
        FakeGoogle.routes[("POST", f"{APP}/edits/e1:commit")] = (400, {"error": {
            "code": 400, "message": "Changes cannot be sent for review automatically. Please set the query "
                                    "parameter changesNotSentForReview to true."}})
        code, out = self.run_main("upload", "--package", PKG, "--aab", aab, "--track", "internal",
                                  "--status", "completed", "--version-code", "42")
        self.assertEqual(code, 1)
        self.assertIn("PLAY_CHANGES_NOT_SENT_FOR_REVIEW=true", out)
        FakeGoogle.routes[("POST", f"{APP}/edits/e1:commit")] = (200, {})
        FakeGoogle.calls = []
        code, out = self.run_main("upload", "--package", PKG, "--aab", aab, "--track", "internal",
                                  "--status", "completed", "--version-code", "42",
                                  env=dict(self.env, PLAY_CHANGES_NOT_SENT_FOR_REVIEW="true"))
        self.assertEqual(code, 0, out)
        commit = next(c for c in FakeGoogle.calls if c[1].endswith(":commit"))
        self.assertEqual(commit[2], "changesNotSentForReview=true")

    def test_error_annotation_is_single_line(self):
        code, out = self.run_main("verify", "--package", PKG, env={"PLAY_SERVICE_ACCOUNT_JSON": "nope"})
        self.assertEqual(code, 1)
        line = [ln for ln in out.splitlines() if ln.startswith("::error")]
        self.assertEqual(len(line), 1)
        self.assertRegex(line[0], r"^::error title=[^:,]+::")


if __name__ == "__main__":
    unittest.main()
