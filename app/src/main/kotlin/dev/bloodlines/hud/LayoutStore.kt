package dev.bloodlines.hud

import android.content.Context

/**
 * Persists one [HudLayout] per aspect bucket, so a 16:9 phone and a 20:9 phone can keep different
 * arrangements of the same controls. A bucket without a saved layout falls back to the shared
 * "default" bucket, then to the built-in defaults.
 */
class LayoutStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(bucket: AspectBucket): HudLayout {
        HudLayoutCodec.decode(prefs.getString(key(bucket), null))?.let { return it }
        HudLayoutCodec.decode(prefs.getString(KEY_DEFAULT, null))?.let { return it }
        return HudLayout.defaults()
    }

    fun save(bucket: AspectBucket, layout: HudLayout) {
        prefs.edit().putString(key(bucket), HudLayoutCodec.encode(layout)).apply()
    }

    /** Saves the layout as the fallback used by every bucket that has no custom layout. */
    fun saveAsDefault(layout: HudLayout) {
        prefs.edit().putString(KEY_DEFAULT, HudLayoutCodec.encode(layout)).apply()
    }

    fun reset(bucket: AspectBucket) {
        prefs.edit().remove(key(bucket)).apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    fun hasCustom(bucket: AspectBucket): Boolean = prefs.contains(key(bucket))

    private fun key(bucket: AspectBucket): String = "layout_" + bucket.id

    private companion object {
        const val PREFS = "hud_layouts_v1"
        const val KEY_DEFAULT = "layout_default"
    }
}
