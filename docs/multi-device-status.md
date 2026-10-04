# Multi-device Receiver and Sender status

Update Receiver plus Full or Lite Sender to get the complete status/build reporting.
Older Sender #149 remains compatible with data delivery and pairing. Its build can
be inferred only for the known historical `0.1.(2000 + run)` version convention;
unrecognized versions display Unknown. New Senders report the build explicitly.

- Pair another phone adds a profile; it never deletes the previous pair.
- The dashboard has no wide device bar. The menu contains Pair another phone and
  Manage devices. Tap a device name to switch; its right-hand ⋮ menu offers Rename
  and Remove device. Names change display text only, never cryptographic keys.
- Manage devices shows Paired / Awaiting pairing / Disconnected and last received
  time. Paired is not proof that the remote phone is currently online.
- Existing Receiver ECDH identity is retained. Each pair ID and Sender key remain
  separate inputs to the existing E2EE key derivation; the wire format is unchanged.
- Room records use a stable local history ID (initially the pair ID). Recovery under
  a new pairing code keeps this history ID and its named places/monitoring settings.
  E2EE continues deriving its transport key from the current pair ID and Sender key.
  Queue acknowledgments happen after persistence.
- Every live profile is watched while Receiver is foregrounded. Background workers
  without an explicit pair sync all live profiles. Inactive devices' queues are not
  mistaken for the selected device's history.
- Remove device requires confirmation and a working internet connection. It marks
  the Firebase pair as disconnected, hides the selected profile/history immediately,
  and leaves other devices connected. Reset/remove/name are no longer separate menu
  items. Older archives remain accessible until explicitly removed.
- Re-pair the same Sender within 6 hours to recover its local history, name and
  settings. Recognition uses the server-confirmed existing Sender public key, not
  the six-digit code, device model or display name. Clearing Sender data/reinstalling
  with a new key prevents recognition. At exactly 6 hours recovery is no longer
  accepted. WorkManager purges expired local history/settings and the removed
  Firebase queue; open/sync also checks local expiry. Android can delay physical
  deletion while the app is force-stopped/offline or battery-restricted.
- The revocation marker retains only pairing identity metadata so offline Senders
  can learn removal after reconnection; it does not restore a removed pairing.
- Both updated Full and Lite Senders watch pair revocation while their process is
  alive, clear local pairing/call/location state, cancel location work, and return
  to the pairing screen. Identity keys are kept for recovery. Before every upload,
  a Firestore transaction checks the current pair, members, keys and revocation;
  revocation racing an upload causes transaction retry/rejection. Offline payloads
  wait in a private encrypted SQLite outbox and drain on reconnection. Removing a
  pairing deletes its pending Sender outbox instead of sending it to a later pair.
  No foreground service or high-frequency polling is introduced.
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
warning clearing, status precedence, explicit/historical build display, same-key
recovery before expiry, expiry deletion, wrong-key isolation and Sender revocation
cleanup/key preservation for each Full/Lite edition.
Real device battery restrictions and end-to-end pairing across physical phones still
require user testing; emulator results are not a guarantee of all-device behavior.
