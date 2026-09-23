package com.phonebridge.receiver

data class TimelinePoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val accuracy: String,
    val trigger: String
)
