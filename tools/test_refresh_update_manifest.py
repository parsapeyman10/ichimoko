"""Offline tests for tools/refresh_update_manifest.py — no network, no release access."""

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import refresh_update_manifest as refresh  # noqa: E402

DIGEST = "dfeb1e95a268e291bd61c6165846648f6beee9ea7cdd1f012d15461da6569ddf"
SIGNER = "137f3f98f6c85fdd8671d1d2099b095ed82f3f91594ac9e7b1ba22c264ed4a04"


def sidecar(**overrides):
    data = {
        "applicationId": "com.aurum.edge",
        "versionCode": 677,
        "versionName": "2.0.5",
        "tagName": "v2.0.5",
        "commitSha": "0" * 40,
        "publishedAt": "2026-10-20T10:00:00Z",
        "artifactName": "AurumEdge-v2.0.5.apk",
        "notes": "test",
        "apkUrl": f"https://github.com/{refresh.REPO}/releases/download/v2.0.5/AurumEdge-v2.0.5.apk",
        "sha256": DIGEST,
        "sizeBytes": 12311465,
        "signerSha256": SIGNER,
        "minSdk": 26,
        "targetSdk": 35,
        "supportedAbis": ["arm64-v8a", "armeabi-v7a"],
    }
    data.update(overrides)
    return data


class BuildManifestTest(unittest.TestCase):
    def test_accepts_a_real_sidecar_and_keeps_the_known_key_order(self):
        manifest = refresh.build_manifest(sidecar(), "v2.0.5")
        self.assertEqual(list(manifest), [k for k in refresh.KEYS if k in manifest])
        self.assertEqual(manifest["versionCode"], 677)
        self.assertEqual(manifest["tagName"], "v2.0.5")
        self.assertEqual(manifest["sha256"], DIGEST)

    def test_drops_unknown_keys_and_normalises_case(self):
        manifest = refresh.build_manifest(
            sidecar(sha256=DIGEST.upper(), signerSha256=SIGNER.upper(), extra="x"), "v2.0.5")
        self.assertNotIn("extra", manifest)
        self.assertEqual(manifest["sha256"], DIGEST)
        self.assertEqual(manifest["signerSha256"], SIGNER)

    def test_rejects_anything_the_phone_would_have_to_guess(self):
        cases = {
            "wrong package": {"applicationId": "com.example.fake"},
            "no code": {"versionCode": 0},
            "code as text": {"versionCode": "677"},
            "no name": {"versionName": "  "},
            "debug apk": {"artifactName": "AurumEdge-debug.apk"},
            "third-party url": {"apkUrl": "https://example.com/AurumEdge-v2.0.5.apk"},
            "mismatched url": {"apkUrl": f"https://github.com/{refresh.REPO}/releases/download/v2.0.5/Other.apk"},
            "short digest": {"sha256": DIGEST[:-2]},
            "no signer": {"signerSha256": ""},
            "no size": {"sizeBytes": 0},
        }
        for label, change in cases.items():
            with self.subTest(label):
                with self.assertRaises(refresh.ManifestError):
                    refresh.build_manifest(sidecar(**change), "v2.0.5")

    def test_the_hand_written_note_survives_a_refresh(self):
        manifest = refresh.build_manifest(sidecar(), "v2.0.5", keep_notes="  یادداشت فارسی  ")
        self.assertEqual(manifest["notes"], "یادداشت فارسی")
        self.assertEqual(refresh.build_manifest(sidecar(), "v2.0.5")["notes"], "test")

    def test_tag_argument_wins_over_the_sidecar(self):
        manifest = refresh.build_manifest(sidecar(tagName="v9.9.9"), "v2.0.5")
        self.assertEqual(manifest["tagName"], "v2.0.5")


class CommandLineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.out = self.dir / "aurum-edge.json"
        self.sidecar_path = self.dir / "AurumEdge-v2.0.5.apk.json"
        self.sidecar_path.write_text(json.dumps(sidecar()), encoding="utf-8")

    def run_cli(self, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = refresh.main(list(args))
        return code, stdout.getvalue(), stderr.getvalue()

    def test_writes_the_manifest_then_reports_it_as_current(self):
        code, out, _ = self.run_cli("--sidecar", str(self.sidecar_path), "--output", str(self.out))
        self.assertEqual(code, 0, out)
        written = json.loads(self.out.read_text(encoding="utf-8"))
        self.assertEqual(written["versionCode"], 677)
        self.assertEqual(written["tagName"], "v2.0.5")

        code, out, _ = self.run_cli("--sidecar", str(self.sidecar_path), "--output", str(self.out),
                                    "--check")
        self.assertEqual(code, 0, out)

    def test_check_fails_on_a_stale_manifest(self):
        self.out.write_text(json.dumps(sidecar(versionCode=676, tagName="v2.0.4")), encoding="utf-8")
        code, _, err = self.run_cli("--sidecar", str(self.sidecar_path), "--output", str(self.out),
                                    "--check")
        self.assertEqual(code, 1)
        self.assertIn("--tag v2.0.5", err)
        self.assertEqual(json.loads(self.out.read_text(encoding="utf-8"))["versionCode"], 676,
                         "--check must not write")

    def test_refresh_keeps_the_existing_note_but_updates_the_version(self):
        self.out.write_text(json.dumps(sidecar(versionCode=676, tagName="v2.0.4",
                                              notes="یادداشت نسخهٔ قبل")), encoding="utf-8")
        code, _, err = self.run_cli("--sidecar", str(self.sidecar_path), "--output", str(self.out))
        self.assertEqual(code, 0, err)
        written = json.loads(self.out.read_text(encoding="utf-8"))
        self.assertEqual(written["versionCode"], 677)
        self.assertEqual(written["notes"], "یادداشت نسخهٔ قبل")

    def test_invalid_sidecar_never_writes_a_file(self):
        self.sidecar_path.write_text(json.dumps(sidecar(applicationId="com.other")), encoding="utf-8")
        code, _, err = self.run_cli("--sidecar", str(self.sidecar_path), "--output", str(self.out))
        self.assertEqual(code, 2)
        self.assertIn("applicationId", err)
        self.assertFalse(self.out.exists())


if __name__ == "__main__":
    unittest.main()
