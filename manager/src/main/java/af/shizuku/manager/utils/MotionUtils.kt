package af.shizuku.manager.utils

import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object MotionUtils {

    /**
     * Applies a spring-based scale down/up effect on touch.
     */
    fun View.applySpringTouch(scale: Float = 0.97f) {
        val springAnimX = SpringAnimation(this, SpringAnimation.SCALE_X, 1.0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
        val springAnimY = SpringAnimation(this, SpringAnimation.SCALE_Y, 1.0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }

        // A press on a home card may be the start of a horizontal swipe to the next page, so the
        // DOWN only starts the press animation. The tap vibration is held until release and only
        // fires when the finger never left touch slop - otherwise swiping from Home to Authorize
        // buzzed once per swipe while every other page stayed silent.
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false

        fun release() {
            springAnimX.start()
            springAnimY.start()
        }

        setOnTouchListener { v, event ->
            // Unlike every other animation/haptic call site in the app, this touch feedback
            // used to fire unconditionally on every card tap, ignoring the user's Expressive
            // Animations toggle.
            if (!af.shizuku.manager.ShizukuSettings.isExpressiveAnimationsEnabled()) {
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    springAnimX.cancel()
                    springAnimY.cancel()
                    v?.animate()?.scaleX(scale)?.scaleY(scale)
                        ?.setDuration(af.shizuku.manager.ShizukuSettings.scaledAnimationDuration(100))
                        ?.start()
                }
                MotionEvent.ACTION_MOVE -> {
                    // Past slop the press became a scroll or a page swipe: drop the pressed state
                    // and stay silent, exactly like releasing a ripple that was cancelled.
                    if (!moved && (kotlin.math.abs(event.rawX - downX) > slop ||
                                kotlin.math.abs(event.rawY - downY) > slop)
                    ) {
                        moved = true
                        release()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    release()
                    if (!moved) v?.let { HapticUtils.tap(it) }
                }
                MotionEvent.ACTION_CANCEL -> release()
            }
            false
        }
    }
}
