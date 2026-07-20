package com.livecap.app.util

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

object DisplayMetricsUtil {

    /** Real display size in pixels, accounting for current rotation. */
    fun realSize(context: Context): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point
        }
    }

    fun densityDpi(context: Context): Int = context.resources.displayMetrics.densityDpi
}
