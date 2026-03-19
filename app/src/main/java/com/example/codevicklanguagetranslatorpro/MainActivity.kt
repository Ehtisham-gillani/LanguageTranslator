package com.example.codevicklanguagetranslatorpro

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.example.codevicklanguagetranslatorpro.databinding.ActivityMainBinding
import com.example.codevicklanguagetranslatorpro.service.BubbleOverlayService
import com.example.codevicklanguagetranslatorpro.service.ScreenTextService
import com.example.codevicklanguagetranslatorpro.ui.TranslationViewModel
import com.google.mlkit.nl.translate.TranslateLanguage

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TranslationViewModel by viewModels()

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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        setupSpinners()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        if (isAccessibilityServiceEnabled()) {
            binding.btnStartBubble.text = getString(R.string.bubble_active)
            binding.btnStartBubble.alpha = 1.0f
        } else {
            binding.btnStartBubble.text = getString(R.string.enable_accessibility)
        }
    }

    private fun setupSpinners() {
        val languages = listOf("English", "Spanish", "French", "German", "Urdu", "Arabic", "Hindi")
        val adapter = ArrayAdapter(this, R.layout.spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        binding.spinnerSource.adapter = adapter
        binding.spinnerTarget.adapter = adapter
        
        binding.spinnerSource.setSelection(0)
        binding.spinnerTarget.setSelection(1)
    }

    private fun setupListeners() {
        binding.btnTranslate.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) {
                binding.etInput.error = getString(R.string.please_enter_text)
                return@setOnClickListener
            }
            
            val source = getLangCode(binding.spinnerSource.selectedItem.toString())
            val target = getLangCode(binding.spinnerTarget.selectedItem.toString())
            viewModel.translate(text, source, target)
        }

        binding.btnStartBubble.setOnClickListener {
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
        Toast.makeText(this, getString(R.string.accessibility_toast), Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun observeViewModel() {
        viewModel.translatedText.observe(this) { result ->
            binding.tvOutput.text = result
        }

        viewModel.error.observe(this) { errorMsg ->
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLangCode(lang: String): String {
        return when (lang) {
            "English" -> TranslateLanguage.ENGLISH
            "Spanish" -> TranslateLanguage.SPANISH
            "French" -> TranslateLanguage.FRENCH
            "German" -> TranslateLanguage.GERMAN
            "Urdu" -> TranslateLanguage.URDU
            "Arabic" -> TranslateLanguage.ARABIC
            "Hindi" -> TranslateLanguage.HINDI
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
}
