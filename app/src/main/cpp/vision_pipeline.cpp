#include <jni.h>
#include <android/log.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <vector>
#include <algorithm>
#include <cmath>
#include <mutex>

#include "CommonPacketDef.h"
#include "OneEuroFilter.hpp"

#define TAG "CameraGun-Vision"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

class VisionPipelineCore {
private:
    std::mutex pipelineMutex;
    
    // HSV Calibration Range (Default: Electric Cyan #00E5FF)
    // Monitor screen backlight lowers saturation significantly (glare / wash-out).
    // Set sMin to 15 and vMin to 30 for high sensitivity on laptops and TVs.
    cv::Scalar lowerHsv{60, 15, 30};
    cv::Scalar upperHsv{125, 255, 255};
    
    int pcScreenWidth = 1920;
    int pcScreenHeight = 1080;
    
    OneEuroFilter2D smoother{1.2f, 0.05f};
    
    int lostTrackingFrames = 0;
    const int RELOAD_FRAME_THRESHOLD = 4;
    
    float lastNormX = 0.5f;
    float lastNormY = 0.5f;
    
    // Cache for last known corners for visual debugging
    std::vector<cv::Point2f> lastDetectedCorners;

    // Konversi koordinat sensor mentah ke ruang koordinat layar tegak (Upright Screen Coordinates)
    // Mengakomodasi rotasi kamera bawaan Android (native sensor 90 derajat searah jarum jam)
    static cv::Point2f SensorToUpright(const cv::Point2f& pt, int frameW, int frameH, int rotationDegrees, int& dispW, int& dispH) {
        float x = pt.x;
        float y = pt.y;
        float fw = static_cast<float>(frameW);
        float fh = static_cast<float>(frameH);

        switch (rotationDegrees) {
            case 0: // Portrait: sensor kamera 90° searah jarum jam relatif terhadap bodi HP
                dispW = frameH;
                dispH = frameW;
                return cv::Point2f(fh - 1.0f - y, x);
            case 90: // Landscape
                dispW = frameW;
                dispH = frameH;
                return cv::Point2f(x, y);
            case 180: // Reverse Portrait
                dispW = frameH;
                dispH = frameW;
                return cv::Point2f(y, fw - 1.0f - x);
            case 270: // Reverse Landscape
                dispW = frameW;
                dispH = frameH;
                return cv::Point2f(fw - 1.0f - x, fh - 1.0f - y);
            default:
                dispW = frameW;
                dispH = frameH;
                return cv::Point2f(x, y);
        }
    }

    // Urutkan 4 titik sudut secara konsisten di ruang koordinat tegak:
    // [0] = Top-Left, [1] = Top-Right, [2] = Bottom-Right, [3] = Bottom-Left
    std::vector<cv::Point2f> SortQuadCorners(const std::vector<cv::Point2f>& pts) {
        std::vector<cv::Point2f> ordered(4);
        auto sumCompare = [](const cv::Point2f& a, const cv::Point2f& b) {
            return (a.x + a.y) < (b.x + b.y);
        };
        auto diffCompare = [](const cv::Point2f& a, const cv::Point2f& b) {
            return (a.y - a.x) < (b.y - b.x);
        };

        ordered[0] = *std::min_element(pts.begin(), pts.end(), sumCompare);
        ordered[2] = *std::max_element(pts.begin(), pts.end(), sumCompare);
        ordered[1] = *std::min_element(pts.begin(), pts.end(), diffCompare);
        ordered[3] = *std::max_element(pts.begin(), pts.end(), diffCompare);

        return ordered;
    }

