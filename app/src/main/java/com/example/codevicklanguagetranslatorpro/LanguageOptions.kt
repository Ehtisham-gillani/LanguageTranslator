package com.example.codevicklanguagetranslatorpro

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

data class LanguageOption(val label: String, val code: String)

object LanguageOptions {
    const val PREFS_NAME = "translation_preferences"
    const val KEY_SOURCE = "source_language"
    const val KEY_TARGET = "target_language"

    val all = listOf(
        LanguageOption("EN", TranslateLanguage.ENGLISH),
        LanguageOption("ES", TranslateLanguage.SPANISH),
        LanguageOption("FR", TranslateLanguage.FRENCH),
        LanguageOption("DE", TranslateLanguage.GERMAN),
        LanguageOption("UR", TranslateLanguage.URDU),
        LanguageOption("AR", TranslateLanguage.ARABIC),
        LanguageOption("HI", TranslateLanguage.HINDI)
    )

    val labels: List<String> = all.map { it.label }

    fun codeForLabel(label: String): String =
        all.firstOrNull { it.label == label }?.code ?: TranslateLanguage.ENGLISH

    fun labelForCode(code: String): String =
        all.firstOrNull { it.code == code }?.label ?: "EN"

    fun defaultSource(context: Context): String =
        context.translationPrefs().getString(KEY_SOURCE, TranslateLanguage.ENGLISH)
            ?: TranslateLanguage.ENGLISH

    fun defaultTarget(context: Context): String =
        context.translationPrefs().getString(KEY_TARGET, TranslateLanguage.URDU)
            ?: TranslateLanguage.URDU

    fun save(context: Context, sourceCode: String, targetCode: String) {
        context.translationPrefs()
            .edit()
            .putString(KEY_SOURCE, sourceCode)
            .putString(KEY_TARGET, targetCode)
            .apply()
    }

    private fun Context.translationPrefs() =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
