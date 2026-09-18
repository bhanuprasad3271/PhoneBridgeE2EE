package com.phonebridge.receiver

import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PhoneBridgeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        FcmRegistration.registerToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val pairId = message.data["pairId"]
        val queueId = message.data["queueId"]

        val input = Data.Builder()
            .apply {
                if (!pairId.isNullOrBlank()) putString(ReceiverSyncWorker.KEY_PAIR_ID, pairId)
                if (!queueId.isNullOrBlank()) putString(ReceiverSyncWorker.KEY_QUEUE_ID, queueId)
            }
            .build()

        val request = OneTimeWorkRequestBuilder<ReceiverSyncWorker>()
            .setInputData(input)
            .build()

        WorkManager.getInstance(this).enqueue(request)
    }
}
