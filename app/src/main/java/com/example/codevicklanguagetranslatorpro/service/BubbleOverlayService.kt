package com.example.codevicklanguagetranslatorpro.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.codevicklanguagetranslatorpro.R
import com.example.codevicklanguagetranslatorpro.TextTranslationActivity
import com.example.codevicklanguagetranslatorpro.data.TranslationRepository
import com.example.codevicklanguagetranslatorpro.databinding.BubbleLayoutBinding
import com.example.codevicklanguagetranslatorpro.databinding.MiniTranslatorPanelBinding
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlin.math.abs

class BubbleOverlayService : Service() {

    companion object {
        private const val TAG = "BubbleOverlayService"

        private val OVERLAY_TYPE
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    private lateinit var windowManager: WindowManager
    private var displayW = 0
    private var displayH = 0

    private var bubbleBinding: BubbleLayoutBinding? = null
    private var panelBinding: MiniTranslatorPanelBinding? = null
    private var cropOverlayView: CropOverlayView? = null
    private var cropControlView: View? = null
    private var inPlaceOverlay: InPlaceTranslationOverlay? = null
    private val repository = TranslationRepository()

    private val systemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) dismissAllOverlays()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        measureRealDisplay()

        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(systemDialogsReceiver, filter, Context.RECEIVER_EXPORTED)
        else
            registerReceiver(systemDialogsReceiver, filter)

        startForegroundService()
        showBubble()
    }

    private fun measureRealDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            displayW = b.width(); displayH = b.height()
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(p)
            displayW = p.x; displayH = p.y
        }
        Log.d(TAG, "Display: ${displayW}x$displayH")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(systemDialogsReceiver) } catch (_: Exception) {}
        bubbleBinding?.let { if (it.root.isAttachedToWindow) windowManager.removeView(it.root) }
        dismissAllOverlays()
        repository.close()
    }

    private fun startForegroundService() {
        val ch = "bubble_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(ch, "Translator", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, NotificationCompat.Builder(this, ch)
            .setContentTitle("Translator Active")
            .setSmallIcon(android.R.drawable.ic_menu_send).build())
    }

    // ── Bubble ────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val ctx = ContextThemeWrapper(this, R.style.Theme_CodeVickLanguageTranslatorPro)
        bubbleBinding = BubbleLayoutBinding.inflate(LayoutInflater.from(ctx))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_TYPE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 500 }

        bubbleBinding?.root?.setOnTouchListener(object : View.OnTouchListener {
            private var iX = 0; private var iY = 0
            private var iTX = 0f; private var iTY = 0f; private var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { iX = params.x; iY = params.y; iTX = e.rawX; iTY = e.rawY; moved = false }
                    MotionEvent.ACTION_UP   -> { if (!moved) showPanel() }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - iTX).toInt(); val dy = (e.rawY - iTY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            moved = true; params.x = iX + dx; params.y = iY + dy
                            windowManager.updateViewLayout(bubbleBinding?.root, params)
                        }
                    }
                }
                return true
            }
        })
        windowManager.addView(bubbleBinding?.root, params)
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panelBinding != null) return
        val ctx = ContextThemeWrapper(this, R.style.Theme_CodeVickLanguageTranslatorPro)
        panelBinding = MiniTranslatorPanelBinding.inflate(LayoutInflater.from(ctx))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        panelBinding?.btnInPlace?.setOnClickListener   { startInPlaceTranslation() }
        panelBinding?.btnCropScan?.setOnClickListener  { showCropOverlay() }
        panelBinding?.btnBubbleClose?.setOnClickListener { stopSelf() }
        windowManager.addView(panelBinding?.root, params)
    }

    // ── In-place ──────────────────────────────────────────────────────────────

    private fun startInPlaceTranslation() {
        val elements = ScreenTextService.getTextElementsFromScreen()
        if (elements.isEmpty()) {
            Toast.makeText(this, "No text detected on screen", Toast.LENGTH_SHORT).show()
            return
        }
        removePanel()
        showInPlaceOverlayView()
        inPlaceOverlay?.startScanning()

        val result = mutableListOf<InPlaceTranslationOverlay.TranslatedElement>()
        fun next(i: Int) {
            if (i >= elements.size) { inPlaceOverlay?.updateTranslations(result); return }
            val el = elements[i]
            repository.translate(el.text, TranslateLanguage.ENGLISH, TranslateLanguage.SPANISH,
                onSuccess = { t ->
                    result.add(InPlaceTranslationOverlay.TranslatedElement(t, el.text, el.bounds))
                    next(i + 1)
                },
                onError = { next(i + 1) })
        }
        next(0)
    }

    private fun showInPlaceOverlayView() {
        if (inPlaceOverlay != null) return

        inPlaceOverlay = InPlaceTranslationOverlay(this).also { ov ->
            ov.realDisplayWidth  = displayW
            ov.realDisplayHeight = displayH

            // ↓ Switch to true, run once, take screenshot, then set back to false
            ov.debugMode = false

            ov.setOnClickListener { dismissAllOverlays(); showPanel() }
            ov.isFocusableInTouchMode = true
            ov.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismissAllOverlays(); showPanel(); true
                } else false
            }
        }

        val params = WindowManager.LayoutParams(
            displayW,
            displayH,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }

        windowManager.addView(inPlaceOverlay, params)
        inPlaceOverlay?.post { inPlaceOverlay?.requestFocus() }
    }

    // ── Crop ──────────────────────────────────────────────────────────────────

    private fun showCropOverlay() {
        if (cropOverlayView != null) return
        removePanel()
        val ctx = ContextThemeWrapper(this, R.style.Theme_CodeVickLanguageTranslatorPro)
        cropOverlayView = CropOverlayView(ctx)

        val ovParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            OVERLAY_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT)

        val controls = LayoutInflater.from(ctx).inflate(R.layout.crop_scan_button, null)
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_TYPE, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 150 }

        controls.findViewById<View>(R.id.btnConfirmCrop).setOnClickListener {
            val text = ScreenTextService.getTextFromScreen(cropOverlayView?.cropRect)
            removeCropOverlay(); showPanel()
            if (!text.isNullOrEmpty()) startActivity(
                Intent(this, TextTranslationActivity::class.java)
                    .putExtra("EXTRA_TEXT", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        controls.findViewById<View>(R.id.btnCancelCrop).setOnClickListener { removeCropOverlay(); showPanel() }

        windowManager.addView(cropOverlayView, ovParams)
        windowManager.addView(controls, btnParams)
        cropControlView = controls
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dismissAllOverlays() {
        inPlaceOverlay?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        inPlaceOverlay = null
        removeCropOverlay(); removePanel()
    }

    private fun removeCropOverlay() {
        cropOverlayView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        cropControlView?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        cropOverlayView = null; cropControlView = null
    }

    private fun removePanel() {
        panelBinding?.let { if (it.root.isAttachedToWindow) windowManager.removeView(it.root) }
        panelBinding = null
    }
}