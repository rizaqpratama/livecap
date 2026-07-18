package com.livecap.app.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.ImageReader

/**
 * Pulls the freshest available frame from the capture ImageReader and crops it to the
 * user-selected region. Uses acquireLatestImage (not acquireNextImage) — at a 2fps-ish sample
 * rate we always want the newest frame and are fine dropping anything buffered before it.
 */
object FrameGrabber {

    fun grabCroppedBitmap(imageReader: ImageReader, cropRectPx: Rect): Bitmap? {
        val image = imageReader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmapWidth = image.width + rowPadding / pixelStride

            val fullBitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(buffer)

            val safeRect = Rect(
                cropRectPx.left.coerceIn(0, image.width),
                cropRectPx.top.coerceIn(0, image.height),
                cropRectPx.right.coerceIn(0, image.width),
                cropRectPx.bottom.coerceIn(0, image.height),
            )
            if (safeRect.width() <= 0 || safeRect.height() <= 0) {
                fullBitmap.recycle()
                return null
            }

            val cropped = Bitmap.createBitmap(
                fullBitmap,
                safeRect.left,
                safeRect.top,
                safeRect.width(),
                safeRect.height(),
            )
            if (cropped !== fullBitmap) fullBitmap.recycle()
            return cropped
        } finally {
            image.close()
        }
    }
}
