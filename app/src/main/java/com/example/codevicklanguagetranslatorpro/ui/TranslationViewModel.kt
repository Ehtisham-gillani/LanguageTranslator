package com.example.codevicklanguagetranslatorpro.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.codevicklanguagetranslatorpro.data.TranslationRepository

class TranslationViewModel : ViewModel() {

    private val repository = TranslationRepository()

    private val _translatedText = MutableLiveData<String>()
    val translatedText: LiveData<String> = _translatedText

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    fun translate(text: String, sourceLang: String, targetLang: String) {
        if (text.isBlank()) {
            _error.value = "Please enter text"
            return
        }

        repository.translate(
            text,
            sourceLang,
            targetLang,
            onSuccess = { result ->
                _translatedText.postValue(result)
            },
            onError = { exception ->
                _error.postValue(exception.message ?: "Translation failed")
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }
}
