# Temporary build account

This private repository starts from bhanuprasad3271/PhoneBridgeE2EE main commit
d7f69c1bd3863dc32c2b17fb91f2ec893d616746 (original build #143).
Application sources are copied unchanged. Keep all subsequent changes for sync back
to the original repository; no source change should be dropped on return.

The same Firebase configuration, application IDs, durable signing key and certificate
pin are required. Never commit secrets or distribute APKs signed with another key.

Temporary CI version codes are PHONEBRIDGE_VERSION_CODE_BASE + GITHUB_RUN_NUMBER,
with base 1143 unless overridden by the repository variable. On returning to the
original account, set the base so its next version code exceeds the highest version
actually distributed from either account. Do not revert blindly to 1000 + run number.
Record distributed version codes from APK-BUILD.txt before returning.

Source history before migration remains in the original repository. This temporary
repository uses a snapshot of the named source commit as its baseline. Compare later
changes against that baseline when syncing back.


## Returned to the main account — 2026-10-02

All application changes through temporary commit bd8f29ac41908a7102cdb8894c4bff9d4b26217a (build #5) were imported into bhanuprasad3271/PhoneBridgeE2EE on top of its existing main history. Future work and APK builds belong in the main repository.

The main workflow accepts separate Firebase configurations when present, falling back to its existing combined FIREBASE_GOOGLE_SERVICES secret. It retains the original durable signing secrets and pinned certificate. Distributed version codes are now 2000 + GITHUB_RUN_NUMBER, above temporary build #5 (1148).

Imported work: live receiver refresh; Google Maps point links and timeline column; daily/weekly record navigation; saved identity monitoring; background monitoring searches and paged results; stable message identities and duplicate protection. Source records are not deleted by the display duplicate filter.
