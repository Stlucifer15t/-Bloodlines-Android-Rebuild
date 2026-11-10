package dev.bloodlines.hud

/** Screen corner a button is positioned from. Offsets are always measured *inwards* from there. */
enum class HudAnchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * One on-screen button.
 *
 * Position is stored as (anchor, offset in dp) rather than as a normalised coordinate: offsets in
 * dp keep the spacing between buttons constant on every screen, which is what prevents overlap on
 * 16:9 and 20:9 phones alike. Normalised coordinates cannot do that — a 0.05 x fraction is ~120 px
 * on a 2400 px-wide screen while 0.05 y is ~54 px on a 1080 px-high one, so a layout tuned on one
 * aspect ratio collides on another.
 *
 * [offsetXDp]/[offsetYDp] are the distance from the anchored screen edges to the button's bounding
 * box (its near edges), in dp.
 */
data class HudButtonLayout(
    val action: HudAction,
    val anchor: HudAnchor,
    val offsetXDp: Float,
    val offsetYDp: Float,
    val sizeDp: Float,
    val visible: Boolean = true,
)

/**
 * A complete HUD configuration.
 *
 * Everything the player can change lives here so it can be persisted, exported, diffed and unit
 * tested without an Android device.
 */
data class HudLayout(
    val opacity: Float = 0.86f,
    /** Global multiplier on button/stick size. */
    val buttonScale: Float = 1.0f,
    val stickAnchor: HudAnchor = HudAnchor.BOTTOM_LEFT,
    val stickOffsetXDp: Float = 16f,
    val stickOffsetYDp: Float = 16f,
    val stickRadiusDp: Float = 64f,
    /** Fraction of the stick radius ignored, so a resting thumb does not drift. */
    val stickDeadzone: Float = 0.14f,
    val lookSensitivity: Float = 1.0f,
    /** >1 makes small movements finer and leaves flicks responsive; keeps precision aiming possible. */
    val lookExponent: Float = 1.3f,
    val haptics: Boolean = true,
    val showSkipButton: Boolean = true,
    val buttons: List<HudButtonLayout> = defaultButtons(),
) {
    fun button(action: HudAction): HudButtonLayout? = buttons.firstOrNull { it.action == action }

    fun withButton(action: HudAction, transform: (HudButtonLayout) -> HudButtonLayout): HudLayout {
        if (button(action) == null) return this
        return copy(buttons = buttons.map { if (it.action == action) transform(it) else it })
    }

    fun without(action: HudAction): HudLayout =
        copy(buttons = buttons.filterNot { it.action == action })

    fun withDefaultsForMissing(): HudLayout {
        val present = buttons.map { it.action }.toSet()
        val missing = defaultButtons().filter { it.action !in present }
        return copy(buttons = HudAction.buttonActions().mapNotNull { a ->
            buttons.firstOrNull { it.action == a } ?: missing.firstOrNull { it.action == a }
        })
    }

    /** Clamps every user-settable value into its supported range. */
    fun clamped(): HudLayout = copy(
        opacity = opacity.coerceIn(MIN_OPACITY, 1f),
        buttonScale = buttonScale.coerceIn(MIN_SCALE, MAX_SCALE),
        stickOffsetXDp = stickOffsetXDp.coerceIn(0f, 400f),
        stickOffsetYDp = stickOffsetYDp.coerceIn(0f, 400f),
        stickRadiusDp = stickRadiusDp.coerceIn(40f, 110f),
        stickDeadzone = stickDeadzone.coerceIn(0f, 0.5f),
        lookSensitivity = lookSensitivity.coerceIn(0.2f, 3f),
        lookExponent = lookExponent.coerceIn(1f, 2.5f),
        buttons = buttons.map { b ->
            b.copy(
                offsetXDp = b.offsetXDp.coerceIn(0f, 600f),
                offsetYDp = b.offsetYDp.coerceIn(0f, 600f),
                sizeDp = b.sizeDp.coerceIn(MIN_BUTTON_DP, MAX_BUTTON_DP),
            )
        },
    )

    companion object {
        const val MIN_OPACITY = 0.25f
        const val MIN_SCALE = 0.7f
        const val MAX_SCALE = 1.6f
        const val MIN_BUTTON_DP = 34f
        const val MAX_BUTTON_DP = 104f

        /**
         * Default landscape layout.
         *
         * Left: movement stick, sprint next to it, cutscene skip above it, meta cluster in the
         * top-left. Right: combat arc under the right thumb (attack, discipline, interact, jump,
         * crouch) with the weapon cycle and menu in the top-right. Nothing sits in the centre band.
         */
        fun defaultButtons(): List<HudButtonLayout> = listOf(
            // Right thumb: primary combat arc
            HudButtonLayout(HudAction.ATTACK, HudAnchor.BOTTOM_RIGHT, 14f, 14f, 64f),
            HudButtonLayout(HudAction.DISCIPLINE, HudAnchor.BOTTOM_RIGHT, 92f, 10f, 54f),
            HudButtonLayout(HudAction.INTERACT, HudAnchor.BOTTOM_RIGHT, 16f, 92f, 58f),
            HudButtonLayout(HudAction.FEED, HudAnchor.BOTTOM_RIGHT, 16f, 166f, 50f),
            HudButtonLayout(HudAction.JUMP, HudAnchor.BOTTOM_RIGHT, 96f, 80f, 56f),
            HudButtonLayout(HudAction.CROUCH, HudAnchor.BOTTOM_RIGHT, 168f, 12f, 48f),
            // Right thumb: weapon + menu, out of the way at the top
            HudButtonLayout(HudAction.MENU, HudAnchor.TOP_RIGHT, 12f, 10f, 40f),
            HudButtonLayout(HudAction.WEAPON_NEXT, HudAnchor.TOP_RIGHT, 60f, 10f, 40f),
            HudButtonLayout(HudAction.WEAPON_PREV, HudAnchor.TOP_RIGHT, 108f, 10f, 40f),
            // Left thumb: movement state + cutscene skip (missing control from the assignment)
            HudButtonLayout(HudAction.SPRINT, HudAnchor.BOTTOM_LEFT, 152f, 12f, 46f),
            HudButtonLayout(HudAction.SKIP_CUTSCENE, HudAnchor.BOTTOM_LEFT, 12f, 152f, 46f),
            // Meta cluster: top-left, small, deliberately away from combat
            HudButtonLayout(HudAction.INVENTORY, HudAnchor.TOP_LEFT, 12f, 10f, 42f),
            HudButtonLayout(HudAction.JOURNAL, HudAnchor.TOP_LEFT, 62f, 10f, 42f),
            HudButtonLayout(HudAction.CHARACTER, HudAnchor.TOP_LEFT, 12f, 58f, 42f),
            HudButtonLayout(HudAction.MAP, HudAnchor.TOP_LEFT, 62f, 58f, 42f),
        )

        fun defaults(): HudLayout = HudLayout()
    }
}
