package dev.bloodlines.hud

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/** Screen insets (system bars + display cutout), in pixels, in the current orientation. */
data class HudInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        val NONE = HudInsets()
    }
}

/** Axis-aligned rectangle. Deliberately not android.graphics.RectF so this file is JVM-testable. */
data class HudRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f

    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    fun inset(dx: Float, dy: Float = dx): HudRect =
        HudRect(left + dx, top + dy, right - dx, bottom - dy)

    fun intersects(other: HudRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Smallest gap between the two rectangles (0 when they overlap or touch). */
    fun gapTo(other: HudRect): Float {
        val dx = maxOf(0f, maxOf(other.left - right, left - other.right))
        val dy = maxOf(0f, maxOf(other.top - bottom, top - other.bottom))
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        fun centered(cx: Float, cy: Float, size: Float): HudRect =
            HudRect(cx - size * 0.5f, cy - size * 0.5f, cx + size * 0.5f, cy + size * 0.5f)
    }
}

/** Resolved movement-stick geometry. */
data class StickMetrics(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val deadzone: Float,
) {
    val area: HudRect get() = HudRect.centered(centerX, centerY, radius * 2f)
}

/** Aspect-ratio buckets, so a 16:9 phone and a 20:9 phone can keep separate layouts. */
enum class AspectBucket(val id: String) {
    R16_9("16:9"),
    R18_9("18:9"),
    R19_5_9("19.5:9"),
    R20_9("20:9");

    companion object {
        fun of(width: Int, height: Int): AspectBucket {
            val long = maxOf(width, height).toFloat()
            val short = maxOf(1, minOf(width, height)).toFloat()
            val ratio = long / short
            return when {
                ratio <= 1.85f -> R16_9
                ratio <= 2.05f -> R18_9
                ratio <= 2.18f -> R19_5_9
                else -> R20_9
            }
        }
    }
}

/**
 * All HUD layout maths. Every function here is pure: no Android types, no state, no I/O.
 * The overlay view only draws these rectangles and routes pointers to them.
 */
object HudGeometry {

    /** Edge padding around the safe area, in dp. */
    const val EDGE_PADDING_DP = 10f

    /** Below this usable width (dp) the whole layout is uniformly compacted, to protect small screens. */
    const val COMPACT_THRESHOLD_DP = 520f
    const val MIN_COMPACTION = 0.75f

    /** Fraction of usable width reserved for the movement side. */
    const val MOVEMENT_COLUMN_FRACTION = 0.34f

    private const val MAX_BUTTON_FRACTION = 0.19f

    fun usableArea(width: Float, height: Float, density: Float, insets: HudInsets): HudRect {
        val pad = EDGE_PADDING_DP * density
        val left = insets.left + pad
        val top = insets.top + pad
        val right = width - insets.right - pad
        val bottom = height - insets.bottom - pad
        return HudRect(left, top, maxOf(right, left + 1f), maxOf(bottom, top + 1f))
    }

    /**
     * Uniform factor applied to sizes *and* offsets together on very narrow screens. Scaling both
     * keeps the relative spacing (hence the no-overlap property) intact.
     */
    fun compaction(usable: HudRect, density: Float): Float {
        if (density <= 0f) return 1f
        val widthDp = usable.width / density
        return if (widthDp >= COMPACT_THRESHOLD_DP) 1f
        else (widthDp / COMPACT_THRESHOLD_DP).coerceIn(MIN_COMPACTION, 1f)
    }

    fun buttonSizePx(layout: HudLayout, usable: HudRect, density: Float, sizeDp: Float): Float {
        val factor = compaction(usable, density)
        val raw = sizeDp * layout.buttonScale * density * factor
        val minPx = HudLayout.MIN_BUTTON_DP * density * factor
        val hardMaxPx = HudLayout.MAX_BUTTON_DP * density
        val screenMax = min(usable.width, usable.height) * MAX_BUTTON_FRACTION
        val maxPx = min(hardMaxPx, maxOf(screenMax, minPx))
        return raw.coerceIn(minPx, maxOf(minPx, maxPx))
    }

