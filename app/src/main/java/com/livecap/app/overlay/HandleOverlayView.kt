package com.livecap.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.livecap.app.R
import com.livecap.app.util.DisplayMetricsUtil
import kotlin.math.abs

/**
 * Small always-on-top draggable pill. Tapping it (without dragging) reopens the region selector
 * so the user can adjust the capture area without leaving the app they're watching.
 */
class HandleOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: HandleView? = null

    val isShowing: Boolean get() = view != null

    fun show(onTap: () -> Unit) {
        if (view != null) return

        val density = context.resources.displayMetrics.density
        val sizePx = (48 * density).toInt()
        val screenSize = DisplayMetricsUtil.realSize(context)

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenSize.x - sizePx - (16 * density).toInt()
            y = screenSize.y - sizePx * 3
        }

        lateinit var handleView: HandleView
        handleView = HandleView(context) { dx, dy ->
            params.x = (params.x + dx).toInt().coerceIn(0, screenSize.x - sizePx)
            params.y = (params.y + dy).toInt().coerceIn(0, screenSize.y - sizePx)
            runCatching { windowManager.updateViewLayout(handleView, params) }
        }
        handleView.onTap = onTap

        windowManager.addView(handleView, params)
        view = handleView
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }
}

private class HandleView(
    context: Context,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
) : View(context) {

    var onTap: (() -> Unit)? = null

    private val bgPaint = Paint().apply {
        color = context.getColor(R.color.primary)
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private val tapSlop = 12f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var totalMovement = 0f

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = width.coerceAtMost(height) / 2f
        canvas.drawCircle(cx, cy, radius, bgPaint)
        val iconHalf = radius * 0.4f
        canvas.drawRect(cx - iconHalf, cy - iconHalf, cx + iconHalf, cy + iconHalf, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                totalMovement = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                totalMovement += abs(dx) + abs(dy)
                onDrag(dx, dy)
                lastX = event.rawX
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (totalMovement < tapSlop) onTap?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
