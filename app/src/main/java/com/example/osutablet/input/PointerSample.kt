package com.example.osutablet.input

import android.view.MotionEvent
import com.example.osutablet.net.Protocol

/** Phase of a pointer sample, mapped to its single-character wire tag. */
enum class PointerPhase(val wire: Char) {
    /** Contact started. */
    DOWN('D'),

    /** Contact moved while touching. */
    MOVE('M'),

    /** Contact lifted normally. */
    UP('U'),

    /** In range but not touching (stylus hover). */
    HOVER('H'),

    /** Left hover range. */
    OUT_OF_RANGE('X'),
    ;

    val isContact: Boolean get() = this == DOWN || this == MOVE || this == UP
}

/** Input device that produced a sample. Ordinals are part of the wire format. */
enum class ToolType {
    FINGER,
    STYLUS,
    ERASER,
    MOUSE,
    ;

    companion object {
        fun fromMotionEvent(event: MotionEvent, pointerIndex: Int): ToolType =
            when (event.getToolType(pointerIndex)) {
                MotionEvent.TOOL_TYPE_STYLUS -> STYLUS
                MotionEvent.TOOL_TYPE_ERASER -> ERASER
                MotionEvent.TOOL_TYPE_MOUSE -> MOUSE
                else -> FINGER
            }
    }
}

/**
 * One immutable pointer sample in normalized tablet space.
 *
 * [x] and [y] are 0..1 across the active area, already clamped. [pressure] is
 * 0..1, defaulting to 1 for tools that cannot report it. [tiltX] and [tiltY]
 * are signed degrees; both are 0 for non-stylus input.
 */
data class PointerSample(
    val phase: PointerPhase,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val buttons: Int,
    val tool: ToolType,
    val tiltX: Int = 0,
    val tiltY: Int = 0,
) {
    /**
     * True for samples that must never be dropped when the writer coalesces a
     * backlog: losing one desynchronizes the button state on the PC.
     */
    val isCritical: Boolean
        get() = phase == PointerPhase.DOWN || phase == PointerPhase.UP ||
            phase == PointerPhase.OUT_OF_RANGE

    /** Appends the wire encoding, without a trailing newline, to [out]. */
    fun encodeTo(out: StringBuilder) {
        out.append(phase.wire)
            .append(' ').append(scale(x, Protocol.COORD_SCALE))
            .append(' ').append(scale(y, Protocol.COORD_SCALE))
            .append(' ').append(scale(pressure, Protocol.PRESSURE_SCALE))
            .append(' ').append(buttons)
            .append(' ').append(tool.ordinal)
            .append(' ').append(tiltX)
            .append(' ').append(tiltY)
    }

    private fun scale(value: Float, scale: Int): Int =
        (value * scale + 0.5f).toInt().coerceIn(0, scale)
}