    fun buttonRect(
        layout: HudLayout,
        button: HudButtonLayout,
        usable: HudRect,
        density: Float,
    ): HudRect {
        val factor = compaction(usable, density)
        val size = buttonSizePx(layout, usable, density, button.sizeDp)
        val ox = button.offsetXDp * density * factor
        val oy = button.offsetYDp * density * factor
        return when (button.anchor) {
            HudAnchor.TOP_LEFT -> HudRect.centered(
                usable.left + ox + size * 0.5f,
                usable.top + oy + size * 0.5f,
                size,
            )
            HudAnchor.TOP_RIGHT -> HudRect.centered(
                usable.right - ox - size * 0.5f,
                usable.top + oy + size * 0.5f,
                size,
            )
            HudAnchor.BOTTOM_LEFT -> HudRect.centered(
                usable.left + ox + size * 0.5f,
                usable.bottom - oy - size * 0.5f,
                size,
            )
            HudAnchor.BOTTOM_RIGHT -> HudRect.centered(
                usable.right - ox - size * 0.5f,
                usable.bottom - oy - size * 0.5f,
                size,
            )
        }
    }

    fun buttonRects(
        layout: HudLayout,
        usable: HudRect,
        density: Float,
    ): Map<HudAction, HudRect> = buildMap {
        for (b in layout.buttons) {
            if (!b.visible) continue
            if (b.action.isStick || b.action.isLookArea) continue
            if (b.action == HudAction.SKIP_CUTSCENE && !layout.showSkipButton) continue
            put(b.action, buttonRect(layout, b, usable, density))
        }
    }

    fun stickMetrics(layout: HudLayout, usable: HudRect, density: Float): StickMetrics {
        val factor = compaction(usable, density)
        val radius = (layout.stickRadiusDp * layout.buttonScale * density * factor)
            .coerceIn(40f * density * factor, 120f * density * factor)
        val ox = layout.stickOffsetXDp * density * factor
        val oy = layout.stickOffsetYDp * density * factor
        val cx = when (layout.stickAnchor) {
            HudAnchor.TOP_LEFT, HudAnchor.BOTTOM_LEFT -> usable.left + ox + radius
            HudAnchor.TOP_RIGHT, HudAnchor.BOTTOM_RIGHT -> usable.right - ox - radius
        }
        val cy = when (layout.stickAnchor) {
            HudAnchor.TOP_LEFT, HudAnchor.TOP_RIGHT -> usable.top + oy + radius
            HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_RIGHT -> usable.bottom - oy - radius
        }
        return StickMetrics(cx, cy, radius, layout.stickDeadzone)
    }

    /** Camera-drag region: the right-hand part of the usable area, away from the movement column. */
    fun lookArea(usable: HudRect): HudRect =
        HudRect(usable.left + usable.width * MOVEMENT_COLUMN_FRACTION, usable.top, usable.right, usable.bottom)

    /** The region the HUD should keep clear: the centre of the screen, where aiming happens. */
    fun centreKeepClear(usable: HudRect): HudRect =
        usable.inset(usable.width * 0.30f, usable.height * 0.22f)

    /**
     * Normalises a raw look delta: per-density, then a power curve (fine control near the centre of
     * finger travel, full speed on flicks), then the sensitivity multiplier.
     */
    fun applyLookCurve(deltaPx: Float, layout: HudLayout, density: Float): Float {
        if (density <= 0f) return 0f
        val normalised = deltaPx / density
        val shaped = sign(normalised) * abs(normalised).pow(layout.lookExponent)
        return shaped * layout.lookSensitivity
    }

