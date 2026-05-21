package com.example.codevicklanguagetranslatorpro.service

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen overlay that masks original text and draws translated replacements
 * pixel-perfectly aligned to the source text nodes from AccessibilityService.
 *
 * Coordinate contract:
 *   - Overlay is added with FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS, gravity TOP|START, x=0, y=0
 *   - Window size = real display size (including status bar + nav bar)
 *   - Therefore canvas (0,0) == getBoundsInScreen() (0,0) == physical screen top-left
 *   - offsetX/Y should be (0,0); measured at onLayout() for safety
 */
class InPlaceTranslationOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "InPlaceOverlay"
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Set true to draw red boxes at original node bounds (alignment check) */
    var debugMode = false
    var chatMode = false

    /** Real physical display size — set by BubbleOverlayService before addView */
    var realDisplayWidth  = 0
    var realDisplayHeight = 0

    // ── State ─────────────────────────────────────────────────────────────────

    private val translations = mutableListOf<TranslatedElement>()
    private var isScanning   = true
    private var scanLineY    = 0f
    private var offsetX      = 0
    private var offsetY      = 0
    private val locationBuf  = IntArray(2)

    data class TranslatedElement(val text: String, val originalText: String, val bounds: Rect)

    // ── Paints ────────────────────────────────────────────────────────────────

    /** Solid white — completely erases original text pixels */
    private val maskPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = false   // hard edges for perfect masking
    }

    /** Semi-transparent white card — drawn ON TOP of mask */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(2f, 0f, 1f, Color.argb(22, 0, 0, 0))
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#D1D5DB".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 0.8f
        alpha = 120
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = "#111827".toColorInt()
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = "#6366F1".toColorInt()
        strokeWidth = 4f
        style       = Paint.Style.STROKE
        alpha       = 180
    }

    // Debug paints
    private val dbgFill   = Paint().apply { color = Color.argb(70, 255, 0, 0); style = Paint.Style.FILL }
    private val dbgStroke = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val dbgLabel  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; textSize = 20f
        setShadowLayer(3f, 1f, 1f, Color.WHITE)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!isAttachedToWindow) return
        getLocationOnScreen(locationBuf)
        offsetX = locationBuf[0]
        offsetY = locationBuf[1]
        Log.d(TAG, "Offset: ($offsetX,$offsetY)  view: ${width}x$height  display: ${realDisplayWidth}x$realDisplayHeight")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startScanning() {
        isScanning = true
        scanLineY  = 0f
        translations.clear()
        invalidate()
    }

    fun updateTranslations(newTranslations: List<TranslatedElement>) {
        isScanning = false
        translations.clear()
        translations.addAll(newTranslations)
        if (isAttachedToWindow) {
            getLocationOnScreen(locationBuf)
            offsetX = locationBuf[0]
            offsetY = locationBuf[1]
        }
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isScanning) { drawScanLine(canvas); return }
        drawTranslations(canvas)
    }

    private fun drawScanLine(canvas: Canvas) {
        canvas.drawLine(40f, scanLineY, width - 40f, scanLineY, scanLinePaint)
        scanLineY = (scanLineY + 20f) % height.toFloat()
        postInvalidateDelayed(16)
    }

    private fun drawTranslations(canvas: Canvas) {
        for (item in translations) {

            // ── Convert screen coords → canvas coords ─────────────────────
            // With our window setup offsetX/Y should be 0, but subtract for safety.
            val left   = (item.bounds.left   - offsetX).toFloat()
            val top    = (item.bounds.top    - offsetY).toFloat()
            val right  = (item.bounds.right  - offsetX).toFloat()
            val bottom = (item.bounds.bottom - offsetY).toFloat()

            val nodeW = right  - left
            val nodeH = bottom - top

            // Skip degenerate or fully out-of-canvas nodes
            if (nodeW < 4f || nodeH < 4f) continue
            if (right < 0 || bottom < 0 || left > width || top > height) continue

            // ── Debug: red original-bounds box ────────────────────────────
            if (debugMode) {
                canvas.drawRect(left, top, right, bottom, dbgFill)
                canvas.drawRect(left, top, right, bottom, dbgStroke)
                canvas.drawText(item.originalText.take(14), left + 2f, top + 20f, dbgLabel)
                continue   // skip translation render in debug mode
            }

            if (chatMode) {
                drawChatTranslation(canvas, item.text, left, top, right, bottom, nodeW, nodeH)
                continue
            }

            // ── 1. MASK — erase original text completely ──────────────────
            // Extend 1px on all sides to cover sub-pixel rendering bleed
            canvas.drawRect(left - 1f, top - 1f, right + 1f, bottom + 1f, maskPaint)

            // ── 2. Font size — fit translated text into node height ────────
            //    Start at 78% of node height, shrink if it overflows
            var fontSize = (nodeH * 0.72f).coerceIn(10f, 26f)
            textPaint.textSize = fontSize

            // Layout width: allow up to full screen width so text isn't clipped
            // but anchor it to start at same x as original node
            val availableRight = width.toFloat()
            val layoutMaxW = (availableRight - left).toInt().coerceAtLeast(80)
            var layout = buildLayout(item.text, min(layoutMaxW, (nodeW * 1.35f).toInt().coerceAtLeast(100)))

            var tries = 0
            while (layout.height > nodeH * 1.5f && fontSize > 10f && tries < 6) {
                fontSize *= 0.82f
                textPaint.textSize = fontSize
                layout = buildLayout(item.text, min(layoutMaxW, (nodeW * 1.35f).toInt().coerceAtLeast(100)))
                tries++
            }

            // ── 3. Box dimensions ─────────────────────────────────────────
            // Width: at least original node width, expand right if text is longer
            // Height: at least original node height
            val hPad = (fontSize * 0.2f).coerceAtLeast(3f)
            val vPad = (fontSize * 0.08f).coerceAtLeast(2f)

            val boxW = max(layout.width.toFloat() + hPad * 2f, nodeW)
            val boxH = max(layout.height.toFloat() + vPad * 2f, nodeH)

            // Align box LEFT edge with original node left edge (not centered)
            // This matches reference app behavior where translation starts at same x
            val boxLeft   = left
            val boxTop    = top + (nodeH - boxH) / 2f   // vertically centered on node
            val boxRight  = (boxLeft + boxW).coerceAtMost(width.toFloat())
            val boxBottom = boxTop + boxH

            val boxRect = RectF(boxLeft, boxTop, boxRight, boxBottom)

            // If box expanded left of screen, clamp it
            if (boxRect.left < 0f) boxRect.offset(-boxRect.left, 0f)

            // ── 4. Draw box ───────────────────────────────────────────────
            val r = (fontSize * 0.28f).coerceIn(4f, 10f)
            canvas.drawRoundRect(boxRect, r, r, bgPaint)
            canvas.drawRoundRect(boxRect, r, r, borderPaint)

            // ── 5. Draw translated text — left-aligned inside box ─────────
            canvas.withTranslation(boxRect.left + hPad, boxTop + vPad) {
                layout.draw(this)
            }
        }
    }

    private fun drawChatTranslation(
        canvas: Canvas,
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        nodeW: Float,
        nodeH: Float
    ) {
        val fontSize = (nodeH * 0.3f).coerceIn(13f, 16f)

        val isOutgoing = left > width * 0.35f
        textPaint.color = if (isOutgoing) "#166534".toColorInt() else "#0F766E".toColorInt()
        textPaint.textSize = fontSize
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textPaint.setShadowLayer(5f, 0f, 1.5f, Color.WHITE)

        val maxTextWidth = min(width - 48, max(170, (nodeW * 0.9f).toInt())).coerceAtLeast(120)
        val maxLines = when {
            nodeH > 260f -> 4
            nodeH > 140f -> 3
            else -> 2
        }
        val layout = buildLayout(text, maxTextWidth, maxLines = maxLines)
        val textW = layout.width.toFloat()
        val textH = layout.height.toFloat()

        val textLeft = if (isOutgoing) {
            (right - textW - 18f).coerceIn(16f, width - textW - 16f)
        } else {
            (left + 18f).coerceIn(16f, width - textW - 16f)
        }

        val safeBottom = height - 150f
        val textTop = if (nodeH > 120f) {
            (top + min(42f, nodeH * 0.14f)).coerceIn(top + 8f, safeBottom - textH)
        } else {
            val preferredTop = bottom + 4f
            if (preferredTop + textH <= safeBottom) {
                preferredTop
            } else {
                (top - textH - 4f).coerceAtLeast(8f)
            }
        }

        canvas.withTranslation(textLeft, textTop) {
            layout.draw(this)
        }
        textPaint.clearShadowLayer()
        textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun buildLayout(text: String, maxWidth: Int, maxLines: Int = 6): StaticLayout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, maxWidth.coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)   // left-aligned like reference
            .setLineSpacing(1f, 1.0f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
}
