package org.linphone

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * AFIF Phone visual styles. These are intentionally not clones of the Infinix
 * dialer or WhatsApp: they are AFIF-branded and 3D/gradient themes that tint the
 * call screen, chat canvas and message bubbles.
 */
object AfifTheme {
    private const val PREFS = "afif_phone"
    private const val KEY = "visual_style_v1"
    private const val MESSAGE_KEY = "message_style_v1"

    data class Style(
        val label: String,
        val background: IntArray,
        val outgoing: Int,
        val incoming: Int,
        val accent: Int,
        val elevation: Float
    )

    val styles = listOf(
        Style("AFIF Aurora", intArrayOf(0xFF050B1A.toInt(), 0xFF0B3A66.toInt()), 0xFF0A84FF.toInt(), 0xFF16283D.toInt(), 0xFF4DA3FF.toInt(), 3f),
        Style("Neon Orbit", intArrayOf(0xFF0A0016.toInt(), 0xFF2A0A3D.toInt()), 0xFF7B2CBF.toInt(), 0xFF241032.toInt(), 0xFFC44DFF.toInt(), 10f),
        Style("Glass Depth", intArrayOf(0xFF071018.toInt(), 0xFF163246.toInt()), 0xFF0E6F8C.toInt(), 0xFF133D36.toInt(), 0xFF8ED8FF.toInt(), 5f),
        Style("Sunset Pulse", intArrayOf(0xFF1A0508.toInt(), 0xFF5A1A08.toInt()), 0xFFE85D04.toInt(), 0xFF3A1A10.toInt(), 0xFFFF9E3D.toInt(), 9f),
        Style("Infinix Default", intArrayOf(0xFF0B1220.toInt(), 0xFF1E293B.toInt()), 0xFF16A34A.toInt(), 0xFF1F2937.toInt(), 0xFF22D3EE.toInt(), 2f),
        // New originals: circuit-trace green, drifting-ember crimson, nebula violet.
        Style("Emerald Circuit", intArrayOf(0xFF04120D.toInt(), 0xFF0C3B2E.toInt()), 0xFF059669.toInt(), 0xFF123528.toInt(), 0xFF10B981.toInt(), 6f),
        Style("Crimson Drift", intArrayOf(0xFF14020A.toInt(), 0xFF4A0A1E.toInt()), 0xFFBE123C.toInt(), 0xFF3A1020.toInt(), 0xFFE11D48.toInt(), 7f),
        Style("Violet Nebula", intArrayOf(0xFF0A0415.toInt(), 0xFF2D1B4E.toInt()), 0xFF6D28D9.toInt(), 0xFF251638.toInt(), 0xFFA78BFA.toInt(), 11f)
    )

    fun index(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(0, styles.size - 1)

    fun style(context: Context): Style = styles[index(context)]

    fun messageIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(MESSAGE_KEY, 0)
            .coerceIn(0, styles.size - 1)

    fun messageStyle(context: Context): Style = styles[messageIndex(context)]

    fun labels(): Array<String> = styles.map { it.label }.toTypedArray()

    fun setIndex(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY, value.coerceIn(0, styles.size - 1)).apply()
    }

    fun setMessageIndex(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(MESSAGE_KEY, value.coerceIn(0, styles.size - 1)).apply()
    }

    /** Apply the theme before the first frame is inflated, avoiding the black flash. */
    fun applyCallWindow(activity: Activity) {
        val s = style(activity)
        activity.window.setBackgroundDrawable(gradient(s.background, 0f))
        activity.window.statusBarColor = s.background[0]
        activity.window.navigationBarColor = s.background[1]
    }

    fun applyCall(activity: Activity, root: View) {
        val s = style(activity)
        root.background = gradient(s.background, 0f)
        applyCallWindow(activity)
    }

    fun applyCallControls(root: View) {
        val s = style(root.context)
        val answer = root.findViewById<View>(R.id.answer_call)
        val decline = root.findViewById<View>(R.id.hang_up)
        styleControl(answer, Color.rgb(0, 200, 83), 14f)
        styleControl(decline, Color.rgb(229, 57, 53), 14f)
        for (id in intArrayOf(R.id.toggle_mute_mic, R.id.change_audio_output, R.id.toggle_video, R.id.recording)) {
            styleControl(root.findViewById(id), s.accent, 10f)
        }
    }

    /**
     * AFIF's own button look: a glassy, top-lit gradient disc (or pill, for a
     * wide/short view like the hang-up button) instead of Linphone's flat
     * single-colour tint - a brighter rim top-left, the base colour in the
     * middle, a darker shade at the bottom for a simple top-down light cue,
     * and a soft translucent rim stroke for a "glass" edge highlight.
     */
    private fun styleControl(view: View?, color: Int, elevation: Float) {
        if (view == null) return
        view.background = afifButtonDrawable(color)
        view.elevation = elevation
        view.translationZ = elevation / 2f
    }

    private fun afifButtonDrawable(color: Int): GradientDrawable {
        val highlight = mix(color, Color.WHITE, 0.4f)
        val shadow = mix(color, Color.BLACK, 0.3f)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(highlight, color, shadow)).apply {
            shape = GradientDrawable.OVAL
            setStroke(2, Color.argb(150, 255, 255, 255))
        }
    }

    /** Blends [color] toward [with] by [amount] (0 = color unchanged, 1 = fully [with]). */
    private fun mix(color: Int, with: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val r = (Color.red(color) * (1 - a) + Color.red(with) * a).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1 - a) + Color.green(with) * a).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1 - a) + Color.blue(with) * a).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun applyChat(root: View) {
        val s = messageStyle(root.context)
        root.background = gradient(s.background, 0f)
    }

    fun applyBubble(bubble: View, outgoing: Boolean) {
        val s = messageStyle(bubble.context)
        val color = if (outgoing) s.outgoing else s.incoming
        bubble.backgroundTintList = ColorStateList.valueOf(color)
        bubble.elevation = s.elevation
    }

    private fun gradient(colors: IntArray, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = radius
            setStroke(2, Color.argb(90, 255, 255, 255))
        }
}
