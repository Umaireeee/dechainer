package io.github.warleysr.dechainer.utils

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView

/**
 * Touch-swallowing panels drawn exactly over screen regions, so one part of an activity can be
 * blocked while the rest of it stays usable. A panel is either an opaque cover with a message, or
 * invisible: the screen looks untouched but the area can't be tapped or scrolled, because the
 * window over it consumes the touches. Views are pooled and only touched when something changed.
 */
class RegionOverlay(private val service: AccessibilityService, private val message: String) {
    data class Cover(val rect: Rect, val invisible: Boolean)

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val views = ArrayList<TextView>()
    private var current: List<Cover> = emptyList()

    fun show(covers: List<Cover>) {
        if (covers == current) return
        current = covers.map { Cover(Rect(it.rect), it.invisible) }

        while (views.size > covers.size) {
            runCatching { windowManager.removeView(views.removeAt(views.lastIndex)) }
        }
        covers.forEachIndexed { i, cover ->
            val params = paramsFor(cover.rect)
            if (i < views.size) {
                style(views[i], cover.invisible)
                runCatching { windowManager.updateViewLayout(views[i], params) }
            } else {
                val view = TextView(service).apply {
                    gravity = Gravity.CENTER
                    textSize = 14f
                }
                style(view, cover.invisible)
                runCatching { windowManager.addView(view, params) }.onSuccess { views.add(view) }
            }
        }
    }

    fun hide() {
        if (views.isEmpty()) return
        views.forEach { runCatching { windowManager.removeView(it) } }
        views.clear()
        current = emptyList()
    }

    private fun style(view: TextView, invisible: Boolean) {
        if (invisible) {
            view.setBackgroundColor(Color.TRANSPARENT)
            view.text = ""
        } else {
            view.setBackgroundColor(Color.rgb(24, 24, 24))
            view.setTextColor(Color.LTGRAY)
            view.text = message
        }
    }

    companion object {
        /** Visible in `dumpsys window`, which the emulator tests rely on. */
        const val WINDOW_TITLE = "DechainerRegionOverlay"
    }

    private fun paramsFor(rect: Rect) = LayoutParams(
        rect.width(),
        rect.height(),
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touchable on purpose: the panel must absorb taps instead of letting them through.
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = WINDOW_TITLE
    }
}
