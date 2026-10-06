package app.ljos.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Small, deliberate haptics. Uses Android 14's richer constants where available (toggle on/off,
 * segment ticks) and falls back to subtle clock ticks. The system haptics setting is respected.
 */
object Haptics {
    fun toggle(view: View, on: Boolean) {
        val c = when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            Build.VERSION.SDK_INT >= 30 -> if (on) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(c)
    }

    /** A segment snapped into place. */
    fun segment(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        )
    }

    /** Light, frequent tick for scrubbing across the hour bars. */
    fun scrub(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        )
    }

    /** Button press. */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Something settled: the sheet snapped, the score landed in the header. */
    fun settle(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CLOCK_TICK
        )
    }
}
