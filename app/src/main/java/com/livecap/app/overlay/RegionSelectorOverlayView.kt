package com.livecap.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.livecap.app.R
import com.livecap.app.data.CapturePrefs
import com.livecap.app.data.NormalizedRect
import com.livecap.app.util.DisplayMetricsUtil
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the full-screen overlay window used to let the user drag/resize the capture region.
 * Shown on demand (first run, or when the user taps the always-on adjust handle) and removed
 * once the user confirms.
 */
class RegionSelectorOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: RegionSelectorView? = null

    val isShowing: Boolean get() = view != null

    fun show(onConfirm: () -> Unit) {
        if (view != null) return

        val screenSize = DisplayMetricsUtil.realSize(context)
        val orientation = context.resources.configuration.orientation
        val saved = CapturePrefs.region(context, orientation)
        val initialRectPx = if (saved != null) {
            RectF(
                saved.left * screenSize.x,
                saved.top * screenSize.y,
                saved.right * screenSize.x,
                saved.bottom * screenSize.y,
            )
        } else {
            // Default: a band across the lower third of the screen, a common caption position.
            RectF(
                screenSize.x * 0.1f,
                screenSize.y * 0.78f,
                screenSize.x * 0.9f,
                screenSize.y * 0.92f,
            )
        }

        val regionView = RegionSelectorView(
            context = context,
            screenWidth = screenSize.x,
            screenHeight = screenSize.y,
            initialRectPx = initialRectPx,
            onConfirm = { finalRectPx ->
                val rect = NormalizedRect(
                    left = (finalRectPx.left / screenSize.x).coerceIn(0f, 1f),
                    top = (finalRectPx.top / screenSize.y).coerceIn(0f, 1f),
                    right = (finalRectPx.right / screenSize.x).coerceIn(0f, 1f),
                    bottom = (finalRectPx.bottom / screenSize.y).coerceIn(0f, 1f),
                )
                CapturePrefs.setRegion(context, orientation, rect)
                hide()
                onConfirm()
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(regionView, params)
        view = regionView
    }

    fun hide() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }
}

private class RegionSelectorView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    initialRectPx: RectF,
    private val onConfirm: (RectF) -> Unit,
) : View(context) {

    private val rect = RectF(initialRectPx)

    private val scrimPaint = Paint().apply { color = Color.parseColor("#99000000") }
    private val borderPaint = Paint().apply {
        color = context.getColor(R.color.region_border)
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val handlePaint = Paint().apply {
        color = context.getColor(R.color.handle_dot)
        style = Paint.Style.FILL
    }
    private val confirmBgPaint = Paint().apply {
        color = context.getColor(R.color.primary)
        style = Paint.Style.FILL
    }
    private val confirmCheckPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val handleRadius = 14f * resources.displayMetrics.density
    private val touchSlop = 32f * resources.displayMetrics.density
    private val confirmRadius = 24f * resources.displayMetrics.density
    private val minRectSize = 40f * resources.displayMetrics.density

    private var confirmCx = 0f
    private var confirmCy = 0f

    private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var dragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, screenWidth.toFloat(), 0f.coerceAtLeast(rect.top), scrimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, scrimPaint)
        canvas.drawRect(rect.right, rect.top, screenWidth.toFloat(), rect.bottom, scrimPaint)
        canvas.drawRect(0f, rect.bottom, screenWidth.toFloat(), screenHeight.toFloat(), scrimPaint)

        canvas.drawRect(rect, borderPaint)

        canvas.drawCircle(rect.left, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.top, handleRadius, handlePaint)
        canvas.drawCircle(rect.left, rect.bottom, handleRadius, handlePaint)
        canvas.drawCircle(rect.right, rect.bottom, handleRadius, handlePaint)

        confirmCx = rect.right
        confirmCy = (rect.bottom + confirmRadius * 2).coerceAtMost(screenHeight.toFloat() - confirmRadius)
        canvas.drawCircle(confirmCx, confirmCy, confirmRadius, confirmBgPaint)
        canvas.drawLine(
            confirmCx - confirmRadius * 0.45f, confirmCy,
            confirmCx - confirmRadius * 0.1f, confirmCy + confirmRadius * 0.35f,
            confirmCheckPaint,
        )
        canvas.drawLine(
            confirmCx - confirmRadius * 0.1f, confirmCy + confirmRadius * 0.35f,
            confirmCx + confirmRadius * 0.5f, confirmCy - confirmRadius * 0.35f,
            confirmCheckPaint,
        )
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                dragMode = when {
                    distance(x, y, confirmCx, confirmCy) <= confirmRadius -> {
                        onConfirm(RectF(rect))
                        DragMode.NONE
                    }
                    distance(x, y, rect.left, rect.top) <= touchSlop -> DragMode.TOP_LEFT
                    distance(x, y, rect.right, rect.top) <= touchSlop -> DragMode.TOP_RIGHT
                    distance(x, y, rect.left, rect.bottom) <= touchSlop -> DragMode.BOTTOM_LEFT
                    distance(x, y, rect.right, rect.bottom) <= touchSlop -> DragMode.BOTTOM_RIGHT
                    rect.contains(x, y) -> DragMode.MOVE
                    else -> DragMode.NONE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                when (dragMode) {
                    DragMode.MOVE -> {
                        val width = rect.width()
                        val height = rect.height()
                        val newLeft = (rect.left + dx).coerceIn(0f, screenWidth - width)
                        val newTop = (rect.top + dy).coerceIn(0f, screenHeight - height)
                        rect.set(newLeft, newTop, newLeft + width, newTop + height)
                    }
                    DragMode.TOP_LEFT -> {
                        rect.left = min(rect.left + dx, rect.right - minRectSize).coerceAtLeast(0f)
                        rect.top = min(rect.top + dy, rect.bottom - minRectSize).coerceAtLeast(0f)
                    }
                    DragMode.TOP_RIGHT -> {
                        rect.right = max(rect.right + dx, rect.left + minRectSize).coerceAtMost(screenWidth.toFloat())
                        rect.top = min(rect.top + dy, rect.bottom - minRectSize).coerceAtLeast(0f)
                    }
                    DragMode.BOTTOM_LEFT -> {
                        rect.left = min(rect.left + dx, rect.right - minRectSize).coerceAtLeast(0f)
                        rect.bottom = max(rect.bottom + dy, rect.top + minRectSize).coerceAtMost(screenHeight.toFloat())
                    }
                    DragMode.BOTTOM_RIGHT -> {
                        rect.right = max(rect.right + dx, rect.left + minRectSize).coerceAtMost(screenWidth.toFloat())
                        rect.bottom = max(rect.bottom + dy, rect.top + minRectSize).coerceAtMost(screenHeight.toFloat())
                    }
                    DragMode.NONE -> return true
                }
                lastTouchX = x
                lastTouchY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
