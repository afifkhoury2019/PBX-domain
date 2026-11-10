package org.linphone

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Swaps the *shape* of the incoming-call answer/decline controls, without ever
 * touching how an answer or a hang-up actually happens.
 *
 * Linphone's own hang_up / answer_call ImageViews (R.id.hang_up, R.id.answer_call)
 * stay exactly where Linphone put them, fully laid out and fully clickable - this
 * class only sets their alpha to 0 and draws AFIF's own shapes on top. A tap on
 * the AFIF shape calls performClick() on the real, hidden Linphone button, so the
 * real call-handling code runs unmodified. CLASSIC removes the AFIF shapes
 * entirely and restores alpha, so it is byte-for-byte Linphone's original screen.
 */
object AfifCallLayoutOverlay {
    private const val OVERLAY_ID = 0x7F0F0002

    fun install(root: View) {
        if (root !is ViewGroup) return
        val answer = root.findViewById<View>(R.id.answer_call)
        val decline = root.findViewById<View>(R.id.hang_up)
        // Not the incoming-call screen (e.g. active/ended call fragments don't
        // have these ids) - nothing for this layout picker to do here.
        if (answer == null || decline == null) return

        (root.findViewById<View>(OVERLAY_ID)?.parent as? ViewGroup)?.removeView(root.findViewById(OVERLAY_ID))
        answer.alpha = 1f
        decline.alpha = 1f

        val style = AfifTheme.style(root.context)
        val overlay = when (AfifCallLayout.layout(root.context)) {
            AfifCallLayout.Layout.CLASSIC -> null
            AfifCallLayout.Layout.SLIDE -> AfifSlideToAnswerBar(root.context, answer, decline, style.accent)
            AfifCallLayout.Layout.FAB -> buildFabRow(root.context, answer, decline, style.accent)
        } ?: return

        answer.alpha = 0f
        decline.alpha = 0f
        overlay.id = OVERLAY_ID
        root.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        overlay.translationZ = 20f
        // Anchor to the bottom of the screen regardless of the parent's layout
        // type (ConstraintLayout for the fragments this runs in).
        when (val lp = overlay.layoutParams) {
            is androidx.constraintlayout.widget.ConstraintLayout.LayoutParams -> {
                lp.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                overlay.layoutParams = lp
            }
            else -> {}
        }
    }

    private fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    private fun circle(context: Context, color: Int, icon: Int, sizeDp: Float): ImageView {
        val highlight = mix(color, Color.WHITE, 0.35f)
        val shadow = mix(color, Color.BLACK, 0.3f)
        val d = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(highlight, color, shadow)).apply {
            shape = GradientDrawable.OVAL
            setStroke((dp(context, 1.5f)).toInt(), Color.argb(140, 255, 255, 255))
        }
        return ImageView(context).apply {
            background = d
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            val pad = dp(context, sizeDp * 0.28f).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = dp(context, 10f)
            layoutParams = FrameLayout.LayoutParams(dp(context, sizeDp).toInt(), dp(context, sizeDp).toInt())
        }
    }

    private fun mix(color: Int, with: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val r = (Color.red(color) * (1 - a) + Color.red(with) * a).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1 - a) + Color.green(with) * a).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1 - a) + Color.blue(with) * a).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    /** Two large FABs - red decline (left), green answer (right) - each forwarding to the real button. */
    private fun buildFabRow(context: Context, answer: View, decline: View, accent: Int): FrameLayout {
        val row = FrameLayout(context)
        row.setPadding(0, 0, 0, dp(context, 22f).toInt())

        val declineFab = circle(context, Color.rgb(229, 57, 53), R.drawable.phone_disconnect, 64f)
        (declineFab.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            marginEnd = dp(context, 56f).toInt()
            rightMargin = dp(context, 56f).toInt()
        }
        declineFab.setOnClickListener { decline.performClick() }

        val answerFab = circle(context, Color.rgb(0, 200, 83), R.drawable.phone, 64f)
        (answerFab.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            leftMargin = dp(context, 56f).toInt()
        }
        answerFab.setOnClickListener { answer.performClick() }

        row.addView(declineFab)
        row.addView(answerFab)
        return row
    }
}

/**
 * A slide-to-answer bar: drag the handle to the green end to answer, to the red
 * end to decline, matching Linphone's own locked-screen gesture. Every tap/drag
 * outcome calls performClick() on the real [answer]/[decline] views passed in -
 * this class never calls into Linphone's call stack itself.
 */
private class AfifSlideToAnswerBar(
    context: Context,
    private val answer: View,
    private val decline: View,
    accent: Int
) : FrameLayout(context) {

    private val handle: ImageView
    private val label: TextView
    private var initialX = 0f
    private var dragStartRawX = 0f

    init {
        val density = context.resources.displayMetrics.density
        val barHeight = (76 * density).toInt()
        setPadding(0, 0, 0, (22 * density).toInt())

        val track = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = barHeight / 2f
                setColor(Color.argb(40, 255, 255, 255))
                setStroke((1.5f * density).toInt(), Color.argb(60, 255, 255, 255))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, barHeight
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                leftMargin = (28 * density).toInt()
                rightMargin = (28 * density).toInt()
            }
        }

        label = TextView(context).apply {
            text = "Slide to answer"
            setTextColor(Color.argb(230, 255, 255, 255))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val declineHint = ImageView(context).apply {
            setImageResource(R.drawable.phone_disconnect)
            setColorFilter(Color.argb(160, 255, 255, 255))
            val s = (26 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                leftMargin = (18 * density).toInt()
            }
        }
        val answerHint = ImageView(context).apply {
            setImageResource(R.drawable.phone)
            setColorFilter(Color.argb(160, 255, 255, 255))
            val s = (26 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
                rightMargin = (18 * density).toInt()
            }
        }

        val handleSize = (64 * density).toInt()
        val highlight = mixColor(accent, Color.WHITE, 0.4f)
        val shadow = mixColor(accent, Color.BLACK, 0.3f)
        handle = ImageView(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(highlight, accent, shadow)).apply {
                shape = GradientDrawable.OVAL
                setStroke((1.5f * density).toInt(), Color.argb(150, 255, 255, 255))
            }
            setImageResource(R.drawable.phone)
            setColorFilter(Color.WHITE)
            val pad = (handleSize * 0.26f).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 10f * density
            layoutParams = FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = android.view.Gravity.CENTER
            }
        }

        track.addView(declineHint)
        track.addView(answerHint)
        track.addView(label)
        track.addView(handle)
        addView(track)

        // Handle starts centred; dragging it to the right edge answers (green hint),
        // to the left edge declines (red hint) - same two outcomes, same two ends,
        // as Linphone's own locked-screen slider, just centred instead of starting
        // flush against one side.
        handle.setOnTouchListener { view, event ->
            val trackWidth = track.width.toFloat()
            val maxTravel = ((trackWidth - handle.width) / 2f - (10 * density)).coerceAtLeast(1f)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = view.translationX
                    dragStartRawX = event.rawX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartRawX)
                    view.translationX = (initialX + dx).coerceIn(-maxTravel, maxTravel)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val progress = view.translationX / maxTravel
                    when {
                        progress > 0.78f -> answer.performClick()
                        progress < -0.78f -> decline.performClick()
                        else -> view.animate().translationX(0f).setDuration(220).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun mixColor(color: Int, with: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        val r = (Color.red(color) * (1 - a) + Color.red(with) * a).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1 - a) + Color.green(with) * a).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1 - a) + Color.blue(with) * a).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
