package com.phonebridge.receiver

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Coordinates leave the offline timeline only when the user taps a Maps action. */
object TimelineMaps {
    fun open(context: Context, point: TimelinePoint) {
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${point.latitude},${point.longitude}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Install Google Maps or a browser to open this location.", Toast.LENGTH_LONG).show()
        }
    }

    fun bindMessage(dialog: AlertDialog, text: String, point: TimelinePoint) {
        val label = "Open in Google Maps"
        val message = SpannableString("$text\n\n$label")
        message.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) { open(widget.context, point) }
        }, message.length - label.length, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        dialog.setMessage(message)
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }
}
