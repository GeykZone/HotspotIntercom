package com.example.hotspotintercom

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Dark neumorphic palette. */
object Neu {
    val BASE = 0xFF22252A.toInt()
    val LIGHT = 0xFF2F333A.toInt()    // highlight shadow (top-left)
    val DARK = 0xFF141619.toInt()     // deep shadow (bottom-right)
    val TEXT = 0xFFE8EBF1.toInt()
    val MUTED = 0xFF8B93A3.toInt()
    val ACCENT = 0xFF00E5FF.toInt()
    val RED = 0xFFFF4D6D.toInt()
    val GREEN = 0xFF3DDC97.toInt()
}

/**
 * Soft raised ("convex") or pressed-in ("inset") surface with a light and a dark shadow.
 * The shape is drawn inside the bounds, inset by [padPx], so the shadows are not clipped.
 */
class NeuDrawable(
    private val radiusPx: Float,
    private val padPx: Float,
    private val blurPx: Float,
    private val offsetPx: Float
) : Drawable() {

    var inset: Boolean = false
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    /** Colored glow around the shape, or 0 for none. */
    var glow: Int = 0
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b.left + padPx, b.top + padPx, b.right - padPx, b.bottom - padPx)
        val r = minOf(radiusPx, minOf(rect.width(), rect.height()) / 2f)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Neu.BASE

        if (!inset) {
            paint.setShadowLayer(blurPx, -offsetPx, -offsetPx, Neu.LIGHT)
            c.drawRoundRect(rect, r, r, paint)
            paint.setShadowLayer(blurPx, offsetPx, offsetPx, Neu.DARK)
            c.drawRoundRect(rect, r, r, paint)
        }
        if (glow != 0) {
            paint.setShadowLayer(blurPx * 1.8f, 0f, 0f, (glow and 0x00FFFFFF) or (0xB0 shl 24))
            c.drawRoundRect(rect, r, r, paint)
        }
        paint.clearShadowLayer()

        paint.shader = if (!inset) {
            LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                0xFF2B2E35.toInt(), 0xFF1E2126.toInt(), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                0xFF16181C.toInt(), 0xFF2A2D34.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rect, r, r, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

fun View.dpf(v: Float): Float = v * resources.displayMetrics.density

/** Gives any view a neumorphic background; padding = shadow room + the extra content padding you ask for. */
fun View.applyNeu(
    radiusDp: Float,
    inset: Boolean = false,
    padDp: Float = 12f,
    hPadDp: Float = 0f,
    vPadDp: Float = 0f,
    blurDp: Float = 8f,
    offDp: Float = 4f
): NeuDrawable {
    setLayerType(View.LAYER_TYPE_SOFTWARE, null)   // needed for shadow layers on every Android version
    val d = NeuDrawable(dpf(radiusDp), dpf(padDp), dpf(blurDp), dpf(offDp))
    d.inset = inset
    background = d
    val ph = (dpf(padDp) + dpf(hPadDp)).toInt()
    val pv = (dpf(padDp) + dpf(vPadDp)).toInt()
    setPadding(ph, pv, ph, pv)
    return d
}

/** Raised button that sinks in when pressed. */
class NeuButton(
    ctx: Context,
    label: String,
    labelColor: Int = Neu.TEXT,
    sizeSp: Float = 15f,
    radiusDp: Float = 20f
) : TextView(ctx) {

    private var look: NeuDrawable? = null

    init {
        text = label
        textSize = sizeSp
        setTextColor(labelColor)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.08f
        isClickable = true
        look = applyNeu(radiusDp, hPadDp = 6f, vPadDp = 6f)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        look?.inset = pressed
    }

    fun setGlow(color: Int) { look?.glow = color }
}

/** Neumorphic on/off switch: sunken track, raised knob, glowing accent dot when on. */
class NeuToggle(ctx: Context) : View(ctx) {

    var checkedState: Boolean = false
        private set
    var onChange: ((Boolean) -> Unit)? = null

    private var pos = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var anim: ValueAnimator? = null
    private val argb = ArgbEvaluator()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        setOnClickListener { setState(!checkedState, animate = true, notify = true) }
    }

    fun setState(value: Boolean, animate: Boolean = false, notify: Boolean = false) {
        checkedState = value
        val target = if (value) 1f else 0f
        anim?.cancel()
        if (animate) {
            anim = ValueAnimator.ofFloat(pos, target).apply {
                duration = 180
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            pos = target
            invalidate()
        }
        if (notify) onChange?.invoke(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dpf(72f).toInt(), dpf(44f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val trackH = dpf(22f)
        val m = dpf(6f)
        rect.set(m, h / 2 - trackH / 2, w - m, h / 2 + trackH / 2)
        val tr = trackH / 2

        // sunken track
        p.clearShadowLayer()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
            0xFF121418.toInt(), 0xFF2A2E34.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, tr, tr, p)
        p.shader = null
        if (pos > 0f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = dpf(1.5f)
            p.color = Neu.ACCENT
            p.alpha = (pos * 150).toInt()
            c.drawRoundRect(rect, tr, tr, p)
            p.alpha = 255
            p.style = Paint.Style.FILL
        }

        // raised knob
        val kr = dpf(13f)
        val travel = rect.width() - 2 * kr - dpf(2f)
        val cx = rect.left + kr + dpf(1f) + pos * travel
        val cy = h / 2
        p.color = Neu.BASE
        p.setShadowLayer(dpf(5f), -dpf(2f), -dpf(2f), Neu.LIGHT)
        c.drawCircle(cx, cy, kr, p)
        p.setShadowLayer(dpf(5f), dpf(2f), dpf(2f), Neu.DARK)
        c.drawCircle(cx, cy, kr, p)
        p.clearShadowLayer()
        p.shader = LinearGradient(cx - kr, cy - kr, cx + kr, cy + kr,
            0xFF2E3137.toInt(), 0xFF1F2227.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, kr, p)
        p.shader = null

        // status dot on the knob
        p.color = argb.evaluate(pos, 0xFF4A505A.toInt(), Neu.ACCENT) as Int
        if (pos > 0.05f) {
            val glow = (Neu.ACCENT and 0x00FFFFFF) or ((pos * 200).toInt() shl 24)
            p.setShadowLayer(dpf(6f), 0f, 0f, glow)
        }
        c.drawCircle(cx, cy, dpf(4f), p)
        p.clearShadowLayer()
    }
}
