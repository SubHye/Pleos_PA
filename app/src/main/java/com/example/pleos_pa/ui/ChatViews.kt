package com.example.pleos_pa.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.pleos_pa.R

/** Short, bounded motion. All of it is skipped when the system has animations turned off. */
internal object CardMotion {
    fun enabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    fun enter(view: View, fromStart: Boolean, distance: Float) {
        if (!enabled()) return
        view.alpha = 0f
        view.translationX = if (fromStart) -distance else distance
        view
            .animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(280)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun hop(view: View, height: Float, drop: Boolean): Animator? {
        if (!enabled()) return null
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -height).apply {
            duration = if (drop) 450 else 350
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = if (drop) DecelerateInterpolator() else AccelerateInterpolator(0.6f)
            start()
        }
    }

    fun dot(view: View, lift: Float, index: Int): Animator? {
        if (!enabled()) return null
        return ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -lift, 0f, 0f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.3f, 1f, 0.3f, 0.3f),
            )
            .apply {
                duration = 1000
                startDelay = index * 150L
                repeatCount = ValueAnimator.INFINITE
                start()
            }
    }

    fun pop(view: View) {
        if (!enabled()) return
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view
            .animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350)
            .setInterpolator(OvershootInterpolator(3f))
            .start()
    }

    fun shake(view: View, distance: Float) {
        if (!enabled()) return
        ObjectAnimator.ofFloat(
                view,
                View.TRANSLATION_X,
                0f,
                -distance,
                distance,
                -distance / 2,
                0f,
            )
            .apply {
                duration = 300
                start()
            }
    }

    fun ring(view: View): Animator? {
        if (!enabled()) return null
        return ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 2.6f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 2.6f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0f),
            )
            .apply {
                duration = 2400
                repeatCount = ValueAnimator.INFINITE
                interpolator = DecelerateInterpolator()
                start()
            }
    }
}

internal fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != null) setStroke(strokeWidth, stroke)
    }

/** A small battery with two eyes. [level] is only how full it is drawn, never a vehicle value. */
internal class BatteryFaceView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var filling: ValueAnimator? = null
    var color: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var fillColor: Int = 0
    var level: Float = 0.25f
        set(value) {
            field = value
            invalidate()
        }

    fun fillTo(target: Float, animate: Boolean) {
        filling?.cancel()
        if (!animate || !CardMotion.enabled()) {
            level = target
            return
        }
        filling =
            ValueAnimator.ofFloat(level, target).apply {
                duration = 500
                addUpdateListener { level = it.animatedValue as Float }
                start()
            }
    }

    fun stop() {
        filling?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val line = h * 0.14f
        val nub = w * 0.1f
        val body = w - nub
        val corner = h * 0.25f
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        val inset = line * 1.6f
        canvas.drawRoundRect(
            inset,
            inset,
            inset + (body - inset * 2) * level.coerceIn(0f, 1f),
            h - inset,
            corner / 2,
            corner / 2,
            paint,
        )
        paint.color = color
        canvas.drawRect(body, h * 0.32f, w, h * 0.68f, paint)
        val eye = h * 0.09f
        canvas.drawCircle(body * 0.36f, h * 0.5f, eye, paint)
        canvas.drawCircle(body * 0.64f, h * 0.5f, eye, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = line
        canvas.drawRoundRect(
            line / 2,
            line / 2,
            body - line / 2,
            h - line / 2,
            corner,
            corner,
            paint,
        )
    }
}

/**
 * Keeps the newest rows in view and cannot be scrolled: older rows leave at the top, so a long
 * record is never browsed on this screen.
 */
