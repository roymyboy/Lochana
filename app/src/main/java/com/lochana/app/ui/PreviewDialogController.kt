package com.lochana.app.ui

import android.content.Context
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.lochana.app.R
import com.lochana.app.databinding.ActivityMainBinding

class PreviewDialogController(
    private val context: Context,
    private val snapshotManager: SnapshotManager,
    private val toast: (String) -> Unit
) {

    fun show(imagePath: String, description: String) {
        try {
            val dialogView = android.view.LayoutInflater.from(context)
                .inflate(R.layout.dialog_image_preview, null, false)
            val imageView = dialogView.findViewById<ImageView>(R.id.previewImage)
            val descriptionView = dialogView.findViewById<TextView>(R.id.previewDescription)
            val closeButton = dialogView.findViewById<ImageButton>(R.id.previewClose)

            val bitmap = snapshotManager.loadSnapshot(imagePath)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(R.drawable.ic_image_placeholder)
            }

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

