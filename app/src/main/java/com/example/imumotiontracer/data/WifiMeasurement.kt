package com.example.imumotiontracer.data

import kotlinx.serialization.Serializable

@Serializable
data class WifiMeasurement(
    val row: Int,
    val col: Int,
    val cellTag: String,
    val timestamp: Long,
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int
)
