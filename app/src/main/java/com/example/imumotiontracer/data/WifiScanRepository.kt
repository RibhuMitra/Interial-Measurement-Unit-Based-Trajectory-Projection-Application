package com.example.imumotiontracer.data

import android.net.wifi.ScanResult
import kotlinx.coroutines.flow.StateFlow

interface WifiScanRepository {
    val scanResults: StateFlow<List<ScanResult>>
    val isScanning: StateFlow<Boolean>
    val macFilters: StateFlow<Set<String>>
    val signalHistory: Map<String, List<Pair<Long, Int>>>
    val measurements: StateFlow<List<WifiMeasurement>>
    val floorPlanUri: StateFlow<String?>
    
    fun addMacFilter(mac: String): Boolean
    fun removeMacFilter(mac: String)
    fun startScan(): Boolean
    fun hasPermissions(): Boolean
    fun isGpsEnabled(): Boolean
    fun saveMeasurement(measurement: WifiMeasurement)
    fun clearAllMeasurements()
    fun setFloorPlanUri(uri: String?)
}
