import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from provision import create_peer, init_ca, validate_password


class ProvisioningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="lantern-provision-test-")
        cls.directory = Path(cls.temporary.name)
        cls.password = "test-only-password-not-for-deployment"
        cls.ca = init_ca(cls.directory / "household", cls.password)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_ca_is_encrypted_private_and_does_not_allow_subordinate_ca(self):
        key = self.ca / "ca-key.pem"
        self.assertIn("ENCRYPTED PRIVATE KEY", key.read_text())
        self.assertEqual(key.stat().st_mode & 0o777, 0o600)
        text = subprocess.run(
            ["openssl", "x509", "-in", str(self.ca / "ca-cert.pem"), "-text", "-noout"],
            text=True, capture_output=True, check=True,
        ).stdout
        self.assertIn("CA:TRUE, pathlen:0", text)

    def test_leaf_identity_chain_sans_password_and_no_ca_key_distribution(self):
        peer = create_peer(
            self.ca, self.directory / "front-door", "Front door",
            ["door.nord", "192.168.1.50", "100.101.12.13", "fd00::1"],
            self.password, self.password,
        )
        self.assertEqual((peer / "device.p12").stat().st_mode & 0o777, 0o600)
        self.assertFalse((peer / "ca-key.pem").exists())
        self.assertFalse((peer / "peer-key.pem").exists())
        result = subprocess.run(
            ["openssl", "pkcs12", "-in", str(peer / "device.p12"), "-nokeys", "-passin", "stdin"],
            input=self.password + "\n", text=True, capture_output=True, check=True,
        )
        self.assertIn("BEGIN CERTIFICATE", result.stdout)
        text = subprocess.run(
            ["openssl", "x509", "-in", str(peer / "device-cert.pem"), "-text", "-noout"],
            text=True, capture_output=True, check=True,
        ).stdout
        self.assertIn("DNS:door.nord", text)
        self.assertIn("IP Address:192.168.1.50", text)
        self.assertIn("IP Address:100.101.12.13", text)
        self.assertIn("CA:FALSE", text)
        self.assertIn("TLS Web Server Authentication", text)
        manifest = json.loads((peer / "identity.json").read_text())
        self.assertEqual(manifest["validDays"], 365)

    def test_never_overwrites_an_existing_identity(self):
        with self.assertRaisesRegex(ValueError, "overwrite"):
            init_ca(self.ca, self.password)
        target = self.directory / "existing"
        target.mkdir()
        marker = target / "keep.txt"
        marker.write_text("untouched")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            create_peer(self.ca, target, "Camera", ["door.nord"], self.password, self.password)
        self.assertEqual(marker.read_text(), "untouched")

    def test_rejects_injection_and_invalid_names_before_writing(self):
        for index, hostname in enumerate(["door.nord\nkeyUsage=keyCertSign", "*.nord", "https://door.nord", "../door", "bad_label.nord"]):
            with self.subTest(hostname=hostname), self.assertRaises(ValueError):
                create_peer(self.ca, self.directory / f"bad-{index}", "Camera", [hostname], self.password, self.password)
        with self.assertRaises(ValueError):
            create_peer(self.ca, self.directory / "bad-name", "Camera/O=Other", ["door.nord"], self.password, self.password)

    def test_failed_signing_leaves_no_partial_identity(self):
        target = self.directory / "failed-signing"
        with self.assertRaises(RuntimeError):
            create_peer(self.ca, target, "Camera", ["door.nord"], "incorrect-password", self.password)
        self.assertFalse(target.exists())
        self.assertFalse(any(path.name.startswith(".lantern-peer-") for path in self.directory.iterdir()))

    def test_rejects_weak_and_multiline_passwords(self):
        for password in ["short", "long-password\ninjection", "long-password\0injection"]:
            with self.subTest(password=password), self.assertRaises(ValueError):
                validate_password(password)

    def test_refuses_output_symlinks(self):
        if os.name == "nt":
            self.skipTest("Symlink privileges vary on Windows.")
        target = self.directory / "symlink-output"
        target.symlink_to(self.directory / "nonexistent-target")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            init_ca(target, self.password)


if __name__ == "__main__":
    unittest.main()
