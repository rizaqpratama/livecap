package com.livecap.app.ocr

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.livecap.app.data.TargetLanguage
import kotlinx.coroutines.tasks.await

/**
 * First-run / Settings-screen trigger for the one-time on-device model download, and a way to
 * check current state so the UI can show "ready" vs "download needed" instead of guessing.
 */
object ModelDownloadHelper {

    private fun targetCode(targetLanguage: TargetLanguage): String = when (targetLanguage) {
        TargetLanguage.ENGLISH -> TranslateLanguage.ENGLISH
        TargetLanguage.INDONESIAN -> TranslateLanguage.INDONESIAN
    }

    suspend fun isModelDownloaded(targetLanguage: TargetLanguage): Boolean {
        val downloaded = RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
        return downloaded.any { it.language == targetCode(targetLanguage) }
    }

    /** Downloads the Chinese-to-target translation model; the Chinese OCR model ships bundled. */
    suspend fun downloadTranslationModel(targetLanguage: TargetLanguage, wifiOnly: Boolean) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(targetCode(targetLanguage))
                .build(),
        )
        try {
            val conditions = DownloadConditions.Builder().apply {
                if (wifiOnly) requireWifi()
            }.build()
            translator.downloadModelIfNeeded(conditions).await()
        } finally {
            translator.close()
        }
    }
}
