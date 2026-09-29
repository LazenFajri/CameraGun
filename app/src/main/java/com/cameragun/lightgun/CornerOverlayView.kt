package com.cameragun.lightgun

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CornerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Cyberpunk Neon Colors
    private val cyanColor = Color.parseColor("#00E5FF")
    private val greenColor = Color.parseColor("#00FF66")
    private val magentaColor = Color.parseColor("#FF0066")
    private val dimCyan = Color.parseColor("#4400E5FF")

    // Crosshair Reticle Paint
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyanColor
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private val reticleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1500E5FF")
        style = Paint.Style.FILL
    }

    private val reticleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimCyan
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    // GKHeart Center Target Box Paints
    private val boxCalibratedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val boxCalibratedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1800FF66")
        style = Paint.Style.FILL
    }

    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cyanColor
        style = Paint.Style.FILL
    }

    private val aimDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenColor
        style = Paint.Style.FILL
    }

    // Info Banner Text Paint
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD00E5FF")
        textSize = 22f
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    var isCalibrated: Boolean = false
        private set
    var aimNormX: Float = 0.5f
        private set
    var aimNormY: Float = 0.5f
        private set
    private var isP2Mode: Boolean = false

    var onRecenterRequested: (() -> Unit)? = null

    fun updateAimState(calibrated: Boolean, normX: Float, normY: Float) {
        isCalibrated = calibrated
        aimNormX = normX
        aimNormY = normY
        postInvalidate()
    }

    fun setPlayerRole(isP2: Boolean) {
        isP2Mode = isP2
        val baseColor = if (isP2) magentaColor else cyanColor
        val fillHex = if (isP2) "#18FF0066" else "#1500E5FF"
        val glowHex = if (isP2) "#44FF0066" else "#4400E5FF"

        reticlePaint.color = baseColor
        reticleFillPaint.color = Color.parseColor(fillHex)
        reticleGlowPaint.color = Color.parseColor(glowHex)
        centerDotPaint.color = baseColor
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onRecenterRequested?.invoke()
            return true
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f

        // 1. Crosshair Guides (Full Screen Dashed Grid)
        canvas.drawLine(0f, cy, w, cy, reticleGlowPaint)
        canvas.drawLine(cx, 0f, cx, h, reticleGlowPaint)

        // 2. GKHeart Center Aim Box (Kotak Target Layar di Tengah)
        drawCenterAimBox(canvas, cx, cy, w, h)

        // 3. Central Reflex Crosshair
        drawReflexCrosshair(canvas, cx, cy)
    }

    private fun drawCenterAimBox(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        // Kotak 16:9 proporsional di tengah layar HP
        val maxBoxH = minOf(h * 0.55f, (w * 0.46f) * 0.5625f)
        val boxH = maxBoxH
        val boxW = boxH * (16f / 9f) // 16:9 ratio
        val left = cx - boxW * 0.5f
        val top = cy - boxH * 0.5f
        val right = cx + boxW * 0.5f
        val bottom = cy + boxH * 0.5f

        // Box background fill
        val fill = if (isCalibrated) boxCalibratedFill else reticleFillPaint
        canvas.drawRect(left, top, right, bottom, fill)

        // Dashed border
        canvas.drawRect(left, top, right, bottom, reticleGlowPaint)

        // 4 Corner neon brackets
        val stroke = if (isCalibrated) boxCalibratedPaint else reticlePaint
        val bLen = minOf(36f, boxW * 0.08f)

        // Top-Left
        canvas.drawLine(left, top, left + bLen, top, stroke)
        canvas.drawLine(left, top, left, top + bLen, stroke)
        // Top-Right
        canvas.drawLine(right, top, right - bLen, top, stroke)
        canvas.drawLine(right, top, right, top + bLen, stroke)
        // Bottom-Left
        canvas.drawLine(left, bottom, left + bLen, bottom, stroke)
        canvas.drawLine(left, bottom, left, bottom - bLen, stroke)
        // Bottom-Right
        canvas.drawLine(right, bottom, right - bLen, bottom, stroke)
        canvas.drawLine(right, bottom, right, bottom - bLen, stroke)

        // Status Banner Text
        val bannerY = top - 14f
        val bannerText = if (isCalibrated) {
            "● 100Hz GYRO LOCKED [TAP: RECENTER]"
        } else {
            "⊕ BIDIK KE TENGAH LAYAR & TEKAN RECENTER"
        }
        infoPaint.color = if (isCalibrated) greenColor else reticlePaint.color
        canvas.drawText(bannerText, cx, bannerY, infoPaint)

        // Live Aim Impact Point inside box
        if (isCalibrated) {
            val aimPx = left + aimNormX * boxW
            val aimPy = top + aimNormY * boxH
            aimDotPaint.color = if (isP2Mode) magentaColor else greenColor
            canvas.drawCircle(aimPx, aimPy, 5f, aimDotPaint)
            canvas.drawCircle(aimPx, aimPy, 12f, stroke)
        }
    }

    private fun drawReflexCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        val gapInner = 14f
        val armLen = 32f

        // Center reticle arms
        canvas.drawLine(cx - gapInner - armLen, cy, cx - gapInner, cy, reticlePaint)
        canvas.drawLine(cx + gapInner, cy, cx + gapInner + armLen, cy, reticlePaint)
        canvas.drawLine(cx, cy - gapInner - armLen, cx, cy - gapInner, reticlePaint)
        canvas.drawLine(cx, cy + gapInner, cx, cy + gapInner + armLen, reticlePaint)

        // Center circular sight ring
        val ringColor = if (isCalibrated) boxCalibratedPaint else reticlePaint
        canvas.drawCircle(cx, cy, 20f, ringColor)
        canvas.drawCircle(cx, cy, 4f, centerDotPaint)
    }
}
