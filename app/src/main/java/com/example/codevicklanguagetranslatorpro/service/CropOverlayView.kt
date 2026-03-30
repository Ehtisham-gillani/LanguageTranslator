package com.example.codevicklanguagetranslatorpro.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#806366F1".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#206366F1".toColorInt()
        style = Paint.Style.FILL
    }

    private val bgPaint = Paint().apply {
        color = "#40000000".toColorInt()
        style = Paint.Style.FILL
    }

    var cropRect = RectF(100f, 100f, 500f, 500f)
    private var lastX = 0f
    private var lastY = 0f
    private var isResizing = false
    private var isMoving = false

    private val handleSize = 40f

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        // Draw dimmed background
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(cropRect, Path.Direction.CCW)
        }
        canvas.drawPath(path, bgPaint)

        // Draw crop area
        canvas.drawRect(cropRect, fillPaint)
        canvas.drawRect(cropRect, paint)

        // Draw resize handle (bottom-right)
        canvas.drawCircle(cropRect.right, cropRect.bottom, 15f, paint.apply { style = Paint.Style.FILL })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                val distToBottomRight = hypot(x - cropRect.right, y - cropRect.bottom)
                if (distToBottomRight < handleSize * 2) {
                    isResizing = true
                } else if (cropRect.contains(x, y)) {
                    isMoving = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY

                if (isResizing) {
                    cropRect.right = max(cropRect.left + 100f, min(width.toFloat(), cropRect.right + dx))
                    cropRect.bottom = max(cropRect.top + 100f, min(height.toFloat(), cropRect.bottom + dy))
                } else if (isMoving) {
                    val widthRect = cropRect.width()
                    val heightRect = cropRect.height()
                    
                    cropRect.left = max(0f, min(width - widthRect, cropRect.left + dx))
                    cropRect.top = max(0f, min(height - heightRect, cropRect.top + dy))
                    cropRect.right = cropRect.left + widthRect
                    cropRect.bottom = cropRect.top + heightRect
                }
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isResizing = false
                isMoving = false
            }
        }
        return true
    }
}
