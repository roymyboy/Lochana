package com.lochana.app.vision

import android.graphics.RectF
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Tracks detections across frames to provide temporal smoothing
 * Reduces jitter and provides stable bounding boxes in video
 */
class DetectionTracker {
    companion object {
        private const val TAG = "DetectionTracker"
        private const val MAX_FRAMES_MISSING = 1  // Remove track immediately after 1 frame without match
        private const val IOU_MATCH_THRESHOLD = 0.35f  // IoU threshold for matching
        private const val SMOOTHING_ALPHA = 0.85f  // Higher = more responsive to changes
        private const val MAX_HISTORY_SIZE = 2  // Keep minimal history
    }
    
    private var nextTrackId = 0
    private val trackedObjects = mutableMapOf<Int, TrackedObject>()
    
    /**
     * Represents a tracked object across frames
     */
    data class TrackedObject(
        val trackId: Int,
        var detection: YOLOv11Manager.Detection,
        var framesSinceUpdate: Int = 0,
        val history: MutableList<YOLOv11Manager.Detection> = mutableListOf(),
        var consecutiveFrames: Int = 1  // How many frames this object has been tracked
    )
    
    /**
     * Update tracking with new detections
     * Returns smoothed detections with stable bounding boxes
     */
    fun updateTracking(newDetections: List<YOLOv11Manager.Detection>): List<YOLOv11Manager.Detection> {
        // Increment frames since update for all tracks
        trackedObjects.values.forEach { it.framesSinceUpdate++ }
        
        val matchedTrackIds = mutableSetOf<Int>()
        val matchedDetections = mutableSetOf<YOLOv11Manager.Detection>()
        
        // Match new detections to existing tracks
        for (detection in newDetections) {
            val bestMatch = findBestMatch(detection, trackedObjects.values.filter { it.trackId !in matchedTrackIds })
            
            if (bestMatch != null) {
                // Update existing track
                updateTrack(bestMatch.trackId, detection)
                matchedTrackIds.add(bestMatch.trackId)
                matchedDetections.add(detection)
                
                if (bestMatch.consecutiveFrames <= 3) {
                    Log.d(TAG, "📍 Updated track ${bestMatch.trackId}: ${detection.className} (${(detection.confidence * 100).toInt()}%)")
                }
            }
        }
        
        // Create new tracks for unmatched detections
        val unmatchedDetections = newDetections.filter { it !in matchedDetections }
        for (detection in unmatchedDetections) {
            createNewTrack(detection)
        }
        
        // Remove stale tracks (not seen for MAX_FRAMES_MISSING frames)
        val staleTrackIds = trackedObjects.entries
            .filter { it.value.framesSinceUpdate > MAX_FRAMES_MISSING }
            .map { it.key }
        
        staleTrackIds.forEach { trackId ->
            Log.d(TAG, "🗑️ Removing stale track $trackId (${trackedObjects[trackId]?.detection?.className})")
            trackedObjects.remove(trackId)
        }
        
        // Return smoothed detections from active tracks
        // Only return tracks that have been seen for at least 2 consecutive frames (reduces flicker)
        val smoothedDetections = trackedObjects.values
            .filter { it.consecutiveFrames >= 2 }
            .map { it.detection }
        
        if (newDetections.isNotEmpty()) {
            Log.d(TAG, "📊 Tracking: ${trackedObjects.size} active tracks, ${smoothedDetections.size} stable detections")
        }
        
        return smoothedDetections
    }
    
    /**
     * Find best matching track for a detection
     */
    private fun findBestMatch(
        detection: YOLOv11Manager.Detection,
        candidates: Collection<TrackedObject>
    ): TrackedObject? {
        var bestMatch: TrackedObject? = null
        var bestIoU = IOU_MATCH_THRESHOLD
        
        for (track in candidates) {
            // Only match same class
            if (track.detection.classIndex != detection.classIndex) continue
            
            val iou = calculateIoU(detection.boundingBox, track.detection.boundingBox)
            if (iou > bestIoU) {
                bestIoU = iou
                bestMatch = track
            }
        }
        
        return bestMatch
    }
    
    /**
     * Create a new track for an unmatched detection
     */
    private fun createNewTrack(detection: YOLOv11Manager.Detection) {
        val trackId = nextTrackId++
        trackedObjects[trackId] = TrackedObject(
            trackId = trackId,
            detection = detection,
            framesSinceUpdate = 0,
            consecutiveFrames = 1
        )
        Log.d(TAG, "✨ New track $trackId: ${detection.className} (${(detection.confidence * 100).toInt()}%)")
    }
    
    /**
     * Update an existing track with a new detection
     */
    private fun updateTrack(trackId: Int, newDetection: YOLOv11Manager.Detection) {
        val track = trackedObjects[trackId] ?: return
        
        // Add to history
        track.history.add(newDetection)
        if (track.history.size > MAX_HISTORY_SIZE) {
            track.history.removeAt(0)
        }
        
        // Smooth the detection
        val smoothedDetection = smoothDetection(track.detection, newDetection)
        
        // Update track
        track.detection = smoothedDetection
        track.framesSinceUpdate = 0
        track.consecutiveFrames++
    }
    
    /**
     * Smooth detection using exponential moving average
     */
    private fun smoothDetection(
        oldDetection: YOLOv11Manager.Detection,
        newDetection: YOLOv11Manager.Detection
    ): YOLOv11Manager.Detection {
        // Smooth bounding box coordinates
        val smoothedBox = RectF(
            SMOOTHING_ALPHA * newDetection.boundingBox.left + (1 - SMOOTHING_ALPHA) * oldDetection.boundingBox.left,
            SMOOTHING_ALPHA * newDetection.boundingBox.top + (1 - SMOOTHING_ALPHA) * oldDetection.boundingBox.top,
            SMOOTHING_ALPHA * newDetection.boundingBox.right + (1 - SMOOTHING_ALPHA) * oldDetection.boundingBox.right,
            SMOOTHING_ALPHA * newDetection.boundingBox.bottom + (1 - SMOOTHING_ALPHA) * oldDetection.boundingBox.bottom
        )
        
        // Use new detection's confidence and class
        return YOLOv11Manager.Detection(
            boundingBox = smoothedBox,
            className = newDetection.className,
            classIndex = newDetection.classIndex,
            confidence = newDetection.confidence
        )
    }
    
    /**
     * Calculate Intersection over Union (IoU) between two bounding boxes
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)
        
        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0f
        }
        
        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
    
    /**
     * Clear all tracks
     */
    fun clearTracks() {
        trackedObjects.clear()
        nextTrackId = 0
        Log.d(TAG, "🧹 Cleared all tracks")
    }
    
    /**
     * Get number of active tracks
     */
    fun getActiveTrackCount(): Int = trackedObjects.size
    
    /**
     * Get track statistics
     */
    fun getTrackStatistics(): Map<String, Any> {
        val stableCount = trackedObjects.values.count { it.consecutiveFrames >= 3 }
        val avgConfidence = trackedObjects.values.mapNotNull { it.detection.confidence }.average()
        
        return mapOf(
            "total_tracks" to trackedObjects.size,
            "stable_tracks" to stableCount,
            "avg_confidence" to avgConfidence
        )
    }
}