    // Verifikasi rasio geometri quadrilateral agar bukan artefak latar belakang
    bool IsPlausibleScreenQuad(const std::vector<cv::Point2f>& corners, int dispW, int dispH) {
        float topEdge = static_cast<float>(cv::norm(corners[1] - corners[0]));
        float bottomEdge = static_cast<float>(cv::norm(corners[2] - corners[3]));
        float leftEdge = static_cast<float>(cv::norm(corners[3] - corners[0]));
        float rightEdge = static_cast<float>(cv::norm(corners[2] - corners[1]));

        // Toleransi minimum panjang sisi (TV/laptop dari jarak 1.5 - 3 meter)
        if (topEdge < 18.0f || bottomEdge < 18.0f || leftEdge < 14.0f || rightEdge < 14.0f) {
            return false;
        }

        float avgWidth = (topEdge + bottomEdge) * 0.5f;
        float avgHeight = (leftEdge + rightEdge) * 0.5f;
        float aspectRatio = avgWidth / std::max(avgHeight, 1.0f);

        // Toleransi perspektif yang sangat aman untuk landscape maupun portrait
        return (aspectRatio >= 0.25f && aspectRatio <= 4.0f);
    }

public:
    VisionPipelineCore() {
        lastDetectedCorners.resize(4, cv::Point2f(0.0f, 0.0f));
    }

    void SetHsvBoundaries(uint8_t hMin, uint8_t sMin, uint8_t vMin,
                          uint8_t hMax, uint8_t sMax, uint8_t vMax) {
        std::lock_guard<std::mutex> lock(pipelineMutex);
        // Pertahankan batas minimum saturation aman untuk monitor backlight
        uint8_t safeSMin = std::min<uint8_t>(sMin, 20);
        uint8_t safeVMin = std::min<uint8_t>(vMin, 35);
        lowerHsv = cv::Scalar(hMin, safeSMin, safeVMin);
        upperHsv = cv::Scalar(hMax, sMax, vMax);
        LOGD("Updated HSV Boundaries: [%d,%d,%d] - [%d,%d,%d]", hMin, safeSMin, safeVMin, hMax, sMax, vMax);
    }

    void SetScreenDimensions(int w, int h) {
        std::lock_guard<std::mutex> lock(pipelineMutex);
        pcScreenWidth = std::max(w, 320);
        pcScreenHeight = std::max(h, 240);
    }

    void SetFilterParameters(float minCutoff, float beta) {
        std::lock_guard<std::mutex> lock(pipelineMutex);
        smoother.setParameters(minCutoff, beta);
    }

    void ResetTracking() {
        std::lock_guard<std::mutex> lock(pipelineMutex);
        smoother.reset();
        lostTrackingFrames = 0;
        lastNormX = 0.5f;
        lastNormY = 0.5f;
    }

    // Eksekusi Pipeline dari frame NV21 dengan orientasi layar tegak
    void ProcessFrame(const uint8_t* nv21Data, int frameW, int frameH, float timestampSec,
                      int rotationDegrees,
                      float& outNormX, float& outNormY, uint8_t& outFlags, uint8_t& outConfidence,
                      float* outCorners) {
        std::lock_guard<std::mutex> lock(pipelineMutex);

        cv::Mat yuv(frameH + frameH / 2, frameW, CV_8UC1, const_cast<uint8_t*>(nv21Data));
        cv::Mat bgr, hsv, mask;

        // Konversi warna tercepat
        cv::cvtColor(yuv, bgr, cv::COLOR_YUV2BGR_NV21);
        cv::cvtColor(bgr, hsv, cv::COLOR_BGR2HSV);

        // Thresholding warna border
        cv::inRange(hsv, lowerHsv, upperHsv, mask);

        // MORPH_CLOSE untuk menyambung segmen border tipis pada TV/laptop
        cv::Mat closeKernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
        cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, closeKernel);

        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

        std::vector<cv::Point> bestQuad;
        double maxArea = 0.0;
        double minRequiredArea = (static_cast<double>(frameW) * static_cast<double>(frameH)) * 0.015;

