package org.linphone

import android.content.Context

/**
 * One-tap call-screen presets. Each preset selects an existing AFIF backdrop
 * theme and an incoming-call button arrangement; the call handling itself is
 * still Linphone's own answer/hang-up code.
 */
object AfifCallStyle {
    enum class Preset(val label: String, val theme: Int, val layout: AfifCallLayout.Layout) {
        HIOS("HiOS Native", 4, AfifCallLayout.Layout.FAB),
        AURORA("Aurora Glass", 2, AfifCallLayout.Layout.FAB),
        NEON("Midnight Neon", 1, AfifCallLayout.Layout.FAB),
        LIGHT("Clean Light", 8, AfifCallLayout.Layout.FAB),
        EMERALD("Emerald Calm", 5, AfifCallLayout.Layout.FAB),
        SUNSET("Sunset Photo", 3, AfifCallLayout.Layout.FAB),
        IOS("iOS", 0, AfifCallLayout.Layout.SLIDE)
    }

    private val presets = Preset.entries

    fun labels(): Array<String> = presets.map { it.label }.toTypedArray()

    fun index(context: Context): Int {
        val theme = AfifTheme.index(context)
        val layout = AfifCallLayout.layout(context)
        return presets.indexOfFirst { it.theme == theme && it.layout == layout }.coerceAtLeast(0)
    }

    fun apply(context: Context, index: Int) {
        val p = presets.getOrNull(index) ?: return
        AfifTheme.setIndex(context, p.theme)
        AfifCallLayout.setIndex(context, p.layout.ordinal)
    }
}
