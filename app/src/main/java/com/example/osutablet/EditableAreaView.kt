package com.example.osutablet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Notified as the user edits the active area in setup mode. */
interface AreaChangedListener {
    /** Fired continuously while dragging so readouts track the gesture. */
    fun onAreaChanging(newArea: RectF)

    /** Fired once the gesture settles. */
    fun onAreaChanged(newArea: RectF)
}

/**
 * Draws and edits the active tablet area.
 *
 * The area is held in view pixels but exposed normalized, so it survives
 * rotation and differing surface sizes. Resizing anchors the opposite corner
 * and clamps against a minimum, which stops the rectangle from inverting —
 * an inverted rect yields a negative width and silently mirrors every
 * coordinate sent to the PC.
 */
class EditableAreaView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val areaPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        alpha = 150
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        alpha = 200
        isAntiAlias = true
    }
    private val playModePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 80
        isAntiAlias = true
    }

    private val activeArea = RectF()
    private var isSetupMode = false

    private var currentMode = Mode.NONE
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val handleRadius = resources.displayMetrics.density * HANDLE_RADIUS_DP
    private val minAreaSize = resources.displayMetrics.density * MIN_AREA_DP

    var listener: AreaChangedListener? = null

    private enum class Mode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    // --- Area accessors ---------------------------------------------------

    /** Current area in view pixels. Returns a copy; the field is mutable state. */
    fun getArea(): RectF = RectF(activeArea)

    fun setArea(rect: RectF) {
        activeArea.set(rect)
        enforceConstraints()
        invalidate()
        // Every path that changes the area must notify, or the drawn rectangle
        // and the touch-mapping rectangle drift apart. That happened at
        // startup: the saved area was applied here after onSizeChanged had
        // already announced the default, so input kept mapping against the
        // default until the user re-saved in setup mode.
        listener?.onAreaChanged(getArea())
    }

    /** Current area as fractions of the view, safe to persist across rotations. */
    fun getNormalizedArea(): RectF {
        if (width <= 0 || height <= 0) return RectF(DEFAULT_INSET, DEFAULT_INSET, 1f - DEFAULT_INSET, 1f - DEFAULT_INSET)
        return RectF(
            activeArea.left / width,
            activeArea.top / height,
            activeArea.right / width,
            activeArea.bottom / height,
        )
    }

    /** Applies a normalized area, deferring until the view has been measured. */
    fun setNormalizedArea(normalized: RectF) {
        if (width <= 0 || height <= 0) {
            post { setNormalizedArea(normalized) }
            return
        }
        setArea(
            RectF(
                normalized.left * width,
                normalized.top * height,
                normalized.right * width,
                normalized.bottom * height,
            )
        )
    }

    /** Resizes about the current centre. Dimensions arrive in pixels. */
    fun resizeArea(newWidthPx: Float, newHeightPx: Float) {
        val centerX = activeArea.centerX()
        val centerY = activeArea.centerY()
        val halfWidth = max(newWidthPx, minAreaSize) / 2f
        val halfHeight = max(newHeightPx, minAreaSize) / 2f
        activeArea.set(
            centerX - halfWidth,
            centerY - halfHeight,
            centerX + halfWidth,
            centerY + halfHeight,
        )
        enforceConstraints()
        invalidate()
        listener?.onAreaChanged(getArea())
    }

    fun setSetupMode(enabled: Boolean) {
        if (isSetupMode == enabled) return
        isSetupMode = enabled
        if (!enabled) releaseGesture()
        invalidate()
    }

    // --- Layout -----------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (activeArea.isEmpty) {
            // First measure: centre a default area rather than leaving it at 0.
            activeArea.set(
                w * DEFAULT_INSET,
                h * DEFAULT_INSET,
                w * (1f - DEFAULT_INSET),
                h * (1f - DEFAULT_INSET),
            )
        } else if (oldw > 0 && oldh > 0) {
            // Rescale proportionally so a rotation keeps the configured area.
            val scaleX = w.toFloat() / oldw
            val scaleY = h.toFloat() / oldh
            activeArea.set(
                activeArea.left * scaleX,
                activeArea.top * scaleY,
                activeArea.right * scaleX,
                activeArea.bottom * scaleY,
            )
        }
        enforceConstraints()
        listener?.onAreaChanged(getArea())
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

    // --- Editing gestures -------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // In play mode the view is inert; MainActivity routes those events to
        // the tablet pipeline instead.
        if (!isSetupMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val mode = modeForTouch(event.x, event.y)
                if (mode == Mode.NONE) return false
                currentMode = mode
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (currentMode == Mode.NONE) return false
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                updateArea(x - lastTouchX, y - lastTouchY)
                lastTouchX = x
                lastTouchY = y
                invalidate()
                listener?.onAreaChanging(getArea())
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Only the pointer that started the edit matters; ignore others.
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                finishGesture()
                return true
            }

            MotionEvent.ACTION_UP -> {
                finishGesture()
                return true
            }

            // Without this the drag state survives a stolen gesture and the
            // next touch resumes editing from a stale anchor.
            MotionEvent.ACTION_CANCEL -> {
                releaseGesture()
                listener?.onAreaChanged(getArea())
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean = super.performClick()

    private fun finishGesture() {
        releaseGesture()
        listener?.onAreaChanged(getArea())
        performClick()
    }

    private fun releaseGesture() {
        currentMode = Mode.NONE
        activePointerId = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun modeForTouch(x: Float, y: Float): Mode = when {
        isInsideHandle(x, y, activeArea.left, activeArea.top) -> Mode.RESIZE_TL
        isInsideHandle(x, y, activeArea.right, activeArea.top) -> Mode.RESIZE_TR
        isInsideHandle(x, y, activeArea.left, activeArea.bottom) -> Mode.RESIZE_BL
        isInsideHandle(x, y, activeArea.right, activeArea.bottom) -> Mode.RESIZE_BR
        activeArea.contains(x, y) -> Mode.MOVE
        else -> Mode.NONE
    }

    private fun isInsideHandle(x: Float, y: Float, cx: Float, cy: Float): Boolean =
        (x - cx).pow(2) + (y - cy).pow(2) < (handleRadius * HANDLE_TOUCH_SLOP).pow(2)

    /**
     * Applies a drag delta. Each resize corner clamps against the anchored
     * opposite edge so the rectangle can shrink to [minAreaSize] and no
     * further, instead of folding through itself.
     */
    private fun updateArea(dx: Float, dy: Float) {
        when (currentMode) {
            Mode.MOVE -> activeArea.offset(dx, dy)

            Mode.RESIZE_TL -> {
                activeArea.left = min(activeArea.left + dx, activeArea.right - minAreaSize)
                activeArea.top = min(activeArea.top + dy, activeArea.bottom - minAreaSize)
            }

            Mode.RESIZE_TR -> {
                activeArea.right = max(activeArea.right + dx, activeArea.left + minAreaSize)
                activeArea.top = min(activeArea.top + dy, activeArea.bottom - minAreaSize)
            }

            Mode.RESIZE_BL -> {
                activeArea.left = min(activeArea.left + dx, activeArea.right - minAreaSize)
                activeArea.bottom = max(activeArea.bottom + dy, activeArea.top + minAreaSize)
            }

            Mode.RESIZE_BR -> {
                activeArea.right = max(activeArea.right + dx, activeArea.left + minAreaSize)
                activeArea.bottom = max(activeArea.bottom + dy, activeArea.top + minAreaSize)
            }

            Mode.NONE -> return
        }
        enforceConstraints()
    }

    /** Keeps the area non-degenerate and fully inside the view. */
    private fun enforceConstraints() {
        if (width <= 0 || height <= 0) return

        // Shrink to fit before clamping position, otherwise an oversized area
        // would be pushed off both edges at once and never settle.
        val maxWidth = min(width.toFloat(), max(minAreaSize, activeArea.width()))
        val maxHeight = min(height.toFloat(), max(minAreaSize, activeArea.height()))
        activeArea.right = activeArea.left + maxWidth
        activeArea.bottom = activeArea.top + maxHeight

        if (activeArea.left < 0f) activeArea.offset(-activeArea.left, 0f)
        if (activeArea.top < 0f) activeArea.offset(0f, -activeArea.top)
        if (activeArea.right > width) activeArea.offset(width - activeArea.right, 0f)
        if (activeArea.bottom > height) activeArea.offset(0f, height - activeArea.bottom)
    }

    private companion object {
        const val HANDLE_RADIUS_DP = 14f

        /** Handles accept touches slightly outside their drawn radius. */
        const val HANDLE_TOUCH_SLOP = 1.6f
        const val MIN_AREA_DP = 48f
        const val DEFAULT_INSET = 0.1f
    }
}
