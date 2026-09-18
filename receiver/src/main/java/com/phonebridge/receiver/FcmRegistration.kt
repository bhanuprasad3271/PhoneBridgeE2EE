package com.phonebridge.receiver

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

object FcmRegistration {
    fun registerCurrentToken(context: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> registerToken(context, token) }
    }

    fun registerToken(context: Context, token: String) {
        val pairId = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
            .getString("pairId", null)
            ?: return

        com.google.firebase.auth.FirebaseAuth.getInstance().let { auth ->
            fun update() {
                val uid = auth.currentUser?.uid ?: return
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("pairs")
                    .document(pairId)
                    .update(
                        mapOf(
                            "receiverFcmToken" to token,
                            "receiverTokenUid" to uid,
                            "receiverTokenUpdatedAt" to System.currentTimeMillis()
                        )
                    )
            }

            if (auth.currentUser == null) {
                auth.signInAnonymously().addOnSuccessListener { update() }
            } else {
                update()
            }
        }
    }
}
