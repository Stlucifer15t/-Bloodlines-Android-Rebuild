package dev.bloodlines.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayoutCodecTest {

    private fun byAction(layout: HudLayout) = layout.buttons.associateBy { it.action }

    @Test
    fun roundTripPreservesEveryField() {
        val original = HudLayout.defaults().copy(
            opacity = 0.55f,
            buttonScale = 1.25f,
            stickAnchor = HudAnchor.BOTTOM_LEFT,
            stickOffsetXDp = 22f,
            stickOffsetYDp = 30f,
            stickRadiusDp = 78f,
            stickDeadzone = 0.2f,
            lookSensitivity = 1.7f,
            lookExponent = 1.8f,
            haptics = false,
            showSkipButton = false,
        ).withButton(HudAction.ATTACK) {
            it.copy(anchor = HudAnchor.TOP_RIGHT, offsetXDp = 40f, offsetYDp = 44f, sizeDp = 70f)
        }

        val decoded = HudLayoutCodec.decode(HudLayoutCodec.encode(original))
        assertNotNull(decoded)
        val parsed = decoded!!

        assertEquals(original.opacity, parsed.opacity, 0.001f)
        assertEquals(original.buttonScale, parsed.buttonScale, 0.001f)
        assertEquals(original.stickAnchor, parsed.stickAnchor)
        assertEquals(original.stickOffsetXDp, parsed.stickOffsetXDp, 0.001f)
        assertEquals(original.stickOffsetYDp, parsed.stickOffsetYDp, 0.001f)
        assertEquals(original.stickRadiusDp, parsed.stickRadiusDp, 0.001f)
        assertEquals(original.stickDeadzone, parsed.stickDeadzone, 0.001f)
        assertEquals(original.lookSensitivity, parsed.lookSensitivity, 0.001f)
        assertEquals(original.lookExponent, parsed.lookExponent, 0.001f)
        assertEquals(original.haptics, parsed.haptics)
        assertEquals(original.showSkipButton, parsed.showSkipButton)

        val a = byAction(original)
        val b = byAction(parsed)
        assertEquals(a.keys, b.keys)
        for ((action, spec) in a) {
            val other = b.getValue(action)
            assertEquals(action.name, spec.anchor, other.anchor)
            assertEquals(action.name, spec.offsetXDp, other.offsetXDp, 0.001f)
            assertEquals(action.name, spec.offsetYDp, other.offsetYDp, 0.001f)
            assertEquals(action.name, spec.sizeDp, other.sizeDp, 0.001f)
            assertEquals(action.name, spec.visible, other.visible)
        }
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        val partial = """
            hudlayout 2
            opacity 0.5
        """.trimIndent()
        val decoded = HudLayoutCodec.decode(partial)
        assertNotNull(decoded)
        assertEquals(0.5f, decoded!!.opacity, 0.001f)
        // Everything not mentioned keeps the shipped default, including every button.
        assertEquals(HudLayout.defaults().stickRadiusDp, decoded.stickRadiusDp, 0.001f)
        assertEquals(HudAction.buttonActions().size, decoded.buttons.size)
    }

    @Test
    fun unknownKeysAndActionsAreIgnoredRatherThanCorruptingTheLayout() {
        val text = """
            hudlayout 2
            opacity 0.7
            futureSetting 42
            button teleport BOTTOM_LEFT 10 10 50 1
            button attack BOTTOM_RIGHT 20 24 66 1
        """.trimIndent()
        val decoded = HudLayoutCodec.decode(text)!!
        assertEquals(0.7f, decoded.opacity, 0.001f)
        val attack = decoded.button(HudAction.ATTACK)!!
        assertEquals(20f, attack.offsetXDp, 0.001f)
        assertEquals(66f, attack.sizeDp, 0.001f)
        assertTrue(decoded.buttons.all { it.action != HudAction.MOVE })
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val text = """
            hudlayout 2
            opacity 5
            buttonScale 9
            stickRadiusDp 999
            button attack BOTTOM_RIGHT -40 -40 900 1
        """.trimIndent()
        val decoded = HudLayoutCodec.decode(text)!!
        assertEquals(1f, decoded.opacity, 0.0001f)
        assertEquals(HudLayout.MAX_SCALE, decoded.buttonScale, 0.0001f)
        assertEquals(110f, decoded.stickRadiusDp, 0.0001f)
        val attack = decoded.button(HudAction.ATTACK)!!
        assertEquals(HudLayout.MAX_BUTTON_DP, attack.sizeDp, 0.0001f)
        assertEquals(0f, attack.offsetXDp, 0.0001f)
    }

    @Test
    fun newerFormatIsRefusedInsteadOfMisread() {
        assertNull(HudLayoutCodec.decode("hudlayout 99\nopacity 0.5"))
    }

    @Test
    fun garbageIsRefused() {
        assertNull(HudLayoutCodec.decode(null))
        assertNull(HudLayoutCodec.decode(""))
        assertNull(HudLayoutCodec.decode("   "))
        assertNull(HudLayoutCodec.decode("hello world"))
        assertNull(HudLayoutCodec.decode("hudlayout"))
        assertNull(HudLayoutCodec.decode("hudlayout abc"))
    }

    @Test
    fun versionOneButtonLinesDoNotSilentlyMoveControls() {
        // v1 stored normalised coordinates with no anchor; those lines must be ignored, leaving the
        // default placement, rather than being interpreted as offsets.
        val v1 = """
            hudlayout 1
            opacity 0.6
            button attack 0.845 0.735 62 1
        """.trimIndent()
        val decoded = HudLayoutCodec.decode(v1)!!
        assertEquals(0.6f, decoded.opacity, 0.001f)
        val attack = decoded.button(HudAction.ATTACK)!!
        assertEquals(HudLayout.defaults().button(HudAction.ATTACK)!!.offsetXDp, attack.offsetXDp, 0.001f)
        assertEquals(HudLayout.defaults().button(HudAction.ATTACK)!!.anchor, attack.anchor)
    }

    @Test
    fun encodeProducesStableSortedOutput() {
        // Stable ordering keeps saved layouts diffable in git and makes change detection trivial.
        val text = HudLayoutCodec.encode(HudLayout.defaults())
        val buttonLines = text.lines().filter { it.startsWith("button ") }
        val ids = buttonLines.map { it.split(' ')[1] }
        assertEquals(HudAction.buttonActions().map { it.id }, ids)
        assertTrue(text.startsWith("hudlayout 2"))
    }

    @Test
    fun defaultsRoundTripUnchanged() {
        val defaults = HudLayout.defaults()
        val decoded = HudLayoutCodec.decode(HudLayoutCodec.encode(defaults))!!
        assertEquals(defaults.opacity, decoded.opacity, 0.001f)
        assertEquals(defaults.buttons.size, decoded.buttons.size)
    }
}
