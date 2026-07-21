package com.example.osutablet.input

import android.graphics.RectF
import android.view.MotionEvent
import com.example.osutablet.net.Protocol
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Receives pointer samples produced by [TabletInputRouter]. */
interface PointerSink {
    fun onSample(sample: PointerSample)

    /**
     * The gesture was taken away from us (system gesture, shade pull, app
     * backgrounded). The PC must release every held button; no position is
     * implied.
     */
    fun onCancel()
}

/**
 * Translates raw [MotionEvent]s into normalized tablet samples.
 *
 * The rules this enforces are the ones a real tablet driver has to get right:
 *
 *  - One pointer owns the cursor for the life of a stroke. Additional fingers
 *    are ignored rather than allowed to teleport it.
 *  - Once a stroke is captured, movement outside the active area is *clamped*
 *    to the border, not discarded. Dropping it freezes the cursor mid-stroke.
 *  - The stroke always terminates. UP and CANCEL are emitted regardless of
 *    where the pointer ended up, so a button can never be left held on the PC.
 *  - Batched historical samples are replayed in order. Android coalesces
 *    touch samples per frame; keeping only the newest throws away most of the
 *    resolution the digitizer actually reported.
 */
class TabletInputRouter(private val sink: PointerSink) {

    private val area = RectF()
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var hovering = false

    /** True while a stroke is in flight and this router owns the gesture. */
    val isStrokeActive: Boolean get() = activePointerId != MotionEvent.INVALID_POINTER_ID

    fun setArea(rect: RectF) = area.set(rect)

    /**
     * Feeds a touch event. Returns true when the event was consumed, i.e. when
     * it belongs to a stroke this router owns.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return beginStroke(event, event.actionIndex)

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger during a stroke must not disturb the cursor.
                // Only adopt it if nothing owns the gesture yet.
                if (isStrokeActive) return true
                return beginStroke(event, event.actionIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                val index = activePointerIndex(event) ?: return false
                emitHistory(event, index)
                emit(event, index, PointerPhase.MOVE, contact = true)
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != activePointerId) return true
                endStroke(event, event.actionIndex)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = activePointerIndex(event) ?: return false
                endStroke(event, index)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!isStrokeActive) return false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                sink.onCancel()
                return true
            }
        }
        return false
    }

    /**
     * Feeds a hover event from a stylus that is in range but not touching.
     * Mirrors the proximity reporting of a real graphics tablet.
     */
    fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!area.contains(event.x, event.y)) {
                    endHover(event)
                    return false
                }
                hovering = true
                emit(event, pointerIndex = 0, phase = PointerPhase.HOVER, contact = false)
                return true
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                endHover(event)
                return true
            }
        }
        return false
    }

    /**
     * Releases any in-flight stroke. Call when the surface loses input, e.g.
     * from onPause, so the PC never keeps a button held after we stop sending.
     */
    fun reset() {
        val hadInput = isStrokeActive || hovering
        activePointerId = MotionEvent.INVALID_POINTER_ID
        hovering = false
        if (hadInput) sink.onCancel()
    }

    private fun beginStroke(event: MotionEvent, pointerIndex: Int): Boolean {
        // A stroke may only start inside the active area; outside it the touch
        // belongs to the UI (buttons, setup controls).
        if (!area.contains(event.getX(pointerIndex), event.getY(pointerIndex))) return false
        activePointerId = event.getPointerId(pointerIndex)
        hovering = false
        emit(event, pointerIndex, PointerPhase.DOWN, contact = true)
        return true
    }

    private fun endStroke(event: MotionEvent, pointerIndex: Int) {
        // Replay anything batched into the terminating event before lifting, so
        // the final position is accurate rather than snapped.
        emitHistory(event, pointerIndex)
        emit(event, pointerIndex, PointerPhase.UP, contact = false)
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    private fun endHover(event: MotionEvent) {
        if (!hovering) return
        hovering = false
        emit(event, pointerIndex = 0, phase = PointerPhase.OUT_OF_RANGE, contact = false)
    }

    private fun activePointerIndex(event: MotionEvent): Int? {
        if (!isStrokeActive) return null
        val index = event.findPointerIndex(activePointerId)
        return if (index >= 0) index else null
    }

    private fun emitHistory(event: MotionEvent, pointerIndex: Int) {
        for (h in 0 until event.historySize) {
            sink.onSample(
                sampleAt(
                    phase = PointerPhase.MOVE,
                    x = event.getHistoricalX(pointerIndex, h),
                    y = event.getHistoricalY(pointerIndex, h),
                    pressure = event.getHistoricalPressure(pointerIndex, h),
                    tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h),
                    orientation = event.getHistoricalOrientation(pointerIndex, h),
                    buttonState = event.buttonState,
                    tool = ToolType.fromMotionEvent(event, pointerIndex),
                    contact = true,
                )
            )
        }
    }

    private fun emit(event: MotionEvent, pointerIndex: Int, phase: PointerPhase, contact: Boolean) {
        sink.onSample(
            sampleAt(
                phase = phase,
                x = event.getX(pointerIndex),
                y = event.getY(pointerIndex),
                pressure = event.getPressure(pointerIndex),
                tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex),
                orientation = event.getOrientation(pointerIndex),
                buttonState = event.buttonState,
                tool = ToolType.fromMotionEvent(event, pointerIndex),
                contact = contact,
            )
        )
    }

    private fun sampleAt(
        phase: PointerPhase,
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        buttonState: Int,
        tool: ToolType,
        contact: Boolean,
    ): PointerSample {
        // Degenerate areas would divide by zero; park the cursor at the centre.
        val nx = if (area.width() > 0f) ((x - area.left) / area.width()).coerceIn(0f, 1f) else 0.5f
        val ny = if (area.height() > 0f) ((y - area.top) / area.height()).coerceIn(0f, 1f) else 0.5f

        // Fingers report a capacitance-derived "pressure" that is not usable as
        // pen pressure; report full pressure for them instead of noise.
        val reported = when (tool) {
            ToolType.STYLUS, ToolType.ERASER -> pressure.coerceIn(0f, 1f)
            else -> if (contact) 1f else 0f
        }

        return PointerSample(
            phase = phase,
            x = nx,
            y = ny,
            pressure = reported,
            buttons = buttonMask(buttonState, contact),
            tool = tool,
            tiltX = tiltXDegrees(tilt, orientation),
            tiltY = tiltYDegrees(tilt, orientation),
        )
    }

    private fun buttonMask(buttonState: Int, contact: Boolean): Int {
        var mask = if (contact) Protocol.BUTTON_PRIMARY else 0
        val secondary = MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_STYLUS_PRIMARY
        val tertiary = MotionEvent.BUTTON_TERTIARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        if (buttonState and secondary != 0) mask = mask or Protocol.BUTTON_SECONDARY
        if (buttonState and tertiary != 0) mask = mask or Protocol.BUTTON_TERTIARY
        return mask
    }

    /**
     * Android reports stylus tilt as a single angle from vertical plus an
     * orientation around the screen normal. Most desktop tablet APIs want it
     * decomposed into two signed axes instead.
     */
    private fun tiltXDegrees(tilt: Float, orientation: Float): Int =
        Math.toDegrees(atan2(sin(orientation) * sin(tilt), cos(tilt)).toDouble()).roundToInt()

    private fun tiltYDegrees(tilt: Float, orientation: Float): Int =
        Math.toDegrees(atan2(-cos(orientation) * sin(tilt), cos(tilt)).toDouble()).roundToInt()
}
