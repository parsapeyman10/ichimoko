import copy
import unittest
from verify_apk_upgrade import parse_badging, parse_signers, verify, MAX_APK_BYTES

CERT = "137f3f98f6c85fdd8671d1d2099b095ed82f3f91594ac9e7b1ba22c264ed4a04"
BADGING = """package: name='com.aurum.edge' versionCode='666' versionName='1.3.50' platformBuildVersionName='15'
sdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'
native-code: 'arm64-v8a' 'armeabi-v7a'
"""


class UpgradeCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.candidate = dict(parse_badging(BADGING), signersSha256=[CERT],
                              torAbis=["arm64-v8a", "armeabi-v7a"], testOnly=False,
                              sha256="a" * 64, sizeBytes=40_000_000)
        self.previous = dict(copy.deepcopy(self.candidate), versionCode=665, versionName="1.3.49")

    def check(self, require_newer=True):
        return verify(self.previous, self.candidate, certificate=CERT, version_code=666,
                      version_name="1.3.50", require_newer=require_newer)

    def test_same_channel_higher_version_is_an_upgrade_not_a_device_install_claim(self):
        report = self.check()
        self.assertEqual("verified-upgrade", report["status"])
        self.assertFalse(report["physicalDeviceInstallTested"])
        self.assertTrue(report["requiresAndroidInstallerConfirmation"])

    def test_equal_and_lower_codes_cannot_publish_but_validation_can_run_after_release(self):
        for code in (666, 667):
            self.previous["versionCode"] = code
            with self.assertRaises(ValueError):
                self.check()
        self.assertEqual("validation-only", self.check(False)["status"])

    def test_rejects_incompatible_package_signer_version_manifest_abi_and_size(self):
        changes = {
            "applicationId": "com.aurum.edge.debug", "versionCode": 665, "versionName": "1.3.49",
            "signersSha256": [CERT, "b" * 64], "debuggable": True, "testOnly": True,
            "split": "config.arm64_v8a", "minSdk": 27, "targetSdk": 34,
            "abis": ["arm64-v8a"], "torAbis": ["arm64-v8a"],
            "canRequestInstalls": False, "sizeBytes": MAX_APK_BYTES + 1,
        }
        original = copy.deepcopy(self.candidate)
        for field, value in changes.items():
            with self.subTest(field=field):
                self.candidate = dict(original, **{field: value})
                with self.assertRaises(ValueError):
                    self.check()
        self.candidate = original

    def test_previous_apk_must_match_installed_channel_too(self):
        self.previous["signersSha256"] = ["b" * 64]
        with self.assertRaises(ValueError):
            self.check()

    def test_minimum_sdk_cannot_increase_even_below_current_floor(self):
        self.previous["minSdk"] = 23
        with self.assertRaises(ValueError):
            self.check()

    def test_badging_reads_real_flags_and_rejects_missing_fields(self):
        self.assertTrue(parse_badging(BADGING + "application-debuggable\n")["debuggable"])
        self.assertEqual(26, parse_badging(BADGING)["minSdk"])
        with self.assertRaises(ValueError):
            parse_badging("not an APK")
        with self.assertRaises(ValueError):
            parse_badging(BADGING.replace("sdkVersion:'26'", "sdkVersion:'Future'"))

    def test_signers_are_exact_not_partial_or_source_stamp_matches(self):
        self.assertEqual([CERT], parse_signers(f"Signer #1 certificate SHA-256 digest: {CERT.upper()}\n"))
        with self.assertRaises(ValueError):
            parse_signers(f"Source Stamp Signer certificate SHA-256 digest: {CERT}\n")
        self.assertEqual(2, len(parse_signers(
            f"Signer #1 certificate SHA-256 digest: {CERT}\nSigner #2 certificate SHA-256 digest: {'b' * 64}\n")))


if __name__ == "__main__":
    unittest.main()
