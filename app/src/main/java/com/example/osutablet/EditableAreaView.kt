package com.example.osutablet // Make sure this matches your package name

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.pow

// NEW: An interface to notify MainActivity when the user finishes dragging
interface AreaChangedListener {
    fun onAreaManuallyChanged(newArea: RectF)
}

class EditableAreaView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // Paints (no changes here)
    private val areaPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 5f; alpha = 150 }
    private val handlePaint = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL; alpha = 200 }
    private val playModePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f; alpha = 80 }

    private var activeArea = RectF(100f, 100f, 600f, 500f)
    private var isSetupMode = false

    // State variables
    private var currentMode = Mode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val handleRadius = 30f
    private val minAreaSize = 100f

    // NEW: A variable to hold the listener
    var listener: AreaChangedListener? = null

    private enum class Mode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    fun setArea(rect: RectF) {
        activeArea.set(rect)
        invalidate()
    }

    // NEW: Function to resize the area from numerical input, keeping it centered
    fun resizeArea(newWidthPx: Float, newHeightPx: Float) {
        val centerX = activeArea.centerX()
        val centerY = activeArea.centerY()
        activeArea.left = centerX - newWidthPx / 2
        activeArea.top = centerY - newHeightPx / 2
        activeArea.right = centerX + newWidthPx / 2
        activeArea.bottom = centerY + newHeightPx / 2
        enforceConstraints()
        invalidate()
    }

    fun getArea(): RectF = activeArea
    fun setSetupMode(enabled: Boolean) {
        isSetupMode = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isSetupMode) {
            canvas.drawRect(activeArea, areaPaint)
            canvas.drawCircle(activeArea.left, activeArea.top, handleRadius, handlePaint)
            canvas.drawCircle(activeArea.right, activeArea.top, handleRadius, handlePaint)
            canvas.drawCircle(activeArea.left, activeArea.bottom, handleRadius, handlePaint)
            canvas.drawCircle(activeArea.right, activeArea.bottom, handleRadius, handlePaint)
        } else {
            canvas.drawRect(activeArea, playModePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isSetupMode) return false
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                currentMode = getModeForTouch(x, y)
                return currentMode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentMode == Mode.NONE) return false
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                updateArea(dx, dy) // MODIFIED: Reverted to free-form resizing
                lastTouchX = x
                lastTouchY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentMode = Mode.NONE
                // NEW: Notify the listener that the user has finished dragging
                listener?.onAreaManuallyChanged(activeArea)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getModeForTouch(x: Float, y: Float): Mode {
        if (isInsideCircle(x, y, activeArea.left, activeArea.top)) return Mode.RESIZE_TL
        if (isInsideCircle(x, y, activeArea.right, activeArea.top)) return Mode.RESIZE_TR
        if (isInsideCircle(x, y, activeArea.left, activeArea.bottom)) return Mode.RESIZE_BL
        if (isInsideCircle(x, y, activeArea.right, activeArea.bottom)) return Mode.RESIZE_BR
        if (activeArea.contains(x, y)) return Mode.MOVE
        return Mode.NONE
    }

    private fun isInsideCircle(x: Float, y: Float, cx: Float, cy: Float): Boolean {
        return (x - cx).pow(2) + (y - cy).pow(2) < handleRadius.pow(2)
    }

    // MODIFIED: Reverted to the simple, non-ratio-locked version
    private fun updateArea(dx: Float, dy: Float) {
        when (currentMode) {
            Mode.MOVE -> activeArea.offset(dx, dy)
            Mode.RESIZE_TL -> { activeArea.left += dx; activeArea.top += dy }
            Mode.RESIZE_TR -> { activeArea.right += dx; activeArea.top += dy }
            Mode.RESIZE_BL -> { activeArea.left += dx; activeArea.bottom += dy }
            Mode.RESIZE_BR -> { activeArea.right += dx; activeArea.bottom += dy }
            Mode.NONE -> return
        }
        enforceConstraints()
    }

    private fun enforceConstraints() {
        if (activeArea.width() < minAreaSize) { /* ... */ }
        if (activeArea.height() < minAreaSize) { /* ... */ }
        if (activeArea.left < 0) activeArea.offset(-activeArea.left, 0f)
        if (activeArea.top < 0) activeArea.offset(0f, -activeArea.top)
        if (activeArea.right > width) activeArea.offset(width - activeArea.right, 0f)
        if (activeArea.bottom > height) activeArea.offset(0f, height - activeArea.bottom)
    }
}