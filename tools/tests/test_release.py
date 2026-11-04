# Copyright 2026 Creative Moods, Lda.
# SPDX-License-Identifier: Apache-2.0

import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("release", Path(__file__).parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseIdentityTest(unittest.TestCase):
    source = 'versionName = "0.2.15"; versionCode = 17'

    def test_matching_version_records_android_identity(self):
        info = release.metadata(self.source, "v0.2.15", "a" * 40)
        self.assertEqual((info["versionName"], info["versionCode"], info["package"]),
                         ("0.2.15", 17, "pt.creativemoods.scorepuff"))

    def test_wrong_tag_or_prerelease_is_rejected(self):
        for tag in ("v0.2.14", "0.2.15", "v0.2.15-rc1", "v0.2.15;echo bad"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.metadata(self.source, tag, "a" * 40)

    def test_missing_version_code_or_invalid_commit_is_rejected(self):
        for source, commit in ((self.source, "abcd"), ('versionName = "0.2.15"', "a" * 40)):
            with self.assertRaises(ValueError):
                release.metadata(source, "v0.2.15", commit)


class ReleaseVerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "scorepuff.apk"
        self.cert = self.root / "certificate.sha256"
        self.cert.write_text("a" * 64)
        self.info = release.metadata(ReleaseIdentityTest.source, "v0.2.15", "a" * 40)
        self.signature = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        self.badging = "package: name='pt.creativemoods.scorepuff' versionCode='17' versionName='0.2.15'"

    def verify(self, entries=None, signature=None, badging=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for key, value in (entries or {"classes.dex": b"release code"}).items():
                archive.writestr(key, value)
        with patch.object(release, "output", side_effect=[signature or self.signature, badging or self.badging]), \
                patch.object(release.subprocess, "run"):
            return release.verify(self.apk, self.info, self.root, self.cert)

    def test_expected_certificate_and_identity_are_accepted(self):
        self.assertEqual(self.verify(), "a" * 64)

    def test_wrong_signing_key_is_rejected(self):
        with self.assertRaises(ValueError):
            self.verify(signature="Signer #1 certificate SHA-256 digest: " + "b" * 64)

    def test_debuggable_or_wrong_version_is_rejected(self):
        for badging in (self.badging + " application-debuggable", self.badging.replace("versionCode='17'", "versionCode='16'")):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                self.verify(badging=badging)

    def test_private_files_and_debug_fixtures_are_rejected(self):
        for name in ("assets/captured-home.xml", "keystore.properties", "assets/release.jks"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.verify(entries={name: b"not releasable"})

    def test_signing_password_is_rejected_in_apk_contents(self):
        with patch.dict(release.os.environ, {"ANDROID_STORE_PASSWORD": "examplePasswordForTests"}):
            with self.assertRaises(ValueError):
                self.verify(entries={"classes.dex": b"examplePasswordForTests"})
