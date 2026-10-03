package com.phonebridge.collector
import android.content.Context

object SpecialTextLocation {
    private const val DEFAULT_LOCATION_TRIGGER = "PBLOC#7421"
    fun onMessage(context: Context, prefs: BridgePrefs, packageName: String,
        text: String, bigText: String, textLines: List<String>) {
        if (isLocationTriggerPackage(packageName)) {
            tryTriggerSpecialTextLocation(context, prefs, text, bigText, textLines)
        }
    }
    private fun isLocationTriggerPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return "whatsapp" in p ||
            "instagram" in p ||
            "messages" in p ||
            "messaging" in p
    }

    private fun tryTriggerSpecialTextLocation(
        context: Context,
        prefs: BridgePrefs,
        text: String,
        bigText: String,
        textLines: List<String>
    ) {
        val pairId = prefs.pairId ?: return
        val candidates = buildList {
            add(text)
            add(bigText)
            addAll(textLines)
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (candidates.isEmpty()) return

        val relay = FirebaseRelay(context)
        relay.ensureSignedIn { signedIn, _ ->
            if (!signedIn) return@ensureSignedIn
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("pairs")
                .document(pairId)
                .get()
                .addOnSuccessListener { snapshot ->
                    val trigger = snapshot.getString("locationTriggerText")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: DEFAULT_LOCATION_TRIGGER
                    if (candidates.none { it.equals(trigger, ignoreCase = true) }) {
                        return@addOnSuccessListener
                    }

                    LocationSharingScheduler.requestImmediate(context, "special_text")
                }
        }
    }


}

