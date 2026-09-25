import base64
import contextlib
import hashlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import apk_signing


class SigningRestoreTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory()
        key = Path(cls.fixture.name) / "fixture.p12"
        subprocess.run([
            "keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12",
            "-alias", "fixture", "-storepass", "test-password", "-keypass", "test-password",
            "-dname", "CN=Disposable test", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "1", "-noprompt",
        ], check=True, capture_output=True)
        cert = subprocess.check_output([
            "keytool", "-exportcert", "-keystore", str(key), "-alias", "fixture",
            "-storepass", "test-password",
        ])
        cls.values = {
            "PHONEBRIDGE_KEYSTORE_BASE64": base64.b64encode(key.read_bytes()).decode(),
            "PHONEBRIDGE_STORE_PASSWORD": "test-password",
            "PHONEBRIDGE_KEY_PASSWORD": "test-password",
            "PHONEBRIDGE_KEY_ALIAS": "fixture",
            "PHONEBRIDGE_SIGNING_SHA256": hashlib.sha256(cert).hexdigest(),
        }

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.key = Path(self.temp.name) / "restored.keystore"
        self.env = dict(self.values, PHONEBRIDGE_KEYSTORE_PATH=str(self.key))

    def restore(self):
        with patch.dict(os.environ, self.env, clear=True), contextlib.redirect_stdout(io.StringIO()):
            apk_signing.restore()

    def test_repeated_restore_preserves_identity_and_private_permissions(self):
        self.restore()
        original = self.key.read_bytes()
        self.assertEqual(self.key.stat().st_mode & 0o777, 0o600)
        self.key.unlink()
        self.restore()
        self.assertEqual(self.key.read_bytes(), original)

    def test_missing_secret_fails_without_key(self):
        del self.env["PHONEBRIDGE_KEY_PASSWORD"]
        with self.assertRaises(ValueError):
            self.restore()
        self.assertFalse(self.key.exists())

    def test_wrong_certificate_removes_runner_copy(self):
        self.env["PHONEBRIDGE_SIGNING_SHA256"] = "0" * 64
        with self.assertRaises(ValueError):
            self.restore()
        self.assertFalse(self.key.exists())

    def test_wrong_password_removes_runner_copy(self):
        self.env["PHONEBRIDGE_STORE_PASSWORD"] = "incorrect"
        with self.assertRaises(ValueError):
            self.restore()
        self.assertFalse(self.key.exists())

    def test_existing_file_is_not_overwritten_or_deleted(self):
        self.key.write_bytes(b"existing key")
        with self.assertRaises(FileExistsError):
            self.restore()
        self.assertEqual(self.key.read_bytes(), b"existing key")



class ApkVerificationTest(unittest.TestCase):
    expected = "a" * 64

    def verify_output(self, output, code=0):
        result = subprocess.CompletedProcess([], code, output, "")
        with patch.dict(os.environ, {"PHONEBRIDGE_SIGNING_SHA256": self.expected}), \
                patch.object(apk_signing.subprocess, "run", return_value=result), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            apk_signing.verify("apksigner", "test.apk")

    def test_current_and_legacy_formats(self):
        for label in ("Signer #1", "V2 Signer:"):
            with self.subTest(label=label):
                self.verify_output(f"Number of signers: 1\n{label} certificate SHA-256 digest: {self.expected}\n")

    def test_rejects_wrong_certificate(self):
        with self.assertRaises(ValueError):
            self.verify_output("Number of signers: 1\nV2 Signer: certificate SHA-256 digest: " + "b" * 64 + "\n")

    def test_source_stamp_cannot_substitute_for_signer(self):
        with self.assertRaises(ValueError):
            self.verify_output(f"Number of signers: 1\nSource Stamp Signer certificate SHA-256 digest: {self.expected}\n")

    def test_nonzero_verification_exit_is_rejected(self):
        with self.assertRaises(ValueError):
            self.verify_output(f"Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {self.expected}\n", code=1)

    def test_multiple_signers_are_rejected(self):
        with self.assertRaises(ValueError):
            self.verify_output(f"Number of signers: 2\nSigner #1 certificate SHA-256 digest: {self.expected}\n")

if __name__ == "__main__":
    unittest.main()
