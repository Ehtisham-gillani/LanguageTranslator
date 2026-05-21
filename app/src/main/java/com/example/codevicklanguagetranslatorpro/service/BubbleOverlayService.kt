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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import androidx.core.content.ContextCompat
import com.example.codevicklanguagetranslatorpro.LanguageOptions
import com.example.codevicklanguagetranslatorpro.R
import com.example.codevicklanguagetranslatorpro.TextTranslationActivity
import com.example.codevicklanguagetranslatorpro.data.TranslationRepository
import com.example.codevicklanguagetranslatorpro.databinding.BubbleLayoutBinding
import com.example.codevicklanguagetranslatorpro.databinding.CropScanButtonBinding
import com.example.codevicklanguagetranslatorpro.databinding.MiniTranslatorPanelBinding
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var chatTranslationOn = false
    private var chatScanId = 0
    private val chatTranslationCache = mutableMapOf<String, String>()
    private val chatScreenChangeListener: () -> Unit = { scheduleChatTranslationRefresh() }
    private val chatRefreshRunnable = Runnable { refreshChatTranslations() }

    private val systemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            @Suppress("DEPRECATION")
            if (intent?.action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) dismissAllOverlays()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        measureRealDisplay()

        @Suppress("DEPRECATION")
        val filter = IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        ContextCompat.registerReceiver(
            this, systemDialogsReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )

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
        stopChatTranslation(removeOverlay = false)
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
                    MotionEvent.ACTION_UP   -> { if (!moved) togglePanel() }
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

    private fun togglePanel() {
        if (panelBinding != null) {
            removePanel()
        } else {
            showPanel()
        }
    }

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

        setupPanelControls()
        windowManager.addView(panelBinding?.root, params)
    }

    private fun setupPanelControls() {
        val binding = panelBinding ?: return
        updateLanguageLabels()

        binding.layoutLanguagesBubble.setOnClickListener { cycleTargetLanguage() }
        binding.btnInPlace.setOnClickListener { startInPlaceTranslation() }
        binding.btnChatTranslate.setOnClickListener { toggleChatTranslation() }
        binding.btnCropScan.setOnClickListener { showCropOverlay() }
        binding.btnBubbleClose.setOnClickListener { stopSelf() }
        updateChatTranslationLabel()
    }

    // ── In-place ──────────────────────────────────────────────────────────────

    private fun startInPlaceTranslation() {
        if (chatTranslationOn) stopChatTranslation(removeOverlay = true)
        removePanel()
        mainHandler.postDelayed({ runInPlaceTranslation() }, 250)
    }

    private fun runInPlaceTranslation() {
        val elements = ScreenTextService.getTextElementsFromScreen()
        if (elements.isEmpty()) {
            Toast.makeText(this, "No text detected on screen", Toast.LENGTH_SHORT).show()
            return
        }

        showInPlaceOverlayView(touchable = true)
        inPlaceOverlay?.startScanning()

        val result = mutableListOf<InPlaceTranslationOverlay.TranslatedElement>()
        val source = LanguageOptions.defaultSource(this)
        val target = LanguageOptions.defaultTarget(this)
        fun next(i: Int) {
            if (i >= elements.size) { inPlaceOverlay?.updateTranslations(result); return }
            val el = elements[i]
            repository.translate(el.text, source, target,
                onSuccess = { t ->
                    result.add(InPlaceTranslationOverlay.TranslatedElement(t, el.text, el.bounds))
                    next(i + 1)
                },
                onError = { next(i + 1) })
        }
        next(0)
    }

    private fun showInPlaceOverlayView(touchable: Boolean) {
        if (inPlaceOverlay != null) return

        inPlaceOverlay = InPlaceTranslationOverlay(this).also { ov ->
            ov.realDisplayWidth  = displayW
            ov.realDisplayHeight = displayH
            ov.chatMode = !touchable

            // ↓ Switch to true, run once, take screenshot, then set back to false
            ov.debugMode = false

            if (touchable) {
                ov.setOnClickListener { dismissAllOverlays(); showPanel() }
                ov.isFocusableInTouchMode = true
                ov.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismissAllOverlays(); showPanel(); true
                    } else false
                }
            }
        }

        val flags = if (touchable) {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }

        val params = WindowManager.LayoutParams(
            displayW,
            displayH,
            OVERLAY_TYPE,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
            if (!touchable) alpha = 0.78f
        }

        windowManager.addView(inPlaceOverlay, params)
        if (touchable) inPlaceOverlay?.post { inPlaceOverlay?.requestFocus() }
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

        val cropControlBinding = CropScanButtonBinding.inflate(LayoutInflater.from(ctx))
        val controls = cropControlBinding.root
        val btnParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            OVERLAY_TYPE, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 150 }

        cropControlBinding.btnConfirmCrop.setOnClickListener {
            val text = ScreenTextService.getTextFromScreen(cropOverlayView?.cropRect)
            removeCropOverlay(); showPanel()
            if (text.isNotEmpty()) startActivity(
                Intent(this, TextTranslationActivity::class.java)
                    .putExtra("EXTRA_TEXT", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        cropControlBinding.btnCancelCrop.setOnClickListener { removeCropOverlay(); showPanel() }

        windowManager.addView(cropOverlayView, ovParams)
        windowManager.addView(controls, btnParams)
        cropControlView = controls
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun dismissAllOverlays() {
        stopChatTranslation(removeOverlay = false)
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

    private fun languageDisplayText(): String {
        val source = LanguageOptions.labelForCode(LanguageOptions.defaultSource(this))
        val target = LanguageOptions.labelForCode(LanguageOptions.defaultTarget(this))
        return "$source -> $target"
    }

    private fun updateLanguageLabels() {
        panelBinding?.tvLangDisplay?.text = languageDisplayText()
        updateChatTranslationLabel()
    }

    private fun cycleTargetLanguage() {
        val currentTarget = LanguageOptions.defaultTarget(this)
        val currentIndex = LanguageOptions.all.indexOfFirst { it.code == currentTarget }.takeIf { it >= 0 } ?: 0
        val nextTarget = LanguageOptions.all[(currentIndex + 1) % LanguageOptions.all.size].code
        LanguageOptions.save(this, LanguageOptions.defaultSource(this), nextTarget)
        chatTranslationCache.clear()
        if (chatTranslationOn) scheduleChatTranslationRefresh()
        updateLanguageLabels()
        Toast.makeText(this, languageDisplayText(), Toast.LENGTH_SHORT).show()
    }

    private fun openScreenTextInTranslator() {
        val text = ScreenTextService.getTextFromScreen()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.no_text_found), Toast.LENGTH_SHORT).show()
            return
        }
        removePanel()
        startActivity(Intent(this, TextTranslationActivity::class.java)
            .putExtra("EXTRA_TEXT", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toggleChatTranslation() {
        if (chatTranslationOn) {
            stopChatTranslation(removeOverlay = true)
            updateChatTranslationLabel()
            Toast.makeText(this, "Chat translation off", Toast.LENGTH_SHORT).show()
        } else {
            startChatTranslation()
        }
    }

    private fun startChatTranslation() {
        removePanel()
        chatTranslationOn = true
        chatScanId++
        ScreenTextService.addScreenChangeListener(chatScreenChangeListener)
        showInPlaceOverlayView(touchable = false)
        inPlaceOverlay?.updateTranslations(emptyList())
        Toast.makeText(this, "Chat translation on", Toast.LENGTH_SHORT).show()
        mainHandler.postDelayed({ scheduleChatTranslationRefresh() }, 300)
    }

    private fun stopChatTranslation(removeOverlay: Boolean) {
        if (!chatTranslationOn && !removeOverlay) return
        chatTranslationOn = false
        chatScanId++
        mainHandler.removeCallbacks(chatRefreshRunnable)
        ScreenTextService.removeScreenChangeListener(chatScreenChangeListener)
        if (removeOverlay) {
            inPlaceOverlay?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
            inPlaceOverlay = null
        }
    }

    private fun scheduleChatTranslationRefresh() {
        if (!chatTranslationOn) return
        mainHandler.removeCallbacks(chatRefreshRunnable)
        mainHandler.postDelayed(chatRefreshRunnable, 450)
    }

    private fun refreshChatTranslations() {
        if (!chatTranslationOn) return

        val elements = selectChatMessageElements(ScreenTextService.getTextElementsFromScreen())
            .takeLast(12)

        if (elements.isEmpty()) {
            inPlaceOverlay?.updateTranslations(emptyList())
            return
        }

        val scanId = ++chatScanId
        val source = LanguageOptions.defaultSource(this)
        val target = LanguageOptions.defaultTarget(this)
        val translated = mutableListOf<InPlaceTranslationOverlay.TranslatedElement>()

        fun next(index: Int) {
            if (!chatTranslationOn || scanId != chatScanId) return
            if (index >= elements.size) {
                inPlaceOverlay?.updateTranslations(translated)
                return
            }

            val element = elements[index]
            val sourceText = prepareChatSourceText(element.text)
            if (sourceText.isBlank()) {
                next(index + 1)
                return
            }

            val cached = chatTranslationCache[sourceText]
            if (cached != null) {
                translated.add(InPlaceTranslationOverlay.TranslatedElement(cached, element.text, element.bounds))
                next(index + 1)
                return
            }

            repository.translate(sourceText, source, target,
                onSuccess = { result ->
                    if (!chatTranslationOn || scanId != chatScanId) return@translate
                    chatTranslationCache[sourceText] = result
                    translated.add(InPlaceTranslationOverlay.TranslatedElement(result, element.text, element.bounds))
                    next(index + 1)
                },
                onError = {
                    if (chatTranslationOn && scanId == chatScanId) next(index + 1)
                })
        }

        next(0)
    }

    private fun updateChatTranslationLabel() {
        panelBinding?.tvChatTranslate?.text =
            if (chatTranslationOn) "Chat translation off" else "Chat translation on"
    }

    private fun selectChatMessageElements(elements: List<TextElement>): List<TextElement> {
        val visibleTop = (displayH * 0.12f).toInt()
        val visibleBottom = (displayH * 0.88f).toInt()
        val minBubbleWidth = (displayW * 0.18f).toInt()
        val maxBubbleWidth = (displayW * 0.94f).toInt()

        val candidates = elements
            .filter { isLikelyChatText(it.text) }
            .filter { el ->
                val b = el.bounds
                b.top >= visibleTop &&
                        b.bottom <= visibleBottom &&
                        b.width() in minBubbleWidth..maxBubbleWidth &&
                        b.height() >= 24 &&
                        b.left >= 8 &&
                        b.right <= displayW - 8
            }
            .filterNot { isLikelySystemBanner(it) }
            .sortedWith(compareBy<TextElement> { it.bounds.top }.thenBy { it.bounds.left })

        return removeChatOverlaps(candidates)
    }

    private fun isLikelyChatText(text: String): Boolean {
        val value = text.trim()
        if (value.length !in 2..1200) return false
        if (Regex("^\\d{1,2}:\\d{2}\\s?(am|pm)?$", RegexOption.IGNORE_CASE).matches(value)) return false
        if (Regex("^[a-z0-9]{3,6}$", RegexOption.IGNORE_CASE).matches(value)) return false
        if (value.equals("message", ignoreCase = true)) return false
        if (value.equals("today", ignoreCase = true)) return false
        if (value.equals("learn more", ignoreCase = true)) return false
        if (value.equals("see details", ignoreCase = true)) return false
        if (value.contains("messages are generated by ai", ignoreCase = true)) return false
        if (value.contains("some may be inaccurate", ignoreCase = true)) return false
        return value.any { it.isLetter() }
    }

    private fun isLikelySystemBanner(element: TextElement): Boolean {
        val b = element.bounds
        val text = element.text.trim()
        val isCentered = b.left < displayW * 0.18f && b.right > displayW * 0.82f
        val isNearTop = b.top < displayH * 0.28f
        val isShortBanner = text.length < 180 && b.height() < displayH * 0.12f
        return isCentered && isNearTop && isShortBanner
    }

    private fun removeChatOverlaps(elements: List<TextElement>): List<TextElement> {
        val kept = mutableListOf<TextElement>()
        for (candidate in elements) {
            val existingIndex = kept.indexOfFirst { overlapRatio(it.bounds, candidate.bounds) > 0.72f }
            if (existingIndex == -1) {
                kept.add(candidate)
                continue
            }

            val existing = kept[existingIndex]
            val better = chooseBetterChatCandidate(existing, candidate)
            kept[existingIndex] = better
        }
        return kept
    }

    private fun chooseBetterChatCandidate(a: TextElement, b: TextElement): TextElement {
        val aScore = chatCandidateScore(a)
        val bScore = chatCandidateScore(b)
        return if (bScore > aScore) b else a
    }

    private fun chatCandidateScore(element: TextElement): Int {
        val text = element.text.trim()
        val b = element.bounds
        var score = 0
        score += minOf(text.length, 500) / 10
        score += minOf(b.width(), displayW) / 80
        score += minOf(b.height(), displayH) / 80
        if (text.contains('\n')) score += 8
        if (Regex("\\b(hi|hello|hey|weather|what|where|when|why|how)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) score += 4
        return score
    }

    private fun overlapRatio(a: android.graphics.Rect, b: android.graphics.Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val overlapArea = maxOf(0, right - left) * maxOf(0, bottom - top)
        if (overlapArea == 0) return 0f
        val smallerArea = minOf(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1)
        return overlapArea.toFloat() / smallerArea
    }

    private fun prepareChatSourceText(text: String): String {
        val cleaned = text
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.length <= 360) return cleaned

        val chunks = cleaned
            .split(Regex("(?<=[.!?؟])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.length >= 12 && it.any(Char::isLetter) }

        val selected = mutableListOf<String>()
        var total = 0
        for (chunk in chunks) {
            if (total + chunk.length > 520) break
            selected.add(chunk)
            total += chunk.length + 1
            if (selected.size >= 4) break
        }

        return selected.joinToString(" ").ifBlank { cleaned.take(520) }
    }
}
