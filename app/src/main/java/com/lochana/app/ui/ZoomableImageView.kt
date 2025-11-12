package com.lochana.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val supplementaryMatrix = Matrix()
    private val displayMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val matrixRect = RectF()

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var currentScale = 1f
    private var maxScale = 4f
    private var minScale = 1f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitImageToView() }
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        post { fitImageToView() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount
        val x = getX(event)
        val y = getY(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && pointerCount == 1) {
                    val dx = x - lastTouchX
                    val dy = y - lastTouchY
                    if (!isDragging) {
                        isDragging = kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > 4
                    }
                    if (isDragging) {
                        translate(dx, dy)
                        applyMatrix()
                    }
                    lastTouchX = x
                    lastTouchY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scaleDetector.isInProgress) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                isDragging = false
            }
        }

        return true
    }

    fun resetZoom() {
        currentScale = 1f
        supplementaryMatrix.reset()
        fitImageToView()
    }

    private fun fitImageToView() {
        val drawable = drawable ?: return
        if (width == 0 || height == 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()

        baseMatrix.reset()
        if (drawableWidth > 0 && drawableHeight > 0) {
            val scale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
            val dx = (viewWidth - drawableWidth * scale) / 2f
            val dy = (viewHeight - drawableHeight * scale) / 2f
            baseMatrix.postScale(scale, scale)
            baseMatrix.postTranslate(dx, dy)
        }

        minScale = 1f
        currentScale = 1f
        supplementaryMatrix.reset()
        applyMatrix()
    }

    private fun translate(dx: Float, dy: Float) {
        supplementaryMatrix.postTranslate(dx, dy)
        ensureTranslationBounds()
    }

    private fun zoom(scaleFactor: Float, focusX: Float, focusY: Float) {
        var newScale = currentScale * scaleFactor
        newScale = max(minScale, min(newScale, maxScale))
        val factor = newScale / currentScale
        supplementaryMatrix.postScale(factor, factor, focusX, focusY)
        currentScale = newScale
        ensureTranslationBounds()
        applyMatrix()
    }

    private fun ensureTranslationBounds() {
        val drawable = drawable ?: return
        val mapMatrix = Matrix()
        mapMatrix.set(baseMatrix)
        mapMatrix.postConcat(supplementaryMatrix)
        val rect = getMatrixRect(mapMatrix, drawable)

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= width) {
            deltaX = (width - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0) deltaX = -rect.left
            if (rect.right < width) deltaX = width - rect.right
        }

        if (rect.height() <= height) {
            deltaY = (height - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0) deltaY = -rect.top
            if (rect.bottom < height) deltaY = height - rect.bottom
        }

        supplementaryMatrix.postTranslate(deltaX, deltaY)
    }

    private fun getMatrixRect(matrix: Matrix, drawable: Drawable): RectF {
        matrixRect.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        matrix.mapRect(matrixRect)
        return matrixRect
    }

    private fun applyMatrix() {
        displayMatrix.set(baseMatrix)
        displayMatrix.postConcat(supplementaryMatrix)
        imageMatrix = displayMatrix
        invalidate()
    }

    private fun getX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) {
            sum += event.getX(i)
        }
        return sum / event.pointerCount
    }

    private fun getY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) {
            sum += event.getY(i)
        }
        return sum / event.pointerCount
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > minScale + 0.05f) {
                resetZoom()
            } else {
                zoom(2f, e.x, e.y)
            }
            return true
        }
    }
}
