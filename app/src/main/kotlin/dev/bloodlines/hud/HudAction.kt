package dev.bloodlines.hud

/**
 * Logical control families. Used only for visual grouping and for choosing a button shape;
 * never for routing input (routing is per-action).
 */
enum class HudFamily {
    MOVEMENT,
    COMBAT,
    INTERACTION,
    META,
    SYSTEM,
}

/**
 * Stable identifiers for every touch control.
 *
 * IMPORTANT: this enum describes *intent*, not key codes. The mapping from these actions to the
 * port's real input events lives in [InputSink] and must be taken from the existing, working input
 * bridge rather than re-invented here (see docs/reports/001-technical-investigation.md §8.3).
 *
 * The set of actions below is deliberately a superset of what any single build needs: layouts hide
 * actions the running port does not report as available ([HudButtonLayout.visible]).
 */
enum class HudAction(
    val id: String,
    val label: String,
    val family: HudFamily,
    /** Toggle-style controls stay "on" after a tap (crouch, sprint). */
    val latched: Boolean = false,
) {
    // Movement
    MOVE("move", "Move", HudFamily.MOVEMENT),
    LOOK("look", "Look", HudFamily.MOVEMENT),
    JUMP("jump", "Jump", HudFamily.MOVEMENT),
    CROUCH("crouch", "Crouch", HudFamily.MOVEMENT, latched = true),
    SPRINT("sprint", "Sprint", HudFamily.MOVEMENT, latched = true),

    // Combat
    ATTACK("attack", "Attack", HudFamily.COMBAT),
    DISCIPLINE("discipline", "Discipline", HudFamily.COMBAT),
    FEED("feed", "Feed", HudFamily.COMBAT),

    // Interaction
    INTERACT("interact", "Use", HudFamily.INTERACTION),
    WEAPON_NEXT("weapon_next", "Weapon +", HudFamily.INTERACTION),
    WEAPON_PREV("weapon_prev", "Weapon -", HudFamily.INTERACTION),

    // Meta
    INVENTORY("inventory", "Inventory", HudFamily.META),
    JOURNAL("journal", "Journal", HudFamily.META),
    CHARACTER("character", "Character", HudFamily.META),
    MAP("map", "Map", HudFamily.META),

    // System
    SKIP_CUTSCENE("skip_cutscene", "Skip", HudFamily.SYSTEM),
    MENU("menu", "Menu", HudFamily.SYSTEM);

    val isStick: Boolean get() = this == MOVE
    val isLookArea: Boolean get() = this == LOOK

    companion object {
        @Suppress("DEPRECATION")
        fun byId(id: String): HudAction? = values().firstOrNull { it.id == id }

        /** Actions that get an on-screen button (MOVE and LOOK are regions, not buttons). */
        @Suppress("DEPRECATION")
        fun buttonActions(): List<HudAction> = values().filterNot { it.isStick || it.isLookArea }
    }
}
