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


if __name__ == "__main__":
    unittest.main()
