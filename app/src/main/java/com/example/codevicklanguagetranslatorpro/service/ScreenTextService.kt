package com.example.codevicklanguagetranslatorpro.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicReference

data class TextElement(val text: String, val bounds: Rect)

@SuppressLint("AccessibilityPolicy")
class ScreenTextService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenTextService"
        private val instance = AtomicReference<ScreenTextService?>()

        fun getTextElementsFromScreen(cropRect: RectF? = null): List<TextElement> {
            val service = instance.get() ?: run {
                Log.e(TAG, "Service not running")
                return emptyList()
            }

            val raw = mutableListOf<TextElement>()
            val myPkg = service.packageName ?: ""

            val root = service.rootInActiveWindow
            if (root != null) {
                collectTextNodes(root, raw, myPkg, cropRect)
            } else {
                service.windows?.forEach { win ->
                    win.root?.let { r ->
                        collectTextNodes(r, raw, myPkg, cropRect)
                    }
                }
            }

            Log.d(TAG, "Raw nodes: ${raw.size}")
            raw.forEach { Log.d(TAG, "  [${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}] \"${it.text.take(30)}\"") }

            val deduped = removeSupersets(raw)
            Log.d(TAG, "After dedup: ${deduped.size}")
            return deduped
        }

        /**
         * Collect only VISIBLE text-bearing nodes.
         *
         * Strategy:
         *  - Walk the full tree
         *  - A node is "text-bearing" if it has non-blank text AND is either:
         *      (a) a leaf (childCount == 0), OR
         *      (b) none of its direct children carry the same text
         *        (avoids duplicating parent labels that mirror a child's text)
         *  - Never collect from our own package
         */
        private fun collectTextNodes(
            node: AccessibilityNodeInfo?,
            out: MutableList<TextElement>,
            myPkg: String,
            cropRect: RectF?
        ) {
            if (node == null) return
            if (!node.isVisibleToUser) return
            if (node.packageName?.toString() == myPkg) return

            val text = node.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }

            if (text != null) {
                val childCount = node.childCount

                // Check if any direct child already has this exact text
                var childHasSameText = false
                for (i in 0 until childCount) {
                    val child = node.getChild(i) ?: continue
                    val childText = child.text?.toString()?.trim()
                    if (childText == text) { childHasSameText = true; break }
                }

                if (!childHasSameText) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() > 5 && bounds.height() > 5) {
                        if (cropRect == null || RectF.intersects(cropRect, RectF(bounds))) {
                            out.add(TextElement(text, Rect(bounds)))
                        }
                    }
                }

                // Always recurse into children
                for (i in 0 until childCount) {
                    collectTextNodes(node.getChild(i), out, myPkg, cropRect)
                }
            } else {
                // No text on this node — still recurse
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    collectTextNodes(node.getChild(i), out, myPkg, cropRect)
                }
            }
        }

        /**
         * Remove any element whose bounds are a SUPERSET of another element
         * with the same text — keep the smallest (most specific) bounds.
         *
         * Also remove near-duplicates: same text, center within 8px of another.
         */
        private fun removeSupersets(elements: List<TextElement>): List<TextElement> {
            if (elements.size <= 1) return elements

            val kept = mutableListOf<TextElement>()

            outer@ for (candidate in elements) {
                for (other in elements) {
                    if (other === candidate) continue
                    if (other.text == candidate.text && candidate.bounds.contains(other.bounds)) {
                        // candidate is larger — skip it, keep the smaller 'other'
                        continue@outer
                    }
                }
                kept.add(candidate)
            }

            // Final pass: deduplicate by (text + approximate center)
            val seen = mutableSetOf<String>()
            return kept.filter { el ->
                val key = "${el.text}|${el.bounds.centerX() / 6}|${el.bounds.centerY() / 6}"
                seen.add(key)
            }
        }

        fun getTextFromScreen(cropRect: RectF? = null): String =
            getTextElementsFromScreen(cropRect).joinToString("\n") { it.text }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Connected")
        instance.set(this)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() { instance.set(null) }
    override fun onDestroy() { super.onDestroy(); instance.set(null) }
}
