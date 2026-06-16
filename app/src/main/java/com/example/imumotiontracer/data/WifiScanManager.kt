package com.example.imumotiontracer.data

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class WifiScanManager private constructor(private val context: Context) : WifiScanRepository {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val prefs = context.getSharedPreferences("wifi_measurer_prefs", Context.MODE_PRIVATE)

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    override val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _macFilters = MutableStateFlow<Set<String>>(emptySet())
    override val macFilters: StateFlow<Set<String>> = _macFilters.asStateFlow()

    // Map of BSSID to history of (timestampMillis, RSSI)
    private val _signalHistory = ConcurrentHashMap<String, MutableList<Pair<Long, Int>>>()
    override val signalHistory: Map<String, List<Pair<Long, Int>>> get() = _signalHistory

    private val _measurements = MutableStateFlow<List<WifiMeasurement>>(emptyList())
    override val measurements: StateFlow<List<WifiMeasurement>> = _measurements.asStateFlow()

    private val _floorPlanUri = MutableStateFlow<String?>(null)
    override val floorPlanUri: StateFlow<String?> = _floorPlanUri.asStateFlow()

    init {
        // Load initial filters
        _macFilters.value = prefs.getStringSet("mac_filters", emptySet()) ?: emptySet()
        _floorPlanUri.value = prefs.getString("floor_plan_uri", null)
        loadMeasurementsFromFile()
    }

    private fun loadMeasurementsFromFile() {
        try {
            val file = File(context.filesDir, "wifi_measurements.json")
            if (file.exists()) {
                val jsonStr = file.readText()
                _measurements.value = Json.decodeFromString(jsonStr)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveMeasurementsToFile() {
        try {
            val file = File(context.filesDir, "wifi_measurements.json")
            val jsonStr = Json.encodeToString(_measurements.value)
            file.writeText(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun saveMeasurement(measurement: WifiMeasurement) {
        val current = _measurements.value.toMutableList()
        current.add(measurement)
        _measurements.value = current
        saveMeasurementsToFile()
    }

    override fun clearAllMeasurements() {
        _measurements.value = emptyList()
        try {
            val file = File(context.filesDir, "wifi_measurements.json")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setFloorPlanUri(uri: String?) {
        _floorPlanUri.value = uri
        if (uri != null) {
            prefs.edit().putString("floor_plan_uri", uri).apply()
        } else {
            prefs.edit().remove("floor_plan_uri").apply()
        }
    }


    override fun addMacFilter(mac: String): Boolean {
        val normalized = normalizeMac(mac) ?: return false
        val current = _macFilters.value.toMutableSet()
        if (current.add(normalized)) {
            _macFilters.value = current
            prefs.edit().putStringSet("mac_filters", current).apply()
            return true
        }
        return false
    }

    override fun removeMacFilter(mac: String) {
        val current = _macFilters.value.toMutableSet()
        if (current.remove(mac)) {
            _macFilters.value = current
            prefs.edit().putStringSet("mac_filters", current).apply()
            // Also clean up history for this mac
            _signalHistory.remove(mac)
        }
    }

    override fun startScan(): Boolean {
        if (!hasPermissions() || !isGpsEnabled()) {
            return false
        }
        _isScanning.value = true
        val success = wifiManager.startScan()
        if (!success) {
            // If startScan fails (e.g. throttled), immediately trigger update with cached results
            _isScanning.value = false
            updateResults()
        }
        return success
    }

    fun updateResults() {
        try {
            if (hasPermissions()) {
                val results = wifiManager.scanResults ?: emptyList()
                _scanResults.value = results
                
                val count = results.size
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Scan complete: Detected $count networks", Toast.LENGTH_SHORT).show()
                }
                
                // Update history for filtered MACs
                val now = System.currentTimeMillis()
                val filters = _macFilters.value
                for (result in results) {
                    val bssid = result.BSSID.uppercase()
                    if (filters.contains(bssid)) {
                        val historyList = _signalHistory.getOrPut(bssid) { mutableListOf() }
                        historyList.add(Pair(now, result.level))
                        // Limit history to last 50 points
                        if (historyList.size > 50) {
                            historyList.removeAt(0)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            // Handle exception if permission revoked dynamically
        } finally {
            _isScanning.value = false
        }
    }

    override fun hasPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        val nearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        
        return (fineLocation || coarseLocation) && nearbyWifi
    }

    override fun isGpsEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateResults()
        }
    }

    fun registerReceiver() {
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(wifiScanReceiver, intentFilter)
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: WifiScanManager? = null

        fun getInstance(context: Context): WifiScanManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WifiScanManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun normalizeMac(input: String): String? {
            val clean = input.replace(":", "").replace("-", "").replace("\\s".toRegex(), "").uppercase()
            if (clean.length != 12 || !clean.all { it in '0'..'9' || it in 'A'..'F' }) {
                return null
            }
            return clean.chunked(2).joinToString(":")
        }
    }
}
