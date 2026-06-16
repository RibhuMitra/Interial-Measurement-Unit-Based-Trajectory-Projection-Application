#include "motion-engine.h"
#include <jni.h>
#include <cmath>
#include <numeric>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "MotionEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

// Constructor
MotionEngine::MotionEngine(float weinbergK, float minVariation, float filterAlpha)
    : weinbergK(weinbergK),
      minVariation(minVariation),
      filterAlpha(filterAlpha),
      isStep(false),
      lastStepTimeMs(0),
      prevStepTimeMs(0),
      nextStride(0.75f),
      currentX(0.0f),
      currentY(0.0f),
      currentHeading(0.0f),
      smoothedSin(0.0f),
      smoothedCos(1.0f), // Face North initially
      stepCount(0),
      totalDistance(0.0f) {
    pathPoints.push_back({0.0f, 0.0f});
}

// Thread-safe update of parameters
void MotionEngine::setParameters(float len, float sens, float alpha) {
    std::lock_guard<std::mutex> lock(engineMutex);
    weinbergK = len;
    minVariation = sens;
    filterAlpha = alpha;
    LOGD("Navigine parameters synced: K=%.3f, threshold=%.3f, filterAlpha=%.3f", weinbergK, minVariation, filterAlpha);
}

// Core step processing matching navigine's pedometer
bool MotionEngine::processSensorData(float x, float y, float z, long long timestampNs) {
    std::lock_guard<std::mutex> lock(engineMutex);

    long long timestampMs = timestampNs / 1000000LL;

    // 1. Calculate raw magnitude
    float rawMag = std::sqrt(x * x + y * y + z * z);

    // 2. Append raw measurement
    accelMeasurements.push_back(SensorSample{rawMag, timestampMs});

    // 3. Compute running mean over 2500ms and LPF over 200ms
    long long lastMeasTs = timestampMs;

    // Average magnitude over the last 2500ms (Averaging Window)
    double sumAverage = 0.0;
    int countAverage = 0;
    for (auto it = accelMeasurements.rbegin(); it != accelMeasurements.rend(); ++it) {
        if (lastMeasTs - it->timestampMs >= AVERAGING_TIME_INTERVAL_MS) {
            break;
        }
        sumAverage += it->magnitude;
        countAverage++;
    }

    // Filtered magnitude over the last 200ms (LPF Window)
    double sumFiltered = 0.0;
    int countFiltered = 0;
    for (auto it = accelMeasurements.rbegin(); it != accelMeasurements.rend(); ++it) {
        if (lastMeasTs - it->timestampMs >= FILTER_TIME_INTERVAL_MS) {
            break;
        }
        sumFiltered += it->magnitude;
        countFiltered++;
    }

    if (countAverage == 0 || countFiltered == 0) {
        return false;
    }

    double averMagnitude = sumAverage / countAverage;
    double filterMagnitude = sumFiltered / countFiltered;

    // 4. Align magnitude relative to zero (Subtracts gravity vector dynamically)
    filterMagnitude -= averMagnitude;

    // 5. Store zero-aligned filtered magnitude
    filteredAccMagnitudes.push_back(FilteredSample{static_cast<float>(filterMagnitude), lastMeasTs});

    // 6. Clean up raw history to keep buffer short (2 * 2500ms)
    while (!accelMeasurements.empty() && 
           lastMeasTs - accelMeasurements.front().timestampMs > 2 * AVERAGING_TIME_INTERVAL_MS) {
        accelMeasurements.pop_front();
    }

    // 7. Clean up filtered history to keep buffer short (2 * 700ms)
    while (!filteredAccMagnitudes.empty() && 
           lastMeasTs - filteredAccMagnitudes.front().timestampMs > 2 * UPDATE_TIME_INTERVAL_MS) {
        filteredAccMagnitudes.pop_front();
    }

    // 8. Step detection logic
    if (filteredAccMagnitudes.size() <= 3) {
        return false;
    }

    // Dynamic debounce based on average step interval times
    long long averageStepTime = 0;
    auto nSteps = std::max(static_cast<long long>(stepTimes.size()), 1LL);
    if (stepTimes.size() >= MINIMAL_NUMBER_OF_STEPS) {
        long long sum = 0;
        for (long long t : stepTimes) {
            sum += t;
        }
        averageStepTime = sum / nSteps;
    }
    float timeBetweenSteps = std::max(1.0f * MIN_TIME_BETWEEN_STEPS_MS, 0.6f * averageStepTime);

    bool stepDetected = false;
    FilteredSample curAcc = filteredAccMagnitudes.back();
    FilteredSample prevAcc = filteredAccMagnitudes[filteredAccMagnitudes.size() - 2];

    // Crossover detection: crosses threshold (minVariation) in an upward direction
    if (!isStep &&
        prevAcc.value < minVariation &&
        curAcc.value > minVariation &&
        curAcc.timestampMs - lastStepTimeMs > MIN_TIME_BETWEEN_STEPS_MS &&
        timeBetweenSteps > 0 &&
        curAcc.timestampMs - lastStepTimeMs > timeBetweenSteps) {
        
        isStep = true;
        prevStepTimeMs = lastStepTimeMs;
        lastStepTimeMs = curAcc.timestampMs;
        stepDetected = true;

        // 9. Weinberg Stride Length Estimation using 700ms window max/min peak amplitude
        float maxMagn = -1e9f;
        float minMagn = 1e9f;
        for (const auto& fmsr : filteredAccMagnitudes) {
            if (lastMeasTs - fmsr.timestampMs <= UPDATE_TIME_INTERVAL_MS) {
                if (fmsr.value > maxMagn) maxMagn = fmsr.value;
                if (fmsr.value < minMagn) minMagn = fmsr.value;
            }
        }
        
        float accAmplitude = maxMagn - minMagn;
        float stride = weinbergK * std::sqrt(std::sqrt(accAmplitude));
        
        // Clamp step size to anatomical limits (0.3m to 1.8m)
        stride = std::max(0.3f, std::min(stride, 1.8f));
        nextStride = stride;

        // Save step duration interval to dynamic debounce history
        if (prevStepTimeMs != 0) {
            long long stepDuration = lastStepTimeMs - prevStepTimeMs;
            if (stepDuration < MAX_STEP_TIME_MS) {
                stepTimes.push_back(stepDuration);
                while (stepTimes.size() > MAXIMUM_NUMBER_OF_STEPS) {
                    stepTimes.pop_front();
                }
            }
        }

        isStep = false; // Prepare to count next step
    }

    return stepDetected;
}

