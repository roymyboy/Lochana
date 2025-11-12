package com.lochana.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lochana.app.R
import kotlin.math.max
import kotlin.math.roundToInt

class PreviewDialogController(
    private val context: Context,
    private val snapshotManager: SnapshotManager,
    private val toast: (String) -> Unit
) {

    fun show(imagePath: String, description: String) {
        try {
            val dialogView = android.view.LayoutInflater.from(context)
                .inflate(R.layout.dialog_image_preview, null, false)
            val rootLayout = dialogView as LinearLayout
            val imageFrame = dialogView.findViewById<FrameLayout>(R.id.imageFrame)
            val imageView = dialogView.findViewById<ZoomableImageView>(R.id.previewImage)
            val descriptionView = dialogView.findViewById<TextView>(R.id.previewDescription)
            val closeButton = dialogView.findViewById<ImageButton>(R.id.previewClose)

            rootLayout.gravity = Gravity.CENTER_HORIZONTAL

            val bitmap = snapshotManager.loadSnapshot(imagePath)
            val displayMetrics = context.resources.displayMetrics
            val maxWidth = (displayMetrics.widthPixels * 0.9f).roundToInt()
            val maxHeight = (displayMetrics.heightPixels * 0.8f).roundToInt()
            val minWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 320f, displayMetrics).roundToInt()

            val (targetWidth, targetHeight) = bitmap?.let {
                val widthScale = it.width.toFloat() / maxWidth
                val heightScale = it.height.toFloat() / maxHeight
                val scale = max(max(widthScale, heightScale), 1f)
                var scaledWidth = max(1, (it.width / scale).roundToInt())
                var scaledHeight = max(1, (it.height / scale).roundToInt())

                if (scaledWidth < minWidth) {
                    val upscaleFactor = minWidth.toFloat() / scaledWidth.toFloat()
                    scaledWidth = minWidth
                    scaledHeight = max(1, (scaledHeight * upscaleFactor).roundToInt())
                    if (scaledHeight > maxHeight) {
                        val heightScaleDown = scaledHeight.toFloat() / maxHeight
                        scaledHeight = maxHeight
                        scaledWidth = max(1, (scaledWidth / heightScaleDown).roundToInt())
                    }
                }
                Pair(scaledWidth, scaledHeight)
            } ?: run {
                val placeholderWidth = max(minWidth, (maxWidth * 0.7f).roundToInt())
                val placeholderHeight = (placeholderWidth * 9f / 16f).roundToInt()
                Pair(placeholderWidth, placeholderHeight)
            }

            val contentWidth = maxWidth
            val horizontalPadding = rootLayout.paddingLeft + rootLayout.paddingRight
            val availableTextWidth = (contentWidth - horizontalPadding).coerceAtLeast(minWidth)

            rootLayout.layoutParams = LinearLayout.LayoutParams(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT)

            val frameParams = imageFrame.layoutParams as LinearLayout.LayoutParams
            frameParams.width = targetWidth
            frameParams.height = targetHeight
            frameParams.gravity = Gravity.CENTER_HORIZONTAL
            imageFrame.layoutParams = frameParams

            val imageParams = imageView.layoutParams as FrameLayout.LayoutParams
            imageParams.width = targetWidth
            imageParams.height = targetHeight
            imageParams.gravity = Gravity.CENTER
            imageView.layoutParams = imageParams

            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(R.drawable.ic_image_placeholder)
            }
            imageView.resetZoom()

            val descriptionParams = descriptionView.layoutParams as LinearLayout.LayoutParams
            descriptionParams.width = availableTextWidth
            descriptionParams.gravity = Gravity.CENTER_HORIZONTAL
            descriptionView.layoutParams = descriptionParams
            descriptionView.gravity = Gravity.START

            descriptionView.text = description.ifBlank {
                context.getString(R.string.chat_image_preview_fallback_description)
            }

            val dialog = AlertDialog.Builder(context)
                .setView(dialogView)
                .create()

            closeButton.setOnClickListener { dialog.dismiss() }
            imageView.setOnClickListener { toggleDescriptionVisibility(descriptionView) }

            dialog.setOnDismissListener {
                imageView.setImageDrawable(null)
                bitmap?.let {
                    if (!it.isRecycled) {
                        it.recycle()
                    }
                }
            }

            dialog.show()
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(contentWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (e: Exception) {
            toast(context.getString(R.string.chat_image_preview_error))
        }
    }

    private fun toggleDescriptionVisibility(targetView: View) {
        val targetAlpha = if (targetView.alpha == 1f) 0f else 1f
        targetView.animate()
            .alpha(targetAlpha)
            .setDuration(200)
            .withEndAction {
                targetView.visibility = if (targetAlpha == 0f) View.GONE else View.VISIBLE
            }
            .start()
    }

}

