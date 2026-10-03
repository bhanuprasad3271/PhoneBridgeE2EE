package com.phonebridge.collector
import android.content.Context

/** No trigger lookup, request, or location work in Lite. */
object SpecialTextLocation {
    @Suppress("UNUSED_PARAMETER")
    fun onMessage(context: Context, prefs: BridgePrefs, packageName: String,
        text: String, bigText: String, textLines: List<String>) = Unit
}

