# Android 11 compatibility

Both apps support API 30 (Android 11); the declared minimum remains API 26.
The previous pairing implementation unconditionally used Android Keystore ECDH
key generation, whose PURPOSE_AGREE_KEY was introduced in API 31.

On API 26–30 an ordinary P-256 key pair is generated and its private key is
encrypted with AES-256-GCM using a non-exportable Android Keystore wrapping key.
The authenticated file lives in the private no-backup directory. Public key and
alias are authenticated. The private key is used in app memory during ECDH on
these older versions; it is never stored unencrypted. API 31+ keeps the existing
Keystore-backed identity. Existing wrapped identities are retained after OS
upgrades. Lost or corrupt keys fail rather than silently replacing the identity.
The ECDH/HKDF/AES-GCM wire format has not changed.

Both pairing screens report key failures rather than letting exceptions crash
the activity. Sender notification listener rebind permission errors do not undo
a successful pairing.

CI checks NewApi/InlinedApi usage in all three modules, tests key persistence,
ECDH interoperability, encrypted round trips and tamper rejection on API 30,
and starts both APKs offline. These are not a substitute for full Firebase
pairing, delivery, location, or vendor-specific background tests on real phones.
Distribution signing and increasing version codes are retained; update both
apps in place, without uninstalling or clearing data.
