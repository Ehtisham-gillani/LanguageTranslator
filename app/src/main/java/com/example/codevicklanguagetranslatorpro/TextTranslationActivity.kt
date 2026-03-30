package com.example.codevicklanguagetranslatorpro

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.codevicklanguagetranslatorpro.databinding.ActivityTextTranslationBinding
import com.example.codevicklanguagetranslatorpro.ui.TranslationViewModel
import com.google.mlkit.nl.translate.TranslateLanguage

class TextTranslationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextTranslationBinding
    private val viewModel: TranslationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupListeners()
        observeViewModel()

        // If text was passed from MainActivity
        val initialText = intent.getStringExtra("EXTRA_TEXT")
        if (!initialText.isNullOrEmpty()) {
            binding.etInputDetail.setText(initialText)
            performTranslation()
        }
    }

    private fun setupSpinners() {
        val languages = listOf("EN", "ES", "FR", "DE", "UR", "AR", "HI")
        val adapter = ArrayAdapter(this, R.layout.spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        binding.spinnerSourceDetail.adapter = adapter
        binding.spinnerTargetDetail.adapter = adapter
        
        binding.spinnerSourceDetail.setSelection(0)
        binding.spinnerTargetDetail.setSelection(4) // UR
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }

        binding.btnClear.setOnClickListener {
            binding.etInputDetail.text.clear()
            binding.tvOutputDetail.text = ""
        }

        binding.etInputDetail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    performTranslation()
                } else {
                    binding.tvOutputDetail.text = ""
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCopy.setOnClickListener {
            val text = binding.tvOutputDetail.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Translated Text", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performTranslation() {
        val text = binding.etInputDetail.text.toString().trim()
        if (text.isEmpty()) return

        val source = getLangCode(binding.spinnerSourceDetail.selectedItem.toString())
        val target = getLangCode(binding.spinnerTargetDetail.selectedItem.toString())
        viewModel.translate(text, source, target)
    }

    private fun observeViewModel() {
        viewModel.translatedText.observe(this) { result ->
            binding.tvOutputDetail.text = result
        }
    }

    private fun getLangCode(lang: String): String {
        return when (lang) {
            "EN" -> TranslateLanguage.ENGLISH
            "ES" -> TranslateLanguage.SPANISH
            "FR" -> TranslateLanguage.FRENCH
            "DE" -> TranslateLanguage.GERMAN
            "UR" -> TranslateLanguage.URDU
            "AR" -> TranslateLanguage.ARABIC
            "HI" -> TranslateLanguage.HINDI
            else -> TranslateLanguage.ENGLISH
        }
    }
}
