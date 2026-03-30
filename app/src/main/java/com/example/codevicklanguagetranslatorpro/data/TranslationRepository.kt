package com.example.codevicklanguagetranslatorpro.data

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslationRepository {

    companion object {
        private const val TAG = "TranslationRepository"
    }

    private var translator: Translator? = null
    private var lastSource: String? = null
    private var lastTarget: String? = null
    private val modelManager = RemoteModelManager.getInstance()

    /**
     * Pre-downloads a language model in the background.
     */
    fun downloadModel(langCode: String, onComplete: (Boolean) -> Unit = {}) {
        Log.d(TAG, "Background download started for: $langCode")
        val model = TranslateRemoteModel.Builder(langCode).build()
        val conditions = DownloadConditions.Builder().build() // Allow cellular

        modelManager.download(model, conditions)
            .addOnSuccessListener {
                Log.d(TAG, "Model download successful: $langCode")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download failed for $langCode: ${e.message}")
                onComplete(false)
            }
    }

    /**
     * Checks if a model is already downloaded.
     */
    fun isModelDownloaded(langCode: String, callback: (Boolean) -> Unit) {
        val model = TranslateRemoteModel.Builder(langCode).build()
        modelManager.isModelDownloaded(model)
            .addOnSuccessListener { callback(it) }
            .addOnFailureListener { callback(false) }
    }

    fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        Log.d(TAG, "Request: '$text' ($sourceLang -> $targetLang)")
        
        val needsNewClient = translator == null || lastSource != sourceLang || lastTarget != targetLang
        
        val client = if (!needsNewClient) {
            translator!!
        } else {
            Log.d(TAG, "Creating new Translator client")
            translator?.close()
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            val newClient = Translation.getClient(options)
            translator = newClient
            lastSource = sourceLang
            lastTarget = targetLang
            newClient
        }

        val conditions = DownloadConditions.Builder().build()

        client.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                client.translate(text)
                    .addOnSuccessListener { translatedText ->
                        Log.d(TAG, "Success: '$translatedText'")
                        onSuccess(translatedText)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Translation fail: ${e.message}")
                        onError(e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Model download fail: ${e.message}")
                onError(e)
            }
    }

    fun close() {
        Log.d(TAG, "Closing")
        translator?.close()
        translator = null
    }
}
