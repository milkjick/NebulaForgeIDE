package com.nebulaforge.app.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.nebulaforge.core.agent.DiffReviewEngine

/**
 * Lightweight inline review gutter layered over the real Sora editor.
 * It is deliberately independent from undocumented Sora internals: scrolling and
 * line metrics are observed through the public View surface and the editor's
 * current text size. It never edits the document.
 */
class DiffGutterOverlay(
    private var editor: View,
    private var hunks: List<DiffReviewEngine.DiffHunk> = emptyList(),
    private val onHunkClick: (DiffReviewEngine.DiffHunk) -> Unit
) : View(editor.context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lineHeightPx = 0f
    private var topPaddingPx = 0f
    private var selectedHunkIndex = -1
    private var scrollListener: (() -> Unit)? = null
    private var layoutListener: (() -> Unit)? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        attachToEditor(editor)
    }

    fun setEditor(value: View) {
        if (editor === value) return
        detachFromEditor()
        editor = value
        attachToEditor(editor)
        invalidate()
    }

    private fun attachToEditor(value: View) {
        val scroll = { invalidate() }
        val layout = { invalidate() }
        scrollListener = scroll
        layoutListener = layout
        value.viewTreeObserver.addOnScrollChangedListener(scroll)
        value.viewTreeObserver.addOnGlobalLayoutListener(layout)
    }

    private fun detachFromEditor() {
        scrollListener?.let { listener -> editor.viewTreeObserver.removeOnScrollChangedListener(listener) }
        layoutListener?.let { listener -> editor.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        scrollListener = null
        layoutListener = null
    }

    fun setSelectedHunk(index: Int) {
        selectedHunkIndex = index
        invalidate()
    }

    override fun onDetachedFromWindow() {
        detachFromEditor()
        super.onDetachedFromWindow()
    }

    fun updateHunks(value: List<DiffReviewEngine.DiffHunk>) {
        hunks = value
        invalidate()
    }

    private fun metrics() {
        val density = resources.displayMetrics.density
        val textPx = editor.resources.displayMetrics.scaledDensity * 14f
        lineHeightPx = (textPx * 1.45f).coerceAtLeast(12f)
        topPaddingPx = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        metrics()
        val density = resources.displayMetrics.density
        val scrollY = editor.scrollY.toFloat()
        val markerWidth = 3f * density
        val inset = 1f * density
        for (h in hunks) {
            val line = (h.newStart - 1).coerceAtLeast(0)
            val count = h.newCount.coerceAtLeast(1)
            val top = topPaddingPx + line * lineHeightPx - scrollY
            val bottom = top + count * lineHeightPx
            if (bottom < 0f || top > height) continue
            if (h.index == selectedHunkIndex) {
                paint.color = 0xff1565c0.toInt()
                canvas.drawRoundRect(
                    RectF(0f, top.coerceAtLeast(0f), width.toFloat(), bottom.coerceAtMost(height.toFloat())),
                    markerWidth, markerWidth, paint
                )
            }
            paint.color = when (h.status) {
                DiffReviewEngine.HunkStatus.ACCEPTED -> 0xff2e7d32.toInt()
                DiffReviewEngine.HunkStatus.REJECTED -> 0xffc62828.toInt()
                DiffReviewEngine.HunkStatus.PENDING -> 0xffef6c00.toInt()
            }
            canvas.drawRoundRect(
                RectF(inset, top.coerceAtLeast(0f), inset + markerWidth, bottom.coerceAtMost(height.toFloat())),
                markerWidth, markerWidth, paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        metrics()
        val line = ((event.y + editor.scrollY - topPaddingPx) / lineHeightPx).toInt().coerceAtLeast(0) + 1
        val hit = hunks.firstOrNull { line in it.newStart..(it.newStart + it.newCount.coerceAtLeast(1) - 1) }
            ?: hunks.minByOrNull { kotlin.math.abs(it.newStart - line) }
        if (hit != null) onHunkClick(hit)
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
