package com.example.codevicklanguagetranslatorpro.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.codevicklanguagetranslatorpro.R
import com.example.codevicklanguagetranslatorpro.data.TranslationRepository
import com.example.codevicklanguagetranslatorpro.databinding.BubbleLayoutBinding
import com.example.codevicklanguagetranslatorpro.databinding.MiniTranslatorPanelBinding
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlin.math.abs

class BubbleOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleBinding: BubbleLayoutBinding? = null
    private var panelBinding: MiniTranslatorPanelBinding? = null
    private var cropOverlayView: CropOverlayView? = null
    private var cropControlView: View? = null
    private var inPlaceOverlay: InPlaceTranslationOverlay? = null
    private val repository = TranslationRepository()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundService()
        showBubble()
    }

    private fun startForegroundService() {
        val channelId = "bubble_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Translator Bubble Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bubble_active))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_CodeVickLanguageTranslatorPro)
        bubbleBinding = BubbleLayoutBinding.inflate(LayoutInflater.from(themedContext))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        bubbleBinding?.root?.setOnTouchListener(object : View.OnTouchListener {
            private var lastAction: Int = 0
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        lastAction = event.action
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            showPanel()
                        }
                        lastAction = event.action
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubbleBinding?.root, params)
                        lastAction = event.action
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleBinding?.root, params)
    }

    private fun showPanel() {
        if (panelBinding != null) return

        val themedContext = ContextThemeWrapper(this, R.style.Theme_CodeVickLanguageTranslatorPro)
        panelBinding = MiniTranslatorPanelBinding.inflate(LayoutInflater.from(themedContext))

        setupBubbleSpinners(themedContext)

        val widthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 300f, resources.displayMetrics
        ).toInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        panelBinding?.btnScanScreen?.setOnClickListener {
            val screenText = ScreenTextService.getTextFromScreen()
            if (screenText.isNotEmpty()) {
                panelBinding?.etBubbleInput?.setText(screenText)
                translateText(screenText)
            } else {
                panelBinding?.tvBubbleOutput?.text = getString(R.string.no_text_found)
            }
        }

        panelBinding?.btnCropScan?.setOnClickListener {
            showCropOverlay()
        }

        panelBinding?.btnInPlace?.setOnClickListener {
            startInPlaceTranslation()
        }

        panelBinding?.btnBubbleTranslate?.setOnClickListener {
            val text = panelBinding?.etBubbleInput?.text.toString()
            if (text.isNotEmpty()) {
                translateText(text)
            }
        }

        panelBinding?.btnBubbleClose?.setOnClickListener {
            removePanel()
        }

        panelBinding?.btnStopService?.setOnClickListener {
            stopSelf()
        }

        windowManager.addView(panelBinding?.root, params)
    }

    private fun startInPlaceTranslation() {
        val elements = ScreenTextService.getTextElementsFromScreen()
        if (elements.isEmpty()) {
            Toast.makeText(this, "No text detected on screen", Toast.LENGTH_SHORT).show()
            return
        }
        
        val source = getLangCode(panelBinding?.spinnerSourceBubble?.selectedItem?.toString() ?: "English")
        val target = getLangCode(panelBinding?.spinnerTargetBubble?.selectedItem?.toString() ?: "Spanish")
        
        removePanel()
        showInPlaceOverlay()
        inPlaceOverlay?.startScanning()
        
        val translatedList = mutableListOf<InPlaceTranslationOverlay.TranslatedElement>()
        var processedCount = 0
        
        for (element in elements) {
            repository.translate(element.text, source, target, 
                onSuccess = { translated ->
                    translatedList.add(InPlaceTranslationOverlay.TranslatedElement(translated, element.text, element.bounds))
                    processedCount++
                    if (processedCount == elements.size) {
                        inPlaceOverlay?.updateTranslations(translatedList)
                    }
                },
                onError = {
                    processedCount++
                    if (processedCount == elements.size) {
                        inPlaceOverlay?.updateTranslations(translatedList)
                    }
                }
            )
        }
    }

    private fun showInPlaceOverlay() {
        if (inPlaceOverlay != null) return
        inPlaceOverlay = InPlaceTranslationOverlay(this).apply {
            setOnClickListener { 
                if (this.isAttachedToWindow) {
                    windowManager.removeView(this)
                }
                inPlaceOverlay = null
                showPanel()
            }
        }
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(inPlaceOverlay, params)
    }

    private fun setupBubbleSpinners(context: ContextThemeWrapper) {
        val languages = listOf("English", "Spanish", "French", "German", "Urdu", "Arabic", "Hindi")
        val adapter = ArrayAdapter(context, R.layout.spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        panelBinding?.spinnerSourceBubble?.adapter = adapter
        panelBinding?.spinnerTargetBubble?.adapter = adapter
        
        panelBinding?.spinnerSourceBubble?.setSelection(0)
        panelBinding?.spinnerTargetBubble?.setSelection(1)
    }

    private fun showCropOverlay() {
        if (cropOverlayView != null) return
        removePanel()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_CodeVickLanguageTranslatorPro)
        cropOverlayView = CropOverlayView(themedContext)
        
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val controls = LayoutInflater.from(themedContext).inflate(R.layout.crop_scan_button, null)
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        controls.findViewById<View>(R.id.btnConfirmCrop).setOnClickListener {
            val text = ScreenTextService.getTextFromScreen(cropOverlayView?.cropRect)
            removeCropOverlay()
            showPanel()
            if (text.isNotEmpty()) {
                panelBinding?.etBubbleInput?.setText(text)
                translateText(text)
            }
        }

        controls.findViewById<View>(R.id.btnCancelCrop).setOnClickListener {
            removeCropOverlay()
            showPanel()
        }

        windowManager.addView(cropOverlayView, params)
        windowManager.addView(controls, btnParams)
        cropControlView = controls
    }

    private fun removeCropOverlay() {
        cropOverlayView?.let { 
            if (it.isAttachedToWindow) windowManager.removeView(it) 
        }
        cropControlView?.let { 
            if (it.isAttachedToWindow) windowManager.removeView(it) 
        }
        cropOverlayView = null
        cropControlView = null
    }

    private fun translateText(text: String) {
        val sourceStr = panelBinding?.spinnerSourceBubble?.selectedItem?.toString() ?: "English"
        val targetStr = panelBinding?.spinnerTargetBubble?.selectedItem?.toString() ?: "Spanish"
        
        repository.translate(
            text,
            getLangCode(sourceStr),
            getLangCode(targetStr),
            onSuccess = { panelBinding?.tvBubbleOutput?.text = it },
            onError = { panelBinding?.tvBubbleOutput?.text = getString(R.string.error_prefix, it.message) }
        )
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

    private fun removePanel() {
        panelBinding?.let {
            if (it.root.isAttachedToWindow) {
                windowManager.removeView(it.root)
            }
            panelBinding = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleBinding?.let { 
            if (it.root.isAttachedToWindow) {
                windowManager.removeView(it.root) 
            }
        }
        removePanel()
        removeCropOverlay()
        inPlaceOverlay?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        repository.close()
    }
}
