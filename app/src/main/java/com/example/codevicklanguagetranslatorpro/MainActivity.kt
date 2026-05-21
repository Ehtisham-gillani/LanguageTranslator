package com.example.codevicklanguagetranslatorpro

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.net.toUri
import com.example.codevicklanguagetranslatorpro.data.TranslationRepository
import com.example.codevicklanguagetranslatorpro.databinding.ActivityMainBinding
import com.example.codevicklanguagetranslatorpro.service.BubbleOverlayService
import com.example.codevicklanguagetranslatorpro.service.ScreenTextService
import com.example.codevicklanguagetranslatorpro.ui.TranslationViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TranslationViewModel by viewModels()
    private val repository = TranslationRepository()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startBubbleService()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupListeners()
        observeViewModel()
        
        // Background download all models on startup to ensure zero delay
        downloadAllModels()
    }

    private fun downloadAllModels() {
        val source = LanguageOptions.defaultSource(this)
        val target = LanguageOptions.defaultTarget(this)
        repository.downloadModel(source)
        repository.downloadModel(target)
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        if (isAccessibilityServiceEnabled()) {
            Log.d("MainActivity", "Accessibility Service is active")
        }
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(this, R.layout.spinner_item, LanguageOptions.labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        binding.spinnerSource.adapter = adapter
        binding.spinnerTarget.adapter = adapter
        
        binding.spinnerSource.setSelection(indexForCode(LanguageOptions.defaultSource(this)))
        binding.spinnerTarget.setSelection(indexForCode(LanguageOptions.defaultTarget(this)))

        val saveSelectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveSelectedLanguages()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.spinnerSource.onItemSelectedListener = saveSelectionListener
        binding.spinnerTarget.onItemSelectedListener = saveSelectionListener
    }

    private fun setupListeners() {
        val openTextTranslation = {
            val intent = Intent(this, TextTranslationActivity::class.java)
            val currentText = binding.etInput.text.toString()
            intent.putExtra("EXTRA_TEXT", currentText)
            val options = ActivityOptionsCompat.makeCustomAnimation(
                this, android.R.anim.fade_in, android.R.anim.fade_out
            )
            startActivity(intent, options.toBundle())
        }

        binding.cardInput.setOnClickListener { openTextTranslation() }
        binding.etInput.isFocusable = false
        binding.etInput.isClickable = true
        binding.etInput.setOnClickListener { openTextTranslation() }

        binding.btnTranslate.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val source = getLangCode(binding.spinnerSource.selectedItem.toString())
                val target = getLangCode(binding.spinnerTarget.selectedItem.toString())
                saveSelectedLanguages()
                viewModel.translate(text, source, target)
            } else {
                openTextTranslation()
            }
        }

        binding.btnStartBubbleMain.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                openAccessibilitySettings()
            } else {
                checkOverlayPermission()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${ScreenTextService::class.java.canonicalName}"
        val enabled = Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (enabled == 1) {
            val settingValue = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return settingValue?.contains(service) == true
        }
        return false
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Please enable Accessibility Service for Screen Translation", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun observeViewModel() {
        viewModel.translatedText.observe(this) { result ->
            if (result.isNotEmpty()) {
                binding.tvOutput.text = result
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
        }
        viewModel.error.observe(this) { message ->
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLangCode(lang: String): String = LanguageOptions.codeForLabel(lang)

    private fun indexForCode(code: String): Int =
        LanguageOptions.all.indexOfFirst { it.code == code }.takeIf { it >= 0 } ?: 0

    private fun saveSelectedLanguages() {
        val source = getLangCode(binding.spinnerSource.selectedItem.toString())
        val target = getLangCode(binding.spinnerTarget.selectedItem.toString())
        LanguageOptions.save(this, source, target)
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            startBubbleService()
        }
    }

    private fun startBubbleService() {
        val intent = Intent(this, BubbleOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.close()
    }
}
