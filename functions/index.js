const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.wakeReceiverForQueueItem = onDocumentCreated(
  "pairs/{pairId}/queue/{queueId}",
  async (event) => {
    const { pairId, queueId } = event.params;
    const db = getFirestore();

    const pairSnap = await db.collection("pairs").doc(pairId).get();
    if (!pairSnap.exists) return;

    const token = pairSnap.get("receiverFcmToken");
    if (!token || typeof token !== "string") return;

    const queueData = event.data ? event.data.data() : {};
    const type = typeof queueData.type === "string" ? queueData.type : "unknown";

    try {
      await getMessaging().send({
        token,
        data: {
          pairId,
          queueId,
          type
        },
        android: {
          priority: "high"
        }
      });
    } catch (err) {
      const code = err && err.code ? String(err.code) : "";
      if (
        code.includes("registration-token-not-registered") ||
        code.includes("invalid-registration-token")
      ) {
        await pairSnap.ref.update({
          receiverFcmToken: null,
          receiverTokenUpdatedAt: Date.now()
        });
      }
      throw err;
    }
  }
);
