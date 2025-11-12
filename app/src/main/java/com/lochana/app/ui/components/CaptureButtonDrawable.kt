package com.lochana.app.ui.components

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import android.os.Handler
import android.os.Looper

/**
 * Custom drawable for the capture button that shows animated progress
 * The outer ring changes from white to red in a clockwise sweep over 5 seconds
 */
class CaptureButtonDrawable : Drawable() {
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private var progress = 0f // 0.0 to 1.0
    private var isAnimating = false
    private var animationHandler: Handler? = null
    private var animationRunnable: Runnable? = null
    
    // Colors
    private val whiteColor = Color.WHITE
    private val redColor = Color.RED
    private val greyColor = Color.GRAY
    
    // Button state
    private var isDisabled = false
    
    // Dimensions
    private val strokeWidth = 5f // 4dp converted to pixels
    private val innerCirclePadding = 13f // 5dp converted to pixels (reduced gap)
    
    init {
        // Outer ring paint (white)
        paint.color = whiteColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Paint.Cap.ROUND
        
        // Progress ring paint (red)
        progressPaint.color = redColor
        progressPaint.style = Paint.Style.STROKE
        progressPaint.strokeWidth = strokeWidth
        progressPaint.strokeCap = Paint.Cap.ROUND
        
        // Inner circle paint (white)
        innerPaint.color = whiteColor
        innerPaint.style = Paint.Style.FILL
    }
    
    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        val radius = (bounds.width() / 2f) - strokeWidth / 2f
        
        // Use grey colors if disabled, otherwise use normal colors
        val outerColor = if (isDisabled) greyColor else whiteColor
        val innerColor = if (isDisabled) greyColor else whiteColor
        
        // Update paint colors
        paint.color = outerColor
        innerPaint.color = innerColor
        
        // Draw outer ring
        canvas.drawCircle(centerX, centerY, radius, paint)
        
        // Draw progress ring (red) if there's progress - show even when disabled during video capture
        if (progress > 0f) {
            val sweepAngle = progress * 360f
            val startAngle = -90f // Start from top (12 o'clock position)
            
            val rect = RectF(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )
            
            canvas.drawArc(rect, startAngle, sweepAngle, false, progressPaint)
        }
        
        // Draw inner circle
        val innerRadius = radius - innerCirclePadding
        canvas.drawCircle(centerX, centerY, innerRadius, innerPaint)
    }
    
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        progressPaint.alpha = alpha
        innerPaint.alpha = alpha
    }
    
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        progressPaint.colorFilter = colorFilter
        innerPaint.colorFilter = colorFilter
    }
    
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    
    /**
     * Starts the 5-second progress animation
     */
    fun startProgressAnimation() {
        if (isAnimating) return
        
        isAnimating = true
        progress = 0f
        animationHandler = Handler(Looper.getMainLooper())
        
        val startTime = System.currentTimeMillis()
        val duration = 5000L // 5 seconds
        
        animationRunnable = object : Runnable {
            override fun run() {
                if (!isAnimating) return
                
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                
                invalidateSelf()
                
                if (progress < 1f) {
                    animationHandler?.postDelayed(this, 16) // ~60 FPS
                } else {
                    isAnimating = false
                }
            }
        }
        
        animationHandler?.post(animationRunnable!!)
    }
    
    /**
     * Stops the progress animation and resets to initial state
     */
    fun stopProgressAnimation() {
        isAnimating = false
        progress = 0f
        animationHandler?.removeCallbacks(animationRunnable!!)
        invalidateSelf()
    }
    
    /**
     * Sets the button to disabled (grey) or enabled (white) state
     * @param disabled True to set grey/disabled state, false to enable
     */
    fun setDisabled(disabled: Boolean) {
        if (isDisabled != disabled) {
            isDisabled = disabled
            invalidateSelf()
        }
    }
}
