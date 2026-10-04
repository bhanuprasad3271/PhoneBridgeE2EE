package com.phonebridge.collector

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SenderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SenderEdition.onProcessStart(this)
        SenderPairMonitor.start(this)
    }
}

class EditionUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            SenderEdition.onProcessStart(context)
        }
    }
}
