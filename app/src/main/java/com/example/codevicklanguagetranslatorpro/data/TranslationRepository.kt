package com.example.codevicklanguagetranslatorpro.data

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslationRepository {

    private var translator: Translator? = null

    fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()

        val client = Translation.getClient(options)
        translator = client

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        client.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                client.translate(text)
                    .addOnSuccessListener { translatedText ->
                        onSuccess(translatedText)
                    }
                    .addOnFailureListener { exception ->
                        onError(exception)
                    }
            }
            .addOnFailureListener { exception ->
                onError(exception)
            }
    }

    fun close() {
        translator?.close()
    }
}
