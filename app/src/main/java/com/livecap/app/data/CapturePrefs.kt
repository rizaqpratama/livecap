package com.livecap.app.data

import android.content.Context
import android.content.res.Configuration

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Target languages supported by the on-device translator, keyed by ML Kit's BCP-47 code. */
enum class TargetLanguage(val code: String) {
    ENGLISH("en"),
    INDONESIAN("id"),
}

/**
 * Small scalar settings store. Plain SharedPreferences is enough here — a handful of values,
 * no need for DataStore's Flow/coroutine machinery.
 */
object CapturePrefs {

    private const val FILE = "livecap_prefs"

    private const val KEY_REGION_PORTRAIT = "region_portrait"
    private const val KEY_REGION_LANDSCAPE = "region_landscape"
    private const val KEY_TARGET_LANGUAGE = "target_language"
    private const val KEY_CAPTURE_INTERVAL_MS = "capture_interval_ms"
    private const val KEY_WIFI_ONLY_DOWNLOAD = "wifi_only_download"

    const val DEFAULT_CAPTURE_INTERVAL_MS = 500L
    const val MIN_CAPTURE_INTERVAL_MS = 200L
    const val MAX_CAPTURE_INTERVAL_MS = 5000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun regionKey(orientation: Int) =
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) KEY_REGION_LANDSCAPE else KEY_REGION_PORTRAIT

    fun region(context: Context, orientation: Int): NormalizedRect? {
        val raw = prefs(context).getString(regionKey(orientation), null) ?: return null
        val parts = raw.split(",").mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4) return null
        return NormalizedRect(parts[0], parts[1], parts[2], parts[3])
    }

    fun setRegion(context: Context, orientation: Int, rect: NormalizedRect) {
        val raw = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        prefs(context).edit().putString(regionKey(orientation), raw).apply()
    }

    fun targetLanguage(context: Context): TargetLanguage {
        val code = prefs(context).getString(KEY_TARGET_LANGUAGE, TargetLanguage.ENGLISH.code)
        return TargetLanguage.entries.firstOrNull { it.code == code } ?: TargetLanguage.ENGLISH
    }

    fun setTargetLanguage(context: Context, language: TargetLanguage) {
        prefs(context).edit().putString(KEY_TARGET_LANGUAGE, language.code).apply()
    }

    /** Read fresh every loop iteration by the capture service, so changes apply on the next tick. */
    fun captureIntervalMs(context: Context): Long {
        val stored = prefs(context).getLong(KEY_CAPTURE_INTERVAL_MS, DEFAULT_CAPTURE_INTERVAL_MS)
        return stored.coerceIn(MIN_CAPTURE_INTERVAL_MS, MAX_CAPTURE_INTERVAL_MS)
    }

    fun setCaptureIntervalMs(context: Context, intervalMs: Long) {
        prefs(context).edit()
            .putLong(KEY_CAPTURE_INTERVAL_MS, intervalMs.coerceIn(MIN_CAPTURE_INTERVAL_MS, MAX_CAPTURE_INTERVAL_MS))
            .apply()
    }

    fun wifiOnlyDownload(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY_DOWNLOAD, true)

    fun setWifiOnlyDownload(context: Context, wifiOnly: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY_DOWNLOAD, wifiOnly).apply()
    }
}
