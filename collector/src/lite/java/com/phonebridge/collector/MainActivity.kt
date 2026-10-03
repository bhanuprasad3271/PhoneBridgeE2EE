package com.phonebridge.collector

class MainActivity : SenderActivity() {
    override fun locationState() = "unsupported"
    override fun stopLocationSharing() = SenderEdition.onProcessStart(this)
}

