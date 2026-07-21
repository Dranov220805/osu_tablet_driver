package com.example.osutablet.net

/**
 * Wire protocol shared with the PC server (osu_tablet_server).
 *
 * Messages are newline-terminated ASCII lines. Coordinates travel as scaled
 * integers rather than formatted floats so that the hot path never allocates a
 * String per sample: at a 240 Hz report rate that difference is measurable.
 *
 * Server greeting:
 *   v2: "OSUTABLET/2 <hostname>"
 *   v1: "HOSTNAME:<hostname>"        (older servers; we degrade gracefully)
 *
 * Client -> server, once per pointer sample:
 *   <phase> <x> <y> <pressure> <buttons> <tool> <tiltX> <tiltY>
 * where phase is one of D/M/U/H/X, x and y are 0..COORD_SCALE, pressure is
 * 0..PRESSURE_SCALE, buttons is a BUTTON_* bitmask, tool is a ToolType ordinal
 * and the tilt values are signed degrees in -90..90.
 *
 * Two bare messages carry no payload:
 *   "C"  cancel  - the gesture was taken away from us; release everything.
 *   "K"  keepalive - sent while idle so a dead link surfaces quickly.
 */
object Protocol {
    const val VERSION = 2

    const val HOST = "127.0.0.1"
    const val PORT = 28200

    const val GREETING_PREFIX = "OSUTABLET/"
    const val LEGACY_GREETING_PREFIX = "HOSTNAME:"

    /** Sent immediately after a v2 greeting to confirm the upgrade. */
    const val CLIENT_HELLO = "V$VERSION"

    const val CANCEL = "C"
    const val KEEPALIVE = "K"

    /** Fixed-point scales. Kept in sync with the server's protocol module. */
    const val COORD_SCALE = 10_000
    const val PRESSURE_SCALE = 1_000

    const val BUTTON_PRIMARY = 1
    const val BUTTON_SECONDARY = 1 shl 1
    const val BUTTON_TERTIARY = 1 shl 2
}
