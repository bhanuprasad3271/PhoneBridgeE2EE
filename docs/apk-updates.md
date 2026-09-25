# APK signing and updates

Android updates require the same application ID and a compatible signing certificate.
Increasing the version code does not fix a certificate mismatch. Do not uninstall
an existing Receiver to work around this: its local history, saved names and pairing
can be lost. An APK contains the public certificate, not the signing private key.

## Before enabling distribution

Recover the keystore used for the installed APK if possible. Compare its certificate
SHA-256 with the installed/original APK using `apksigner verify --print-certs`.
The old workflow used the runner's default debug key and did not explicitly archive
it. A replacement key establishes future update compatibility only; it cannot update
an installation signed with a lost key. Receiver and Collector may have been installed
from different old builds, so check both certificates before claiming compatibility.

Keep an independent, secure backup of the original keystore and passwords. GitHub
secrets cannot be downloaded later through the settings UI. Never commit the keystore,
print it in logs, or include it in the APK artifact.

Configure these repository Actions secrets:

| Secret | Value |
| --- | --- |
| `PHONEBRIDGE_KEYSTORE_BASE64` | Base64-encoded durable JKS or PKCS12 keystore |
| `PHONEBRIDGE_STORE_PASSWORD` | Keystore password |
| `PHONEBRIDGE_KEY_ALIAS` | Private-key entry alias |
| `PHONEBRIDGE_KEY_PASSWORD` | Private-key entry password |

Configure repository Actions variable `PHONEBRIDGE_SIGNING_SHA256` with that
certificate's SHA-256 fingerprint (hex, with or without colons). This public fingerprint
pins the expected identity, independently of the key secret. Do not change it casually.

## Build behavior

Pull requests compile and test, but upload no installable APKs. Main builds restore
the durable key into a permission-restricted temporary file, verify its certificate,
build both debug APKs with that key, and verify each APK's actual signature before
uploading. Missing secrets or mismatched certificates stop publication; there is no
fallback to a new debug key. The runner copy is removed when the build step exits.

Version codes are `1000 + GITHUB_RUN_NUMBER`; do not reset this workflow's counter
without preserving monotonic version codes. `APK-BUILD.txt` records the source commit,
version code and certificate fingerprint. Local debug builds remain available, but
must not be distributed as updates unless explicitly configured with the durable key.

After setup, verify two successive main builds have the pinned certificate, then
test an in-place update on a test device and confirm local data remains intact.
An existing installation from an older, differently signed build is a separate
migration problem and must not be assumed compatible.
