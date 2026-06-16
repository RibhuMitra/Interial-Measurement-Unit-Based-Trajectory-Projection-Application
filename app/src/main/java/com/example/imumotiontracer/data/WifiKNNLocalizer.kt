package com.example.imumotiontracer.data

import kotlin.math.sqrt

class WifiKNNLocalizer(private val k: Int = 3) {
    
    // Feature vector format: List of unique BSSIDs in the fingerprint database
    private var featureBssids: List<String> = emptyList()
    
    // Fingerprint representation
    data class Fingerprint(
        val x: Float, // grid column index (0.0 to 7.0)
        val y: Float, // grid row index (0.0 to 7.0)
        val rssiVector: FloatArray
    )
    
    private var fingerprints: List<Fingerprint> = emptyList()

    /**
     * Rebuilds the fingerprint database from historical measurements.
     */
    fun buildDatabase(measurements: List<WifiMeasurement>) {
        if (measurements.isEmpty()) {
            featureBssids = emptyList()
            fingerprints = emptyList()
            return
        }
        
        // 1. Identify all unique BSSIDs to establish feature dimension
        featureBssids = measurements.map { it.bssid.uppercase() }.distinct().sorted()
        
        // 2. Group measurements by grid cell (row, col)
        val groupedByCell = measurements.groupBy { Pair(it.row, it.col) }
        val newFingerprints = mutableListOf<Fingerprint>()
        
        for ((coords, cellMs) in groupedByCell) {
            val (row, col) = coords
            
            // Vector representing average RSSI at this grid position for each feature BSSID
            val rssiVector = FloatArray(featureBssids.size) { -100f } // -100dBm represents default absent signal
            
            val msByBssid = cellMs.groupBy { it.bssid.uppercase() }
            for (i in featureBssids.indices) {
                val bssid = featureBssids[i]
                val bssidMs = msByBssid[bssid]
                if (bssidMs != null) {
                    val avgRssi = bssidMs.map { it.rssi.toFloat() }.average().toFloat()
                    rssiVector[i] = avgRssi
                }
            }
            
            // Map grid col to x, grid row to y
            newFingerprints.add(Fingerprint(col.toFloat(), row.toFloat(), rssiVector))
        }
        
        fingerprints = newFingerprints
    }

    /**
     * Runs KNN regression to estimate grid position (x, y) based on current live Wi-Fi scan.
     * Returns null if database is not loaded or no target AP is visible.
     */
    fun predictLocation(currentScan: List<android.net.wifi.ScanResult>): Pair<Float, Float>? {
        if (fingerprints.isEmpty() || featureBssids.isEmpty()) return null
        
        // 1. Construct live scan feature vector
        val currentVector = FloatArray(featureBssids.size) { -100f }
        val scanMap = currentScan.associateBy({ it.BSSID.uppercase() }, { it.level.toFloat() })
        
        var matchedFeatures = 0
        for (i in featureBssids.indices) {
            val bssid = featureBssids[i]
            if (scanMap.containsKey(bssid)) {
                currentVector[i] = scanMap[bssid]!!
                matchedFeatures++
            }
        }
        
        // If none of our fingerprint BSSIDs are currently visible, cannot localize
        if (matchedFeatures == 0) return null
        
        // 2. Compute Euclidean distance from live vector to each fingerprint
        val distances = fingerprints.map { fingerprint ->
            var sumSq = 0f
            for (i in featureBssids.indices) {
                val diff = currentVector[i] - fingerprint.rssiVector[i]
                sumSq += diff * diff
            }
            val dist = sqrt(sumSq)
            Pair(fingerprint, dist)
        }.sortedBy { it.second }
        
        // 3. Select K nearest neighbors
        val nearest = distances.take(k)
        if (nearest.isEmpty()) return null
        
        // 4. Compute inverse-distance weighted average position
        var totalWeight = 0f
        var weightedX = 0f
        var weightedY = 0f
        
        for ((fingerprint, dist) in nearest) {
            // Protect against divide-by-zero if distance is zero
            val weight = 1f / (dist + 0.001f)
            weightedX += fingerprint.x * weight
            weightedY += fingerprint.y * weight
            totalWeight += weight
        }
        
        return Pair(weightedX / totalWeight, weightedY / totalWeight)
    }
}
