package com.example.codevicklanguagetranslatorpro

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.google.mlkit.nl.translate.TranslateLanguage

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
        val allLangs = listOf(
            TranslateLanguage.ENGLISH, TranslateLanguage.SPANISH, 
            TranslateLanguage.FRENCH, TranslateLanguage.GERMAN, 
            TranslateLanguage.URDU, TranslateLanguage.ARABIC, 
            TranslateLanguage.HINDI
        )
        allLangs.forEach { repository.downloadModel(it) }
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
        val languages = listOf("EN", "ES", "FR", "DE", "UR", "AR", "HI")
        val adapter = ArrayAdapter(this, R.layout.spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        binding.spinnerSource.adapter = adapter
        binding.spinnerTarget.adapter = adapter
        
        binding.spinnerSource.setSelection(0)
        binding.spinnerTarget.setSelection(4) // UR
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
                binding.etInput.setText(result)
            }
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
