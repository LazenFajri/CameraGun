package com.cameragun.lightgun

class NativeVisionBridge {
    companion object {
        init {
            System.loadLibrary("cameragun_vision")
        }
    }

    external fun initVision(screenW: Int, screenH: Int)
    
    external fun updateHsvBoundaries(
        hMin: Int, sMin: Int, vMin: Int,
        hMax: Int, sMax: Int, vMax: Int
    )

    external fun updateFilterParams(minCutoff: Float, beta: Float)

    /**
     * Memproses buffer frame NV21 secara native
     * @param nv21Array Buffer continuous NV21 (Y plane + interleaved VU)
     * @param width Resolusi lebar kamera
     * @param height Resolusi tinggi kamera
     * @param timestampSec Monotonic timestamp dalam satuan detik
     * @param rotationDegrees Derajat rotasi layar (0: Portrait, 90: Land, 180: RevPort, 270: RevLand)
     * @param outResults FloatArray(4): [0]=NormX, [1]=NormY, [2]=Flags, [3]=Confidence
     * @param outCorners FloatArray(8): [x0, y0, x1, y1, x2, y2, x3, y3] dinormalisasi 0..1 untuk preview
     */
    external fun processFrameNV21(
        nv21Array: ByteArray,
        width: Int,
        height: Int,
        timestampSec: Float,
        rotationDegrees: Int,
        outResults: FloatArray,
        outCorners: FloatArray
    ): Boolean
}
