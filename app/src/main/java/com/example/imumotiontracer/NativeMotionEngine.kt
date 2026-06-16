package com.example.imumotiontracer

/**
 * Kotlin wrapper for the native C++ MotionEngine.
 * Handles JNI communication for real-time step counting, filtering, and path tracking.
 */
class NativeMotionEngine(
    stepLength: Float = 0.75f,
    sensitivity: Float = 1.2f,
    filterAlpha: Float = 0.15f
) {
    // Stores the memory pointer to the native C++ MotionEngine instance
    private var nativePtr: Long = 0

    init {
        // Load the shared library compiled by CMake/NDK
        System.loadLibrary("motion-engine")
        // Instantiate the native C++ object and store its pointer
        nativePtr = createEngine(stepLength, sensitivity, filterAlpha)
    }

    /**
     * Reconfigures parameters dynamically (e.g. from UI sliders).
     */
    fun setParameters(stepLength: Float, sensitivity: Float, filterAlpha: Float) {
        if (nativePtr != 0L) {
            setParameters(nativePtr, stepLength, sensitivity, filterAlpha)
        }
    }

    /**
     * Process raw accelerometer data.
     * @return true if a step was registered.
     */
    fun processSensorData(x: Float, y: Float, z: Float, timestampNs: Long): Boolean {
        return if (nativePtr != 0L) {
            processSensorData(nativePtr, x, y, z, timestampNs)
        } else {
            false
        }
    }

    /**
     * Add a step in the given direction and update internal path.
     */
    fun addStep(headingDegrees: Float, isSimulator: Boolean) {
        if (nativePtr != 0L) {
            addStep(nativePtr, headingDegrees, isSimulator)
        }
    }

    /**
     * Update the heading continuously.
     */
    fun updateHeading(headingDegrees: Float) {
        if (nativePtr != 0L) {
            updateHeading(nativePtr, headingDegrees)
        }
    }

    /**
     * Fuses the current PDR coordinate with Wi-Fi coordinate.
     */
    fun fuseLocation(wifiX: Float, wifiY: Float, beta: Float) {
        if (nativePtr != 0L) {
            fuseLocation(nativePtr, wifiX, wifiY, beta)
        }
    }

    /**
     * Reset the engine state (steps, position, and path points).
     */
    fun reset() {
        if (nativePtr != 0L) {
            resetEngine(nativePtr)
        }
    }

    /**
     * Get the total detected steps.
     */
    fun getStepCount(): Int {
        return if (nativePtr != 0L) {
            getStepCount(nativePtr)
        } else {
            0
        }
    }

    /**
     * Get the total estimated distance in meters.
     */
    fun getDistance(): Float {
        return if (nativePtr != 0L) {
            getDistance(nativePtr)
        } else {
            0.0f
        }
    }

    /**
     * Get the current path points.
     * Returns an array of interleaved x, y points: [x0, y0, x1, y1, ...]
     */
    fun getPathPoints(): FloatArray {
        if (nativePtr == 0L) return floatArrayOf(0.0f, 0.0f)
        return getPathPoints(nativePtr) ?: floatArrayOf(0.0f, 0.0f)
    }

    /**
     * Clean up native memory allocations when this object is garbage collected.
     */
    fun destroy() {
        if (nativePtr != 0L) {
            destroyEngine(nativePtr)
            nativePtr = 0
        }
    }

    protected fun finalize() {
        destroy()
    }

    // ============================================================================
    // NATIVE EXTERNAL DECLARATIONS (JNI mappings)
    // ============================================================================

    private external fun createEngine(stepLength: Float, sensitivity: Float, filterAlpha: Float): Long
    private external fun destroyEngine(nativePtr: Long)
    private external fun setParameters(nativePtr: Long, stepLength: Float, sensitivity: Float, filterAlpha: Float)
    private external fun processSensorData(nativePtr: Long, x: Float, y: Float, z: Float, timestampNs: Long): Boolean
    private external fun updateHeading(nativePtr: Long, headingDegrees: Float)
    private external fun addStep(nativePtr: Long, headingDegrees: Float, isSimulator: Boolean)
    private external fun fuseLocation(nativePtr: Long, wifiX: Float, wifiY: Float, beta: Float)
    private external fun resetEngine(nativePtr: Long)
    private external fun getStepCount(nativePtr: Long): Int
    private external fun getDistance(nativePtr: Long): Float
    private external fun getPathPoints(nativePtr: Long): FloatArray?
}
