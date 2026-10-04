# Multi-device Receiver and Sender status

Update Receiver plus Full or Lite Sender to get the complete status/build reporting.
Older Sender #149 remains compatible with data delivery and pairing. Its build can
be inferred only for the known historical `0.1.(2000 + run)` version convention;
unrecognized versions display Unknown. New Senders report the build explicitly.

- Pair another phone adds a profile; it never deletes the previous pair.
- The device button and Switch / manage devices menu select a saved profile.
- Name this device changes only Receiver display text, not pairing IDs or keys.
- Existing Receiver ECDH identity is retained. Each pair ID and Sender key remain
  separate inputs to the existing E2EE key derivation; the wire format is unchanged.
- New Room records carry their pair ID; IDs for calls and other records are also
  namespaced by pair and Sender key. Queue acknowledgments happen after persistence.
- Every live profile is watched while Receiver is foregrounded. Background workers
  without an explicit pair sync all live profiles. Inactive devices' queues are not
  mistaken for the selected device's history.
- Reset/remove disconnect only the chosen profile. Its name and local history stay
  available as an archive; other profiles are not disconnected.
- Old untagged history is preserved in Earlier records. It cannot reliably be
  assigned to a device, and previously deleted Firebase pairings cannot be restored
  from Receiver data. Re-pairing such a Sender creates a new saved profile.
- Saved place names and monitoring identities use per-profile preferences; the
  earlier global preferences remain attached to Earlier records.
- Current status has seven rows in this order: Sender app, Notifications, Call
  access, Location, Last connected, App version, GitHub build. Permission reports
  update current state; resolved reports are not displayed as repeated alert cards.
- Clear warnings affects only the selected profile and preserves current status.
- Last connected is the latest received record time (or historical Sender timestamp
  for old records), not proof of a persistent live connection. No heartbeat, hidden
  collection, foreground service, or periodic location work is added by Receiver.

CI retains the Full/Lite switching tests and adds Android 11 Receiver tests for
profile migration, renaming, key preservation, Room migration, per-device isolation,
warning clearing, status precedence, and explicit/historical build display.
Real device battery restrictions and end-to-end pairing across physical phones still
require user testing; emulator results are not a guarantee of all-device behavior.