void MotionEngine::updateHeading(float headingDegrees) {
    std::lock_guard<std::mutex> lock(engineMutex);
    float rad = headingDegrees * (M_PI / 180.0f);
    smoothedSin = filterAlpha * std::sin(rad) + (1.0f - filterAlpha) * smoothedSin;
    smoothedCos = filterAlpha * std::cos(rad) + (1.0f - filterAlpha) * smoothedCos;
    
    float smoothedHeadingRad = std::atan2(smoothedSin, smoothedCos);
    currentHeading = smoothedHeadingRad * (180.0f / M_PI);
    if (currentHeading < 0) currentHeading += 360.0f;
}

// Dead-reckoning position integrator using Weinberg model and smoothed heading
void MotionEngine::addStep(float headingDegrees, bool isSimulator) {
    std::lock_guard<std::mutex> lock(engineMutex);

    float smoothedHeadingRad;
    if (isSimulator) {
        // In simulator mode, set heading immediately without any lag or smoothing
        currentHeading = headingDegrees;
        float rad = headingDegrees * (M_PI / 180.0f);
        smoothedSin = std::sin(rad);
        smoothedCos = std::cos(rad);
        smoothedHeadingRad = rad;
    } else {
        // In live mode, use the continuously smoothed heading
        smoothedHeadingRad = currentHeading * (M_PI / 180.0f);
    }

    // 2. Compute path updates (Y goes down in screen coordinates)
    float dx = nextStride * std::sin(smoothedHeadingRad);
    float dy = -nextStride * std::cos(smoothedHeadingRad);

    currentX += dx;
    currentY += dy;
    
    pathPoints.push_back({currentX, currentY});
    stepCount++;
    totalDistance += nextStride;

    LOGD("Step #%d: Weinberg Stride=%.2fm, Heading=%.1f°, Position=(%.2f, %.2f)", 
         stepCount, nextStride, currentHeading, currentX, currentY);
}

