package com.cameragun.lightgun

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CornerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00FF66")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#3300FF66")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val crosshairPaint = Paint().apply {
        color = Color.parseColor("#FF0055")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val corners = FloatArray(8) { -1f }
    private var isTrackingLocked = false

    fun updateCorners(detectedCorners: FloatArray, locked: Boolean) {
        System.arraycopy(detectedCorners, 0, corners, 0, 8)
        isTrackingLocked = locked
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // 1. Gambar Center Crosshair (Titik Bidik Laras)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val chSize = 30f

        canvas.drawLine(cx - chSize, cy, cx + chSize, cy, crosshairPaint)
        canvas.drawLine(cx, cy - chSize, cx, cy + chSize, crosshairPaint)
        canvas.drawCircle(cx, cy, 12f, crosshairPaint)

        // 2. Gambar 4 Sudut Border jika terdeteksi
        if (isTrackingLocked && corners[0] >= 0f) {
            val p0x = corners[0] * w; val p0y = corners[1] * h
            val p1x = corners[2] * w; val p1y = corners[3] * h
            val p2x = corners[4] * w; val p2y = corners[5] * h
            val p3x = corners[6] * w; val p3y = corners[7] * h

            val path = android.graphics.Path().apply {
                moveTo(p0x, p0y)
                lineTo(p1x, p1y)
                lineTo(p2x, p2y)
                lineTo(p3x, p3y)
                close()
            }

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, cornerPaint)

            // Lingkaran sudut
            val dotRadius = 14f
            canvas.drawCircle(p0x, p0y, dotRadius, cornerPaint)
            canvas.drawCircle(p1x, p1y, dotRadius, cornerPaint)
            canvas.drawCircle(p2x, p2y, dotRadius, cornerPaint)
            canvas.drawCircle(p3x, p3y, dotRadius, cornerPaint)
        }
    }
}
