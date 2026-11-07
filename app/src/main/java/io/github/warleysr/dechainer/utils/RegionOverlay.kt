package io.github.warleysr.dechainer.utils

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.TextView

/**
 * Opaque, touch-swallowing panels drawn exactly over screen regions, so one part of an activity
 * can be blocked while the rest of it stays usable. Views are pooled and only touched when the
 * set of rectangles actually changes.
 */
class RegionOverlay(private val service: AccessibilityService, private val message: String) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val views = ArrayList<TextView>()
    private var current: List<Rect> = emptyList()

    fun show(rects: List<Rect>) {
        if (rects == current) return
        current = rects.map { Rect(it) }

        while (views.size > rects.size) {
            runCatching { windowManager.removeView(views.removeAt(views.lastIndex)) }
        }
        rects.forEachIndexed { i, rect ->
            val params = paramsFor(rect)
            if (i < views.size) {
                runCatching { windowManager.updateViewLayout(views[i], params) }
            } else {
                val view = TextView(service).apply {
                    setBackgroundColor(Color.rgb(24, 24, 24))
                    setTextColor(Color.LTGRAY)
                    gravity = Gravity.CENTER
                    text = message
                    textSize = 14f
                }
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

    private fun paramsFor(rect: Rect) = LayoutParams(
        rect.width(),
        rect.height(),
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touchable on purpose: the panel must absorb taps instead of letting them through.
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }
}
