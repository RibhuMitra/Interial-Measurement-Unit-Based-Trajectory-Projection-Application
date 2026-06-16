package com.example.imumotiontracer.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation3.runtime.NavKey
import com.example.imumotiontracer.NativeMotionEngine
import com.example.imumotiontracer.data.WifiKNNLocalizer
import com.example.imumotiontracer.data.WifiMeasurement
import com.example.imumotiontracer.data.WifiScanManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Color Palette
private val DarkObsidian = Color(0xFF0D0E12)
private val SlateSteel = Color(0xFF1B1D26)
private val BorderGray = Color(0xFF2E3240)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonGreen = Color(0xFF39FF14)
private val CyberOrange = Color(0xFFFF5722)
private val MutedText = Color(0xFF8E95A5)

private val SignalExcellent = Color(0xFF39FF14)
private val SignalFair = Color(0xFFFFEB3B)
private val SignalPoor = Color(0xFFFF5722)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Core Native Engine Instance
    val nativeEngine = remember { NativeMotionEngine(stepLength = 0.42f, sensitivity = 1.0f, filterAlpha = 0.15f) }

    // 2. Wi-Fi Scan Manager & KNN Classifier
    val wifiScanManager = remember { WifiScanManager.getInstance(context) }
    val wifiKNNLocalizer = remember { WifiKNNLocalizer(k = 3) }

    // Flow states
    val scanResults by wifiScanManager.scanResults.collectAsState()
    val isScanning by wifiScanManager.isScanning.collectAsState()
    val macFilters by wifiScanManager.macFilters.collectAsState()
    val measurements by wifiScanManager.measurements.collectAsState()
    val floorPlanUriString by wifiScanManager.floorPlanUri.collectAsState()

    var activeTab by remember { mutableStateOf(0) }
    var permissionsGranted by remember { mutableStateOf(false) }

    // State of predicted KNN location (col: Float, row: Float)
    var knnPredictedLocation by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    // Wi-Fi calibration state variables
    var selectedCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showOnlyFiltered by remember { mutableStateOf(false) }

    // Settings States
    var stepLength by remember { mutableStateOf(0.42f) }
    var sensitivity by remember { mutableStateOf(1.0f) }
    var filterAlpha by remember { mutableStateOf(0.15f) }
    var wifiFusionEnabled by remember { mutableStateOf(false) }
    var fusionBeta by remember { mutableStateOf(0.2f) }

    // Telemetry States
    var stepCount by remember { mutableStateOf(0) }
    var distance by remember { mutableStateOf(0.0f) }
    var rawHeading by remember { mutableStateOf(0.0f) }
    var pathCoordinates by remember { mutableStateOf(floatArrayOf(0f, 0f)) }

    // Live Sensor Graph
    var accelHistory by remember { mutableStateOf(List(80) { 9.8f }) }
    var filteredHistory by remember { mutableStateOf(List(80) { 9.8f }) }
    var currentRawMag by remember { mutableStateOf(9.8f) }

    // Simulator
    var simMode by remember { mutableStateOf(true) }
    var simHeading by remember { mutableStateOf(0.0f) }

    // Interactive Canvas Viewport
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasScale by remember { mutableStateOf(1.0f) }

    // Simulation Scripts
    var activeScriptJob by remember { mutableStateOf<Job?>(null) }
    var activeScriptName by remember { mutableStateOf<String?>(null) }

    fun stopActiveScript() {
        activeScriptJob?.cancel()
        activeScriptJob = null
        activeScriptName = null
    }

    fun recenterCanvas() {
        canvasOffset = Offset.Zero
        canvasScale = 1.0f
    }

    fun resetEngineAndStats() {
        stopActiveScript()
        nativeEngine.reset()
        stepCount = 0
        distance = 0.0f
        pathCoordinates = floatArrayOf(0f, 0f)
        recenterCanvas()
    }

    // Load Floor Plan ImageBitmap
    var floorPlanBitmap by remember(floorPlanUriString) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(floorPlanUriString) {
        if (!floorPlanUriString.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(floorPlanUriString)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                floorPlanBitmap = bitmap.asImageBitmap()
            } catch (e: Exception) {
                e.printStackTrace()
                floorPlanBitmap = null
            }
        } else {
            floorPlanBitmap = null
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        permissionsGranted = perms.values.all { it }
    }

    // Check permissions on start
    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        val hasAll = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasAll) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            permissionsGranted = true
        }
    }

    // Build database whenever measurements change
    LaunchedEffect(measurements) {
        wifiKNNLocalizer.buildDatabase(measurements)
    }

    // Predict location when new scans arrive
    LaunchedEffect(scanResults) {
        if (scanResults.isNotEmpty()) {
            knnPredictedLocation = wifiKNNLocalizer.predictLocation(scanResults)
        }
    }

    // Periodic Wi-Fi scanning loop (every 10s)
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            while (true) {
                if (wifiScanManager.isGpsEnabled()) {
                    wifiScanManager.startScan()
                }
                delay(10000)
            }
        }
    }

    // Register scan broadcast receiver
    DisposableEffect(Unit) {
        wifiScanManager.registerReceiver()
        onDispose {
            wifiScanManager.unregisterReceiver()
            nativeEngine.destroy()
        }
    }

    // Sync calibration parameters
    LaunchedEffect(stepLength, sensitivity, filterAlpha) {
        nativeEngine.setParameters(stepLength, sensitivity, filterAlpha)
    }

    // Register Live IMU Sensors
    DisposableEffect(simMode) {
        if (!simMode) {
            stopActiveScript()
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

            var localFilteredMag = 9.80665f

            val sensorListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    when (event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            val timestampNs = event.timestamp

                            val stepDetected = nativeEngine.processSensorData(x, y, z, timestampNs)
                            if (stepDetected) {
                                nativeEngine.addStep(rawHeading, false)

                                // Apply Wi-Fi sensor fusion if enabled
                                if (wifiFusionEnabled) {
                                    knnPredictedLocation?.let { (wifiCol, wifiRow) ->
                                        val wifiX = (wifiCol - 3.5f) * 3.0f
                                        val wifiY = (wifiRow - 3.5f) * 3.0f
                                        nativeEngine.fuseLocation(wifiX, wifiY, fusionBeta)
                                    }
                                }

                                stepCount = nativeEngine.getStepCount()
                                distance = nativeEngine.getDistance()
                                pathCoordinates = nativeEngine.getPathPoints()
                            }

                            val rawMag = sqrt(x * x + y * y + z * z)
                            currentRawMag = rawMag
                            localFilteredMag = filterAlpha * rawMag + (1f - filterAlpha) * localFilteredMag

                            accelHistory = accelHistory.drop(1) + rawMag
                            filteredHistory = filteredHistory.drop(1) + localFilteredMag
                        }
                        Sensor.TYPE_ROTATION_VECTOR -> {
                            val rotationMatrix = FloatArray(9)
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(rotationMatrix, orientation)
                            
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

    // Main scaffold layout
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SlateSteel,
                tonalElevation = 8.dp
            ) {
                listOf(
                    Pair("🚶 PDR", 0),
                    Pair("📶 Scan", 1),
                    Pair("🔍 Filters", 2),
                    Pair("🗺️ Minimap", 3)
                ).forEach { (title, idx) ->
                    NavigationBarItem(
                        selected = activeTab == idx,
                        onClick = { activeTab = idx },
                        icon = {
                            val iconText = when (idx) {
                                0 -> "🚶"
                                1 -> "📶"
                                2 -> "🔍"
                                3 -> "🗺️"
                                else -> "🚶"
                            }
                            Text(iconText, fontSize = 18.sp)
                        },
                        label = { Text(title, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            unselectedIconColor = MutedText,
                            selectedTextColor = NeonCyan,
                            unselectedTextColor = MutedText,
                            indicatorColor = BorderGray
                        )
                    )
                }
            }
        },
        containerColor = DarkObsidian
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(DarkObsidian)
                .padding(8.dp)
        ) {
            when (activeTab) {
                0 -> {
                    // PDR TRACER VIEW
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // --- HEADER BAR ---
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("IMU Motion Tracer", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Localization & Sensor Fusion", color = MutedText, fontSize = 11.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .background(if (simMode) SlateSteel else NeonCyan.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                    .border(1.dp, if (simMode) BorderGray else NeonCyan, RoundedCornerShape(12.dp))
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

                        // --- TELEMETRY SCOREBOARD ---
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = SlateSteel)) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("STEPS", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("$stepCount", color = NeonGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Card(modifier = Modifier.weight(1.2f), colors = CardDefaults.cardColors(containerColor = SlateSteel)) {
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("EST. DISTANCE", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(String.format("%.2f m", distance), color = NeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = SlateSteel)) {
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
                                Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("HEADING", color = MutedText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(String.format("%d° %s", dispHeading.toInt(), cardinal), color = CyberOrange, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- PATH CANVAS ---
                        Card(
                            modifier = Modifier.fillMaxWidth().height(300.dp),
                            colors = CardDefaults.cardColors(containerColor = SlateSteel),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, BorderGray)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
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

                                    // 1. Draw floor plan image centered at origin
                                    floorPlanBitmap?.let { img ->
                                        withTransform({
                                            translate(center.x + canvasOffset.x, center.y + canvasOffset.y)
                                            scale(canvasScale, canvasScale, pivot = Offset.Zero)
                                        }) {
                                            // 8x8 grid is 24x24 meters. At 100 pixels per meter, that is 2400x2400 pixels.
                                            drawImage(
                                                image = img,
                                                dstOffset = IntOffset(-1200, -1200),
                                                dstSize = IntSize(2400, 2400),
                                                alpha = 0.35f
                                            )
                                        }
                                    }

                                    // 2. Draw Grid Lines inside transform matrix
                                    withTransform({
                                        translate(center.x + canvasOffset.x, center.y + canvasOffset.y)
                                        scale(canvasScale, canvasScale, pivot = Offset.Zero)
                                    }) {
                                        val pixelsPerMeter = 100f
                                        val gridSize = 24.0f * pixelsPerMeter
                                        val stepSize = 3.0f * pixelsPerMeter

                                        // Outer border
                                        drawRect(
                                            color = BorderGray.copy(alpha = 0.5f),
                                            topLeft = Offset(-gridSize / 2f, -gridSize / 2f),
                                            size = Size(gridSize, gridSize),
                                            style = Stroke(width = 2f)
                                        )

                                        // Verticals
                                        for (c in 1 until 8) {
                                            val x = -gridSize / 2f + c * stepSize
                                            drawLine(
                                                color = BorderGray.copy(alpha = 0.2f),
                                                start = Offset(x, -gridSize / 2f),
                                                end = Offset(x, gridSize / 2f),
                                                strokeWidth = 1f
                                            )
                                        }

                                        // Horizontals
                                        for (r in 1 until 8) {
                                            val y = -gridSize / 2f + r * stepSize
                                            drawLine(
                                                color = BorderGray.copy(alpha = 0.2f),
                                                start = Offset(-gridSize / 2f, y),
                                                end = Offset(gridSize / 2f, y),
                                                strokeWidth = 1f
                                            )
                                        }
                                    }

                                    // 3. Draw walking trail and current position
                                    withTransform({
                                        translate(center.x + canvasOffset.x, center.y + canvasOffset.y)
                                        scale(canvasScale, canvasScale, pivot = Offset.Zero)
                                    }) {
                                        val pixelsPerMeter = 100f

                                        // A. Draw Wi-Fi predicted location target (glowing orange dot)
                                        knnPredictedLocation?.let { (wifiCol, wifiRow) ->
                                            val wifiX = (wifiCol - 3.5f) * 3.0f * pixelsPerMeter
                                            val wifiY = (wifiRow - 3.5f) * 3.0f * pixelsPerMeter

                                            drawCircle(
                                                color = CyberOrange.copy(alpha = 0.15f),
                                                radius = 35f,
                                                center = Offset(wifiX, wifiY)
                                            )
                                            drawCircle(
                                                color = CyberOrange,
                                                radius = 20f,
                                                center = Offset(wifiX, wifiY),
                                                style = Stroke(width = 2f)
                                            )
                                            drawCircle(
                                                color = CyberOrange,
                                                radius = 6f,
                                                center = Offset(wifiX, wifiY)
                                            )
                                        }

                                        // B. Draw PDR/fused trail
                                        if (pathCoordinates.size >= 2) {
                                            val path = Path()
                                            path.moveTo(pathCoordinates[0] * pixelsPerMeter, pathCoordinates[1] * pixelsPerMeter)
                                            
                                            for (i in 2 until pathCoordinates.size step 2) {
                                                path.lineTo(pathCoordinates[i] * pixelsPerMeter, pathCoordinates[i + 1] * pixelsPerMeter)
                                            }

                                            drawPath(
                                                path = path,
                                                color = if (wifiFusionEnabled) NeonGreen else NeonCyan,
                                                style = Stroke(width = 4f, miter = 1f)
                                            )

                                            for (i in 0 until pathCoordinates.size step 2) {
                                                val pt = Offset(pathCoordinates[i] * pixelsPerMeter, pathCoordinates[i + 1] * pixelsPerMeter)
                                                val circleColor = if (i == 0) CyberOrange else if (i == pathCoordinates.size - 2) NeonGreen else NeonCyan
                                                drawCircle(
                                                    color = circleColor,
                                                    radius = if (i == pathCoordinates.size - 2) 8f else 5f,
                                                    center = pt
                                                )
                                            }

                                            // Draw orientation arrow at current node
                                            val lastIdx = pathCoordinates.size - 2
                                            val curX = pathCoordinates[lastIdx] * pixelsPerMeter
                                            val curY = pathCoordinates[lastIdx + 1] * pixelsPerMeter

                                            val angleDeg = if (simMode) simHeading else rawHeading
                                            val angleRad = Math.toRadians(angleDeg.toDouble())
                                            val arrowLength = 25f
                                            val headX = curX + arrowLength * sin(angleRad).toFloat()
                                            val headY = curY - arrowLength * cos(angleRad).toFloat()

                                            drawLine(
                                                color = CyberOrange,
                                                start = Offset(curX, curY),
                                                end = Offset(headX, headY),
                                                strokeWidth = 5f
                                            )
                                        }
                                    }
                                }

                                // Zoom Overlay controls
                                Column(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(onClick = { canvasScale = (canvasScale + 0.2f).coerceAtMost(5.0f) }, modifier = Modifier.background(DarkObsidian.copy(alpha = 0.8f), RoundedCornerShape(4.dp))) {
                                        Text("➕", color = Color.White)
                                    }
                                    IconButton(onClick = { canvasScale = (canvasScale - 0.2f).coerceAtLeast(0.5f) }, modifier = Modifier.background(DarkObsidian.copy(alpha = 0.8f), RoundedCornerShape(4.dp))) {
                                        Text("➖", color = Color.White)
                                    }
                                    IconButton(onClick = { recenterCanvas() }, modifier = Modifier.background(DarkObsidian.copy(alpha = 0.8f), RoundedCornerShape(4.dp))) {
                                        Text("🎯", color = Color.White)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- MODE SELECTION ---
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SlateSteel), border = BorderStroke(1.dp, BorderGray)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("INTEGRATION MODE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { simMode = true },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (simMode) NeonCyan.copy(alpha = 0.2f) else DarkObsidian, contentColor = if (simMode) NeonCyan else MutedText),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, if (simMode) NeonCyan else BorderGray)
                                    ) { Text("Simulator") }
                                    Button(
                                        onClick = { simMode = false },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (!simMode) NeonCyan.copy(alpha = 0.2f) else DarkObsidian, contentColor = if (!simMode) NeonCyan else MutedText),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, if (!simMode) NeonCyan else BorderGray)
                                    ) { Text("Live Sensors") }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- SIMULATOR CONTROLLERS ---
                        if (simMode) {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SlateSteel), border = BorderStroke(1.dp, BorderGray)) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("WALK SIMULATOR", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                nativeEngine.addStep(simHeading, true)
                                                if (wifiFusionEnabled) {
                                                    knnPredictedLocation?.let { (wifiCol, wifiRow) ->
                                                        val wifiX = (wifiCol - 3.5f) * 3.0f
                                                        val wifiY = (wifiRow - 3.5f) * 3.0f
                                                        nativeEngine.fuseLocation(wifiX, wifiY, fusionBeta)
                                                    }
                                                }
                                                stepCount = nativeEngine.getStepCount()
                                                distance = nativeEngine.getDistance()
                                                pathCoordinates = nativeEngine.getPathPoints()
                                            },
                                            modifier = Modifier.weight(1.2f),
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                            shape = RoundedCornerShape(6.dp)
                                        ) { Text("Walk Step 🚶", fontWeight = FontWeight.Bold) }
                                        Button(onClick = { simHeading = (simHeading - 15f + 360f) % 360f }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = DarkObsidian, contentColor = Color.White), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, BorderGray)) { Text("↩️ Turn L") }
                                        Button(onClick = { simHeading = (simHeading + 15f) % 360f }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = DarkObsidian, contentColor = Color.White), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, BorderGray)) { Text("Turn R ↪️") }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Simulated Heading: ${simHeading.toInt()}°", color = Color.White, fontSize = 11.sp)
                                    Slider(value = simHeading, onValueChange = { simHeading = it }, valueRange = 0f..359f, colors = SliderDefaults.colors(thumbColor = CyberOrange, activeTrackColor = CyberOrange.copy(alpha = 0.5f)))
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Auto Walking Scripts", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                                                            if (wifiFusionEnabled) {
                                                                                knnPredictedLocation?.let { (wifiCol, wifiRow) ->
                                                                                    val wifiX = (wifiCol - 3.5f) * 3.0f
                                                                                    val wifiY = (wifiRow - 3.5f) * 3.0f
                                                                                    nativeEngine.fuseLocation(wifiX, wifiY, fusionBeta)
                                                                                }
                                                                            }
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
                                                                        if (wifiFusionEnabled) {
                                                                            knnPredictedLocation?.let { (wifiCol, wifiRow) ->
                                                                                val wifiX = (wifiCol - 3.5f) * 3.0f
                                                                                val wifiY = (wifiRow - 3.5f) * 3.0f
                                                                                nativeEngine.fuseLocation(wifiX, wifiY, fusionBeta)
                                                                            }
                                                                        }
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
                                                                            if (wifiFusionEnabled) {
                                                                                knnPredictedLocation?.let { (wifiCol, wifiRow) ->
                                                                                    val wifiX = (wifiCol - 3.5f) * 3.0f
                                                                                    val wifiY = (wifiRow - 3.5f) * 3.0f
                                                                                    nativeEngine.fuseLocation(wifiX, wifiY, fusionBeta)
                                                                                }
                                                                            }
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
                                                                        simHeading = (simHeading + (-45..45).random() + 360f) % 360f
                                                                        nativeEngine.addStep(simHeading, true)
                                                                        if (wifiFusionEnabled) {
                                                                            knnPredictedLocation?.let { (wifiCol, wifiRow) ->
                                                                                val wifiX = (wifiCol - 3.5f) * 3.0f
                                                                                val wifiY = (wifiRow - 3.5f) * 3.0f
                                                                                nativeEngine.fuseLocation(wifiX, wifiY, fusionBeta)
                                                                            }
                                                                        }
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
                                                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) CyberOrange else DarkObsidian, contentColor = Color.White),
                                                shape = RoundedCornerShape(4.dp),
                                                border = BorderStroke(1.dp, if (isRunning) CyberOrange else BorderGray)
                                            ) { Text(if (isRunning) "Stop ⏹️" else pattern, fontSize = 10.sp) }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Live Sensor instruction
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SlateSteel), border = BorderStroke(1.dp, BorderGray)) {
                                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("LIVE SENSORS MODE ENGAGED", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text("Hold phone horizontally and walk. C++ reads accelerometer signals to count steps while fusing continuously-smoothed rotation vector heading.", color = MutedText, fontSize = 10.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- ACCELEROMETER SIGNAL GRAPH ---
                        Card(modifier = Modifier.fillMaxWidth().height(150.dp), colors = CardDefaults.cardColors(containerColor = SlateSteel), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BorderGray)) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("VIBRATION SIGNAL (ACCEL MAG)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text(String.format("%.2f m/s²", currentRawMag), color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Canvas(modifier = Modifier.fillMaxWidth().weight(1f).background(DarkObsidian, RoundedCornerShape(4.dp))) {
                                    val w = size.width
                                    val h = size.height
                                    val gravityY = h / 2f
                                    drawLine(color = BorderGray, start = Offset(0f, gravityY), end = Offset(w, gravityY), strokeWidth = 1f)
                                    val thresholdY = gravityY - (sensitivity * (h / 15f))
                                    drawLine(color = CyberOrange.copy(alpha = 0.5f), start = Offset(0f, thresholdY), end = Offset(w, thresholdY), strokeWidth = 1f)

                                    if (accelHistory.size >= 2) {
                                        val rawPath = Path()
                                        val filteredPath = Path()
                                        val stepX = w / (accelHistory.size - 1).toFloat()
                                        val scaleY = h / 15f
                                        val getPixelY = { valAcc: Float -> gravityY - ((valAcc - 9.8f) * scaleY) }

                                        rawPath.moveTo(0f, getPixelY(accelHistory[0]))
                                        filteredPath.moveTo(0f, getPixelY(filteredHistory[0]))
                                        for (i in 1 until accelHistory.size) {
                                            val cx = i * stepX
                                            rawPath.lineTo(cx, getPixelY(accelHistory[i]))
                                            filteredPath.lineTo(cx, getPixelY(filteredHistory[i]))
                                        }

                                        drawPath(path = rawPath, color = MutedText.copy(alpha = 0.3f), style = Stroke(width = 2f))
                                        drawPath(path = filteredPath, color = NeonGreen, style = Stroke(width = 3f))
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("🟢 Filtered", color = NeonGreen, fontSize = 9.sp)
                                    Text("⚪ Raw", color = MutedText.copy(alpha = 0.5f), fontSize = 9.sp)
                                    Text("🔴 Threshold", color = CyberOrange, fontSize = 9.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- CALIBRATION SETTINGS ---
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SlateSteel), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, BorderGray)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("CALIBRATION & PARAMETERS", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Weinberg Stride Factor (K)", color = Color.White, fontSize = 11.sp)
                                    Text(String.format("%.3f", stepLength), color = NeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Slider(value = stepLength, onValueChange = { stepLength = it }, valueRange = 0.25f..0.75f, colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan.copy(alpha = 0.5f)))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Peak Detection Threshold", color = Color.White, fontSize = 11.sp)
                                    Text(String.format("%.2f m/s²", sensitivity), color = NeonGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Slider(value = sensitivity, onValueChange = { sensitivity = it }, valueRange = 0.5f..2.5f, colors = SliderDefaults.colors(thumbColor = NeonGreen, activeTrackColor = NeonGreen.copy(alpha = 0.5f)))

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Low-Pass Alpha (Smoothness)", color = Color.White, fontSize = 11.sp)
                                    Text(String.format("%.2f", filterAlpha), color = CyberOrange, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Slider(value = filterAlpha, onValueChange = { filterAlpha = it }, valueRange = 0.05f..0.4f, colors = SliderDefaults.colors(thumbColor = CyberOrange, activeTrackColor = CyberOrange.copy(alpha = 0.5f)))

                                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = BorderGray)

                                // FUSION SETTINGS
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text("Wi-Fi ML Location Fusion", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Correct PDR drift dynamically using KNN", color = MutedText, fontSize = 9.sp)
                                    }
                                    Switch(
                                        checked = wifiFusionEnabled,
                                        onCheckedChange = { wifiFusionEnabled = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f))
                                    )
                                }

                                AnimatedVisibility(visible = wifiFusionEnabled) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Fusion Weight (Beta)", color = Color.White, fontSize = 11.sp)
                                            Text(String.format("%.2f", fusionBeta), color = NeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        Slider(value = fusionBeta, onValueChange = { fusionBeta = it }, valueRange = 0.05f..0.95f, colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan.copy(alpha = 0.5f)))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // --- RESET BUTTON ---
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { resetEngineAndStats() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)), shape = RoundedCornerShape(6.dp)) {
                                Text("Reset Session 🗑️", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
                1 -> {
                    // SIGNALS TAB
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Wi-Fi Access Points (${scanResults.size})", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Button(
                                onClick = { wifiScanManager.startScan() },
                                enabled = !isScanning,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(if (isScanning) "Scanning..." else "Scan Now 🔄", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().background(SlateSteel, RoundedCornerShape(8.dp)).padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Filter Target MACs", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Show registered filters only", color = MutedText, fontSize = 10.sp)
                            }
                            Switch(
                                checked = showOnlyFiltered,
                                onCheckedChange = { showOnlyFiltered = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f))
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val displayedAps = if (showOnlyFiltered && macFilters.isNotEmpty()) {
                            scanResults.filter { macFilters.contains(it.BSSID.uppercase()) }
                        } else {
                            scanResults
                        }.sortedByDescending { it.level }

                        if (displayedAps.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No Access Points Found.\nEnsure Location/GPS is ON and permissions are granted.", color = MutedText, textAlign = TextAlign.Center, fontSize = 13.sp)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                                items(displayedAps) { ap ->
                                    Card(colors = CardDefaults.cardColors(containerColor = SlateSteel)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(if (ap.SSID.isNullOrEmpty()) "Hidden SSID" else ap.SSID, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text("${ap.BSSID.uppercase()} | ${ap.frequency} MHz", color = MutedText, fontSize = 11.sp)
                                            }
                                            val qualityColor = when {
                                                ap.level >= -55 -> SignalExcellent
                                                ap.level >= -70 -> SignalFair
                                                else -> SignalPoor
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("${ap.level} dBm", color = qualityColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text(if (ap.level >= -55) "Excellent" else if (ap.level >= -70) "Fair" else "Weak", color = MutedText, fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    // MAC FILTERS TAB
                    val focusManager = LocalFocusManager.current
                    var macInput by remember { mutableStateOf("") }
                    var inputError by remember { mutableStateOf<String?>(null) }

                    fun submitFilter() {
                        if (macInput.isBlank()) {
                            inputError = "MAC address cannot be empty"
                            return
                        }
                        val success = wifiScanManager.addMacFilter(macInput)
                        if (success) {
                            macInput = ""
                            focusManager.clearFocus()
                        } else {
                            inputError = "Invalid MAC format or already added"
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SlateSteel)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Add MAC Filter", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Input router MAC (BSSID) for targeted ML fingerprint tracking.", color = MutedText, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = macInput,
                                    onValueChange = { macInput = it; if (inputError != null) inputError = null },
                                    placeholder = { Text("AA:BB:CC:DD:EE:FF", color = MutedText) },
                                    singleLine = true,
                                    isError = inputError != null,
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = BorderGray, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submitFilter() }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                AnimatedVisibility(visible = inputError != null) {
                                    Text(inputError ?: "", color = SignalPoor, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { submitFilter() },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.align(Alignment.End)
                                ) { Text("Add MAC Filter", fontWeight = FontWeight.Bold) }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Active MAC Filters (${macFilters.size})", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        if (macFilters.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                                Text("No filters registered.", color = MutedText)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                items(macFilters.toList()) { mac ->
                                    Card(colors = CardDefaults.cardColors(containerColor = SlateSteel)) {
                                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(mac, color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            IconButton(onClick = { wifiScanManager.removeMacFilter(mac) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete filter", tint = SignalPoor)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> {
                    // MINIMAP TAB (Grid Calibration)
                    val rows = 8
                    val cols = 8

                    val imagePickerLauncher = rememberLauncherForActivityResult(
                        contract = PickVisualMedia()
                    ) { uri ->
                        if (uri != null) {
                            try {
                                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                            } catch (e: Exception) {}
                            wifiScanManager.setFloorPlanUri(uri.toString())
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Card with overlay grid
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().aspectRatio(1.2f),
                                colors = CardDefaults.cardColors(containerColor = SlateSteel),
                                border = BorderStroke(1.dp, BorderGray)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (floorPlanBitmap != null) {
                                        Image(bitmap = floorPlanBitmap!!, contentDescription = "Minimap floor plan", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Box(modifier = Modifier.fillMaxSize().background(DarkObsidian), contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(Icons.Default.Settings, contentDescription = "No floor plan", tint = MutedText, modifier = Modifier.size(48.dp))
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text("No Floor Plan Loaded", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text("Upload blueprint image below to map Wi-Fi", color = MutedText, fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    // Grid Overlay
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        repeat(rows) { r ->
                                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                                repeat(cols) { c ->
                                                    val colChar = ('A' + c).toString()
                                                    val cellTag = "$colChar${r + 1}"
                                                    val isSelected = selectedCell?.first == r && selectedCell?.second == c

                                                    val cellMs = measurements.filter { it.row == r && it.col == c }
                                                    val cellColor = if (cellMs.isNotEmpty()) {
                                                        val avgRssi = cellMs.map { it.rssi }.average().toInt()
                                                        when {
                                                            avgRssi >= -55 -> SignalExcellent.copy(alpha = 0.45f)
                                                            avgRssi >= -70 -> SignalFair.copy(alpha = 0.45f)
                                                            else -> SignalPoor.copy(alpha = 0.45f)
                                                        }
                                                    } else Color.Transparent

                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .fillMaxHeight()
                                                            .background(cellColor)
                                                            .border(BorderStroke(if (isSelected) 2.dp else 0.5.dp, if (isSelected) NeonCyan else BorderGray.copy(alpha = 0.3f)))
                                                            .clickable { selectedCell = Pair(r, c) },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(cellTag, fontSize = 9.sp, color = if (isSelected) NeonCyan else MutedText, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Floor Plan Actions
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { imagePickerLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = SlateSteel, contentColor = Color.White),
                                    border = BorderStroke(1.dp, BorderGray)
                                ) { Text("Change Blueprint", fontSize = 12.sp) }

                                if (floorPlanUriString != null) {
                                    Button(
                                        onClick = { wifiScanManager.setFloorPlanUri(null) },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SlateSteel, contentColor = SignalPoor),
                                        border = BorderStroke(1.dp, SignalPoor.copy(alpha = 0.4f))
                                    ) { Text("Remove Blueprint", fontSize = 12.sp) }
                                }
                            }
                        }

                        // Recording Calibration Panel
                        item {
                            val cell = selectedCell
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SlateSteel), border = BorderStroke(1.dp, BorderGray)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    if (cell != null) {
                                        val colChar = ('A' + cell.second).toString()
                                        val cellTag = "$colChar${cell.first + 1}"
                                        val cellMs = measurements.filter { it.row == cell.first && it.col == cell.second }

                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column {
                                                Text("Selected Calibration Cell: $cellTag", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                                Text("${cellMs.size} RSSI Fingerprints Saved", color = MutedText, fontSize = 12.sp)
                                            }
                                            Button(
                                                onClick = {
                                                    val now = System.currentTimeMillis()
                                                    val displayed = if (showOnlyFiltered && macFilters.isNotEmpty()) {
                                                        scanResults.filter { macFilters.contains(it.BSSID.uppercase()) }
                                                    } else {
                                                        scanResults
                                                    }
                                                    for (ap in displayed) {
                                                        val ssid = if (ap.SSID.isNullOrEmpty()) "Hidden SSID" else ap.SSID
                                                        val m = WifiMeasurement(
                                                            row = cell.first,
                                                            col = cell.second,
                                                            cellTag = cellTag,
                                                            timestamp = now,
                                                            ssid = ssid,
                                                            bssid = ap.BSSID.uppercase(),
                                                            rssi = ap.level,
                                                            frequency = ap.frequency
                                                        )
                                                        wifiScanManager.saveMeasurement(m)
                                                    }
                                                    Toast.makeText(context, "Fingerprinted cell $cellTag", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black),
                                                shape = RoundedCornerShape(6.dp),
                                                enabled = !isScanning && scanResults.isNotEmpty()
                                            ) { Text("Record Fingerprint", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))
                                        if (cellMs.isEmpty()) {
                                            Text("No measurements recorded at this spot. Tap 'Record Fingerprint' to save Wi-Fi strengths.", color = MutedText, fontSize = 12.sp)
                                        } else {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                cellMs.sortedByDescending { it.timestamp }.take(5).forEach { m ->
                                                    val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(m.timestamp))
                                                    Row(modifier = Modifier.fillMaxWidth().background(DarkObsidian, RoundedCornerShape(6.dp)).border(1.dp, BorderGray, RoundedCornerShape(6.dp)).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Column {
                                                            Text(m.ssid, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                            Text("${m.bssid} | ${m.frequency} MHz", color = MutedText, fontSize = 10.sp)
                                                        }
                                                        Column(horizontalAlignment = Alignment.End) {
                                                            Text("${m.rssi} dBm", color = if (m.rssi >= -55) SignalExcellent else if (m.rssi >= -70) SignalFair else SignalPoor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                            Text(timeStr, color = MutedText, fontSize = 9.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Text("Tap any cell on the grid blueprint above to select it and start Wi-Fi fingerprinting.", color = MutedText, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(8.dp))
                                    }
                                }
                            }
                        }

                        // Export and Reset panel
                        item {
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SlateSteel), border = BorderStroke(1.dp, BorderGray)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Fingerprint Database Management", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Total records in DB: ${measurements.size}", color = MutedText, fontSize = 12.sp)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(
                                            onClick = {
                                                val list = measurements
                                                if (list.isNotEmpty()) {
                                                    val csvHeader = "Timestamp,Date,Grid Cell,SSID,BSSID,RSSI (dBm),Frequency (MHz),Row,Column\n"
                                                    val csvBody = list.joinToString("\n") { m ->
                                                        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(m.timestamp))
                                                        "${m.timestamp},$dateStr,${m.cellTag},${m.ssid.replace(",", " ")},${m.bssid},${m.rssi},${m.frequency},${m.row},${m.col}"
                                                    }
                                                    try {
                                                        val csvFile = java.io.File(context.filesDir, "wifi_measurements.csv")
                                                        csvFile.writeText(csvHeader + csvBody)
                                                        val uri = FileProvider.getUriForFile(context, "com.example.imumotiontracer.fileprovider", csvFile)
                                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "text/csv"
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        context.startActivity(Intent.createChooser(intent, "Share Localization Fingerprints"))
                                                    } catch (e: Exception) {
                                                        e.printStackTrace()
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                                            enabled = measurements.isNotEmpty()
                                        ) { Text("EXPORT CSV", fontWeight = FontWeight.Bold) }

                                        Button(
                                            onClick = { wifiScanManager.clearAllMeasurements() },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(containerColor = DarkObsidian, contentColor = SignalPoor),
                                            border = BorderStroke(1.dp, SignalPoor.copy(alpha = 0.4f)),
                                            enabled = measurements.isNotEmpty()
                                        ) { Text("CLEAR DB", fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
