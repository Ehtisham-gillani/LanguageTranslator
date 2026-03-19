package com.example.codevicklanguagetranslatorpro.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

data class TextElement(val text: String, val bounds: Rect)

class ScreenTextService : AccessibilityService() {

    companion object {
        private val instance = AtomicReference<ScreenTextService?>()

        fun getTextElementsFromScreen(cropRect: RectF? = null): List<TextElement> {
            val service = instance.get() ?: return emptyList()
            val elements = mutableListOf<TextElement>()
            
            // Use rootInActiveWindow as the primary source for better accuracy and performance
            service.rootInActiveWindow?.let { root ->
                collectStrictLeafNodes(root, elements, service.packageName, cropRect)
            }
            
            // If no elements found, try all windows as a fallback
            if (elements.isEmpty()) {
                val windows = service.windows
                if (!windows.isNullOrEmpty()) {
                    for (window in windows) {
                        val root = window.root ?: continue
                        if (root.packageName != service.packageName) {
                            collectStrictLeafNodes(root, elements, service.packageName, cropRect)
                        }
                    }
                }
            }
            return elements
        }

        private fun collectStrictLeafNodes(
            node: AccessibilityNodeInfo?, 
            elements: MutableList<TextElement>, 
            myPackage: CharSequence,
            cropRect: RectF?
        ) {
            if (node == null) return
            
            try {
                // Only process visible nodes from other applications
                if (node.packageName != myPackage && node.isVisibleToUser) {
                    val text = node.text?.toString()
                    
                    // Focus on leaf nodes (no children) which usually represent individual text elements
                    if (!text.isNullOrBlank() && node.childCount == 0) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        
                        // Sanity check for valid bounds
                        if (bounds.width() > 5 && bounds.height() > 5) {
                            if (cropRect == null || RectF.intersects(cropRect, RectF(bounds))) {
                                // Avoid adding the exact same element twice
                                if (elements.none { it.bounds == bounds && it.text == text }) {
                                    elements.add(TextElement(text, bounds))
                                }
                            }
                        }
                    }
                }
                
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        collectStrictLeafNodes(child, elements, myPackage, cropRect)
                        // Child nodes should be recycled to avoid memory issues in AccessibilityServices
                        child.recycle()
                    }
                }
            } catch (e: Exception) {
                // Ignore errors from invalid or recycled nodes
            }
        }
        
        fun getTextFromScreen(cropRect: RectF? = null): String {
            return getTextElementsFromScreen(cropRect).joinToString("\n") { it.text }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance.set(this)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() { instance.set(null) }
    override fun onDestroy() {
        super.onDestroy()
        instance.set(null)
    }
}
