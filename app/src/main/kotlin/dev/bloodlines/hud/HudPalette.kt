package dev.bloodlines.hud

/**
 * Colours and metrics for the HUD, as plain ints so this file stays JVM-testable.
 *
 * Design direction: dark, restrained, gothic. Bone-white strokes, near-black translucent fills, and
 * a single blood-red accent reserved for active/pressed states. No gradients on idle controls, no
 * saturated fills, no oversized circles — the interface must read as part of Bloodlines at night,
 * not as a mobile arcade overlay.
 */
object HudPalette {
    /** Bone / parchment, for labels and idle strokes. */
    val BONE = 0xFFE8E2D6.toInt()

    /** Muted ash, for secondary labels. */
    val ASH = 0xFFA8A296.toInt()

    /** Blood red, used only for active state. */
    val BLOOD = 0xFFB02626.toInt()
    val BLOOD_DIM = 0xFF6E1A1A.toInt()

    /** Idle fill: near-black with alpha (multiplied by the layout opacity at draw time). */
    val FILL = 0xFF0C0C0E.toInt()

    /** Rim shading behind a control, to keep it readable over bright scenes. */
    val SHADOW = 0xFF000000.toInt()

    // Metrics (dp unless noted)
    const val STROKE_IDLE_DP = 1.3f
    const val STROKE_ACTIVE_DP = 2.1f
    const val RIM_DP = 2.6f
    const val LABEL_MIN_DP = 8.5f
    const val LABEL_MAX_DP = 13f
    const val LABEL_SIZE_FRACTION = 0.24f
    const val PRESS_SCALE = 0.93f
}
