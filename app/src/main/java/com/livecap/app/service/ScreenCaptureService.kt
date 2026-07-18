package com.livecap.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.livecap.app.MainActivity
import com.livecap.app.R
import com.livecap.app.capture.FrameDiffUtil
import com.livecap.app.capture.FrameGrabber
import com.livecap.app.data.CapturePrefs
import com.livecap.app.data.NormalizedRect
import com.livecap.app.ocr.OcrTranslationPipeline
import com.livecap.app.overlay.CaptionOverlay
import com.livecap.app.overlay.HandleOverlay
import com.livecap.app.overlay.OverlayPermissionHelper
import com.livecap.app.overlay.RegionSelectorOverlay
import com.livecap.app.util.DisplayMetricsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.livecap.app.action.START"
        const val ACTION_PAUSE = "com.livecap.app.action.PAUSE"
        const val ACTION_RESUME = "com.livecap.app.action.RESUME"
        const val ACTION_STOP = "com.livecap.app.action.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val NOTIFICATION_CHANNEL_ID = "livecap_capture"
        private const val NOTIFICATION_ID = 1001

        @Volatile var isRunning: Boolean = false
            private set

        @Volatile var isPaused: Boolean = false
            private set

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun actionIntent(context: Context, action: String): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { this.action = action }
    }

    // Written from main-thread service callbacks (onStartCommand, onConfigurationChanged,
    // MediaProjection.Callback), read from the Dispatchers.Default capture loop — @Volatile for
    // cross-thread visibility.
    @Volatile private var mediaProjection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var pipeline: OcrTranslationPipeline? = null
    @Volatile private var lastHash: Long? = null

    private val captionOverlay by lazy { CaptionOverlay(this) }
    private val handleOverlay by lazy { HandleOverlay(this) }
    private val regionSelectorOverlay by lazy { RegionSelectorOverlay(this) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loopJob: Job? = null
    private var tornDown = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            teardown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP -> teardown()
            else -> {
                // No projection data to resume with (e.g. process was killed and restarted by the
                // system) — there is no way to silently recover a MediaProjection session, so just
                // stop rather than running in a broken state.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            stopSelf()
            return
        }

        tornDown = false
        startForeground(NOTIFICATION_ID, buildNotification(paused = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (e: SecurityException) {
            // Consent token already used/invalid — no way to resume silently, must re-prompt.
            null
        }
        if (projection == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        setupVirtualDisplay(projection)
        setupPipeline()
        setupOverlays()

        isRunning = true
        isPaused = false
        startLoop()
    }

    private fun setupVirtualDisplay(projection: MediaProjection) {
        val size = DisplayMetricsUtil.realSize(this)
        val dpi = DisplayMetricsUtil.densityDpi(this)

        val reader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "LiveCapCapture",
            size.x, size.y, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )
    }

    private fun setupPipeline() {
        pipeline = OcrTranslationPipeline(CapturePrefs.targetLanguage(this))
    }

    private fun setupOverlays() {
        captionOverlay.show()
        handleOverlay.show(onTap = { toggleRegionSelector() })
        repositionCaptionOverlay()
    }

    private fun toggleRegionSelector() {
        if (regionSelectorOverlay.isShowing) {
            regionSelectorOverlay.hide()
        } else {
            regionSelectorOverlay.show(onConfirm = { repositionCaptionOverlay() })
        }
    }

    private fun currentCropRectPx(): Rect? {
        val size = DisplayMetricsUtil.realSize(this)
        val orientation = resources.configuration.orientation
        val normalized: NormalizedRect = CapturePrefs.region(this, orientation) ?: return null
        return Rect(
            (normalized.left * size.x).toInt(),
            (normalized.top * size.y).toInt(),
            (normalized.right * size.x).toInt(),
            (normalized.bottom * size.y).toInt(),
        )
    }

    private fun repositionCaptionOverlay() {
        val rectPx = currentCropRectPx() ?: return
        val size = DisplayMetricsUtil.realSize(this)
        captionOverlay.reposition(RectF(rectPx), size.x, size.y)
    }

    private fun setPaused(paused: Boolean) {
        isPaused = paused
        if (paused) {
            mainHandler.post { captionOverlay.update(null) }
        }
        updateNotification()
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (!isPaused) tick()
                delay(CapturePrefs.captureIntervalMs(applicationContext))
            }
        }
    }

    private suspend fun tick() {
        if (!OverlayPermissionHelper.canDrawOverlays(this)) {
            withContext(Dispatchers.Main) { teardown() }
            return
        }

        val reader = imageReader ?: return
        val cropRect = currentCropRectPx() ?: return
        val bitmap = try {
            FrameGrabber.grabCroppedBitmap(reader, cropRect)
        } catch (e: Exception) {
            null
        } ?: return

        val hash = FrameDiffUtil.aHash(bitmap)
        if (!FrameDiffUtil.hasChanged(lastHash, hash)) {
            bitmap.recycle()
            return
        }
        lastHash = hash

        val translated = pipeline?.translateCaption(bitmap)
        bitmap.recycle()

        withContext(Dispatchers.Main) { captionOverlay.update(translated) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val display = virtualDisplay ?: return
        val projection = mediaProjection ?: return

        val size = DisplayMetricsUtil.realSize(this)
        val dpi = DisplayMetricsUtil.densityDpi(this)

        val oldReader = imageReader
        val newReader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2)
        imageReader = newReader

        display.resize(size.x, size.y, dpi)
        display.setSurface(newReader.surface)
        oldReader?.close()

        lastHash = null
        repositionCaptionOverlay()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isPaused))
    }

    private fun buildNotification(paused: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseResumeAction = if (paused) {
            NotificationCompat.Action(
                0, getString(R.string.notification_action_resume),
                PendingIntent.getService(
                    this, 1, actionIntent(this, ACTION_RESUME),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            NotificationCompat.Action(
                0, getString(R.string.notification_action_pause),
                PendingIntent.getService(
                    this, 2, actionIntent(this, ACTION_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }

        val stopAction = NotificationCompat.Action(
            0, getString(R.string.notification_action_stop),
            PendingIntent.getService(
                this, 3, actionIntent(this, ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (paused) {
                    getString(R.string.notification_text_paused)
                } else {
                    getString(R.string.notification_text_active, CapturePrefs.captureIntervalMs(this).toInt())
                },
            )
            .setContentIntent(contentIntent)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /**
     * Single teardown path — called from explicit Stop, MediaProjection.Callback.onStop(),
     * a detected permission revocation, or onDestroy — so there's exactly one place that can
     * leak capture resources if it's wrong.
     */
    private fun teardown() {
        if (tornDown) return
        tornDown = true

        loopJob?.cancel()
        loopJob = null

        runCatching { imageReader?.close() }
        imageReader = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        regionSelectorOverlay.hide()
        captionOverlay.hide()
        handleOverlay.hide()

        pipeline?.close()
        pipeline = null

        isRunning = false
        isPaused = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