        for (const auto& contour : contours) {
            double area = cv::contourArea(contour);
            if (area < minRequiredArea) continue;

            std::vector<cv::Point> hull;
            cv::convexHull(contour, hull);

            double hullArea = cv::contourArea(hull);
            if (hullArea < minRequiredArea) continue;

            double perimeter = cv::arcLength(hull, true);

            // Coba multi-factor epsilon
            for (double epsFactor : {0.018, 0.025, 0.035, 0.045, 0.06}) {
                std::vector<cv::Point> approx;
                cv::approxPolyDP(hull, approx, epsFactor * perimeter, true);

                if (approx.size() == 4 && cv::isContourConvex(approx)) {
                    if (hullArea > maxArea) {
                        maxArea = hullArea;
                        bestQuad = approx;
                    }
                    break;
                }
            }
        }

        // Fallback: Jika approxPolyDP terganggu glare atau bezel, gunakan minAreaRect dari kontur terbesar!
        if (bestQuad.empty() && !contours.empty()) {
            double largestArea = 0.0;
            const std::vector<cv::Point>* largestContour = nullptr;
            for (const auto& c : contours) {
                double a = cv::contourArea(c);
                if (a > largestArea && a >= minRequiredArea) {
                    largestArea = a;
                    largestContour = &c;
                }
            }
            if (largestContour != nullptr) {
                cv::RotatedRect rRect = cv::minAreaRect(*largestContour);
                cv::Point2f rectPts[4];
                rRect.points(rectPts);
                bestQuad.clear();
                for (int i = 0; i < 4; ++i) {
                    bestQuad.push_back(cv::Point(static_cast<int>(rectPts[i].x), static_cast<int>(rectPts[i].y)));
                }
                maxArea = largestArea;
            }
        }

        if (bestQuad.empty()) {
            lostTrackingFrames++;
            outFlags = 0;
            outConfidence = 0;
            // Pertahankan posisi terakhir agar pointer tidak melompat atau membeku ke (0,0)
            outNormX = lastNormX;
            outNormY = lastNormY;

            // Jika kehilangan tracking secara konsisten, picu Off-screen reload
            if (lostTrackingFrames >= RELOAD_FRAME_THRESHOLD) {
                outFlags |= LightgunFlags::OFFSCREEN_RELOAD;
            }

            // Kembalikan sudut kosong untuk preview UI
            if (outCorners != nullptr) {
                for (int i = 0; i < 8; ++i) outCorners[i] = -1.0f;
            }
            return;
        }

        // Konversikan ke upright screen space agar orientasi monitor sesuai pandangan pemain
        int dispW = frameW;
        int dispH = frameH;
        std::vector<cv::Point2f> uprightPts(4);
        for (int i = 0; i < 4; ++i) {
            cv::Point2f rawPt(static_cast<float>(bestQuad[i].x), static_cast<float>(bestQuad[i].y));
            uprightPts[i] = SensorToUpright(rawPt, frameW, frameH, rotationDegrees, dispW, dispH);
        }

        std::vector<cv::Point2f> srcCorners = SortQuadCorners(uprightPts);

        if (!IsPlausibleScreenQuad(srcCorners, dispW, dispH)) {
            lostTrackingFrames++;
            outFlags = LightgunFlags::LOW_CONFIDENCE;
            outConfidence = 20;
            outNormX = lastNormX;
            outNormY = lastNormY;
            if (outCorners != nullptr) {
                for (int i = 0; i < 8; ++i) outCorners[i] = -1.0f;
            }
            return;
        }

        lostTrackingFrames = 0;
        lastDetectedCorners = srcCorners;

        // Salin 4 sudut deteksi untuk visual preview di Kotlin SurfaceView / CornerOverlayView
        if (outCorners != nullptr) {
            for (int i = 0; i < 4; ++i) {
                outCorners[i * 2]     = srcCorners[i].x / static_cast<float>(dispW);
                outCorners[i * 2 + 1] = srcCorners[i].y / static_cast<float>(dispH);
            }
        }

        // Koordinat target monitor PC dinormalisasi 0.0f - 1.0f
        std::vector<cv::Point2f> dstCorners = {
            cv::Point2f(0.0f, 0.0f),
            cv::Point2f(1.0f, 0.0f),
            cv::Point2f(1.0f, 1.0f),
            cv::Point2f(0.0f, 1.0f)
        };

