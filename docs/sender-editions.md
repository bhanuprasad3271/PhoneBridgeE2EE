# Full and Lite Sender

Both are variants of collector using com.phonebridge.collector, the same signing
certificate, Firebase project, encrypted protocol, preferences, and key aliases.
Receiver #145 source is unchanged and can receive either edition's normal records.

Full includes location sharing. Lite excludes its permissions, Play Services
Location dependency, collection workers/services, settings, and special-text
lookups. Installing/opening Lite cancels old location work and turns location
sharing off. Returning to Full requires the user to enable location again.
Pairing, call state, notification deduplication and key storage are preserved.

Lite sends an ordinary encrypted alert explaining that location is unavailable,
plus additive senderEdition/supportsLocation status metadata for future clients.
Receiver #145 displays alerts but does not understand the new capability fields:
its location controls remain visible, and requesting location from Lite may leave
its progress waiting. No location is collected or uploaded. To disable those
controls automatically requires a future Receiver update; pairing and other data
do not require it. Existing Receiver location history is retained.

Each build produces both Sender APKs with the same increasing versionCode. An
equal-version replacement allows switching editions of the same release; newer
releases can replace older ones. After upgrading from #145 to Lite, use Full from
the same or a newer build to return, not the older #145 APK. Do not uninstall or
clear data. Only one Sender edition can be installed at once.

CI tests Android 11 crypto plus Full -> Lite -> Full -> Lite APK replacement,
checking pairing preferences, public identity, decryptability of old ciphertext,
call/deduplication state, location permission/class absence and both UIs. Location
work seeded by the Full test must be cancelled by Lite startup. Signing checks
cover both distributed editions. Firebase live delivery and Realme-specific
background behavior still require device validation.

Update only Sender for this edition change. Receiver #145 may stay installed.
