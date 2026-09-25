"""Restore the persistent CI key, or verify an APK against its pinned certificate."""
import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys


def required(name):
    value = os.environ.get(name, "")
    if not value:
        raise ValueError(f"Missing {name}; refusing to publish with a temporary key")
    return value


def fingerprint():
    value = required("PHONEBRIDGE_SIGNING_SHA256").replace(":", "").lower().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("PHONEBRIDGE_SIGNING_SHA256 must be the certificate SHA-256")
    return value


def restore():
    expected = fingerprint()
    for name in ("PHONEBRIDGE_STORE_PASSWORD", "PHONEBRIDGE_KEY_PASSWORD"):
        required(name)
    alias = required("PHONEBRIDGE_KEY_ALIAS")
    encoded = required("PHONEBRIDGE_KEYSTORE_BASE64")
    path = Path(required("PHONEBRIDGE_KEYSTORE_PATH"))
    created = False
    try:
        raw = base64.b64decode("".join(encoded.split()), validate=True)
        # Exclusive creation also prevents accidentally replacing a different key.
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as out:
            created = True
            out.write(raw)
        result = subprocess.run([
            "keytool", "-exportcert", "-keystore", str(path), "-alias", alias,
            "-storepass:env", "PHONEBRIDGE_STORE_PASSWORD",
        ], capture_output=True)
        if result.returncode or hashlib.sha256(result.stdout).hexdigest() != expected:
            raise ValueError("Signing certificate does not match the pinned SHA-256, or key could not be opened")
    except Exception:
        # This is a disposable runner copy, never the owner's original keystore.
        if created:
            path.unlink(missing_ok=True)
        raise
    print("Persistent signing certificate verified")


def verify(apksigner, apk):
    expected = fingerprint()
    result = subprocess.run([apksigner, "verify", "--verbose", "--print-certs", apk],
                            capture_output=True, text=True)
    # Build Tools 37 labels certificates by scheme (e.g. "V2 Signer:");
    # older tools use "Signer #1". Never accept a Source Stamp as the APK signer.
    digests = re.findall(
        r"^(?:Signer #\d+|V[1-4](?:\.\d+)? Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        result.stdout, re.MULTILINE,
    )
    signer_counts = re.findall(r"^Number of signers: (\d+)$", result.stdout, re.MULTILINE)
    if result.returncode or signer_counts != ["1"] or {d.lower() for d in digests} != {expected}:
        # apksigner only reports public APK/certificate metadata, never the private key.
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        raise ValueError(f"APK signature verification failed (exit {result.returncode}); parsed certificates: {digests}")
    print(f"Verified {Path(apk).name}: certificate SHA-256 {expected}")


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["restore"]:
            restore()
        elif len(sys.argv) == 4 and sys.argv[1] == "verify":
            verify(sys.argv[2], sys.argv[3])
        else:
            raise ValueError("Usage: apk_signing.py restore | verify APKSIGNER APK")
    except Exception as error:
        # Do not print subprocess output or secret values.
        print(f"Signing failed: {error}", file=sys.stderr)
        sys.exit(1)
