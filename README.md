# IMU Motion Tracer

An advanced, native Android application that tracks walking paths in real-time using Inertial Measurement Unit (IMU) sensors. The core mathematical filtering, step detection, and Pedestrian Dead Reckoning (PDR) algorithms are written in **C++ via the Android NDK**, and the user interface is built using **Jetpack Compose**.

---

## Key Features

1. **Native C++ Motion Engine:**
   - Real-time step detection using low-pass filters and custom peak-detection.
   - Trigonometric dead-reckoning equations mapping user steps and heading angles into $(x, y)$ coordinate offsets.
   - Core processing offloaded to native code for maximum efficiency and speed.

2. **Futuristic Compose Canvas Dashboard:**
   - Neon-styled map vector canvas drawing your walking coordinates.
   - Double-buffered canvas with gesture-based pan (drag) and zoom (pinch/slider) controls.
   - Compass overlay mapping real-time orientations.

3. **Vibration Signal Graph:**
   - Visualizes live rolling magnitude of accelerometer forces compared against the dynamic detection threshold.
   - Helps calibrate step sensitivity for different mobile devices.

4. **Dual-Mode Operation:**
   - **Simulator Mode:** Run automated paths (Square, Zig-zag, Spiral, Wander) or manually click buttons/drag sliders to simulate walking and turning directly in the emulator.
   - **Live Sensors Mode:** Place on a physical Android device to record real steps and heading changes.

---

## Code Architecture

```
IMU Motion/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/
│   │   │   │   ├── CMakeLists.txt        # Native compilation configurations
│   │   │   │   ├── motion-engine.h       # C++ header for core algorithm
│   │   │   │   └── motion-engine.cpp     # C++ source & JNI JNIEnv bindings
│   │   │   ├── java/com/example/imumotiontracer/
│   │   │   │   ├── MainActivity.kt       # Standard Android Activity shell
│   │   │   │   ├── NativeMotionEngine.kt # Kotlin JNI Bridge class
│   │   │   │   └── ui/main/
│   │   │   │       └── MainScreen.kt     # Main UI Dashboard, canvas, and sensor listener
│   │   │   └── AndroidManifest.xml       # App manifest
│   │   └── build.gradle.kts              # App-level build configurations (linking CMake)
│   └── build.gradle.kts                  # Project-level build configurations
```

---

## Setup & Running Instructions

### Prerequisites
- **Android Studio** (2023.x or newer recommended).
- **Android NDK & CMake** installed via SDK Manager (Tools > SDK Manager > SDK Tools > check `NDK (Side-by-side)` and `CMake`).

### How to Build & Run from Command Line
To compile and assemble the debug APK:
```powershell
# On Windows (ensure Java from Android Studio is used)
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

To install and run on a connected device/emulator:
```powershell
android run --debug
```

---

## Pedestrian Dead Reckoning (PDR) Algorithm

1. **Vibration Filtering:**
   Raw accelerometer signals $a = (a_x, a_y, a_z)$ are converted to magnitude:
   $$a_{\text{mag}} = \sqrt{a_x^2 + a_y^2 + a_z^2}$$
   We apply a Low-Pass Filter (LPF) to smooth out hand jitter:
   $$a_{\text{filtered}}[n] = \alpha \cdot a_{\text{mag}}[n] + (1 - \alpha) \cdot a_{\text{filtered}}[n-1]$$

2. **Step Detection:**
   A step is registered when a local maximum (peak) in $a_{\text{filtered}}$ meets the following:
   - Peak magnitude > Gravity ($9.806\text{ m/s}^2$) + Sensitivity threshold (default $1.2\text{ m/s}^2$).
   - Elapsed time since last registered step > $350\text{ ms}$ (debouncing).

3. **Trigonometric Coordinate Integration:**
   When a step is detected at orientation $\theta$, the relative position updates as:
   $$\Delta x = L \cdot \sin(\theta)$$
   $$\Delta y = -L \cdot \cos(\theta)$$
   *Where $L$ is the configured step length (default $0.75\text{ meters}$).*
