package com.lochana.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Custom view for drawing YOLOv11 detection results on top of camera preview.
 * 
 * Features:
 * - Real-time bounding box rendering
 * - Class labels with confidence scores
 * - Color-coded detections by object class
 * - Performance optimized drawing
 * - FPS counter display
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "DetectionOverlayView"
        private const val TEXT_SIZE_DP = 14f
        private const val STROKE_WIDTH_DP = 2f
        private const val CORNER_RADIUS_DP = 4f
        private const val PADDING_DP = 4f
        
        // Color palette for different object classes
        private val CLASS_COLORS = intArrayOf(
            Color.parseColor("#FF6B6B"), // Red
            Color.parseColor("#4ECDC4"), // Teal
            Color.parseColor("#45B7D1"), // Blue
            Color.parseColor("#96CEB4"), // Green
            Color.parseColor("#FECA57"), // Yellow
            Color.parseColor("#FF9F40"), // Orange
            Color.parseColor("#6C5CE7"), // Purple
            Color.parseColor("#A29BFE"), // Light Purple
            Color.parseColor("#FD79A8"), // Pink
            Color.parseColor("#FDCB6E")  // Light Orange
        )
    }
    
    private var detections: List<YOLOv11Manager.Detection> = emptyList()
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    
    // Paint objects for drawing
    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH_DP * context.resources.displayMetrics.density
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        textSize = TEXT_SIZE_DP * context.resources.displayMetrics.density
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    
    private val backgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // For rounded rectangles
    private val cornerRadius = CORNER_RADIUS_DP * context.resources.displayMetrics.density
    private val padding = PADDING_DP * context.resources.displayMetrics.density
    
    init {
        // Make this view transparent
        setBackgroundColor(Color.TRANSPARENT)
    }
    
    /**
     * Update detections to draw
     */
    fun updateDetections(
        newDetections: List<YOLOv11Manager.Detection>,
        sourceImageWidth: Int,
        sourceImageHeight: Int
    ) {
        this.detections = newDetections
        this.imageWidth = sourceImageWidth
        this.imageHeight = sourceImageHeight
        invalidate() // Trigger redraw
    }
    
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (detections.isEmpty()) {
            return
        }
        
        // Calculate scale factors to map detections to view size.
        // The PreviewView uses centerCrop behaviour, so match that transformation here.
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val scale = max(scaleX, scaleY)
        
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        
        // Offsets place the cropped image centred inside the view
        val offsetX = (width - scaledWidth) / 2f
        val offsetY = (height - scaledHeight) / 2f
        
        // Draw each detection
        detections.forEach { detection ->
            drawDetection(canvas, detection, scale, offsetX, offsetY)
        }
    }
    
    /**
     * Draw a single detection with bounding box and label
     */
    private fun drawDetection(
        canvas: Canvas,
        detection: YOLOv11Manager.Detection,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        // Transform bounding box coordinates
        val left = detection.boundingBox.left * scale + offsetX
        val top = detection.boundingBox.top * scale + offsetY
        val right = detection.boundingBox.right * scale + offsetX
        val bottom = detection.boundingBox.bottom * scale + offsetY
        
        // Get color for this class
        val color = CLASS_COLORS[detection.classIndex % CLASS_COLORS.size]
        boxPaint.color = color
        
        // Draw bounding box
        val rect = RectF(left, top, right, bottom)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)
        
        // Prepare label text
        val confidence = (detection.confidence * 100).toInt()
        val label = "${detection.className} $confidence%"
        
        // Measure text
        val textBounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        
        // Draw label background
        backgroundPaint.color = color
        val labelLeft = left
        val labelTop = top - textBounds.height() - padding * 2
        val labelRight = left + textBounds.width() + padding * 2
        val labelBottom = top
        
        // Ensure label is within bounds
        val adjustedLabelTop = if (labelTop < 0) bottom else labelTop
        val adjustedLabelBottom = if (labelTop < 0) bottom + textBounds.height() + padding * 2 else labelBottom
        
        val labelRect = RectF(labelLeft, adjustedLabelTop, labelRight, adjustedLabelBottom)
        canvas.drawRoundRect(labelRect, cornerRadius, cornerRadius, backgroundPaint)
        
        // Draw label text
        textPaint.color = Color.WHITE
        val textX = labelLeft + padding
        val textY = if (labelTop < 0) {
            bottom + padding + textBounds.height()
        } else {
            top - padding
        }
        canvas.drawText(label, textX, textY, textPaint)
    }
    
    
    fun clear() {
        detections = emptyList()
        invalidate()
    }
}