// Reset the entire engine state
void MotionEngine::reset() {
    std::lock_guard<std::mutex> lock(engineMutex);
    currentX = 0.0f;
    currentY = 0.0f;
    currentHeading = 0.0f;
    smoothedSin = 0.0f;
    smoothedCos = 1.0f;
    stepCount = 0;
    totalDistance = 0.0f;
    lastStepTimeMs = 0;
    prevStepTimeMs = 0;
    isStep = false;
    nextStride = 0.75f;
    accelMeasurements.clear();
    filteredAccMagnitudes.clear();
    stepTimes.clear();
    pathPoints.clear();
    pathPoints.push_back({0.0f, 0.0f});
    LOGD("Navigine-based engine reset completed.");
}

// Accessors
int MotionEngine::getStepCount() const {
    std::lock_guard<std::mutex> lock(engineMutex);
    return stepCount;
}

float MotionEngine::getDistance() const {
    std::lock_guard<std::mutex> lock(engineMutex);
    return totalDistance;
}

float MotionEngine::getX() const {
    std::lock_guard<std::mutex> lock(engineMutex);
    return currentX;
}

float MotionEngine::getY() const {
    std::lock_guard<std::mutex> lock(engineMutex);
    return currentY;
}

std::vector<float> MotionEngine::getPathPoints() const {
    std::lock_guard<std::mutex> lock(engineMutex);
    std::vector<float> points;
    points.reserve(pathPoints.size() * 2);
    for (const auto& pt : pathPoints) {
        points.push_back(pt.x);
        points.push_back(pt.y);
    }
    return points;
}

// ============================================================================
// JNI BINDINGS
// ============================================================================

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_createEngine(
        JNIEnv* env, jobject thiz, jfloat step_length, jfloat sensitivity, jfloat filter_alpha) {
    auto* engine = new MotionEngine(step_length, sensitivity, filter_alpha);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_destroyEngine(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    delete engine;
}

JNIEXPORT void JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_setParameters(
        JNIEnv* env, jobject thiz, jlong native_ptr, jfloat step_length, jfloat sensitivity, jfloat filter_alpha) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine != nullptr) {
        engine->setParameters(step_length, sensitivity, filter_alpha);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_processSensorData(
        JNIEnv* env, jobject thiz, jlong native_ptr, jfloat x, jfloat y, jfloat z, jlong timestamp_ns) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine != nullptr) {
        return engine->processSensorData(x, y, z, timestamp_ns) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_addStep(
        JNIEnv* env, jobject thiz, jlong native_ptr, jfloat heading_degrees, jboolean is_simulator) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine != nullptr) {
        engine->addStep(heading_degrees, is_simulator);
    }
}

JNIEXPORT void JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_updateHeading(
        JNIEnv* env, jobject thiz, jlong native_ptr, jfloat heading_degrees) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine != nullptr) {
        engine->updateHeading(heading_degrees);
    }
}

JNIEXPORT void JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_resetEngine(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine != nullptr) {
        engine->reset();
    }
}

JNIEXPORT jint JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_getStepCount(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine != nullptr) {
        return engine->getStepCount();
    }
    return 0;
}

JNIEXPORT jfloat JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_getDistance(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine != nullptr) {
        return engine->getDistance();
    }
    return 0.0f;
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_imumotiontracer_NativeMotionEngine_getPathPoints(
        JNIEnv* env, jobject thiz, jlong native_ptr) {
    auto* engine = reinterpret_cast<MotionEngine*>(native_ptr);
    if (engine == nullptr) return nullptr;

    std::vector<float> points = engine->getPathPoints();
    jfloatArray result = env->NewFloatArray(points.size());
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, points.size(), points.data());
    }
    return result;
}

} // extern "C"
