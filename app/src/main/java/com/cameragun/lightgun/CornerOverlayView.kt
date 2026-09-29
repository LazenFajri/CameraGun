package com.cameragun.lightgun

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
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
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val reticleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2200E5FF")
        style = Paint.Style.FILL
    }

    private val reticleGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dimCyan
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    // Corner Detection Box Paint
    private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val cornerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A00FF66")
        style = Paint.Style.FILL
    }

    private val cornerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = greenColor
        style = Paint.Style.FILL
    }

    private val cornerLostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = magentaColor
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    // Info Text Paint
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AA00E5FF")
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }

    private val corners = FloatArray(8) { -1f }
    private var isTrackingLocked = false
    private val path = Path()

    fun updateCorners(detectedCorners: FloatArray, locked: Boolean) {
        System.arraycopy(detectedCorners, 0, corners, 0, 8)
        isTrackingLocked = locked
        postInvalidate()
    }

    fun setPlayerRole(isP2: Boolean) {
        val baseColor = if (isP2) magentaColor else cyanColor
        val fillHex = if (isP2) "#22FF0066" else "#2200E5FF"
        val glowHex = if (isP2) "#44FF0066" else "#4400E5FF"

        reticlePaint.color = baseColor
        reticleFillPaint.color = Color.parseColor(fillHex)
        reticleGlowPaint.color = Color.parseColor(glowHex)
        infoPaint.color = if (isP2) Color.parseColor("#AAFF0066") else Color.parseColor("#AA00E5FF")
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f

        // 1. Crosshair Reticle — Cyberpunk Style
        drawCyberpunkReticle(canvas, cx, cy, w, h)

        // 2. Corner Detection Quad
        if (isTrackingLocked && corners[0] >= 0f) {
            drawLockedQuad(canvas, w, h)
        } else if (corners[0] < 0f) {
            drawSearchingIndicator(canvas, cx, cy)
        }
    }

    private fun drawCyberpunkReticle(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        // Full-width / Full-height dashed guides
        canvas.drawLine(0f, cy, w, cy, reticleGlowPaint)
        canvas.drawLine(cx, 0f, cx, h, reticleGlowPaint)

        // Inner reticle gap crosshair (solid lines near center with gap)
        val gapInner = 14f
        val armLen = 36f

        // Horizontal arms
        canvas.drawLine(cx - gapInner - armLen, cy, cx - gapInner, cy, reticlePaint)
        canvas.drawLine(cx + gapInner, cy, cx + gapInner + armLen, cy, reticlePaint)

        // Vertical arms
        canvas.drawLine(cx, cy - gapInner - armLen, cx, cy - gapInner, reticlePaint)
        canvas.drawLine(cx, cy + gapInner, cx, cy + gapInner + armLen, reticlePaint)

        // Center dot with glow
        canvas.drawCircle(cx, cy, 20f, reticleFillPaint)
        canvas.drawCircle(cx, cy, 20f, reticlePaint)
        canvas.drawCircle(cx, cy, 4f, cornerDotPaint.apply { color = cyanColor })

        // Corner brackets (decorative)
        val bracketLen = 28f
        val bracketInset = 40f

        // Top-Left bracket
        canvas.drawLine(bracketInset, bracketInset, bracketInset + bracketLen, bracketInset, reticlePaint)
        canvas.drawLine(bracketInset, bracketInset, bracketInset, bracketInset + bracketLen, reticlePaint)

        // Top-Right bracket
        canvas.drawLine(w - bracketInset, bracketInset, w - bracketInset - bracketLen, bracketInset, reticlePaint)
        canvas.drawLine(w - bracketInset, bracketInset, w - bracketInset, bracketInset + bracketLen, reticlePaint)

        // Bottom-Left bracket
        canvas.drawLine(bracketInset, h - bracketInset, bracketInset + bracketLen, h - bracketInset, reticlePaint)
        canvas.drawLine(bracketInset, h - bracketInset, bracketInset, h - bracketInset - bracketLen, reticlePaint)

        // Bottom-Right bracket
        canvas.drawLine(w - bracketInset, h - bracketInset, w - bracketInset - bracketLen, h - bracketInset, reticlePaint)
        canvas.drawLine(w - bracketInset, h - bracketInset, w - bracketInset, h - bracketInset - bracketLen, reticlePaint)
    }

    private fun drawLockedQuad(canvas: Canvas, w: Float, h: Float) {
        val isPortrait = w < h
        val bufferW = if (isPortrait) 720f else 1280f
        val bufferH = if (isPortrait) 1280f else 720f
        val scale = maxOf(w / bufferW, h / bufferH)
        val contentW = bufferW * scale
        val contentH = bufferH * scale
        val dx = (w - contentW) * 0.5f
        val dy = (h - contentH) * 0.5f

        val p0x = dx + corners[0] * contentW; val p0y = dy + corners[1] * contentH
        val p1x = dx + corners[2] * contentW; val p1y = dy + corners[3] * contentH
        val p2x = dx + corners[4] * contentW; val p2y = dy + corners[5] * contentH
        val p3x = dx + corners[6] * contentW; val p3y = dy + corners[7] * contentH

        path.reset()
        path.moveTo(p0x, p0y)
        path.lineTo(p1x, p1y)
        path.lineTo(p2x, p2y)
        path.lineTo(p3x, p3y)
        path.close()

        canvas.drawPath(path, cornerFillPaint)
        canvas.drawPath(path, cornerStrokePaint)

        // Corner dots with glow
        val dotRadius = 8f
        cornerDotPaint.color = greenColor
        canvas.drawCircle(p0x, p0y, dotRadius, cornerDotPaint)
        canvas.drawCircle(p1x, p1y, dotRadius, cornerDotPaint)
        canvas.drawCircle(p2x, p2y, dotRadius, cornerDotPaint)
        canvas.drawCircle(p3x, p3y, dotRadius, cornerDotPaint)
    }

    private fun drawSearchingIndicator(canvas: Canvas, cx: Float, cy: Float) {
        // Animated-feel scanning rings (static but suggests motion)
        canvas.drawCircle(cx, cy, 60f, cornerLostPaint)
        canvas.drawCircle(cx, cy, 90f, cornerLostPaint.apply { alpha = 80 })
        canvas.drawCircle(cx, cy, 120f, cornerLostPaint.apply { alpha = 40 })
        cornerLostPaint.alpha = 255 // Reset alpha
    }
}
