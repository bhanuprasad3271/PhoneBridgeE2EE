# PhoneBridge security review — 2026-10-07

Baseline: main `059e979103711feab06071780e42d7e912d96426`.
Scope: checked-in Full/Lite Sender, Receiver, shared crypto, pairing, queue,
device recovery, manifests, local data, signing/build workflow. This is a
source-level audit and targeted regression suite, not a penetration-test
certificate or a guarantee of complete security. No production records or
secrets were read, no live Firebase rules were changed, and no live devices
were exercised. Android 11 emulator tests do not certify every OEM/C21Y device.

## Trust model

The Sender user must knowingly approve pairing and Android permissions.
`System Logs` is the installed Sender label, not an Android system component.
This audit does not add concealment, bypass permissions, or expand collection.
The Receiver device can display the data it has legitimately decrypted; a
compromised/rooted or unlocked endpoint is not protected by E2EE alone.

## Findings and implementation

| Area | Baseline finding | Change / remaining limitation |
| --- | --- | --- |
| Encryption | P-256 ECDH, HKDF-SHA256, AES-256-GCM with random 96-bit nonces and authenticated pair/record/type context | Retained wire format and identities; enforce P-256, validate envelope routing/version/size/type; negative decryption tests. Static ECDH has no forward secrecy. |
| Key storage | API 31+ Keystore ECDH; API 26–30 private identity wrapped by Keystore AES-GCM | Retained compatibility and fail-closed identity loading. Added purpose-bound Keystore AES encryption for local settings and history password. Root/process compromise remains out of scope. |
| Local history (high) | Receiver Room database plaintext | SQLCipher encrypted history plus copy/verify/rename migration; remove original only after validation. Migration interruption and reopen tests. Flash-level forensic erasure of previously stored plaintext cannot be guaranteed. |
| Preferences (high) | Call numbers, call sessions, saved places, profile data and monitoring settings plaintext | Migrate app-owned preferences to encrypted values with preference-name/key authenticated context. Preserve types/listeners; remove old plaintext preference values only after successful encrypted commit. Firebase/WorkManager internal storage is still SDK-managed, sandboxed and backup-excluded. |
| Distributed APK (high) | Persistently signed debug variants remained debuggable | Set debuggable=false only for distribution builds; enforce packaged flag before upload. Same package ID, certificate and versionCode strategy. Instrumentation builds remain debuggable and undistributed. |
| Pair claiming (high) | Client unconditionally updated Sender identity | Transaction validates invitation, expiry and existing ownership; no key overwrite, reject changed Receiver keys. Requires deployed security rules to protect against modified clients. |
| Receiver authorization (high) | Sync trusted pair document before explicitly checking Receiver UID/key | Require both auth UID and local public key; existing Sender-key pin must match. Reject spoofed Sender UID, pair ID, document ID, unsupported AAD version and inner/outer type mismatch before saving/acknowledging. |
| Firebase rules (critical verification gap) | No rules or rules tests in repository; deployed policy unknown | Added deny-by-default rules and isolated emulator tests for ownership, expiration, one-time claim, immutable keys, queue roles, revocation, request fields. NOT deployed by this change. |
| Network | Firebase SDK TLS; cleartext disabled in both manifests; no custom trust-all verifier found | Retained. User-initiated Google Maps links intentionally disclose the selected point to Maps. No claim of certificate pinning. |
| Permissions / exported components | Lite excludes location; internal receiver broadcasts not exported; notification listener protected by Android signature permission | Retained; added server-confirmed pair checks before accepting location control/trigger snapshots. No new permission. |
| Special text | Shared hard-coded default trigger across new pairs | New pairs receive a random per-pair trigger; existing chosen triggers unchanged. Matching third-party text is not cryptographic sender authentication: anyone knowing the trigger could wake a consent-enabled Sender. Stronger request authentication needs a protocol/product decision. |
| Backups | allowBackup=false alone lacks explicit Android 12+ transfer exclusions | Added both legacy backup and cloud/device-transfer exclusions for all app data domains. OEM behavior and previously created backups cannot be certified remotely. |
| Logs | No direct app payload/key logging found in inspected Kotlin; UI shows some raw exception messages | Test fixtures contain synthetic data only; avoid adding payload/key logs. Live logcat, historic CI logs, crash collection and Firebase IAM have not been audited. |
| Device isolation | Per-pair HKDF context, pinned Sender key, per-history local IDs and six-hour recovery | Retained and tested. Same Receiver identity is reused across its pairings, so compromise of that private key affects all of its pairs. Recovery keys prove the same Sender identity, not a human identity. |

## Unresolved security gates — do not claim fully secure

1. **Live Firebase deployment/access verification:** an authorized Firebase
   administrator must review current rules and deploy `firestore.rules` after
   emulator tests pass. Also inspect Authentication providers, IAM/service
   accounts, API restrictions, App Check, quotas, and existing malformed pair
   documents. Admin SDK/service accounts bypass client Security Rules.
2. **Short-code guessing and first-pair authenticity:** a six-digit invitation
   has fewer than 20 bits of entropy. Client throttling or deny-list queries
   cannot prevent distributed guessing. Pending invitations remain gettable
   by authenticated code holders for the existing pairing protocol. Use a
   trusted rate-limited claim service (with App Check/abuse controls), or a
   high-entropy QR/invitation plus user-verifiable key confirmation. Either
   changes deployment/pairing requirements and is not silently substituted.
   A malicious Firebase operator could substitute keys before first pairing;
   later key pinning does not solve initial identity verification.
3. **Strong special-text authorization:** restrict the text to waking an
   already authenticated, fresh Receiver request, or adopt signed request
   tokens. Existing standalone trigger behavior is preserved for now.
4. **Live endpoint testing:** use two consenting test devices and one unrelated
   test identity. Test deployed rules, revoked Sender denial, offline removal,
   Full↔Lite updates and multi-device history. Do not probe other users' data.
5. **Dependency/supply-chain review:** this is not a full SBOM/CVE or repository
   history secret scan. CI actions are still major-version tags, not immutable
   SHA pins. A complete release audit must include those plus installed signing
   certificate checks and cloud access review. Do not rotate the production
   APK key casually: that breaks normal in-place updates.

## Testing and roll-out

- `security-tests/rules.test.cjs`: only runs with a local Firestore emulator;
  uses `demo-phonebridge-security`, synthetic users/keys, no service credentials.
- Shared Android tests: correct Receiver decrypts; a third Receiver, wrong
  pair, modified nonce/ciphertext/record ID/type fail; settings migration and
  authenticated local encryption.
- Receiver tests: envelope ownership; legacy Room history → encrypted history
  preserving data; corrupt target cannot destroy the original. Existing
  multi-device recovery/switching and Sender edition-switch tests remain.
- CI tests Android API 30 (Android 11). A passing emulator run is not proof
  of production rule deployment, initial-pair authenticity, or physical-device
  reliability.
- Both Receiver and the installed Sender edition require updates for all
  client-side protections. No uninstall, key rotation, or re-pairing is intended.
- **After encryption migration, do not downgrade to an older APK:** older
  versions cannot read the new private storage. Updating forward retains data;
  app-data clearing/uninstall loses device-bound keys and locally stored history.
- Six-hour removed-device recovery is preserved. The old Receiver can still
  read/delete its previously queued ciphertext for cleanup; the revoked Sender
  cannot create any new records. Revocation cannot erase data already copied
  or decrypted by an authorized Receiver.

References: https://github.com/sqlcipher/sqlcipher-android,
https://www.zetetic.net/sqlcipher/sqlcipher-api/,
https://firebase.google.com/docs/firestore/security/rules-fields
