const { before, after, beforeEach, test } = require('node:test');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, collection, setDoc, updateDoc, getDoc, getDocs, deleteDoc, serverTimestamp } = require('firebase/firestore');
if (!/^127\.0\.0\.1:|^localhost:/.test(process.env.FIRESTORE_EMULATOR_HOST || '')) throw Error('Disposable emulator required');
let env;
const keyR = 'R'.repeat(124), keyS = 'S'.repeat(124);
const pair = (extra = {}) => ({ receiverUid: 'receiver-A', receiverPublicKeyB64: keyR, collectorUid: 'sender-A',
  collectorPublicKeyB64: keyS, createdAtMs: Date.now(), expiresAtMs: Date.now() + 600000,
  locationTriggerText: 'PBLOC#test', ...extra });
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const ref = (uid, id = '123456') => doc(db(uid), 'pairs', id);
const queue = (uid, pairId = '123456', id = 'record-1') => doc(db(uid), 'pairs', pairId, 'queue', id);
const payload = (extra = {}) => ({ id: 'record-1', pairId: '123456', type: 'message', senderUid: 'sender-A',
  ciphertextB64: 'A'.repeat(44), nonceB64: 'A'.repeat(16), aadVersion: 1, createdAt: Date.now(), ...extra });
const seed = async (data = pair(), id = '123456') => env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), 'pairs', id), data));
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-phonebridge-security',
  firestore: { rules: fs.readFileSync('../firestore.rules', 'utf8') } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

test('only designated Receiver can read queued data; Sender can create but not read', async () => {
  await seed(); await assertSucceeds(setDoc(queue('sender-A'), payload()));
  await assertSucceeds(getDoc(queue('receiver-A')));
  for (const uid of [null, 'stranger', 'receiver-B', 'sender-A']) await assertFails(getDoc(queue(uid)));
  await assertFails(getDocs(collection(db('receiver-B'), 'pairs', '123456', 'queue')));
});
test('cross-pair writes, spoofed identity, plaintext fields and changed ciphertext are denied', async () => {
  await seed(); await seed(pair({ receiverUid: 'receiver-B', collectorUid: 'sender-B' }), '654321');
  await assertFails(setDoc(queue('sender-A', '654321'), payload({ pairId: '654321' })));
  await assertFails(setDoc(queue('sender-B'), payload()));
  await assertFails(setDoc(queue('sender-A'), payload({ senderUid: 'sender-B' })));
  await assertFails(setDoc(queue('sender-A'), payload({ pairId: '654321' })));
  await assertFails(setDoc(queue('sender-A'), payload({ body: 'private text' })));
  await assertFails(setDoc(queue('sender-A'), payload({ aadVersion: 2 })));
  await assertFails(setDoc(queue('sender-A'), payload({ nonceB64: 'bad' })));
  const data = payload(); await assertSucceeds(setDoc(queue('sender-A'), data));
  await assertSucceeds(setDoc(queue('sender-A'), data)); // idempotent transport retry
  await assertFails(updateDoc(queue('sender-A'), { ciphertextB64: 'B'.repeat(44) }));
});
test('only owning Receiver can acknowledge or delete data', async () => {
  await seed(); await setDoc(queue('sender-A'), payload());
  for (const uid of [null, 'sender-A', 'receiver-B']) await assertFails(deleteDoc(queue(uid)));
  await assertSucceeds(deleteDoc(queue('receiver-A')));
});
test('pending invitation allows one claim and denies expired/self/second claim', async () => {
  const pending = pair({ collectorUid: null, collectorPublicKeyB64: null });
  await assertSucceeds(setDoc(ref('receiver-A'), pending));
  const claim = uid => ({ collectorUid: uid, collectorPublicKeyB64: keyS, collectorPairedAt: serverTimestamp() });
  await assertFails(updateDoc(ref('receiver-A'), claim('receiver-A')));
  await assertSucceeds(updateDoc(ref('sender-A'), claim('sender-A')));
  await assertFails(updateDoc(ref('sender-B'), claim('sender-B')));
  await seed({ ...pending, expiresAtMs: Date.now() - 1 }, '654321');
  await assertFails(updateDoc(ref('sender-B', '654321'), claim('sender-B')));
});
test('identities cannot be substituted and pairs cannot be enumerated or overwritten', async () => {
  await seed();
  for (const uid of ['receiver-A', 'sender-A', 'stranger']) {
    await assertFails(updateDoc(ref(uid), { receiverPublicKeyB64: 'X'.repeat(124) }));
    await assertFails(updateDoc(ref(uid), { collectorPublicKeyB64: 'X'.repeat(124) }));
    await assertFails(updateDoc(ref(uid), { receiverUid: 'receiver-B' }));
    await assertFails(getDocs(collection(db(uid), 'pairs')));
    await assertFails(deleteDoc(ref(uid)));
  }
  await assertFails(setDoc(ref('receiver-B'), pair({ receiverUid: 'receiver-B' })));
});
test('revocation stops Sender writes and cannot be undone; Receiver can clean old ciphertext', async () => {
  await seed(); await setDoc(queue('sender-A'), payload());
  await assertFails(updateDoc(ref('sender-A'), { disconnectedAtMs: Date.now() }));
  await assertSucceeds(updateDoc(ref('receiver-A'), { disconnectedAtMs: Date.now() }));
  await assertSucceeds(getDoc(ref('sender-A'))); // Sender must discover revocation
  await assertFails(setDoc(queue('sender-A', '123456', 'record-2'), payload({ id: 'record-2' })));
  await assertFails(updateDoc(ref('receiver-A'), { disconnectedAtMs: null }));
  await assertSucceeds(getDocs(collection(db('receiver-A'), 'pairs', '123456', 'queue')));
  await assertSucceeds(deleteDoc(queue('receiver-A')));
});
test('location requests restricted to Receiver, status cannot change keys or request ID', async () => {
  await seed();
  const request = { locationRequestId: 'request-1', locationRequestedAtMs: Date.now(), locationRequestStatusId: 'request-1',
    locationRequestStatus: 'requested', locationRequestStatusAtMs: Date.now() };
  await assertFails(updateDoc(ref('sender-A'), request));
  await assertFails(updateDoc(ref('receiver-B'), request));
  await assertSucceeds(updateDoc(ref('receiver-A'), request));
  for (const status of ['sender_received', 'encrypting_sending', 'interim_sent', 'waiting_fresh', 'send_failed', 'sent']) {
    await assertSucceeds(updateDoc(ref('sender-A'), { locationRequestStatus: status, locationRequestStatusAtMs: Date.now() }));
  }
  await assertFails(updateDoc(ref('sender-A'), { locationRequestStatusId: 'another-request' }));
});
test('unknown collections and unauthenticated pairing are denied', async () => {
  await assertFails(setDoc(ref(null), pair({ collectorUid: null, collectorPublicKeyB64: null })));
  await assertFails(setDoc(doc(db('receiver-A'), 'public', 'secret'), { secret: 'x' }));
});
