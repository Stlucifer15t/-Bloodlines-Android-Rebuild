package dev.bloodlines.hud

import java.util.Locale

/**
 * Text serialisation for [HudLayout].
 *
 * Format (line based, versioned; unknown keys are ignored, missing keys fall back to defaults,
 * out-of-range values are clamped, and an older/unknown file version is refused rather than
 * misinterpreted):
 *
 * ```
 * hudlayout 2
 * opacity 0.86
 * buttonScale 1.0
 * stickAnchor BOTTOM_LEFT
 * stickOffsetXDp 16.0
 * stickOffsetYDp 16.0
 * stickRadiusDp 64.0
 * stickDeadzone 0.14
 * lookSensitivity 1.0
 * lookExponent 1.3
 * haptics 1
 * showSkipButton 1
 * button attack BOTTOM_RIGHT 14.0 14.0 64.0 1
 * ```
 *
 * A deliberately boring format: it survives version bumps, diffs cleanly in git, is human
 * inspectable on the phone, and needs no JSON library (which would drag an Android or third-party
 * dependency onto the unit-test classpath).
 */
object HudLayoutCodec {

    const val MAGIC = "hudlayout"
    const val VERSION = 2

    fun encode(layout: HudLayout): String {
        val l = layout.clamped()
        val sb = StringBuilder()
        sb.append(MAGIC).append(' ').append(VERSION).append('\n')
        sb.append("opacity ").append(f(l.opacity)).append('\n')
        sb.append("buttonScale ").append(f(l.buttonScale)).append('\n')
        sb.append("stickAnchor ").append(l.stickAnchor.name).append('\n')
        sb.append("stickOffsetXDp ").append(f(l.stickOffsetXDp)).append('\n')
        sb.append("stickOffsetYDp ").append(f(l.stickOffsetYDp)).append('\n')
        sb.append("stickRadiusDp ").append(f(l.stickRadiusDp)).append('\n')
        sb.append("stickDeadzone ").append(f(l.stickDeadzone)).append('\n')
        sb.append("lookSensitivity ").append(f(l.lookSensitivity)).append('\n')
        sb.append("lookExponent ").append(f(l.lookExponent)).append('\n')
        sb.append("haptics ").append(if (l.haptics) 1 else 0).append('\n')
        sb.append("showSkipButton ").append(if (l.showSkipButton) 1 else 0).append('\n')
        val ordered = HudAction.buttonActions().mapNotNull { action ->
            l.buttons.firstOrNull { it.action == action }
        }
        for (b in ordered) {
            sb.append("button ")
                .append(b.action.id).append(' ')
                .append(b.anchor.name).append(' ')
                .append(f(b.offsetXDp)).append(' ')
                .append(f(b.offsetYDp)).append(' ')
                .append(f(b.sizeDp)).append(' ')
                .append(if (b.visible) 1 else 0)
                .append('\n')
        }
        return sb.toString()
    }

    /** Returns null when the text is not a HUD layout at all (wrong magic, empty, newer version). */
    fun decode(text: String?): HudLayout? {
        if (text.isNullOrBlank()) return null
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val header = lines.firstOrNull()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (header.size < 2 || header[0] != MAGIC) return null
        val fileVersion = header.getOrNull(1)?.toIntOrNull() ?: return null
        if (fileVersion > VERSION) return null

        var layout = HudLayout.defaults()
        val buttons = linkedMapOf<HudAction, HudButtonLayout>()
        layout.buttons.forEach { buttons[it.action] = it }

        for (line in lines.drop(1)) {
            val parts = line.split(' ').filter { it.isNotEmpty() }
            val key = parts.firstOrNull() ?: continue
            val value = parts.getOrNull(1)
            when (key) {
                "opacity" -> value?.toFloatOrNull()?.let { layout = layout.copy(opacity = it) }
                "buttonScale" -> value?.toFloatOrNull()?.let { layout = layout.copy(buttonScale = it) }
                "stickAnchor" -> anchorOf(value)?.let { layout = layout.copy(stickAnchor = it) }
                "stickOffsetXDp" -> value?.toFloatOrNull()?.let { layout = layout.copy(stickOffsetXDp = it) }
                "stickOffsetYDp" -> value?.toFloatOrNull()?.let { layout = layout.copy(stickOffsetYDp = it) }
                "stickRadiusDp" -> value?.toFloatOrNull()?.let { layout = layout.copy(stickRadiusDp = it) }
                "stickDeadzone" -> value?.toFloatOrNull()?.let { layout = layout.copy(stickDeadzone = it) }
                "lookSensitivity" -> value?.toFloatOrNull()?.let { layout = layout.copy(lookSensitivity = it) }
                "lookExponent" -> value?.toFloatOrNull()?.let { layout = layout.copy(lookExponent = it) }
                "haptics" -> layout = layout.copy(haptics = (value?.toIntOrNull() ?: 1) != 0)
                "showSkipButton" -> layout = layout.copy(showSkipButton = (value?.toIntOrNull() ?: 1) != 0)
                "button" -> {
                    if (parts.size >= 7) {
                        val action = HudAction.byId(parts[1]) ?: continue
                        val anchor = anchorOf(parts[2]) ?: continue
                        val ox = parts[3].toFloatOrNull() ?: continue
                        val oy = parts[4].toFloatOrNull() ?: continue
                        val size = parts[5].toFloatOrNull() ?: continue
                        val visible = (parts[6].toIntOrNull() ?: 1) != 0
                        buttons[action] = HudButtonLayout(action, anchor, ox, oy, size, visible)
                    } else if (parts.size == 6) {
                        // Version 1 files stored `button <id> <x> <y> <size> <visible>`; that model
                        // has no anchor, so the button keeps its default placement instead of being
                        // silently moved to the wrong place.
                        continue
                    }
                }
            }
        }

        val ordered = HudAction.buttonActions().mapNotNull { buttons[it] }
        return layout.copy(buttons = ordered).clamped()
    }

    private fun anchorOf(text: String?): HudAnchor? {
        if (text == null) return null
        return try {
            HudAnchor.valueOf(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun f(value: Float): String = String.format(Locale.US, "%.4f", value)
}