@SuppressLint("ViewConstructor")
internal class NewestRows(context: Context, val rows: LinearLayout) : ScrollView(context) {
    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        rows.orientation = LinearLayout.VERTICAL
        rows.gravity = Gravity.BOTTOM
        addView(
            rows,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        scrollTo(0, (rows.height - height).coerceAtLeast(0))
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = false
}

/**
 * The two views of one request: what the AI asked on the start side, the app's answer on the end.
 */
internal class ChatRow(context: Context, private val unit: Float, textScale: Float) {
    private val surface = ContextCompat.getColor(context, R.color.surface)
    private val outline = ContextCompat.getColor(context, R.color.outline)
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accentContainer = ContextCompat.getColor(context, R.color.accent_container)
    private val danger = ContextCompat.getColor(context, R.color.danger)
    private val dangerContainer = ContextCompat.getColor(context, R.color.danger_container)
    private val caption =
        TextView(context).apply {
            textSize = 12f * textScale
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        }
    private val title =
        TextView(context).apply {
            textSize = 16f * textScale
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
    private val line =
        TextView(context).apply {
            textSize = 16f * textScale
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
    private val battery = BatteryFaceView(context)
    private val symbol = ImageView(context)
    private val iconBox = FrameLayout(context)
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val mark =
        TextView(context).apply {
            textSize = 18f * textScale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
    private val card = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val bubble = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val motions = mutableListOf<Animator>()
    private var shown: ChatCard? = null

    val request: View
    val response: View

    init {
        bubble.apply {
            background = rounded(ContextCompat.getColor(context, R.color.bubble), px(14f).toFloat())
            setPadding(px(14f), px(9f), px(14f), px(10f))
            addView(caption)
            addView(title)
        }
        request = sided(context, bubble, Gravity.START)

        iconBox.addView(battery, FrameLayout.LayoutParams(px(24f), px(15f), Gravity.CENTER))
        iconBox.addView(symbol, FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        repeat(3) {
            dots.addView(
                View(context).apply { background = rounded(accent, px(3f).toFloat()) },
                LinearLayout.LayoutParams(px(5f), px(5f)).apply { marginEnd = px(4f) },
            )
        }
        val indicator =
            FrameLayout(context).apply {
                addView(dots, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                addView(mark, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            }
        card.apply {
            background = rounded(surface, px(16f).toFloat(), outline, px(1f).coerceAtLeast(1))
            setPadding(px(9f), px(8f), px(14f), px(8f))
            addView(iconBox, LinearLayout.LayoutParams(px(38f), px(38f)))
            addView(
                line,
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    marginStart = px(11f)
                    marginEnd = px(11f)
                },
            )
            addView(indicator, LinearLayout.LayoutParams(px(32f), px(26f)))
        }
        response = sided(context, card, Gravity.END)
    }

    private fun px(dp: Float) = (dp * unit).toInt()

    private fun sided(context: Context, child: View, side: Int) =
        FrameLayout(context).apply {
            setPadding(0, 0, 0, px(10f))
            addView(child, FrameLayout.LayoutParams(WRAP, WRAP, side))
        }

    fun enter() {
        CardMotion.enter(bubble, true, px(24f).toFloat())
        CardMotion.enter(card, false, px(24f).toFloat())
    }

    fun bind(next: ChatCard, requestCaption: String, animateEnd: Boolean) {
        val previous = shown
        shown = next
        caption.text = requestCaption
        title.text = next.title
        line.text = next.line
        if (previous?.phase == next.phase && previous.kind == next.kind) return
        stop()
        val energy = next.kind == ChatCard.Kind.ENERGY
        battery.visibility = if (energy) View.VISIBLE else View.GONE
        symbol.visibility = if (energy) View.GONE else View.VISIBLE
        symbol.setImageResource(
            if (next.kind == ChatCard.Kind.NAVIGATION) R.drawable.ic_card_pin
            else R.drawable.ic_card_car
        )
        val failed = next.phase == ChatCard.Phase.FAILED
        val tone = if (failed) danger else accent
        iconBox.background =
            rounded(if (failed) dangerContainer else accentContainer, px(11f).toFloat())
        symbol.imageTintList = ColorStateList.valueOf(tone)
        battery.color = tone
        battery.fillColor = tone and 0x00FFFFFF or 0x66000000
        val icon: View = if (energy) battery else symbol
        icon.translationY = 0f
        val finishing = animateEnd && previous?.phase == ChatCard.Phase.WORKING
        when (next.phase) {
            ChatCard.Phase.WORKING -> {
                dots.visibility = View.VISIBLE
                mark.visibility = View.GONE
                battery.fillTo(0.25f, false)
                val drop = next.kind == ChatCard.Kind.NAVIGATION
                CardMotion.hop(icon, px(if (drop) 5f else 3f).toFloat(), drop)?.let(motions::add)
                for (index in 0 until dots.childCount) {
                    CardMotion.dot(dots.getChildAt(index), px(3f).toFloat(), index)
                        ?.let(motions::add)
                }
            }
            ChatCard.Phase.DONE -> {
                dots.visibility = View.GONE
                mark.visibility = View.VISIBLE
                mark.text = "✓"
                mark.setTextColor(accent)
                battery.fillTo(0.8f, finishing)
                if (finishing) CardMotion.pop(mark)
            }
            ChatCard.Phase.FAILED -> {
                dots.visibility = View.GONE
                mark.visibility = View.VISIBLE
                mark.text = "!"
                mark.setTextColor(danger)
                battery.fillTo(0.25f, false)
                if (finishing) CardMotion.shake(card, px(4f).toFloat())
            }
        }
    }

    fun stop() {
        motions.forEach(Animator::cancel)
        motions.clear()
        battery.stop()
        for (view in listOf(battery, symbol, card, mark)) {
            view.animate().cancel()
        }
        battery.translationY = 0f
        symbol.translationY = 0f
        card.translationX = 0f
        card.alpha = 1f
        mark.scaleX = 1f
        mark.scaleY = 1f
        bubble.apply {
            animate().cancel()
            translationX = 0f
            alpha = 1f
        }
        for (index in 0 until dots.childCount) {
            dots.getChildAt(index).apply {
                translationY = 0f
                alpha = 1f
            }
        }
    }

    private companion object {
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