    /**
     * Stick vector from a touch position relative to the stick home.
     * Returns (x, y) in -1..1, screen convention (x right, y down), magnitude 0..1.
     */
    fun stickVector(dx: Float, dy: Float, metrics: StickMetrics): Pair<Float, Float> {
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-3f) return 0f to 0f
        val dead = metrics.deadzone.coerceIn(0f, 0.9f) * metrics.radius
        if (length <= dead) return 0f to 0f
        val span = (metrics.radius - dead).coerceAtLeast(1f)
        val magnitude = ((length - dead) / span).coerceIn(0f, 1f)
        return (dx / length) * magnitude to (dy / length) * magnitude
    }

    fun normalisedPoint(x: Float, y: Float, usable: HudRect): Pair<Float, Float> {
        val nx = if (usable.width > 0f) (x - usable.left) / usable.width else 0.5f
        val ny = if (usable.height > 0f) (y - usable.top) / usable.height else 0.5f
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    /** Nearest anchor for a dragged button centre; flipping anchors keeps the button on screen. */
    fun anchorFor(centerX: Float, centerY: Float, usable: HudRect): HudAnchor {
        val leftHalf = centerX < usable.centerX
        val topHalf = centerY < usable.centerY
        return when {
            topHalf && leftHalf -> HudAnchor.TOP_LEFT
            topHalf -> HudAnchor.TOP_RIGHT
            leftHalf -> HudAnchor.BOTTOM_LEFT
            else -> HudAnchor.BOTTOM_RIGHT
        }
    }

    /** Offsets, in dp, that reproduce [centerX]/[centerY] for the given [anchor]. */
    fun offsetsFor(
        anchor: HudAnchor,
        centerX: Float,
        centerY: Float,
        sizePx: Float,
        usable: HudRect,
        density: Float,
    ): Pair<Float, Float> {
        val factor = compaction(usable, density)
        val half = sizePx * 0.5f
        val oxPx = when (anchor) {
            HudAnchor.TOP_LEFT, HudAnchor.BOTTOM_LEFT -> centerX - half - usable.left
            HudAnchor.TOP_RIGHT, HudAnchor.BOTTOM_RIGHT -> usable.right - centerX - half
        }
        val oyPx = when (anchor) {
            HudAnchor.TOP_LEFT, HudAnchor.TOP_RIGHT -> centerY - half - usable.top
            HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_RIGHT -> usable.bottom - centerY - half
        }
        return toDpOffset(oxPx, density, factor) to toDpOffset(oyPx, density, factor)
    }

    private fun toDpOffset(px: Float, density: Float, factor: Float): Float {
        val safeDensity = if (density <= 0f) 1f else density
        return (px / (safeDensity * factor)).coerceIn(0f, 600f)
    }

    /**
     * Clamps a button centre so the whole button stays inside [usable] while the player drags it
     * in the editor.
     */
    fun clampButtonCentre(x: Float, y: Float, sizePx: Float, usable: HudRect): Pair<Float, Float> {
        val half = sizePx * 0.5f
        val cx = x.coerceIn(usable.left + half, maxOf(usable.left + half, usable.right - half))
        val cy = y.coerceIn(usable.top + half, maxOf(usable.top + half, usable.bottom - half))
        return cx to cy
    }

    /**
     * Problems with a layout: overlapping buttons, buttons under the centre band, or buttons off
     * screen. Used by the editor (warn) and by unit tests (must be empty for defaults).
     */
    fun validate(layout: HudLayout, usable: HudRect, density: Float): List<String> {
        val problems = mutableListOf<String>()
        val rects = buttonRects(layout, usable, density)
        val entries = rects.entries.toList()
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                if (entries[i].value.intersects(entries[j].value)) {
                    problems += "overlap: ${entries[i].key.id} / ${entries[j].key.id}"
                }
            }
        }
        val stick = stickMetrics(layout, usable, density)
        for ((action, rect) in rects) {
            if (rect.intersects(stick.area)) problems += "overlaps stick: ${action.id}"
        }
        val keepClear = centreKeepClear(usable)
        for ((action, rect) in rects) {
            if (rect.intersects(keepClear)) problems += "intrudes centre: ${action.id}"
        }
        for ((action, rect) in rects) {
            if (rect.left < usable.left - 0.5f || rect.top < usable.top - 0.5f ||
                rect.right > usable.right + 0.5f || rect.bottom > usable.bottom + 0.5f
            ) {
                problems += "outside safe area: ${action.id}"
            }
        }
        return problems
    }
}
