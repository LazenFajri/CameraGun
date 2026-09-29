#pragma once
#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class LowPassFilter {
private:
    float y = 0.0f;
    float s = 0.0f;
    bool initialized = false;

public:
    LowPassFilter() = default;

    float filter(float value, float alpha) {
        if (initialized) {
            y = alpha * value + (1.0f - alpha) * s;
        } else {
            y = value;
            initialized = true;
        }
        s = y;
        return y;
    }

    float last() const {
        return y;
    }

    void reset() {
        initialized = false;
        y = 0.0f;
        s = 0.0f;
    }
};

class OneEuroFilter {
private:
    float minCutoff;   // fc_min (Hz): Mengontrol stabilitas saat diam
    float beta;        // Beta: Kecepatan respon terhadap gerakan cepat
    float dCutoff;     // Cutoff turunan kecepatan
    LowPassFilter xFilt;
    LowPassFilter dxFilt;
    float lastTime = -1.0f;

    static float calculateAlpha(float rate, float cutoff) {
        float tau = 1.0f / (2.0f * static_cast<float>(M_PI) * cutoff);
        float te = 1.0f / rate;
        return 1.0f / (1.0f + (tau / te));
    }

public:
    explicit OneEuroFilter(float minCutoff = 1.2f, float beta = 0.05f, float dCutoff = 1.0f)
        : minCutoff(minCutoff), beta(beta), dCutoff(dCutoff) {}

    void setParameters(float minCut, float b) {
        minCutoff = minCut;
        beta = b;
    }

    void reset() {
        lastTime = -1.0f;
        xFilt.reset();
        dxFilt.reset();
    }

    float filter(float value, float timestamp) {
        if (lastTime < 0.0f) {
            lastTime = timestamp;
            return xFilt.filter(value, 1.0f);
        }

        float dt = timestamp - lastTime;
        if (dt <= 0.0f) {
            dt = 0.001f;
        }
        lastTime = timestamp;
        float rate = 1.0f / dt;

        // Turunan pertama (kecepatan perubahan koordinat)
        float dx = (value - xFilt.last()) * rate;
        float edx = dxFilt.filter(dx, calculateAlpha(rate, dCutoff));

        // Cutoff dinamis berdasarkan magnitudo kecepatan
        float dynamicCutoff = minCutoff + beta * std::abs(edx);
        return xFilt.filter(value, calculateAlpha(rate, dynamicCutoff));
    }
};

class OneEuroFilter2D {
private:
    OneEuroFilter filterX;
    OneEuroFilter filterY;

public:
    OneEuroFilter2D(float minCutoff = 1.2f, float beta = 0.05f)
        : filterX(minCutoff, beta), filterY(minCutoff, beta) {}

    void setParameters(float minCutoff, float beta) {
        filterX.setParameters(minCutoff, beta);
        filterY.setParameters(minCutoff, beta);
    }

    void reset() {
        filterX.reset();
        filterY.reset();
    }

    void filter(float inX, float inY, float timestampSeconds, float& outX, float& outY) {
        outX = filterX.filter(inX, timestampSeconds);
        outY = filterY.filter(inY, timestampSeconds);
    }
};
