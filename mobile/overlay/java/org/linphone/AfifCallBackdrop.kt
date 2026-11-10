package org.linphone

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated, original call-screen backdrops for the five AFIF themes.
 *
 * Each theme draws three simulated depth layers - far, mid and near - that move,
 * scale and fade at different rates, plus a soft radial lighting vignette centred
 * on the caller avatar. That combination (parallax + size/opacity falloff +
 * directional light) is what reads as depth on a flat canvas without needing an
 * actual 3D engine: distant shapes drift slowly and stay dim, close ones move
 * faster, bigger and brighter, and the vignette grounds everything under one
 * light source instead of looking like flat, evenly-lit decals.
 */
class AfifCallBackdropView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val style = AfifTheme.index(context)

    // A fixed near-layer dust/particle field, depth-sorted: depth 0 = far (small,
    // dim, slow), depth 1 = near (large, bright, fast). Seeded by style so the
    // field is stable across frames but differs theme to theme.
    private data class Particle(val x: Float, val y: Float, val size: Float, val depth: Float, val phase: Float)
    private val particles: List<Particle> by lazy {
        val rnd = Random(style * 97 + 13)
        List(26) {
            Particle(
                x = rnd.nextFloat(),
                y = rnd.nextFloat(),
                size = 1.5f + rnd.nextFloat() * 2.5f,
                depth = rnd.nextFloat(),
                phase = rnd.nextFloat() * 6.28f
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        t += 0.008f
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            // Not yet laid out: nothing sane to draw (a 0-radius RadialGradient
            // below would throw), just keep the animation clock ticking.
            if (isAttachedToWindow) postInvalidateOnAnimation()
            return
        }
        val s = AfifTheme.styles[style.coerceIn(0, AfifTheme.styles.size - 1)]

        when (style) {
            0 -> drawAurora(canvas, w, h)
            1 -> drawNeonOrbit(canvas, w, h)
            2 -> drawGlassDepth(canvas, w, h)
            3 -> drawSunsetPulse(canvas, w, h)
            4 -> drawInfinixDefault(canvas, w, h)
            5 -> drawEmeraldCircuit(canvas, w, h)
            6 -> drawCrimsonDrift(canvas, w, h)
            7 -> drawVioletNebula(canvas, w, h)
            else -> drawInfinixDefault(canvas, w, h)
        }

        drawDepthParticles(canvas, w, h, s.accent)
        drawVignette(canvas, w, h, s.background[1])

        if (isAttachedToWindow) postInvalidateOnAnimation()
    }

    // ---- Shared depth cues, reused by every theme ------------------------------

    /** A soft glow disc, faked with concentric fading circles (cheap, no blur mask needed). */
    private fun glow(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        paint.style = Paint.Style.FILL
        val steps = 4
        for (i in steps downTo 1) {
            val frac = i / steps.toFloat()
            paint.color = Color.argb((alpha * (1f - frac) * 0.6f).toInt().coerceIn(0, 255),
                Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(cx, cy, r * frac, paint)
        }
    }

    /** Dust/particle field: far specks are small, dim and slow; near ones are big, bright and fast. */
    private fun drawDepthParticles(canvas: Canvas, w: Float, h: Float, color: Int) {
        paint.style = Paint.Style.FILL
        for (p in particles) {
            val speed = 0.4f + p.depth * 1.3f
            val driftX = sin(t * speed + p.phase) * (6f + p.depth * 18f)
            val driftY = cos(t * speed * 0.7f + p.phase) * (4f + p.depth * 10f)
            val x = p.x * w + driftX
            val y = p.y * h * 0.82f + driftY
            val radius = p.size * (0.6f + p.depth * 1.4f)
            val alpha = (60 + p.depth * 140).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawCircle(x, y, radius, paint)
        }
    }

    /** Radial light centred on the avatar, falling off toward the edges - a single, consistent light source. */
    private fun drawVignette(canvas: Canvas, w: Float, h: Float, edgeColor: Int) {
        val cx = w / 2f
        val cy = h * 0.46f
        val radius = maxOf(w, h) * 0.75f
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb(0, 0, 0, 0),
                Color.argb(90, Color.red(edgeColor), Color.green(edgeColor), Color.blue(edgeColor))
            ),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    private fun drawWave(canvas: Canvas, w: Float, baseY: Float, h: Float, amp: Float, speed: Float, freq: Float, phase: Float) {
        val path = Path()
        var first = true
        var x = 0f
        while (x <= w) {
            val y = h * baseY + sin(x / freq + t * speed + phase) * amp
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += 14f
        }
        canvas.drawPath(path, paint)
    }

    // ---- Per-theme far/mid/near compositions -----------------------------------

    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        // far: slow, wide, faint bands
        for (k in 0 until 3) {
            paint.strokeWidth = 3f
            paint.color = Color.argb(28, 77, 163, 255)
            drawWave(canvas, w, 0.5f + k * 0.07f, h, 34f, 0.6f, 90f, k.toFloat())
        }
        // mid: tighter, brighter bands
        for (k in 0 until 4) {
            paint.strokeWidth = 2.2f
            paint.color = Color.argb(55, 77, 163, 255)
            drawWave(canvas, w, 0.55f + k * 0.045f, h, 24f, 1.3f, 110f, k + 1f)
        }
        // near: crisp, fast, bright
        for (k in 0 until 2) {
            paint.strokeWidth = 1.6f
            paint.color = Color.argb(95, 150, 205, 255)
            drawWave(canvas, w, 0.6f + k * 0.03f, h, 16f, 2.4f, 130f, k + 3f)
        }
    }

    private fun drawNeonOrbit(canvas: Canvas, w: Float, h: Float) {
        canvas.save()
        canvas.translate(w / 2f, h * 0.34f)
        paint.style = Paint.Style.STROKE
        // far ring: slow, faint, wide
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(32, 196, 77, 255)
        canvas.save(); canvas.rotate(t * 7f)
        canvas.drawOval(-w * 0.44f, -h * 0.09f, w * 0.44f, h * 0.09f, paint)
        canvas.restore()
        // mid rings
        for (k in 0 until 3) {
            paint.strokeWidth = 2f
            paint.color = Color.argb(70, 196, 77, 255)
            canvas.save(); canvas.rotate(t * 16f + k * 60f)
            canvas.drawOval(-w * 0.34f, -h * 0.07f, w * 0.34f, h * 0.07f, paint)
            canvas.restore()
        }
        // near ring: bright, fast, closest to camera
        paint.strokeWidth = 2.4f
        paint.color = Color.argb(115, 230, 150, 255)
        canvas.save(); canvas.rotate(-t * 26f)
        canvas.drawOval(-w * 0.22f, -h * 0.05f, w * 0.22f, h * 0.05f, paint)
        canvas.restore()
        canvas.restore()
    }

    private fun drawGlassDepth(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        // far panes: big, slow, very soft
        for (k in 0 until 3) {
            val x = w * 0.08f + k * w * 0.32f + sin(t * 0.5f + k) * 8f
            val y = h * 0.14f + (k % 2) * h * 0.1f + cos(t * 0.5f + k) * 6f
            paint.color = Color.argb(10, 180, 235, 255)
            canvas.drawRoundRect(x, y, x + w * 0.3f, y + h * 0.22f, 40f, 40f, paint)
        }
        // mid panes
        for (k in 0 until 4) {
            val x = w * 0.12f + k * w * 0.19f + sin(t * 0.9f + k) * 12f
            val y = h * 0.18f + (k % 2) * h * 0.16f + cos(t * 0.9f + k) * 10f
            paint.color = Color.argb(18, 180, 235, 255)
            canvas.drawRoundRect(x, y, x + w * 0.22f, y + h * 0.18f, 32f, 32f, paint)
        }
        // near panes: brighter edge, closest and fastest
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        for (k in 0 until 3) {
            val x = w * 0.15f + k * w * 0.28f + sin(t * 1.5f + k) * 16f
            val y = h * 0.3f + (k % 2) * h * 0.12f + cos(t * 1.5f + k) * 12f
            paint.color = Color.argb(45, 210, 245, 255)
            canvas.drawRoundRect(x, y, x + w * 0.2f, y + h * 0.14f, 28f, 28f, paint)
        }
    }

    private fun drawSunsetPulse(canvas: Canvas, w: Float, h: Float) {
        canvas.save()
        canvas.translate(w / 2f, h * 0.34f)
        paint.style = Paint.Style.STROKE
        // far rings: slow, faint, large
        for (k in 0 until 3) {
            val r = w * 0.3f + k * w * 0.08f + (t * 8f) % (w * 0.08f)
            paint.strokeWidth = 1.5f
            paint.color = Color.argb(25 - k * 5, 255, 158, 61)
            canvas.drawCircle(0f, 0f, r, paint)
        }
        // mid rings: the original pulse rhythm
        for (k in 0 until 4) {
            val r = w * 0.18f + k * w * 0.055f + (t * 20f) % (w * 0.055f)
            paint.strokeWidth = 2f
            paint.color = Color.argb(70 - k * 8, 255, 158, 61)
            canvas.drawCircle(0f, 0f, r, paint)
        }
        // near pulse: bright, fastest, closest ring
        val rNear = w * 0.1f + (t * 40f) % (w * 0.1f)
        paint.strokeWidth = 2.6f
        paint.color = Color.argb(120, 255, 190, 120)
        canvas.drawCircle(0f, 0f, rNear, paint)
        canvas.restore()
    }

    private fun drawInfinixDefault(canvas: Canvas, w: Float, h: Float) {
        // Calm, minimal depth: a soft glow seated behind the avatar plus slow
        // concentric ripples, instead of the flat solid colour it had before.
        glow(canvas, w / 2f, h * 0.46f, w * 0.55f, Color.rgb(34, 211, 238), 40)
        paint.style = Paint.Style.STROKE
        for (k in 0 until 3) {
            val r = w * 0.2f + k * w * 0.1f + (t * 10f) % (w * 0.1f)
            paint.strokeWidth = 1.4f
            paint.color = Color.argb(30 - k * 6, 34, 211, 238)
            canvas.drawCircle(w / 2f, h * 0.46f, r, paint)
        }
    }

    private fun drawEmeraldCircuit(canvas: Canvas, w: Float, h: Float) {
        // far: faint static circuit-trace grid (right-angle jogs, PCB-style)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.3f
        paint.color = Color.argb(26, 16, 185, 129)
        for (k in 0 until 4) {
            val y = h * (0.2f + k * 0.14f)
            val path = Path()
            path.moveTo(0f, y)
            path.lineTo(w * 0.3f, y)
            path.lineTo(w * 0.3f, y - 26f)
            path.lineTo(w * 0.7f, y - 26f)
            path.lineTo(w * 0.7f, y)
            path.lineTo(w, y)
            canvas.drawPath(path, paint)
        }
        // mid: travelling signal pulses along three lanes
        paint.style = Paint.Style.FILL
        for (k in 0 until 3) {
            val laneY = h * (0.3f + k * 0.16f)
            val progress = (t * (0.6f + k * 0.2f) + k * 0.33f) % 1f
            paint.color = Color.argb(140, 16, 185, 129)
            canvas.drawCircle(progress * w, laneY, 3.5f, paint)
        }
        // near: glowing junction nodes, gently pulsing - the closest, brightest layer
        for (k in 0 until 5) {
            val jx = w * (0.15f + k * 0.18f)
            val jy = h * (0.42f + (k % 2) * 0.1f)
            val pulse = 0.6f + 0.4f * sin(t * 2.2f + k)
            glow(canvas, jx, jy, 9f * pulse, Color.rgb(16, 185, 129), 90)
        }
    }

    private fun drawCrimsonDrift(canvas: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        // far embers: small, dim, slow rise
        for (k in 0 until 8) {
            val phase = k * 0.73f
            val y = h - (t * 14f + phase * 60f) % (h * 1.1f)
            val x = w * (k * 0.137f % 1f) + sin(t * 0.6f + phase) * 10f
            paint.color = Color.argb(35, 225, 29, 72)
            canvas.drawCircle(x, y, 2f, paint)
        }
        // mid embers: brighter, mid speed, slight flicker
        for (k in 0 until 6) {
            val phase = k * 1.1f
            val y = h - (t * 26f + phase * 70f) % (h * 1.1f)
            val x = w * ((k * 0.19f + 0.1f) % 1f) + sin(t * 0.9f + phase) * 14f
            val flicker = 0.6f + 0.4f * sin(t * 3f + phase)
            paint.color = Color.argb((70 * flicker).toInt().coerceIn(0, 255), 232, 70, 100)
            canvas.drawCircle(x, y, 3f, paint)
        }
        // near embers: bright, fastest, with a soft glow halo
        for (k in 0 until 4) {
            val phase = k * 1.6f
            val y = h - (t * 42f + phase * 90f) % (h * 1.1f)
            val x = w * ((k * 0.27f + 0.2f) % 1f) + sin(t * 1.3f + phase) * 18f
            glow(canvas, x, y, 7f, Color.rgb(248, 113, 133), 110)
        }
    }

    private fun drawVioletNebula(canvas: Canvas, w: Float, h: Float) {
        // far: soft, slowly drifting nebula clouds
        for (k in 0 until 3) {
            val cx = w * (0.2f + k * 0.3f) + sin(t * 0.3f + k) * 18f
            val cy = h * (0.25f + (k % 2) * 0.2f) + cos(t * 0.3f + k) * 14f
            glow(canvas, cx, cy, w * 0.32f, Color.rgb(109, 40, 217), 26)
        }
        // mid: twinkling starfield
        paint.style = Paint.Style.FILL
        for (k in 0 until 16) {
            val sx = w * (k * 0.311f % 1f)
            val sy = h * ((k * 0.197f + 0.05f) % 0.85f)
            val twinkle = 0.3f + 0.7f * ((sin(t * 2f + k) + 1f) / 2f)
            paint.color = Color.argb((110 * twinkle).toInt().coerceIn(0, 255), 196, 181, 253)
            canvas.drawCircle(sx, sy, 1.6f, paint)
        }
        // near: a few bright close stars, faster twinkle - closest layer
        for (k in 0 until 5) {
            val sx = w * ((k * 0.57f + 0.1f) % 1f)
            val sy = h * ((k * 0.41f + 0.08f) % 0.7f)
            val twinkle = 0.4f + 0.6f * ((sin(t * 4f + k * 1.7f) + 1f) / 2f)
            paint.color = Color.argb((190 * twinkle).toInt().coerceIn(0, 255), 221, 214, 254)
            canvas.drawCircle(sx, sy, 2.6f, paint)
        }
    }
}

object AfifCallBackdrop {
    fun install(activity: Activity, root: View) {
        if (root !is ViewGroup) return
        if (root.findViewById<AfifCallBackdropView>(0x7F0F0001) != null) return
        val view = AfifCallBackdropView(activity)
        view.id = 0x7F0F0001
        root.addView(view, 0, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // Keep the animated layer behind the existing call controls.
        if (root is ConstraintLayout) {
            view.translationZ = -1f
        }
    }
}
