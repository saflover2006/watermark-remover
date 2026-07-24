package com.watermarkremover.studio.nativepreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.IOException

class NativeEditorSessionStore(context: Context) {

    data class RestoredSession(
        val snapshot: NativeMaskEditorView.SessionSnapshot,
        val fileBaseName: String,
        val cleanupStrength: Int,
    )

    private val sessionDirectory = File(context.cacheDir, "native-editor-session")
    private val metadataFile = File(sessionDirectory, "metadata.json")
    private val originalBitmapFile = File(sessionDirectory, "original.png")
    private val workingBitmapFile = File(sessionDirectory, "working.png")
    private val maskFile = File(sessionDirectory, "mask.bin")

    fun save(
        snapshot: NativeMaskEditorView.SessionSnapshot,
        fileBaseName: String,
        cleanupStrength: Int,
    ) {
        try {
            if (!sessionDirectory.exists()) {
                sessionDirectory.mkdirs()
            }

            writeBitmap(snapshot.originalBitmap, originalBitmapFile)
            writeBitmap(snapshot.workingBitmap, workingBitmapFile)
            maskFile.writeBytes(snapshot.mask)

            val metadata = JSONObject().apply {
                put("fileBaseName", fileBaseName)
                put("cleanupStrength", cleanupStrength)
                put("maskedPixels", snapshot.maskedPixels)
                put("brushRadiusPx", snapshot.brushRadiusPx.toDouble())
                put("mode", snapshot.mode.name)
                put("previewMode", snapshot.previewMode.name)
                put("compareRatioPercent", snapshot.compareRatioPercent)
                put("zoom", snapshot.zoom.toDouble())
                put("panX", snapshot.panX.toDouble())
                put("panY", snapshot.panY.toDouble())
                put("width", snapshot.workingBitmap.width)
                put("height", snapshot.workingBitmap.height)
            }
            metadataFile.writeText(metadata.toString())
        } finally {
            snapshot.recycle()
        }
    }

    fun restore(): RestoredSession? {
        if (!metadataFile.exists() || !originalBitmapFile.exists() || !workingBitmapFile.exists() || !maskFile.exists()) {
            return null
        }

        val metadata = JSONObject(metadataFile.readText())
        val originalBitmap = decodeBitmap(originalBitmapFile) ?: return null
        val workingBitmap = decodeBitmap(workingBitmapFile) ?: return null
        val mask = maskFile.readBytes()

        val width = metadata.optInt("width", workingBitmap.width)
        val height = metadata.optInt("height", workingBitmap.height)

        if (originalBitmap.width != width || originalBitmap.height != height || workingBitmap.width != width || workingBitmap.height != height || mask.size != width * height) {
            originalBitmap.recycle()
            workingBitmap.recycle()
            clear()
            return null
        }

        val snapshot = NativeMaskEditorView.SessionSnapshot(
            originalBitmap = originalBitmap,
            workingBitmap = workingBitmap,
            mask = mask,
            maskedPixels = metadata.optInt("maskedPixels", 0),
            brushRadiusPx = metadata.optDouble("brushRadiusPx", 36.0).toFloat(),
            mode = parseMode(metadata.optString("mode")),
            previewMode = parsePreviewMode(metadata.optString("previewMode")),
            compareRatioPercent = metadata.optInt("compareRatioPercent", 50),
            zoom = metadata.optDouble("zoom", 1.0).toFloat(),
            panX = metadata.optDouble("panX", 0.0).toFloat(),
            panY = metadata.optDouble("panY", 0.0).toFloat(),
        )

        return RestoredSession(
            snapshot = snapshot,
            fileBaseName = metadata.optString("fileBaseName", "watermark-remover-native"),
            cleanupStrength = metadata.optInt("cleanupStrength", 2),
        )
    }

    fun clear() {
        if (metadataFile.exists()) {
            metadataFile.delete()
        }
        if (originalBitmapFile.exists()) {
            originalBitmapFile.delete()
        }
        if (workingBitmapFile.exists()) {
            workingBitmapFile.delete()
        }
        if (maskFile.exists()) {
            maskFile.delete()
        }
    }

    private fun writeBitmap(bitmap: Bitmap, target: File) {
        target.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("Could not write session bitmap ${target.name}")
            }
        }
    }

    private fun decodeBitmap(file: File): Bitmap? {
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
        if (copy !== decoded) {
            decoded.recycle()
        }
        return copy
    }

    private fun parseMode(value: String): NativeMaskEditorView.Mode {
        return runCatching { NativeMaskEditorView.Mode.valueOf(value) }
            .getOrDefault(NativeMaskEditorView.Mode.PAINT)
    }

    private fun parsePreviewMode(value: String): NativeMaskEditorView.PreviewMode {
        return runCatching { NativeMaskEditorView.PreviewMode.valueOf(value) }
            .getOrDefault(NativeMaskEditorView.PreviewMode.EDITED)
    }
}

