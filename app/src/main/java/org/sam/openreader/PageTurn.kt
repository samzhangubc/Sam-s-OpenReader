package org.sam.openreader

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

/** The live renderer draws the destination; one viewport bitmap holds the departing page. */
class PageTurn(context: Context) : FrameLayout(context) {
    val surface = FrameLayout(context)
    private val old = ImageView(context)
    var mode = 0
    var advance: (Boolean) -> Boolean = { false }
    var restore: () -> Unit = {}
    var settled: () -> Unit = {}
    var allowTurn: () -> Boolean = { true }
    var pending = false
        private set

    private var sign = 0
    private var down = 0f
    private var top = 0f
    private var distance = 0f
    private var animator: ValueAnimator? = null
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        addView(surface, -1, -1)
        addView(old, -1, -1)
        old.scaleType = ImageView.ScaleType.FIT_XY
        old.visibility = GONE
    }

    private fun begin(next: Boolean): Boolean {
        if (width == 0 || height == 0 || pending || !allowTurn()) return false
        if (width.toLong() * height > 8_000_000) return advance(next)
        old.setImageBitmap(
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                surface.draw(Canvas(it))
            }
        )
        pending = true
        if (!advance(next)) {
            finish(false, false)
            return false
        }
        sign = if (next) -1 else 1
        old.visibility = VISIBLE
        if (mode == 1) surface.bringToFront() else old.bringToFront()
        drawAt(0f)
        return true
    }

    private fun drawAt(fraction: Float) {
        distance = fraction.coerceIn(0f, 1f)
        val m = if (ValueAnimator.areAnimatorsEnabled()) mode else 3
        old.translationX = if (m == 0 || m == 2) sign * width * distance else 0f
        surface.translationX = if (m == 0 || m == 1) -sign * width * (1 - distance) else 0f
        if (m == 3) old.bringToFront()
    }

    fun turn(next: Boolean) {
        if (begin(next)) animateTo(true)
    }

    private fun animateTo(commit: Boolean) {
        if (mode == 3 || !ValueAnimator.areAnimatorsEnabled()) {
            finish(commit)
            return
        }
        animator =
            ValueAnimator.ofFloat(distance, if (commit) 1f else 0f).apply {
                duration = 160
                addUpdateListener { drawAt(it.animatedValue as Float) }
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            finish(commit)
                        }
                    }
                )
                start()
            }
    }

    private fun finish(commit: Boolean, moved: Boolean = true) {
        if (!commit && moved) restore()
        old.visibility = GONE
        old.setImageDrawable(null)
        surface.translationX = 0f
        old.translationX = 0f
        pending = false
        animator = null
        if (commit) settled()
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (animator != null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = e.x
                top = e.y
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE ->
                if (
                    e.pointerCount == 1 &&
                        abs(e.x - down) > slop * 2 &&
                        abs(e.x - down) > abs(e.y - top) * 1.5 &&
                        e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout() &&
                        allowTurn()
                ) {
                    return begin(e.x < down)
                }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!pending || animator != null) return super.onTouchEvent(e)
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> drawAt(sign * (e.x - down) / width)
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val fling =
                    sign * (velocity?.xVelocity ?: 0f) >=
                        ViewConfiguration.get(context).scaledMinimumFlingVelocity
                animateTo(distance > 1f / 3f || (distance * width > slop && fling))
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN -> animateTo(false)
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pending && (w != oldw || h != oldh)) {
            animator?.removeAllListeners()
            animator?.cancel()
            finish(false)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.removeAllListeners()
        animator?.cancel()
        if (pending) finish(false)
        super.onDetachedFromWindow()
    }
}
