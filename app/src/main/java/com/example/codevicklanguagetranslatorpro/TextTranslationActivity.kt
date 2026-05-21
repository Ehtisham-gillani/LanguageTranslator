package com.example.codevicklanguagetranslatorpro

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.codevicklanguagetranslatorpro.databinding.ActivityTextTranslationBinding
import com.example.codevicklanguagetranslatorpro.ui.TranslationViewModel

class TextTranslationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextTranslationBinding
    private val viewModel: TranslationViewModel by viewModels()
    private val translationHandler = Handler(Looper.getMainLooper())
    private val pendingTranslation = Runnable { performTranslation() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTextTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupListeners()
        observeViewModel()

        val initialText = intent.getStringExtra("EXTRA_TEXT")
        if (!initialText.isNullOrEmpty()) {
            binding.etInputDetail.setText(initialText)
            performTranslation()
        }
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(this, R.layout.spinner_item, LanguageOptions.labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        binding.spinnerSourceDetail.adapter = adapter
        binding.spinnerTargetDetail.adapter = adapter
        
        binding.spinnerSourceDetail.setSelection(indexForCode(LanguageOptions.defaultSource(this)))
        binding.spinnerTargetDetail.setSelection(indexForCode(LanguageOptions.defaultTarget(this)))

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveSelectedLanguages()
                scheduleTranslation()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.spinnerSourceDetail.onItemSelectedListener = listener
        binding.spinnerTargetDetail.onItemSelectedListener = listener
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }

        binding.btnClear.setOnClickListener {
            translationHandler.removeCallbacks(pendingTranslation)
            binding.etInputDetail.text.clear()
            binding.tvOutputDetail.text = ""
        }

        binding.etInputDetail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    scheduleTranslation()
                } else {
                    translationHandler.removeCallbacks(pendingTranslation)
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
        translationHandler.removeCallbacks(pendingTranslation)
        val text = binding.etInputDetail.text.toString().trim()
        if (text.isEmpty()) return

        val source = getLangCode(binding.spinnerSourceDetail.selectedItem.toString())
        val target = getLangCode(binding.spinnerTargetDetail.selectedItem.toString())
        saveSelectedLanguages()
        binding.tvOutputDetail.text = "Translating..."
        viewModel.translate(text, source, target)
    }

    private fun observeViewModel() {
        viewModel.translatedText.observe(this) { result ->
            binding.tvOutputDetail.text = result
        }
        viewModel.error.observe(this) { message ->
            binding.tvOutputDetail.text = ""
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun scheduleTranslation() {
        translationHandler.removeCallbacks(pendingTranslation)
        translationHandler.postDelayed(pendingTranslation, 450)
    }

    private fun getLangCode(lang: String): String = LanguageOptions.codeForLabel(lang)

    private fun indexForCode(code: String): Int =
        LanguageOptions.all.indexOfFirst { it.code == code }.takeIf { it >= 0 } ?: 0

    private fun saveSelectedLanguages() {
        val source = getLangCode(binding.spinnerSourceDetail.selectedItem.toString())
        val target = getLangCode(binding.spinnerTargetDetail.selectedItem.toString())
        LanguageOptions.save(this, source, target)
    }

    override fun onDestroy() {
        translationHandler.removeCallbacks(pendingTranslation)
        super.onDestroy()
    }
}
