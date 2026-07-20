package com.livecap.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.livecap.app.data.TargetLanguage
import kotlinx.coroutines.tasks.await

/**
 * One instance per active capture session, held by ScreenCaptureService. Recreate (close the old,
 * build a new one) if the target language changes, since Translator is bound to a language pair
 * at construction time.
 */
class OcrTranslationPipeline(targetLanguage: TargetLanguage) {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(
                when (targetLanguage) {
                    TargetLanguage.ENGLISH -> TranslateLanguage.ENGLISH
                    TargetLanguage.INDONESIAN -> TranslateLanguage.INDONESIAN
                },
            )
            .build(),
    )

    /** Returns the translated caption, or null if no text was detected or translation failed. */
    suspend fun translateCaption(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val rawText = try {
            recognizer.process(image).await().text.trim()
        } catch (e: Exception) {
            return null
        }
        if (rawText.isEmpty()) return null

        return try {
            translator.downloadModelIfNeeded().await()
            translator.translate(rawText).await()
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        recognizer.close()
        translator.close()
    }
}
