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
    
    // HSV Calibration Range (Default: High-visibility Neon Green)
    cv::Scalar lowerHsv{35, 100, 100};
    cv::Scalar upperHsv{85, 255, 255};
    
    int pcScreenWidth = 1920;
    int pcScreenHeight = 1080;
    
    OneEuroFilter2D smoother{1.2f, 0.05f};
    
    int lostTrackingFrames = 0;
    const int RELOAD_FRAME_THRESHOLD = 4;
    
    // Cache for last known corners for visual debugging
    std::vector<cv::Point2f> lastDetectedCorners;

    // Urutkan 4 titik sudut secara konsisten:
    // [0] = Top-Left, [1] = Top-Right, [2] = Bottom-Right, [3] = Bottom-Left
    std::vector<cv::Point2f> SortQuadCorners(const std::vector<cv::Point>& quadPts) {
        std::vector<cv::Point2f> pts(4);
        for (int i = 0; i < 4; ++i) {
            pts[i] = cv::Point2f(static_cast<float>(quadPts[i].x), static_cast<float>(quadPts[i].y));
        }

        std::vector<cv::Point2f> ordered(4);
        // Top-Left: terkecil (x + y)
        // Bottom-Right: terbesar (x + y)
        auto sumCompare = [](const cv::Point2f& a, const cv::Point2f& b) {
            return (a.x + a.y) < (b.x + b.y);
        };
        // Top-Right: terkecil (y - x) -> x besar, y kecil
        // Bottom-Left: terbesar (y - x) -> x kecil, y besar
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
    bool IsPlausibleScreenQuad(const std::vector<cv::Point2f>& corners, int frameW, int frameH) {
        float topEdge = static_cast<float>(cv::norm(corners[1] - corners[0]));
        float bottomEdge = static_cast<float>(cv::norm(corners[2] - corners[3]));
        float leftEdge = static_cast<float>(cv::norm(corners[3] - corners[0]));
        float rightEdge = static_cast<float>(cv::norm(corners[2] - corners[1]));

        if (topEdge < 40.0f || bottomEdge < 40.0f || leftEdge < 30.0f || rightEdge < 30.0f) {
            return false;
        }

        float avgWidth = (topEdge + bottomEdge) * 0.5f;
        float avgHeight = (leftEdge + rightEdge) * 0.5f;
        float aspectRatio = avgWidth / std::max(avgHeight, 1.0f);

        // Aspect ratio layar monitor umumnya 4:3 (1.33) hingga 21:9 (2.33), toleransi perspektif 0.8 - 3.0
        return (aspectRatio >= 0.8f && aspectRatio <= 3.2f);
    }

public:
    VisionPipelineCore() {
        lastDetectedCorners.resize(4, cv::Point2f(0.0f, 0.0f));
    }

    void SetHsvBoundaries(uint8_t hMin, uint8_t sMin, uint8_t vMin,
                          uint8_t hMax, uint8_t sMax, uint8_t vMax) {
        std::lock_guard<std::mutex> lock(pipelineMutex);
        lowerHsv = cv::Scalar(hMin, sMin, vMin);
        upperHsv = cv::Scalar(hMax, sMax, vMax);
        LOGD("Updated HSV Boundaries: [%d,%d,%d] - [%d,%d,%d]", hMin, sMin, vMin, hMax, sMax, vMax);
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
    }

    // Eksekusi Pipeline dari frame NV21 (buffer kontinu Y + VU berselang)
    void ProcessFrame(const uint8_t* nv21Data, int frameW, int frameH, float timestampSec,
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

        // Morfologi sederhana 3x3 untuk membuang bintik grafis game
        cv::Mat morphKernel = cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3));
        cv::morphologyEx(mask, mask, cv::MORPH_OPEN, morphKernel);

        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);

        std::vector<cv::Point> bestQuad;
        double maxArea = 0.0;
        double minRequiredArea = (static_cast<double>(frameW) * static_cast<double>(frameH)) * 0.05;

        for (const auto& contour : contours) {
            double area = cv::contourArea(contour);
            if (area < minRequiredArea) continue;

            double perimeter = cv::arcLength(contour, true);
            std::vector<cv::Point> approx;
            cv::approxPolyDP(contour, approx, 0.03 * perimeter, true);

            if (approx.size() == 4 && cv::isContourConvex(approx)) {
                if (area > maxArea) {
                    maxArea = area;
                    bestQuad = approx;
                }
            }
        }

        if (bestQuad.empty()) {
            lostTrackingFrames++;
            outFlags = 0;
            outConfidence = 0;

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

        std::vector<cv::Point2f> srcCorners = SortQuadCorners(bestQuad);

        if (!IsPlausibleScreenQuad(srcCorners, frameW, frameH)) {
            lostTrackingFrames++;
            outFlags = LightgunFlags::LOW_CONFIDENCE;
            outConfidence = 20;
            return;
        }

        lostTrackingFrames = 0;
        lastDetectedCorners = srcCorners;

        // Salin sudut deteksi untuk visual preview di Kotlin SurfaceView
        if (outCorners != nullptr) {
            for (int i = 0; i < 4; ++i) {
                outCorners[i * 2]     = srcCorners[i].x / static_cast<float>(frameW);
                outCorners[i * 2 + 1] = srcCorners[i].y / static_cast<float>(frameH);
            }
        }

        // Koordinat target monitor PC dinormalisasi 0.0f - 1.0f
        std::vector<cv::Point2f> dstCorners = {
            cv::Point2f(0.0f, 0.0f),
            cv::Point2f(1.0f, 0.0f),
            cv::Point2f(1.0f, 1.0f),
            cv::Point2f(0.0f, 1.0f)
        };

        // Homography / Perspective Transform
        cv::Mat H = cv::getPerspectiveTransform(srcCorners, dstCorners);

        // Titik bidik adalah pusat optik frame kamera HP
        cv::Point2f opticalCenter(static_cast<float>(frameW) * 0.5f, static_cast<float>(frameH) * 0.5f);
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
        jfloatArray outResultArray, jfloatArray outCornersArray) {

    jbyte* pNv21 = env->GetByteArrayElements(nv21Array, nullptr);
    if (!pNv21) return JNI_FALSE;

    float normX = 0.0f;
    float normY = 0.0f;
    uint8_t flags = 0;
    uint8_t confidence = 0;
    float corners[8] = { -1.0f };

    g_pipeline.ProcessFrame(
        reinterpret_cast<const uint8_t*>(pNv21),
        width, height, timestampSec,
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