        // Homography / Perspective Transform dari upright screen camera ke monitor PC
        cv::Mat H = cv::getPerspectiveTransform(srcCorners, dstCorners);

        // Titik bidik adalah pusat optik layar kamera HP (posisi crosshair reticle tengah)
        cv::Point2f opticalCenter(static_cast<float>(dispW) * 0.5f, static_cast<float>(dispH) * 0.5f);
        std::vector<cv::Point2f> camPoint = { opticalCenter };
        std::vector<cv::Point2f> screenMappedPoint(1);

        cv::perspectiveTransform(camPoint, screenMappedPoint, H);

        float rawX = screenMappedPoint[0].x;
        float rawY = screenMappedPoint[0].y;

        // Filter koordinat dengan One Euro Filter
        float filteredX = 0.0f;
        float filteredY = 0.0f;
        smoother.filter(rawX, rawY, timestampSec, filteredX, filteredY);

        outNormX = std::clamp(filteredX, 0.0f, 1.0f);
        outNormY = std::clamp(filteredY, 0.0f, 1.0f);
        lastNormX = outNormX;
        lastNormY = outNormY;
        outFlags = LightgunFlags::TRACKING_LOCKED;
        outConfidence = 100;
    }
};

static VisionPipelineCore g_pipeline;

extern "C" {

JNIEXPORT void JNICALL
Java_com_cameragun_lightgun_NativeVisionBridge_initVision(
        JNIEnv* env, jobject /* this */, jint screenW, jint screenH) {
    g_pipeline.SetScreenDimensions(screenW, screenH);
    g_pipeline.ResetTracking();
}

JNIEXPORT void JNICALL
Java_com_cameragun_lightgun_NativeVisionBridge_updateHsvBoundaries(
        JNIEnv* env, jobject /* this */,
        jint hMin, jint sMin, jint vMin,
        jint hMax, jint sMax, jint vMax) {
    g_pipeline.SetHsvBoundaries(
        static_cast<uint8_t>(hMin), static_cast<uint8_t>(sMin), static_cast<uint8_t>(vMin),
        static_cast<uint8_t>(hMax), static_cast<uint8_t>(sMax), static_cast<uint8_t>(vMax)
    );
}

JNIEXPORT void JNICALL
Java_com_cameragun_lightgun_NativeVisionBridge_updateFilterParams(
        JNIEnv* env, jobject /* this */, jfloat minCutoff, jfloat beta) {
    g_pipeline.SetFilterParameters(minCutoff, beta);
}

JNIEXPORT jboolean JNICALL
Java_com_cameragun_lightgun_NativeVisionBridge_processFrameNV21(
        JNIEnv* env, jobject /* this */,
        jbyteArray nv21Array, jint width, jint height, jfloat timestampSec,
        jint rotationDegrees,
        jfloatArray outResultArray, jfloatArray outCornersArray) {

    jbyte* pNv21 = env->GetByteArrayElements(nv21Array, nullptr);
    if (!pNv21) return JNI_FALSE;

    float normX = 0.0f;
    float normY = 0.0f;
    uint8_t flags = 0;
    uint8_t confidence = 0;
    float corners[8] = { -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f, -1.0f };

    g_pipeline.ProcessFrame(
        reinterpret_cast<const uint8_t*>(pNv21),
        width, height, timestampSec,
        rotationDegrees,
        normX, normY, flags, confidence,
        corners
    );

    env->ReleaseByteArrayElements(nv21Array, pNv21, JNI_ABORT);

    // Hasil array: [0]=normX, [1]=normY, [2]=flags, [3]=confidence
    jfloat results[4] = { normX, normY, static_cast<float>(flags), static_cast<float>(confidence) };
    env->SetFloatArrayRegion(outResultArray, 0, 4, results);

    if (outCornersArray != nullptr) {
        env->SetFloatArrayRegion(outCornersArray, 0, 8, corners);
    }

    return (flags & LightgunFlags::TRACKING_LOCKED) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
