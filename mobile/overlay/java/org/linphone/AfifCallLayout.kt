package org.linphone

import android.content.Context

/**
 * Which button *arrangement* the incoming-call screen uses. This is independent
 * from [AfifTheme] (which only controls the backdrop/colour palette): a user can
 * pick any layout with any colour style, since every layout is skinned with the
 * same [AfifTheme.style] accent/background colours at draw time.
 *
 * CLASSIC leaves Linphone's own tap row (hang_up / answer_call) completely
 * untouched - it is the zero-risk fallback. SLIDE and FAB never touch, hide, or
 * replace the real hang_up/answer_call views; they only make them invisible
 * (alpha 0, still laid out and still clickable) and forward taps to them via
 * performClick(), so the actual call-handling code backing those two buttons
 * (CurrentCallViewModel.answer() / hangUp(), wired by Linphone itself) runs
 * completely unmodified no matter which layout is on screen.
 */
object AfifCallLayout {
    private const val PREFS = "afif_phone"
    private const val KEY = "call_layout_v1"

    enum class Layout(val label: String) {
        CLASSIC("Classic (Linphone default)"),
        SLIDE("Slide to answer (iOS-inspired)"),
        FAB("Answer / decline circles (Material-inspired)")
    }

    private val all = Layout.entries.toTypedArray()

    fun index(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, all.size - 1)

    fun layout(context: Context): Layout = all[index(context)]

    fun labels(): Array<String> = all.map { it.label }.toTypedArray()

    fun setIndex(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, value.coerceIn(0, all.size - 1)).apply()
    }
}
