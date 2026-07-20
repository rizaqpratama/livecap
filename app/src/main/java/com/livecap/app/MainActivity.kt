package com.livecap.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.livecap.app.data.CapturePrefs
import com.livecap.app.data.TargetLanguage
import com.livecap.app.databinding.ActivityMainBinding
import com.livecap.app.ocr.ModelDownloadHelper
import com.livecap.app.overlay.OverlayPermissionHelper
import com.livecap.app.overlay.RegionSelectorOverlay
import com.livecap.app.service.ScreenCaptureService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var regionSelectorOverlay: RegionSelectorOverlay

    private val intervalPresetsMs = longArrayOf(250, 500, 1000, 2000)

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshStatus() }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshStatus() }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = ScreenCaptureService.startIntent(this, result.resultCode, data)
            ContextCompat.startForegroundService(this, intent)
        } else {
            Toast.makeText(this, R.string.status_capture_stopped, Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        regionSelectorOverlay = RegionSelectorOverlay(this)

        setupLanguageSelector()
        setupIntervalSpinner()
        setupWifiOnlyToggle()

        binding.btnGrantOverlay.setOnClickListener {
            overlayPermissionLauncher.launch(OverlayPermissionHelper.requestOverlayPermissionIntent(this))
        }

        binding.btnDownloadModels.setOnClickListener { downloadModels() }

        binding.btnAdjustRegion.setOnClickListener { openRegionSelector() }

        binding.btnStartStopCapture.setOnClickListener { onStartStopCaptureClicked() }

        binding.btnPauseResumeTranslation.setOnClickListener { onPauseResumeClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupLanguageSelector() {
        val current = CapturePrefs.targetLanguage(this)
        binding.rgLanguage.check(
            if (current == TargetLanguage.INDONESIAN) R.id.rbIndonesian else R.id.rbEnglish,
        )
        binding.rgLanguage.setOnCheckedChangeListener { _, checkedId ->
            val language = if (checkedId == R.id.rbIndonesian) TargetLanguage.INDONESIAN else TargetLanguage.ENGLISH
            CapturePrefs.setTargetLanguage(this, language)
            if (ScreenCaptureService.isRunning) {
                val label = getString(
                    if (language == TargetLanguage.INDONESIAN) R.string.language_indonesian else R.string.language_english,
                )
                Toast.makeText(this, getString(R.string.toast_language_restart_required, label), Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }
    }

    private fun setupIntervalSpinner() {
        val labels = intervalPresetsMs.map { "${it} ms" }
        binding.spinnerInterval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val currentIndex = intervalPresetsMs.indexOf(CapturePrefs.captureIntervalMs(this))
            .let { if (it == -1) intervalPresetsMs.indexOf(CapturePrefs.DEFAULT_CAPTURE_INTERVAL_MS) else it }
        binding.spinnerInterval.setSelection(currentIndex.coerceAtLeast(0))

        binding.spinnerInterval.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                CapturePrefs.setCaptureIntervalMs(this@MainActivity, intervalPresetsMs[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun setupWifiOnlyToggle() {
        binding.cbWifiOnly.isChecked = CapturePrefs.wifiOnlyDownload(this)
        binding.cbWifiOnly.setOnCheckedChangeListener { _, checked ->
            CapturePrefs.setWifiOnlyDownload(this, checked)
        }
    }

    private fun downloadModels() {
        binding.tvModelsStatus.text = getString(R.string.status_models_downloading)
        binding.btnDownloadModels.isEnabled = false
        val language = CapturePrefs.targetLanguage(this)
        val wifiOnly = CapturePrefs.wifiOnlyDownload(this)
        lifecycleScope.launch {
            runCatching { ModelDownloadHelper.downloadTranslationModel(language, wifiOnly) }
            binding.btnDownloadModels.isEnabled = true
            refreshStatus()
        }
    }

    private fun openRegionSelector() {
        if (!OverlayPermissionHelper.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_grant_overlay_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (regionSelectorOverlay.isShowing) {
            regionSelectorOverlay.hide()
        } else {
            regionSelectorOverlay.show(onConfirm = {})
        }
    }

    private fun onStartStopCaptureClicked() {
        if (ScreenCaptureService.isRunning) {
            startService(ScreenCaptureService.actionIntent(this, ScreenCaptureService.ACTION_STOP))
            refreshStatus()
            return
        }

        if (!OverlayPermissionHelper.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_grant_overlay_first, Toast.LENGTH_SHORT).show()
            overlayPermissionLauncher.launch(OverlayPermissionHelper.requestOverlayPermissionIntent(this))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun onPauseResumeClicked() {
        if (!ScreenCaptureService.isRunning) return
        val action = if (ScreenCaptureService.isPaused) {
            ScreenCaptureService.ACTION_RESUME
        } else {
            ScreenCaptureService.ACTION_PAUSE
        }
        startService(ScreenCaptureService.actionIntent(this, action))
        refreshStatus()
    }

    private fun refreshStatus() {
        binding.tvOverlayStatus.text = getString(
            if (OverlayPermissionHelper.canDrawOverlays(this)) {
                R.string.status_overlay_permission_granted
            } else {
                R.string.status_overlay_permission_missing
            },
        )

        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        binding.tvNotificationStatus.text = getString(
            if (notificationsEnabled) R.string.status_notification_permission_granted else R.string.status_notification_permission_missing,
        )

        val running = ScreenCaptureService.isRunning
        val paused = ScreenCaptureService.isPaused
        binding.tvCaptureStatus.text = getString(
            if (running) R.string.status_capture_running else R.string.status_capture_stopped,
        )
        binding.btnStartStopCapture.text = getString(
            if (running) R.string.button_stop_capture else R.string.button_start_capture,
        )
        binding.btnPauseResumeTranslation.isEnabled = running
        binding.btnPauseResumeTranslation.text = getString(
            if (paused) R.string.button_resume_translation else R.string.button_pause_translation,
        )

        lifecycleScope.launch {
            val downloaded = runCatching {
                ModelDownloadHelper.isModelDownloaded(CapturePrefs.targetLanguage(this@MainActivity))
            }.getOrDefault(false)
            binding.tvModelsStatus.text = getString(
                if (downloaded) R.string.status_models_ready else R.string.status_models_missing,
            )
        }
    }
}
