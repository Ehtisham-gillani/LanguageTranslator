package com.example.codevicklanguagetranslatorpro.service

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class InPlaceTranslationOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val translations = mutableListOf<TranslatedElement>()
    private var isScanning = true
    private var scanLineY = 0f
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2937") 
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6366F1")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    // Pre-allocate objects to avoid allocations in onDraw
    private val drawRect = RectF()
    private val tempLocation = IntArray(2)
    private val tempBounds = Rect()

    data class TranslatedElement(val text: String, val originalText: String, val bounds: Rect)

    fun startScanning() {
        isScanning = true
        scanLineY = 0f
        translations.clear()
        invalidate()
    }

    fun updateTranslations(newTranslations: List<TranslatedElement>) {
        isScanning = false
        translations.clear()
        translations.addAll(newTranslations)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (isScanning) {
            canvas.drawLine(0f, scanLineY, width.toFloat(), scanLineY, scanLinePaint)
            scanLineY += 35f
            if (scanLineY > height) scanLineY = 0f
            postInvalidateDelayed(10)
            return
        }

        // Get this view's location on screen to map Accessibility screen coordinates to Canvas coordinates
        getLocationOnScreen(tempLocation)
        val offsetX = tempLocation[0]
        val offsetY = tempLocation[1]

        for (item in translations) {
            tempBounds.set(item.bounds)
            // Accessibility bounds are in screen coordinates. 
            // Offset them to be relative to this View's top-left.
            tempBounds.offset(-offsetX, -offsetY)

            val nodeH = tempBounds.height().toFloat()
            val nodeW = tempBounds.width().toFloat()
            
            // Set text size proportionally to original node height
            textPaint.textSize = (nodeH * 0.75f).coerceIn(22f, 58f)
            
            val originalTextW = textPaint.measureText(item.originalText)
            val translatedTextW = textPaint.measureText(item.text)
            
            // Heuristic to decide horizontal alignment
            // If the node is significantly wider than the text, it's likely left or right aligned.
            val isWideNode = nodeW > originalTextW * 1.3f
            
            val drawCenterX: Float = if (isWideNode) {
                // Common for list items/settings: left-aligned with some padding
                val estimatedPadding = 24f
                val leftTarget = tempBounds.left + estimatedPadding + (translatedTextW / 2f)
                val minX = tempBounds.left + translatedTextW/2f
                val maxX = tempBounds.right - translatedTextW/2f
                
                if (minX <= maxX) {
                    leftTarget.coerceIn(minX, maxX)
                } else {
                    tempBounds.centerX().toFloat()
                }
            } else {
                // Tight nodes (buttons/icons) usually have centered text
                tempBounds.centerX().toFloat()
            }
            
            val drawCenterY = tempBounds.centerY().toFloat()
            
            // Create a clean background box to hide original text
            // Wrap content style width and height with small padding
            val boxW = translatedTextW + 16f
            val textHeight = textPaint.descent() - textPaint.ascent()
            val boxH = textHeight + 8f
            
            drawRect.set(
                drawCenterX - (boxW / 2f),
                drawCenterY - (boxH / 2f),
                drawCenterX + (boxW / 2f),
                drawCenterY + (boxH / 2f)
            )
            
            // Constrain box within view width
            if (drawRect.left < 0) drawRect.offset(-drawRect.left, 0f)
            if (drawRect.right > width) drawRect.offset(width - drawRect.right, 0f)

            canvas.drawRect(drawRect, bgPaint)
            
            // Center text vertically using descent and ascent
            val textY = drawCenterY - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(item.text, drawCenterX, textY, textPaint)
        }
    }
}
