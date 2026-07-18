package com.livecap.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.livecap.app.R

/**
 * Always-on-top translated caption display. Added once while capture is running; text/visibility
 * are mutated in place on each pipeline result rather than re-adding the window, to avoid flicker.
 */
class CaptionOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var textView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var lastText: String? = null

    val isShowing: Boolean get() = textView != null

    fun show() {
        if (textView != null) return

        val density = context.resources.displayMetrics.density
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.caption_bg))
                cornerRadius = 12f * density
            }
            visibility = View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(tv, params)
        textView = tv
        layoutParams = params
    }

    /** Anchors the caption box just below the capture region, flipping above it if there's no room. */
    fun reposition(regionRectPx: RectF, screenWidthPx: Int, screenHeightPx: Int) {
        val tv = textView ?: return
        val params = layoutParams ?: return
        val density = context.resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val estimatedHeight = (48 * density).toInt()

        params.x = regionRectPx.left.toInt().coerceIn(0, (screenWidthPx - 1))
        params.width = (regionRectPx.width().toInt()).coerceAtLeast((100 * density).toInt())

        params.y = if (regionRectPx.bottom + margin + estimatedHeight <= screenHeightPx) {
            (regionRectPx.bottom + margin).toInt()
        } else {
            (regionRectPx.top - margin - estimatedHeight).coerceAtLeast(0f).toInt()
        }

        runCatching { windowManager.updateViewLayout(tv, params) }
    }

    fun update(text: String?) {
        val tv = textView ?: return
        if (text.isNullOrBlank()) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            lastText = null
            return
        }
        if (text == lastText) return
        tv.text = text
        tv.visibility = View.VISIBLE
        lastText = text
    }

    fun hide() {
        textView?.let { runCatching { windowManager.removeView(it) } }
        textView = null
        layoutParams = null
        lastText = null
    }
}
