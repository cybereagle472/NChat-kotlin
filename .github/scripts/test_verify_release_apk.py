import unittest

from verify_release_apk import DEMO_SIGNER_SHA256, verify


BADGING = ("package: name='com.nchat.app' versionCode='78' versionName='1.0.70' "
           "compileSdkVersion='34' compileSdkVersionCodename='14'")
MANIFEST = {"versionCode": 78, "versionName": "1.0.70"}
SIGNER = f"Signer #1 certificate SHA-256 digest: {DEMO_SIGNER_SHA256}\n"


class VerifyReleaseApkTest(unittest.TestCase):
    def test_matching_build_and_signing_key(self):
        verify(BADGING, SIGNER, MANIFEST)
        verify(BADGING, f"Signer #1 certificate SHA-256 digest: {DEMO_SIGNER_SHA256.upper()}\n", MANIFEST)

    def test_wrong_certificate_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            verify(BADGING, "Signer #1 certificate SHA-256 digest: " + "ab" * 32, MANIFEST)

    def test_additional_signer_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            verify(BADGING, SIGNER + "Signer #2 certificate SHA-256 digest: " + "ab" * 32, MANIFEST)

    def test_missing_signer_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "signing certificate"):
            verify(BADGING, "", MANIFEST)

    def test_version_and_package_are_checked(self):
        with self.assertRaisesRegex(ValueError, "package or version"):
            verify(BADGING, SIGNER, {"versionCode": 79, "versionName": "1.0.70"})
        with self.assertRaisesRegex(ValueError, "package or version"):
            verify(BADGING.replace("com.nchat.app", "com.example.fake"), SIGNER, MANIFEST)

    def test_missing_package_line_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "no package line"):
            verify("not an apk", SIGNER, MANIFEST)


if __name__ == "__main__":
    unittest.main()
