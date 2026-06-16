#ifndef MOTION_ENGINE_H
#define MOTION_ENGINE_H

#include <vector>
#include <mutex>
#include <deque>

struct Point {
    float x;
    float y;
};

struct SensorSample {
    float magnitude;
    long long timestampMs;
};

struct FilteredSample {
    float value;
    long long timestampMs;
};

class MotionEngine {
private:
    mutable std::mutex engineMutex;

    // Parameters
    float weinbergK;        // Stride calculation constant (default 0.52)
    float minVariation;     // Crossover threshold (default 0.05 * 9.80665 = 0.49)
    float filterAlpha;      // Shared filter weight for heading smoothing

    // Navigine sliding window histories
    std::deque<SensorSample> accelMeasurements;
    std::deque<FilteredSample> filteredAccMagnitudes;
    std::deque<long long> stepTimes;

    // Pedometer states
    bool isStep;
    long long lastStepTimeMs;
    long long prevStepTimeMs;
    float nextStride;       // calculated stride length for the current step event

    // Vector integration (relative positions in meters)
    float currentX;
    float currentY;
    float currentHeading;   // smoothed heading in degrees
    
    // Circular heading smoothing components
    float smoothedSin;
    float smoothedCos;

    std::vector<Point> pathPoints;
    int stepCount;
    float totalDistance;

    // Navigine constants
    static constexpr long long AVERAGING_TIME_INTERVAL_MS = 2500; // 2.5 seconds running mean
    static constexpr long long FILTER_TIME_INTERVAL_MS = 200;     // 200 ms low pass filter
    static constexpr long long UPDATE_TIME_INTERVAL_MS = 700;     // 700 ms window for Weinberg max/min
    static constexpr long long MIN_TIME_BETWEEN_STEPS_MS = 300;   // 300 ms minimum debounce
    static constexpr long long MAX_STEP_TIME_MS = 2000;           // 2 seconds max step interval
    static constexpr int MINIMAL_NUMBER_OF_STEPS = 5;
    static constexpr int MAXIMUM_NUMBER_OF_STEPS = 50;

public:
    MotionEngine(float weinbergK = 0.52f, float minVariation = 0.49f, float filterAlpha = 0.15f);
    ~MotionEngine() = default;

    // Sets parameters dynamically
    void setParameters(float weinbergK, float minVariation, float filterAlpha);

    // Feeds raw accelerometer values. Returns true if a step crossover was completed.
    bool processSensorData(float x, float y, float z, long long timestampNs);

    // Updates heading continuously using circular coordinates and filterAlpha
    void updateHeading(float headingDegrees);

    // Updates path coordinate using the dynamic nextStride and smoothed heading
    void addStep(float headingDegrees, bool isSimulator);

    // Fuses the PDR location with absolute Wi-Fi coordinates
    void fuseLocation(float wifiX, float wifiY, float beta);

    // Resets positions and clear histories
    void reset();

    // Data Accessors
    int getStepCount() const;
    float getDistance() const;
    float getX() const;
    float getY() const;
    
    // Returns interleaved path coordinates for drawing
    std::vector<float> getPathPoints() const;
};

#endif // MOTION_ENGINE_H
