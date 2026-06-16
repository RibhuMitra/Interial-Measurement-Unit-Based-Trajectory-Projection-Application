package com.example.imumotiontracer.ui.main

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.imumotiontracer.NativeMotionEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Color Scheme: Dark Obsidian, Slate Steel, Glowing Cyan, Neon Green, Cyber Orange
private val DarkObsidian = Color(0xFF0D0E12)
private val SlateSteel = Color(0xFF1B1D26)
private val BorderGray = Color(0xFF2E3240)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonGreen = Color(0xFF39FF14)
private val CyberOrange = Color(0xFFFF5722)
private val MutedText = Color(0xFF8E95A5)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Core Native Engine Instance
    val nativeEngine = remember { NativeMotionEngine(stepLength = 0.42f, sensitivity = 1.0f, filterAlpha = 0.15f) }

    // Ensure cleanup of native resources
    DisposableEffect(Unit) {
        onDispose {
            nativeEngine.destroy()
        }
    }

    // 2. Settings States
    var stepLength by remember { mutableStateOf(0.42f) }
    var sensitivity by remember { mutableStateOf(1.0f) }
    var filterAlpha by remember { mutableStateOf(0.15f) }

    // Sync parameters with C++ whenever they change
    LaunchedEffect(stepLength, sensitivity, filterAlpha) {
        nativeEngine.setParameters(stepLength, sensitivity, filterAlpha)
    }

    // 3. Telemetry States
    var stepCount by remember { mutableStateOf(0) }
    var distance by remember { mutableStateOf(0.0f) }
    var rawHeading by remember { mutableStateOf(0.0f) } // degrees relative to North
    var pathCoordinates by remember { mutableStateOf(floatArrayOf(0f, 0f)) } // x0, y0, x1, y1...

    // 4. Live Sensor Chart States
    var accelHistory by remember { mutableStateOf(List(80) { 9.8f }) }
    var filteredHistory by remember { mutableStateOf(List(80) { 9.8f }) }
    var currentRawMag by remember { mutableStateOf(9.8f) }

    // 5. App Mode (True = Simulator, False = Live Mobile Sensors)
    var simMode by remember { mutableStateOf(true) }
    var simHeading by remember { mutableStateOf(0.0f) } // slider heading

    // 6. Interactive Canvas Transform States
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasScale by remember { mutableStateOf(1.0f) }

    // 7. Simulation Script Job
    var activeScriptJob by remember { mutableStateOf<Job?>(null) }
    var activeScriptName by remember { mutableStateOf<String?>(null) }

    // Helper to stop running scripts
    fun stopActiveScript() {
        activeScriptJob?.cancel()
        activeScriptJob = null
        activeScriptName = null
    }

    // Helper to reset canvas viewport
    fun recenterCanvas() {
        canvasOffset = Offset.Zero
        canvasScale = 1.0f
    }

    // Helper to reset engine
    fun resetEngineAndStats() {
        stopActiveScript()
        nativeEngine.reset()
        stepCount = 0
        distance = 0.0f
        pathCoordinates = floatArrayOf(0f, 0f)
        recenterCanvas()
    }

    // 8. Mobile Sensors Lifecycle Listener
    DisposableEffect(simMode) {
        if (!simMode) {
            stopActiveScript()
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            // Dynamic LPF accumulator
            var localFilteredMag = 9.80665f

            val sensorListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            val timestampNs = event.timestamp

                            // 1. Process via Native C++ step detector
                            val stepDetected = nativeEngine.processSensorData(x, y, z, timestampNs)
                            if (stepDetected) {
                                // Add step in current heading direction
                                nativeEngine.addStep(rawHeading, false)
                                
                                // Fetch updated stats from C++
                                stepCount = nativeEngine.getStepCount()
                                distance = nativeEngine.getDistance()
                                pathCoordinates = nativeEngine.getPathPoints()
                            }

                            // 2. Fetch raw sensor magnitude for graph
                            val rawMag = sqrt(x * x + y * y + z * z)
                            currentRawMag = rawMag

                            // 3. Keep a filtered copy locally just for the graph visualization
                            localFilteredMag = filterAlpha * rawMag + (1f - filterAlpha) * localFilteredMag

                            // Update UI rolling histories
                            accelHistory = accelHistory.drop(1) + rawMag
                            filteredHistory = filteredHistory.drop(1) + localFilteredMag
                        }
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            val rotationMatrix = FloatArray(9)
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(rotationMatrix, orientation)
                            
                            // Azimuth (yaw) is orientation[0] in radians (-PI to PI)
                            var azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                            if (azimuthDeg < 0) {
                                azimuthDeg += 360f
                            }
                            rawHeading = azimuthDeg
                            nativeEngine.updateHeading(rawHeading)
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
            sensorManager.registerListener(sensorListener, rotationVector, SensorManager.SENSOR_DELAY_UI)

            onDispose {
                sensorManager.unregisterListener(sensorListener)
            }
        } else {
            onDispose {}
        }
    }

    // Main layout wrapper
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkObsidian)
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- HEADER BAR ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "IMU Motion Tracer",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "C++ NDK Pedestrian Dead Reckoning",
                    color = MutedText,
                    fontSize = 11.sp
                )
            }
            
            // Mode Indicator Badge
            Box(
                modifier = Modifier
                    .background(
                        if (simMode) SlateSteel else NeonCyan.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        1.dp,
                        if (simMode) BorderGray else NeonCyan,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (simMode) "Mode: Simulator" else "Mode: Sensors Active",
                    color = if (simMode) Color.White else NeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- TELEMETRY SCOREBOARD ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Steps Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = SlateSteel),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("STEPS", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "$stepCount",
                        color = NeonGreen,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Distance Card
            Card(
                modifier = Modifier.weight(1.2f),
                colors = CardDefaults.cardColors(containerColor = SlateSteel),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("EST. DISTANCE", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format("%.2f m", distance),
                        color = NeonCyan,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Heading Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = SlateSteel),
                shape = RoundedCornerShape(8.dp)
            ) {
                val dispHeading = if (simMode) simHeading else rawHeading
                val cardinal = when (dispHeading.toInt()) {
                    in 338..360, in 0..22 -> "N"
                    in 23..67 -> "NE"
                    in 68..112 -> "E"
                    in 113..157 -> "SE"
                    in 158..202 -> "S"
                    in 203..247 -> "SW"
                    in 248..292 -> "W"
                    in 293..337 -> "NW"
                    else -> "N"
                }
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("HEADING", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format("%d° %s", dispHeading.toInt(), cardinal),
                        color = CyberOrange,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- PATH CANVAS ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSteel),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderGray)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Interactive Vector Canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                canvasOffset += pan
                                canvasScale = (canvasScale * zoom).coerceIn(0.5f, 5.0f)
                            }
                        }
                ) {
                    val w = size.width
                    val h = size.height
                    val center = Offset(w / 2f, h / 2f)

                    // Draw grid lines (radar pattern)
                    val gridSpacing = 40f * canvasScale
                    val startX = (canvasOffset.x % gridSpacing)
                    val startY = (canvasOffset.y % gridSpacing)

                    // Vertical grid lines
                    var xPos = startX
                    while (xPos < w) {
                        drawLine(
                            color = BorderGray.copy(alpha = 0.4f),
                            start = Offset(xPos, 0f),
                            end = Offset(xPos, h),
                            strokeWidth = 1f
                        )
                        xPos += gridSpacing
                    }

                    // Horizontal grid lines
                    var yPos = startY
                    while (yPos < h) {
                        drawLine(
                            color = BorderGray.copy(alpha = 0.4f),
                            start = Offset(0f, yPos),
                            end = Offset(w, yPos),
                            strokeWidth = 1f
                        )
                        yPos += gridSpacing
                    }

                    // Grid Origin Axes
                    drawLine(
                        color = BorderGray,
                        start = Offset(0f, center.y + canvasOffset.y),
                        end = Offset(w, center.y + canvasOffset.y),
                        strokeWidth = 2f
                    )
                    drawLine(
                        color = BorderGray,
                        start = Offset(center.x + canvasOffset.x, 0f),
                        end = Offset(center.x + canvasOffset.x, h),
                        strokeWidth = 2f
                    )

                    // Draw the PDR walking path inside transform matrix
                    withTransform({
                        translate(center.x + canvasOffset.x, center.y + canvasOffset.y)
                        scale(canvasScale, canvasScale, pivot = Offset.Zero)
                    }) {
                        // Coordinates map: 1 meter = 100 pixels
                        val pixelsPerMeter = 100f

                        if (pathCoordinates.size >= 2) {
                            val path = Path()
                            path.moveTo(pathCoordinates[0] * pixelsPerMeter, pathCoordinates[1] * pixelsPerMeter)
                            
                            for (i in 2 until pathCoordinates.size step 2) {
                                path.lineTo(pathCoordinates[i] * pixelsPerMeter, pathCoordinates[i + 1] * pixelsPerMeter)
                            }

                            // Render neon trail
                            drawPath(
                                path = path,
                                color = NeonCyan,
                                style = Stroke(width = 4f, miter = 1f)
                            )

                            // Render step circles
                            for (i in 0 until pathCoordinates.size step 2) {
                                val pt = Offset(pathCoordinates[i] * pixelsPerMeter, pathCoordinates[i + 1] * pixelsPerMeter)
                                
                                val circleColor = if (i == 0) {
                                    CyberOrange // start node
                                } else if (i == pathCoordinates.size - 2) {
                                    NeonGreen // current position node
                                } else {
                                    NeonCyan
                                }
                                
                                drawCircle(
                                    color = circleColor,
                                    radius = if (i == pathCoordinates.size - 2) 8f else 5f,
                                    center = pt
                                )
                            }

                            // Draw direction arrow at the latest coordinate
                            if (pathCoordinates.size >= 2) {
                                val lastIdx = pathCoordinates.size - 2
                                val curX = pathCoordinates[lastIdx] * pixelsPerMeter
                                val curY = pathCoordinates[lastIdx + 1] * pixelsPerMeter

                                val angleDeg = if (simMode) simHeading else rawHeading
                                val angleRad = Math.toRadians(angleDeg.toDouble())

                                // Vector direction pointing forward
                                val arrowLength = 25f
                                val headX = curX + arrowLength * sin(angleRad).toFloat()
                                val headY = curY - arrowLength * cos(angleRad).toFloat()

                                // Arrow lines
                                drawLine(
                                    color = CyberOrange,
                                    start = Offset(curX, curY),
                                    end = Offset(headX, headY),
                                    strokeWidth = 5f
                                )
                            }
                        }
                    }
                }

                // Zoom / Pan Overlay Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = { canvasScale = (canvasScale + 0.2f).coerceAtMost(5.0f) },
                        modifier = Modifier.background(DarkObsidian.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    ) {
                        Text("➕", color = Color.White)
                    }
                    IconButton(
                        onClick = { canvasScale = (canvasScale - 0.2f).coerceAtLeast(0.5f) },
                        modifier = Modifier.background(DarkObsidian.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    ) {
                        Text("➖", color = Color.White)
                    }
                    IconButton(
                        onClick = { recenterCanvas() },
                        modifier = Modifier.background(DarkObsidian.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    ) {
                        Text("🎯", color = Color.White)
                    }
                }

                // Helper drag instruction
                Text(
                    text = "Drag to Pan | Pinch to Zoom",
                    color = MutedText,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- MODE SELECTION ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateSteel),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderGray)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "INTEGRATION MODE",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { simMode = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (simMode) NeonCyan.copy(alpha = 0.2f) else DarkObsidian,
                            contentColor = if (simMode) NeonCyan else MutedText
                        ),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (simMode) NeonCyan else BorderGray)
                    ) {
                        Text("Simulator")
                    }

                    Button(
                        onClick = { simMode = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!simMode) NeonCyan.copy(alpha = 0.2f) else DarkObsidian,
                            contentColor = if (!simMode) NeonCyan else MutedText
                        ),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (!simMode) NeonCyan else BorderGray)
                    ) {
                        Text("Live Sensors")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- SIMULATOR CONTROLLERS ---
        if (simMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SlateSteel),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderGray)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "WALK SIMULATOR",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Simulate stepping and turning manually or using script paths.",
                        color = MutedText,
                        fontSize = 10.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                nativeEngine.addStep(simHeading, true)
                                stepCount = nativeEngine.getStepCount()
                                distance = nativeEngine.getDistance()
                                pathCoordinates = nativeEngine.getPathPoints()
                            },
                            modifier = Modifier.weight(1.2f),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Walk Step 🚶", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { simHeading = (simHeading - 15f + 360f) % 360f },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkObsidian, contentColor = Color.White),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, BorderGray)
                        ) {
                            Text("↩️ Turn L")
                        }

                        Button(
                            onClick = { simHeading = (simHeading + 15f) % 360f },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkObsidian, contentColor = Color.White),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, BorderGray)
                        ) {
                            Text("Turn R ↪️")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Heading Slider
                    Text(
                        text = "Simulated Heading: ${simHeading.toInt()}°",
                        color = Color.White,
                        fontSize = 11.sp
                    )
                    Slider(
                        value = simHeading,
                        onValueChange = { simHeading = it },
                        valueRange = 0f..359f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyberOrange,
                            activeTrackColor = CyberOrange.copy(alpha = 0.5f)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Auto Walking Scripts",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Scripts Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Square", "Zig-Zag", "Spiral", "Wander").forEach { pattern ->
                            val isRunning = activeScriptName == pattern
                            Button(
                                onClick = {
                                    if (isRunning) {
                                        stopActiveScript()
                                    } else {
                                        stopActiveScript()
                                        activeScriptName = pattern
                                        activeScriptJob = coroutineScope.launch {
                                            when (pattern) {
                                                "Square" -> {
                                                    simHeading = 0f
                                                    for (side in 0..3) {
                                                        for (step in 1..4) {
                                                            nativeEngine.addStep(simHeading, true)
                                                            stepCount = nativeEngine.getStepCount()
                                                            distance = nativeEngine.getDistance()
                                                            pathCoordinates = nativeEngine.getPathPoints()
                                                            delay(600)
                                                        }
                                                        simHeading = (simHeading + 90f) % 360f
                                                        delay(300)
                                                    }
                                                }
                                                "Zig-Zag" -> {
                                                    simHeading = 45f
                                                    for (i in 0..12) {
                                                        nativeEngine.addStep(simHeading, true)
                                                        stepCount = nativeEngine.getStepCount()
                                                        distance = nativeEngine.getDistance()
                                                        pathCoordinates = nativeEngine.getPathPoints()
                                                        delay(500)
                                                        if (i % 3 == 0) {
                                                            simHeading = if (simHeading == 45f) 315f else 45f
                                                        }
                                                    }
                                                }
                                                "Spiral" -> {
                                                    var currentRadiusSteps = 1
                                                    simHeading = 0f
                                                    for (lap in 0..6) {
                                                        for (step in 1..currentRadiusSteps) {
                                                            nativeEngine.addStep(simHeading, true)
                                                            stepCount = nativeEngine.getStepCount()
                                                            distance = nativeEngine.getDistance()
                                                            pathCoordinates = nativeEngine.getPathPoints()
                                                            delay(400)
                                                        }
                                                        simHeading = (simHeading + 90f) % 360f
                                                        currentRadiusSteps += 1
                                                    }
                                                }
                                                "Wander" -> {
                                                    for (i in 1..25) {
                                                        // Perturb heading randomly
                                                        simHeading = (simHeading + (-45..45).random() + 360f) % 360f
                                                        nativeEngine.addStep(simHeading, true)
                                                        stepCount = nativeEngine.getStepCount()
                                                        distance = nativeEngine.getDistance()
                                                        pathCoordinates = nativeEngine.getPathPoints()
                                                        delay(500)
                                                    }
                                                }
                                            }
                                            activeScriptName = null
                                            activeScriptJob = null
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRunning) CyberOrange else DarkObsidian,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, if (isRunning) CyberOrange else BorderGray)
                            ) {
                                Text(if (isRunning) "Stop ⏹️" else pattern, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        } else {
            // Live Sensor mode guidance
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SlateSteel),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderGray)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "LIVE SENSORS MODE ENGAGED",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Hold your phone horizontally in front of you. Walk forward and turn. The native C++ step counter is reading live accelerometer vibrations to plot your path.",
                        color = MutedText,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- ACCELEROMETER SIGNAL GRAPH ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSteel),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderGray)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "VIBRATION SIGNAL (ACCEL MAG)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = String.format("%.2f m/s²", currentRawMag),
                        color = NeonGreen,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Chart drawing canvas
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(DarkObsidian, RoundedCornerShape(4.dp))
                ) {
                    val w = size.width
                    val h = size.height

                    // Draw baseline at gravity (9.8 m/s^2)
                    // We map gravity 9.8 to center height of canvas
                    val gravityY = h / 2f
                    drawLine(
                        color = BorderGray,
                        start = Offset(0f, gravityY),
                        end = Offset(w, gravityY),
                        strokeWidth = 1f
                    )

                    // Draw threshold line (gravity + sensitivity)
                    val thresholdY = gravityY - (sensitivity * (h / 15f))
                    drawLine(
                        color = CyberOrange.copy(alpha = 0.5f),
                        start = Offset(0f, thresholdY),
                        end = Offset(w, thresholdY),
                        strokeWidth = 1f,
                        pathEffect = null // solid line
                    )

                    if (accelHistory.size >= 2) {
                        val rawPath = Path()
                        val filteredPath = Path()

                        val stepX = w / (accelHistory.size - 1).toFloat()
                        val scaleY = h / 15f // scale factor: 1 m/s^2 = h/15 pixels

                        // Compute point coordinates
                        val getPixelY = { valAcc: Float ->
                            // Center around gravity (9.8)
                            val diff = valAcc - 9.8f
                            gravityY - (diff * scaleY)
                        }

                        rawPath.moveTo(0f, getPixelY(accelHistory[0]))
                        filteredPath.moveTo(0f, getPixelY(filteredHistory[0]))

                        for (i in 1 until accelHistory.size) {
                            val cx = i * stepX
                            rawPath.lineTo(cx, getPixelY(accelHistory[i]))
                            filteredPath.lineTo(cx, getPixelY(filteredHistory[i]))
                        }

                        // Draw raw magnitude (thin gray)
                        drawPath(
                            path = rawPath,
                            color = MutedText.copy(alpha = 0.3f),
                            style = Stroke(width = 2f)
                        )

                        // Draw filtered magnitude (bright green)
                        drawPath(
                            path = filteredPath,
                            color = NeonGreen,
                            style = Stroke(width = 3f)
                        )
                    }
                }
                
                // Legend
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🟢 Filtered", color = NeonGreen, fontSize = 9.sp)
                    Text("⚪ Raw", color = MutedText.copy(alpha = 0.5f), fontSize = 9.sp)
                    Text("🔴 Threshold", color = CyberOrange, fontSize = 9.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- CALIBRATION SETTINGS ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SlateSteel),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, BorderGray)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "CALIBRATION & PARAMETERS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Adjust step parameters to match your walk speed and phone hardware.",
                    color = MutedText,
                    fontSize = 10.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 1. Step Length Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Weinberg Stride Factor (K)", color = Color.White, fontSize = 11.sp)
                    Text(String.format("%.3f", stepLength), color = NeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = stepLength,
                    onValueChange = { stepLength = it },
                    valueRange = 0.25f..0.75f,
                    colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan.copy(alpha = 0.5f))
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 2. Sensitivity Threshold
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Peak Detection Threshold", color = Color.White, fontSize = 11.sp)
                    Text(String.format("%.2f m/s²", sensitivity), color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    valueRange = 0.5f..2.5f,
                    colors = SliderDefaults.colors(thumbColor = NeonGreen, activeTrackColor = NeonGreen.copy(alpha = 0.5f))
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 3. Filter Alpha LPF
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Low-Pass Alpha (Smoothness)", color = Color.White, fontSize = 11.sp)
                    Text(String.format("%.2f", filterAlpha), color = CyberOrange, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Slider(
                    value = filterAlpha,
                    onValueChange = { filterAlpha = it },
                    valueRange = 0.05f..0.4f,
                    colors = SliderDefaults.colors(thumbColor = CyberOrange, activeTrackColor = CyberOrange.copy(alpha = 0.5f))
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- SESSION ACTIONS (CLEAR / RESET) ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { resetEngineAndStats() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text("Reset Session 🗑️", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
