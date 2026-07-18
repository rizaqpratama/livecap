package com.livecap.app.capture

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Cheap average-hash frame comparison, used to skip OCR+translate when the caption region hasn't
 * visibly changed since the last tick — most ticks either have no caption or an unchanged one.
 */
object FrameDiffUtil {

    private const val HASH_SIZE = 8
    const val DEFAULT_CHANGE_THRESHOLD = 3

    fun aHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        val luminances = IntArray(HASH_SIZE * HASH_SIZE)
        var sum = 0
        for (yPos in 0 until HASH_SIZE) {
            for (xPos in 0 until HASH_SIZE) {
                val pixel = small.getPixel(xPos, yPos)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                luminances[yPos * HASH_SIZE + xPos] = luminance
                sum += luminance
            }
        }
        if (small !== bitmap) small.recycle()
        val mean = sum / luminances.size
        var hash = 0L
        for (i in luminances.indices) {
            if (luminances[i] > mean) hash = hash or (1L shl i)
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun hasChanged(previous: Long?, current: Long, threshold: Int = DEFAULT_CHANGE_THRESHOLD): Boolean {
        if (previous == null) return true
        return hammingDistance(previous, current) > threshold
    }
}
