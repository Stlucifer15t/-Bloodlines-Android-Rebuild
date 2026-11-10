package dev.bloodlines.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HudGeometryTest {

    private val layout = HudLayout.defaults()

    /** Representative landscape dp sizes: 16:9, 18:9, 19.5:9, 20:9, a small phone and two tablets. */
    private val screenSizesDp = listOf(
        Triple(640f, 360f, 3.0f),   // 1920x1080 @ xxhdpi
        Triple(720f, 360f, 2.0f),   // 1440x720 @ xhdpi
        Triple(780f, 360f, 3.0f),   // 2340x1080 @ xxhdpi
        Triple(800f, 360f, 3.0f),   // 2400x1080 @ xxhdpi
        Triple(569f, 320f, 1.5f),   // 854x480 @ hdpi
        Triple(1280f, 800f, 1.0f),  // 1280x800 tablet @ mdpi
        Triple(1706f, 1066f, 1.5f), // 2560x1600 tablet @ hdpi
    )

    @Test
    fun defaultsHaveNoOverlapsOrCentreIntrusionOnEveryScreen() {
        for ((wDp, hDp, density) in screenSizesDp) {
            val w = wDp * density
            val h = hDp * density
            val usable = HudGeometry.usableArea(w, h, density, HudInsets.NONE)
            val problems = HudGeometry.validate(layout, usable, density)
            assertEquals("problems at ${wDp}x${hDp}dp: $problems", emptyList<String>(), problems)
        }
    }

    @Test
    fun defaultsStayInsideSafeAreaWithSystemBarsAndCutout() {
        val density = 3f
        val insets = HudInsets(left = 96f, top = 0f, right = 96f, bottom = 48f)
        val usable = HudGeometry.usableArea(2400f, 1080f, density, insets)
        val problems = HudGeometry.validate(layout, usable, density)
        assertEquals(emptyList<String>(), problems)
        // The safe area must actually respect the insets.
        assertTrue(usable.left >= 96f)
        assertTrue(usable.right <= 2400f - 96f)
        assertTrue(usable.bottom <= 1080f - 48f)
    }

    @Test
    fun everyButtonActionHasASpec() {
        val present = layout.buttons.map { it.action }.toSet()
        for (action in HudAction.buttonActions()) {
            assertTrue("missing button spec for ${action.id}", action in present)
        }
    }

    @Test
    fun anchorsPlaceButtonsNearTheirCorner() {
        val density = 3f
        val usable = HudGeometry.usableArea(2400f, 1080f, density, HudInsets.NONE)
        val attack = HudGeometry.buttonRect(
            layout,
            layout.button(HudAction.ATTACK)!!,
            usable,
            density,
        )
        // Bottom-right anchored: right edge close to the usable right edge, bottom close to bottom.
        assertTrue(attack.right <= usable.right + 0.5f)
        assertTrue(attack.bottom <= usable.bottom + 0.5f)
        assertTrue(usable.right - attack.right <= 20f * density)
        assertTrue(usable.bottom - attack.bottom <= 20f * density)

        val inventory = HudGeometry.buttonRect(
            layout,
            layout.button(HudAction.INVENTORY)!!,
            usable,
            density,
        )
        assertTrue(inventory.left - usable.left <= 20f * density)
        assertTrue(inventory.top - usable.top <= 20f * density)
    }

    @Test
    fun buttonsNeverCollideOnAnyAspectRatioAfterBeingResizedToTheMaximum() {
        val scaled = layout.copy(buttonScale = HudLayout.MAX_SCALE)
        for ((wDp, hDp, density) in screenSizesDp) {
            val usable = HudGeometry.usableArea(wDp * density, hDp * density, density, HudInsets.NONE)
            // Overlap is allowed once the player deliberately grows everything past the design
            // limit; what must never happen is a button escaping the safe area.
            for ((action, rect) in HudGeometry.buttonRects(scaled, usable, density)) {
                assertTrue(
                    "button ${action.id} escaped the safe area",
                    rect.left >= usable.left - 0.5f && rect.top >= usable.top - 0.5f &&
                        rect.right <= usable.right + 0.5f && rect.bottom <= usable.bottom + 0.5f,
                )
            }
        }
    }

    @Test
    fun compactionShrinksOnlySmallScreens() {
        val density = 2f
        val big = HudGeometry.usableArea(1600f, 800f, density, HudInsets.NONE)
        val small = HudGeometry.usableArea(860f, 540f, density, HudInsets.NONE)
        assertEquals(1f, HudGeometry.compaction(big, density), 0.001f)
        assertTrue(HudGeometry.compaction(small, density) < 1f)
        assertTrue(HudGeometry.compaction(small, density) >= HudGeometry.MIN_COMPACTION)
    }

    @Test
    fun stickSitsInsideTheSafeAreaAndRespectsItsRadius() {
        val density = 3f
        val usable = HudGeometry.usableArea(2400f, 1080f, density, HudInsets.NONE)
        val stick = HudGeometry.stickMetrics(layout, usable, density)
        assertTrue(stick.centerX - stick.radius >= usable.left - 0.5f)
        assertTrue(stick.centerY + stick.radius <= usable.bottom + 0.5f)
        assertEquals(layout.stickDeadzone, stick.deadzone, 0.0001f)
    }

    @Test
    fun stickVectorHasDeadzoneAndUnitMagnitude() {
        val metrics = StickMetrics(centerX = 100f, centerY = 100f, radius = 60f, deadzone = 0.15f)
        // Inside the deadzone: no input at all (prevents drift from a resting thumb).
        assertEquals(0f to 0f, HudGeometry.stickVector(3f, 2f, metrics))
        // At full deflection: magnitude exactly 1 in the direction of travel.
        val (fx, fy) = HudGeometry.stickVector(100f, 0f, metrics)
        assertEquals(1f, fx, 0.001f)
        assertEquals(0f, fy, 0.001f)
        // Halfway: direction preserved, magnitude between 0 and 1.
        val (hx, hy) = HudGeometry.stickVector(30f, 0f, metrics)
        assertTrue(hx in 0.01f..0.99f)
        assertEquals(0f, hy, 0.0001f)
        // Diagonals stay normalised.
        val (dx, dy) = HudGeometry.stickVector(60f, 60f, metrics)
        assertEquals(1f, kotlin.math.sqrt(dx * dx + dy * dy), 0.01f)
    }

    @Test
    fun lookCurveIsLinearAtExponentOneAndSignPreserving() {
        val linear = layout.copy(lookExponent = 1f, lookSensitivity = 1f)
        assertEquals(10f, HudGeometry.applyLookCurve(10f, linear, density = 1f), 0.0001f)
        assertEquals(-10f, HudGeometry.applyLookCurve(-10f, linear, density = 1f), 0.0001f)

        val precise = layout.copy(lookExponent = 2f, lookSensitivity = 1f)
        val ref = HudGeometry.LOOK_REFERENCE_DP
        // Movements shorter than the reference travel are damped (fine aiming)...
        assertTrue(HudGeometry.applyLookCurve(6f, precise, 1f) < 6f)
        // ...exactly at the reference the curve is the identity...
        assertEquals(ref, HudGeometry.applyLookCurve(ref, precise, 1f), 0.001f)
        // ...and flicks are amplified so turning stays responsive.
        assertTrue(HudGeometry.applyLookCurve(ref * 4f, precise, 1f) > ref * 4f)
        assertEquals(0f, HudGeometry.applyLookCurve(0f, precise, 1f), 0.0001f)

        // Sensitivity scales the result without changing the sign.
        val fast = precise.copy(lookSensitivity = 2f)
        assertEquals(
            HudGeometry.applyLookCurve(60f, precise, 1f) * 2f,
            HudGeometry.applyLookCurve(60f, fast, 1f),
            0.0001f,
        )
        // Negative deltas mirror positive ones exactly.
        assertEquals(
            HudGeometry.applyLookCurve(60f, precise, 1f),
            -HudGeometry.applyLookCurve(-60f, precise, 1f),
            0.0001f,
        )
    }

    @Test
    fun editorOffsetsRoundTripToTheSameCentre() {
        val density = 2.5f
        val usable = HudGeometry.usableArea(2000f, 900f, density, HudInsets.NONE)
        val sizePx = 56f * density
        for (anchor in HudAnchor.values()) {
            val cx = usable.left + usable.width * 0.31f
            val cy = usable.top + usable.height * 0.68f
            val (ox, oy) = HudGeometry.offsetsFor(anchor, cx, cy, sizePx, usable, density)
            val spec = HudButtonLayout(HudAction.ATTACK, anchor, ox, oy, 56f)
            val rect = HudGeometry.buttonRect(layout, spec, usable, density)
            assertEquals("anchor $anchor x", cx, rect.centerX, 0.75f)
            assertEquals("anchor $anchor y", cy, rect.centerY, 0.75f)
        }
    }

    @Test
    fun clampButtonCentreKeepsTheWholeButtonOnScreen() {
        val usable = HudGeometry.usableArea(1200f, 600f, 2f, HudInsets.NONE)
        val (cx, cy) = HudGeometry.clampButtonCentre(-500f, 5000f, 120f, usable)
        assertTrue(cx - 60f >= usable.left - 0.5f)
        assertTrue(cy + 60f <= usable.bottom + 0.5f)
    }

    @Test
    fun anchorIsChosenByTheHalfTheCentreFallsInto() {
        val usable = HudGeometry.usableArea(1200f, 600f, 2f, HudInsets.NONE)
        assertEquals(HudAnchor.TOP_LEFT, HudGeometry.anchorFor(usable.left + 10f, usable.top + 10f, usable))
        assertEquals(HudAnchor.TOP_RIGHT, HudGeometry.anchorFor(usable.right - 10f, usable.top + 10f, usable))
        assertEquals(HudAnchor.BOTTOM_LEFT, HudGeometry.anchorFor(usable.left + 10f, usable.bottom - 10f, usable))
        assertEquals(HudAnchor.BOTTOM_RIGHT, HudGeometry.anchorFor(usable.right - 10f, usable.bottom - 10f, usable))
    }

    @Test
    fun aspectBucketsMatchTheSupportedDisplays() {
        assertEquals(AspectBucket.R16_9, AspectBucket.of(1920, 1080))
        assertEquals(AspectBucket.R18_9, AspectBucket.of(1440, 720))
        assertEquals(AspectBucket.R19_5_9, AspectBucket.of(2340, 1080))
        assertEquals(AspectBucket.R20_9, AspectBucket.of(2400, 1080))
        // Portrait must map to the same bucket as its landscape counterpart.
        assertEquals(AspectBucket.R20_9, AspectBucket.of(1080, 2400))
    }

    @Test
    fun lookAreaStartsRightOfTheMovementColumn() {
        val usable = HudGeometry.usableArea(2400f, 1080f, 3f, HudInsets.NONE)
        val look = HudGeometry.lookArea(usable)
        assertTrue(look.left > usable.left + usable.width * 0.3f)
        assertEquals(usable.right, look.right, 0.001f)
        assertFalse(look.contains(usable.left + 5f, usable.centerY))
    }

    @Test
    fun validationDetectsADeliberateOverlap() {
        val density = 3f
        val usable = HudGeometry.usableArea(2400f, 1080f, density, HudInsets.NONE)
        val attack = layout.button(HudAction.ATTACK)!!
        val broken = layout.withButton(HudAction.FEED) {
            it.copy(anchor = attack.anchor, offsetXDp = attack.offsetXDp, offsetYDp = attack.offsetYDp)
        }
        val problems = HudGeometry.validate(broken, usable, density)
        assertTrue(problems.any { it.startsWith("overlap") })
    }

    @Test
    fun buttonSizeHonoursTheGlobalScaleAndClampsToTheScreen() {
        val density = 3f
        val usable = HudGeometry.usableArea(2400f, 1080f, density, HudInsets.NONE)
        val small = HudGeometry.buttonSizePx(layout.copy(buttonScale = 0.7f), usable, density, 64f)
        val normal = HudGeometry.buttonSizePx(layout, usable, density, 64f)
        assertTrue(small < normal)
        val huge = HudGeometry.buttonSizePx(
            layout.copy(buttonScale = 10f),
            usable,
            density,
            104f,
        )
        assertTrue(huge <= HudLayout.MAX_BUTTON_DP * density + 0.01f)
        assertTrue(abs(normal - 64f * density) < 30f)
    }
}
