package com.watermarkremover.studio.nativepreview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

class NativeMaskEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    data class SessionSnapshot(
        val originalBitmap: Bitmap,
        val workingBitmap: Bitmap,
        val mask: ByteArray,
        val maskedPixels: Int,
        val brushRadiusPx: Float,
        val mode: Mode,
        val previewMode: PreviewMode,
        val compareRatioPercent: Int,
        val zoom: Float,
        val panX: Float,
        val panY: Float,
    ) {
        fun recycle() {
            originalBitmap.recycle()
            workingBitmap.recycle()
        }
    }

    data class MaskExportData(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val data: ByteArray,
    )

    enum class PreviewMode {
        EDITED,
        ORIGINAL,
        COMPARE,
    }

    enum class Mode {
        PAINT,
        ERASE,
    }

    private data class EditorSnapshot(
        val bitmap: Bitmap,
        val mask: ByteArray,
        val maskedPixels: Int,
    )

    companion object {
        private const val HISTORY_LIMIT = 12
        private const val MASK_COLOR = 0x96EF4444.toInt()
        private const val COMPARE_LINE_COLOR = 0xFFF8FAFC.toInt()
        private const val ERASE_PREVIEW_COLOR = 0x5894A3B8.toInt()
        private const val PAINT_PREVIEW_COLOR = 0x58EF4444.toInt()
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 8f
        private const val EPSILON = 0.001f
    }

    var onMaskChanged: ((Int) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    private val imageBounds = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val compareLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COMPARE_LINE_COLOR
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val brushPreviewStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COMPARE_LINE_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val brushPreviewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val brushPreviewCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COMPARE_LINE_COLOR
        style = Paint.Style.FILL
    }
    private val backgroundPaint = Paint().apply { color = Color.parseColor("#111827") }
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val historyPast = ArrayDeque<EditorSnapshot>()
    private val historyFuture = ArrayDeque<EditorSnapshot>()

    private var originalBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null
    private var maskOverlayBitmap: Bitmap? = null
    private var mask: ByteArray? = null
    private var maskedPixels = 0
    private var brushRadiusPx = 36f
    private var mode = Mode.PAINT
    private var previewMode = PreviewMode.EDITED
    private var compareRatioPercent = 50
    private var brushPreviewImagePoint: PointF? = null
    private var lastImagePoint: PointF? = null
    private var lastGestureFocus: PointF? = null
    private var strokeDirty = false
    private var isTransformGesture = false
    private var zoom = MIN_ZOOM
    private var panX = 0f
    private var panY = 0f

    fun hasImage(): Boolean = workingBitmap != null

    fun imageWidthPx(): Int = workingBitmap?.width ?: 0

    fun imageHeightPx(): Int = workingBitmap?.height ?: 0

    fun hasMask(): Boolean = maskedPixels > 0

    fun maskedPixelCount(): Int = maskedPixels

    fun currentMode(): Mode = mode

    fun currentPreviewMode(): PreviewMode = previewMode

    fun compareRatioPercent(): Int = compareRatioPercent

    fun canUndo(): Boolean = historyPast.size > 1

    fun canRedo(): Boolean = historyFuture.isNotEmpty()

    fun canFitView(): Boolean = hasImage() && (zoom > MIN_ZOOM + EPSILON || abs(panX) > EPSILON || abs(panY) > EPSILON)

    fun zoomPercent(): Int = (zoom * 100f).roundToInt()

    fun brushRadiusPx(): Float = brushRadiusPx

    fun setBrushRadius(radius: Float) {
        brushRadiusPx = radius.coerceAtLeast(1f)
    }

    fun setMode(nextMode: Mode) {
        if (mode == nextMode) {
            return
        }

        mode = nextMode
        invalidate()
    }

    fun setPreviewMode(nextMode: PreviewMode) {
        if (previewMode == nextMode) {
            return
        }

        previewMode = nextMode
        emitStateChanged()
        invalidate()
    }

    fun setCompareRatio(ratioPercent: Int) {
        val normalized = ratioPercent.coerceIn(0, 100)
        if (compareRatioPercent == normalized) {
            return
        }

        compareRatioPercent = normalized
        emitStateChanged()
        invalidate()
    }

    fun setImage(bitmap: Bitmap) {
        recycleEditorState()
        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        workingBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        mask = ByteArray(bitmap.width * bitmap.height)
        maskOverlayBitmap = createMaskOverlayBitmap(bitmap.width, bitmap.height, mask!!)
        maskedPixels = 0
        brushPreviewImagePoint = null
        lastImagePoint = null
        strokeDirty = false
        previewMode = PreviewMode.EDITED
        compareRatioPercent = 50
        resetViewportInternal()
        clearHistory()
        pushHistorySnapshot(clearFuture = true)
        bitmap.recycle()
        emitAllState()
        invalidate()
    }

    fun setFrameBitmap(bitmap: Bitmap, maskExportData: MaskExportData? = null, preserveViewport: Boolean = true) {
        val existingOriginal = originalBitmap
        val existingWorking = workingBitmap

        if (existingWorking != null && existingWorking !== existingOriginal) {
            existingWorking.recycle()
        }
        existingOriginal?.recycle()

        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        workingBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        applyMaskExportDataInternal(maskExportData)
        if (!preserveViewport) {
            resetViewportInternal()
        }

        brushPreviewImagePoint = null
        lastImagePoint = null
        lastGestureFocus = null
        strokeDirty = false
        isTransformGesture = false
        clearHistory()
        pushHistorySnapshot(clearFuture = true)
        bitmap.recycle()
        emitAllState()
        invalidate()
    }

    fun clearMask(): Boolean {
        if (!hasImage() || maskedPixels == 0) {
            return false
        }

        clearMaskInternal()
        pushHistorySnapshot(clearFuture = true)
        emitAllState()
        invalidate()
        return true
    }

    fun resetImage(): Boolean {
        val original = originalBitmap ?: return false
        val currentWorking = workingBitmap
        if (currentWorking != null && currentWorking !== original) {
            currentWorking.recycle()
        }

        workingBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        clearMaskInternal()
        resetViewportInternal()
        pushHistorySnapshot(clearFuture = true)
        emitAllState()
        invalidate()
        return true
    }

    fun applyCleanup(dilationRadius: Int): Boolean {
        val source = workingBitmap ?: return false
        val currentMask = mask ?: return false
        if (maskedPixels == 0) {
            return false
        }

        val cleaned = NativeInpaintEngine.inpaintMaskedBitmap(
            source = source,
            mask = currentMask,
            width = source.width,
            height = source.height,
            dilationRadius = dilationRadius,
            sampleRadius = 5,
            maxRadius = 28,
        )

        if (cleaned !== source) {
            source.recycle()
        }

        workingBitmap = cleaned
        clearMaskInternal()
        pushHistorySnapshot(clearFuture = true)
        emitAllState()
        invalidate()
        return true
    }

    fun outputBitmapCopy(): Bitmap? {
        return workingBitmap?.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun captureMaskExportData(): MaskExportData? {
        val bitmap = workingBitmap ?: return null
        val currentMask = mask ?: return null
        if (maskedPixels <= 0) {
            return null
        }

        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (currentMask[(y * bitmap.width) + x].toInt() == 0) {
                    continue
                }

                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }

        if (maxX < minX || maxY < minY) {
            return null
        }

        val exportWidth = maxX - minX + 1
        val exportHeight = maxY - minY + 1
        val exportMask = ByteArray(exportWidth * exportHeight)

        for (row in 0 until exportHeight) {
            val sourceOffset = ((minY + row) * bitmap.width) + minX
            val targetOffset = row * exportWidth
            System.arraycopy(currentMask, sourceOffset, exportMask, targetOffset, exportWidth)
        }

        return MaskExportData(
            x = minX,
            y = minY,
            width = exportWidth,
            height = exportHeight,
            data = exportMask,
        )
    }

    fun restoreMaskExportData(maskExportData: MaskExportData?) {
        applyMaskExportDataInternal(maskExportData)
        clearHistory()
        pushHistorySnapshot(clearFuture = true)
        emitAllState()
        invalidate()
    }

    fun captureSessionSnapshot(): SessionSnapshot? {
        val original = originalBitmap ?: return null
        val working = workingBitmap ?: return null
        val currentMask = mask ?: return null
        return SessionSnapshot(
            originalBitmap = original.copy(Bitmap.Config.ARGB_8888, false),
            workingBitmap = working.copy(Bitmap.Config.ARGB_8888, false),
            mask = currentMask.copyOf(),
            maskedPixels = maskedPixels,
            brushRadiusPx = brushRadiusPx,
            mode = mode,
            previewMode = previewMode,
            compareRatioPercent = compareRatioPercent,
            zoom = zoom,
            panX = panX,
            panY = panY,
        )
    }

    fun restoreSessionSnapshot(snapshot: SessionSnapshot) {
        recycleEditorState()
        originalBitmap = snapshot.originalBitmap.copy(Bitmap.Config.ARGB_8888, false)
        workingBitmap = snapshot.workingBitmap.copy(Bitmap.Config.ARGB_8888, true)
        mask = snapshot.mask.copyOf()
        maskedPixels = snapshot.maskedPixels
        brushRadiusPx = snapshot.brushRadiusPx.coerceAtLeast(1f)
        mode = snapshot.mode
        previewMode = snapshot.previewMode
        compareRatioPercent = snapshot.compareRatioPercent.coerceIn(0, 100)
        zoom = snapshot.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        panX = snapshot.panX
        panY = snapshot.panY
        brushPreviewImagePoint = null
        lastImagePoint = null
        lastGestureFocus = null
        strokeDirty = false
        isTransformGesture = false
        rebuildMaskOverlay()
        clearHistory()
        pushHistorySnapshot(clearFuture = true)
        snapshot.recycle()
        emitAllState()
        invalidate()
    }

    fun undo(): Boolean {
        if (!canUndo()) {
            return false
        }

        val current = historyPast.removeLast()
        historyFuture.addFirst(current)
        restoreSnapshot(historyPast.peekLast() ?: return false)
        emitAllState()
        invalidate()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) {
            return false
        }

        val snapshot = historyFuture.removeFirst()
        appendPastSnapshot(snapshot)
        restoreSnapshot(snapshot)
        emitAllState()
        invalidate()
        return true
    }

    fun fitToView(): Boolean {
        if (!canFitView()) {
            return false
        }

        resetViewportInternal()
        emitStateChanged()
        invalidate()
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        recycleEditorState()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val editedBitmap = workingBitmap ?: return
        val original = originalBitmap ?: editedBitmap
        updateImageBounds(editedBitmap.width, editedBitmap.height)

        when (previewMode) {
            PreviewMode.EDITED -> {
                canvas.drawBitmap(editedBitmap, null, imageBounds, imagePaint)
            }

            PreviewMode.ORIGINAL -> {
                canvas.drawBitmap(original, null, imageBounds, imagePaint)
            }

            PreviewMode.COMPARE -> {
                canvas.drawBitmap(editedBitmap, null, imageBounds, imagePaint)
                val splitX = imageBounds.left + ((imageBounds.width() * compareRatioPercent) / 100f)
                canvas.save()
                canvas.clipRect(imageBounds.left, imageBounds.top, splitX, imageBounds.bottom)
                canvas.drawBitmap(original, null, imageBounds, imagePaint)
                canvas.restore()
                canvas.drawLine(splitX, imageBounds.top, splitX, imageBounds.bottom, compareLinePaint)
            }
        }

        maskOverlayBitmap?.let { canvas.drawBitmap(it, null, imageBounds, maskOverlayPaint) }
        drawBrushPreview(canvas, editedBitmap.width, editedBitmap.height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = workingBitmap ?: return false
        val currentMask = mask ?: return false
        val overlay = maskOverlayBitmap ?: return false

        updateImageBounds(bitmap.width, bitmap.height)
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isTransformGesture = false
                lastGestureFocus = null
                val point = mapToImage(event.x, event.y, allowOutside = false, bitmap.width, bitmap.height) ?: return false
                parent?.requestDisallowInterceptTouchEvent(true)
                brushPreviewImagePoint = PointF(point.x, point.y)
                lastImagePoint = point
                strokeDirty = false
                val delta = stampMaskSegment(currentMask, overlay, bitmap.width, bitmap.height, point, point, brushRadiusPx, mode)
                if (delta != 0) {
                    maskedPixels += delta
                    strokeDirty = true
                    emitAllState()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    finishStrokeIfNeeded()
                    isTransformGesture = true
                    brushPreviewImagePoint = null
                    lastGestureFocus = calculateFocusPoint(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTransformGesture || event.pointerCount >= 2 || scaleGestureDetector.isInProgress) {
                    isTransformGesture = true
                    val focus = calculateFocusPoint(event)
                    val previousFocus = lastGestureFocus
                    if (previousFocus != null && event.pointerCount >= 2) {
                        panX += focus.x - previousFocus.x
                        panY += focus.y - previousFocus.y
                        clampPanOffsets(bitmap.width, bitmap.height)
                        emitStateChanged()
                        invalidate()
                    }
                    lastGestureFocus = focus
                    return true
                }

                val start = lastImagePoint ?: return false
                val end = mapToImage(event.x, event.y, allowOutside = true, bitmap.width, bitmap.height) ?: return false
                brushPreviewImagePoint = PointF(end.x, end.y)
                val delta = stampMaskSegment(currentMask, overlay, bitmap.width, bitmap.height, start, end, brushRadiusPx, mode)
                if (delta != 0) {
                    maskedPixels += delta
                    strokeDirty = true
                    emitAllState()
                    invalidate()
                }
                lastImagePoint = end
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2) {
                    lastGestureFocus = null
                }
                if (isTransformGesture) {
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishStrokeIfNeeded()
                isTransformGesture = false
                brushPreviewImagePoint = null
                lastGestureFocus = null
                lastImagePoint = null
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val bitmap = workingBitmap ?: return super.onHoverEvent(event)
        updateImageBounds(bitmap.width, bitmap.height)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> {
                val point = mapToImage(event.x, event.y, allowOutside = false, bitmap.width, bitmap.height)
                brushPreviewImagePoint = point?.let { PointF(it.x, it.y) }
                invalidate()
                return true
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                brushPreviewImagePoint = null
                invalidate()
                return true
            }
        }

        return super.onHoverEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun finishStrokeIfNeeded() {
        if (strokeDirty) {
            pushHistorySnapshot(clearFuture = true)
            emitStateChanged()
        }
        strokeDirty = false
    }

    private fun updateImageBounds(bitmapWidth: Int, bitmapHeight: Int) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || width <= 0 || height <= 0) {
            imageBounds.setEmpty()
            return
        }

        clampPanOffsets(bitmapWidth, bitmapHeight)
        val fitScale = minOf(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight)
        val drawnWidth = bitmapWidth * fitScale * zoom
        val drawnHeight = bitmapHeight * fitScale * zoom
        val centeredLeft = (width - drawnWidth) / 2f
        val centeredTop = (height - drawnHeight) / 2f
        imageBounds.set(
            centeredLeft + panX,
            centeredTop + panY,
            centeredLeft + panX + drawnWidth,
            centeredTop + panY + drawnHeight,
        )
    }

    private fun drawBrushPreview(canvas: Canvas, bitmapWidth: Int, bitmapHeight: Int) {
        val previewPoint = brushPreviewImagePoint ?: return
        if (isTransformGesture || imageBounds.isEmpty || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return
        }

        val scale = minOf(imageBounds.width() / bitmapWidth, imageBounds.height() / bitmapHeight)
        val radius = brushRadiusPx * scale
        if (radius <= 0f) {
            return
        }

        val centerX = imageBounds.left + ((previewPoint.x / (bitmapWidth - 1).coerceAtLeast(1).toFloat()) * imageBounds.width())
        val centerY = imageBounds.top + ((previewPoint.y / (bitmapHeight - 1).coerceAtLeast(1).toFloat()) * imageBounds.height())
        brushPreviewFillPaint.color = if (mode == Mode.PAINT) PAINT_PREVIEW_COLOR else ERASE_PREVIEW_COLOR
        canvas.drawCircle(centerX, centerY, radius, brushPreviewFillPaint)
        canvas.drawCircle(centerX, centerY, radius, brushPreviewStrokePaint)
        canvas.drawCircle(centerX, centerY, max(2f * resources.displayMetrics.density, radius * 0.08f), brushPreviewCenterPaint)
    }

    private fun mapToImage(
        viewX: Float,
        viewY: Float,
        allowOutside: Boolean,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): PointF? {
        if (imageBounds.isEmpty) {
            return null
        }

        if (!allowOutside && !imageBounds.contains(viewX, viewY)) {
            return null
        }

        val clampedX = viewX.coerceIn(imageBounds.left, imageBounds.right)
        val clampedY = viewY.coerceIn(imageBounds.top, imageBounds.bottom)
        val normalizedX = ((clampedX - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f)
        val normalizedY = ((clampedY - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        val imageX = (normalizedX * (bitmapWidth - 1)).coerceIn(0f, (bitmapWidth - 1).toFloat())
        val imageY = (normalizedY * (bitmapHeight - 1)).coerceIn(0f, (bitmapHeight - 1).toFloat())
        return PointF(imageX, imageY)
    }

    private fun calculateFocusPoint(event: MotionEvent): PointF {
        var sumX = 0f
        var sumY = 0f
        val pointerCount = event.pointerCount.coerceAtLeast(1)

        for (index in 0 until pointerCount) {
            sumX += event.getX(index)
            sumY += event.getY(index)
        }

        return PointF(sumX / pointerCount, sumY / pointerCount)
    }

    private fun clampPanOffsets(bitmapWidth: Int, bitmapHeight: Int) {
        if (width <= 0 || height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            panX = 0f
            panY = 0f
            return
        }

        if (zoom <= MIN_ZOOM + EPSILON) {
            zoom = MIN_ZOOM
            panX = 0f
            panY = 0f
            return
        }

        val fitScale = minOf(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight)
        val drawnWidth = bitmapWidth * fitScale * zoom
        val drawnHeight = bitmapHeight * fitScale * zoom
        val maxPanX = max(0f, (drawnWidth - width) / 2f)
        val maxPanY = max(0f, (drawnHeight - height) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun setZoomAtPoint(targetZoom: Float, focusX: Float, focusY: Float) {
        val bitmap = workingBitmap ?: return
        val newZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        updateImageBounds(bitmap.width, bitmap.height)

        val oldBounds = RectF(imageBounds)
        val focusRatioX = if (oldBounds.width() <= 0f) 0.5f else ((focusX - oldBounds.left) / oldBounds.width()).coerceIn(0f, 1f)
        val focusRatioY = if (oldBounds.height() <= 0f) 0.5f else ((focusY - oldBounds.top) / oldBounds.height()).coerceIn(0f, 1f)

        zoom = newZoom

        val fitScale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawnWidth = bitmap.width * fitScale * zoom
        val drawnHeight = bitmap.height * fitScale * zoom
        val centeredLeft = (width - drawnWidth) / 2f
        val centeredTop = (height - drawnHeight) / 2f
        panX = focusX - (focusRatioX * drawnWidth) - centeredLeft
        panY = focusY - (focusRatioY * drawnHeight) - centeredTop
        clampPanOffsets(bitmap.width, bitmap.height)
        emitStateChanged()
        invalidate()
    }

    private fun resetViewportInternal() {
        zoom = MIN_ZOOM
        panX = 0f
        panY = 0f
    }

    private fun createSnapshot(): EditorSnapshot? {
        val bitmap = workingBitmap ?: return null
        val currentMask = mask ?: return null
        return EditorSnapshot(
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false),
            mask = currentMask.copyOf(),
            maskedPixels = maskedPixels,
        )
    }

    private fun pushHistorySnapshot(clearFuture: Boolean) {
        val snapshot = createSnapshot() ?: return
        if (clearFuture) {
            recycleSnapshots(historyFuture)
        }
        appendPastSnapshot(snapshot)
    }

    private fun appendPastSnapshot(snapshot: EditorSnapshot) {
        historyPast.addLast(snapshot)
        while (historyPast.size > HISTORY_LIMIT) {
            recycleSnapshot(historyPast.removeFirst())
        }
    }

    private fun restoreSnapshot(snapshot: EditorSnapshot) {
        val currentWorking = workingBitmap
        if (currentWorking != null && currentWorking !== originalBitmap) {
            currentWorking.recycle()
        }

        workingBitmap = snapshot.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        mask = snapshot.mask.copyOf()
        maskedPixels = snapshot.maskedPixels
        rebuildMaskOverlay()
        lastImagePoint = null
        strokeDirty = false
    }

    private fun clearMaskInternal() {
        val currentMask = mask ?: return
        currentMask.fill(0)
        maskOverlayBitmap?.eraseColor(Color.TRANSPARENT)
        maskedPixels = 0
        brushPreviewImagePoint = null
        lastImagePoint = null
        strokeDirty = false
    }

    private fun applyMaskExportDataInternal(maskExportData: MaskExportData?) {
        val bitmap = workingBitmap ?: return
        val fullMask = ByteArray(bitmap.width * bitmap.height)
        var count = 0

        if (maskExportData != null && maskExportData.width > 0 && maskExportData.height > 0) {
            val safeX = maskExportData.x.coerceIn(0, max(0, bitmap.width - maskExportData.width))
            val safeY = maskExportData.y.coerceIn(0, max(0, bitmap.height - maskExportData.height))
            for (row in 0 until maskExportData.height) {
                val sourceOffset = row * maskExportData.width
                val targetOffset = ((safeY + row) * bitmap.width) + safeX
                System.arraycopy(maskExportData.data, sourceOffset, fullMask, targetOffset, maskExportData.width)
                for (column in 0 until maskExportData.width) {
                    if (maskExportData.data[sourceOffset + column].toInt() != 0) {
                        count += 1
                    }
                }
            }
        }

        mask = fullMask
        maskedPixels = count
        maskOverlayBitmap?.recycle()
        maskOverlayBitmap = createMaskOverlayBitmap(bitmap.width, bitmap.height, fullMask)
        brushPreviewImagePoint = null
        lastImagePoint = null
        strokeDirty = false
    }

    private fun rebuildMaskOverlay() {
        val bitmap = workingBitmap ?: return
        val currentMask = mask ?: return
        maskOverlayBitmap?.recycle()
        maskOverlayBitmap = createMaskOverlayBitmap(bitmap.width, bitmap.height, currentMask)
    }

    private fun createMaskOverlayBitmap(width: Int, height: Int, mask: ByteArray): Bitmap {
        val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val overlayPixels = IntArray(width * height)
        for (index in mask.indices) {
            if (mask[index].toInt() != 0) {
                overlayPixels[index] = MASK_COLOR
            }
        }
        overlay.setPixels(overlayPixels, 0, width, 0, 0, width, height)
        return overlay
    }

    private fun clearHistory() {
        recycleSnapshots(historyPast)
        recycleSnapshots(historyFuture)
    }

    private fun recycleSnapshots(deque: ArrayDeque<EditorSnapshot>) {
        while (deque.isNotEmpty()) {
            recycleSnapshot(deque.removeFirst())
        }
    }

    private fun recycleSnapshot(snapshot: EditorSnapshot) {
        snapshot.bitmap.recycle()
    }

    private fun emitMaskChanged() {
        onMaskChanged?.invoke(maskedPixels)
    }

    private fun emitStateChanged() {
        onStateChanged?.invoke()
    }

    private fun emitAllState() {
        emitMaskChanged()
        emitStateChanged()
    }

    private fun stampMaskSegment(
        mask: ByteArray,
        overlay: Bitmap,
        width: Int,
        height: Int,
        start: PointF,
        end: PointF,
        radius: Float,
        mode: Mode,
    ): Int {
        val steps = max(1, ceil(hypot(end.x - start.x, end.y - start.y) / max(1f, radius * 0.3f)).toInt())
        var delta = 0

        for (step in 0..steps) {
            val t = step / steps.toFloat()
            val x = start.x + ((end.x - start.x) * t)
            val y = start.y + ((end.y - start.y) * t)
            delta += stampMaskCircle(mask, overlay, width, height, x, y, radius, mode)
        }

        return delta
    }

    private fun stampMaskCircle(
        mask: ByteArray,
        overlay: Bitmap,
        width: Int,
        height: Int,
        centerX: Float,
        centerY: Float,
        radius: Float,
        mode: Mode,
    ): Int {
        var delta = 0
        val value = if (mode == Mode.PAINT) 1.toByte() else 0.toByte()
        val minX = max(0, floor(centerX - radius).toInt())
        val maxX = minOf(width - 1, ceil(centerX + radius).toInt())
        val minY = max(0, floor(centerY - radius).toInt())
        val maxY = minOf(height - 1, ceil(centerY + radius).toInt())
        val radiusSquared = radius * radius

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - centerX
                val dy = y - centerY
                if ((dx * dx) + (dy * dy) > radiusSquared) {
                    continue
                }

                val index = (y * width) + x
                val previousValue = mask[index]
                if (previousValue == value) {
                    continue
                }

                mask[index] = value
                if (value.toInt() == 1) {
                    overlay.setPixel(x, y, MASK_COLOR)
                    delta += 1
                } else {
                    overlay.setPixel(x, y, Color.TRANSPARENT)
                    delta -= 1
                }
            }
        }

        return delta
    }

    private fun recycleEditorState() {
        clearHistory()
        originalBitmap?.recycle()
        workingBitmap?.recycle()
        maskOverlayBitmap?.recycle()
        originalBitmap = null
        workingBitmap = null
        maskOverlayBitmap = null
        mask = null
        maskedPixels = 0
        brushPreviewImagePoint = null
        strokeDirty = false
        lastImagePoint = null
        lastGestureFocus = null
        isTransformGesture = false
        resetViewportInternal()
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (!hasImage()) {
                return false
            }

            finishStrokeIfNeeded()
            isTransformGesture = true
            brushPreviewImagePoint = null
            lastGestureFocus = PointF(detector.focusX, detector.focusY)
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!hasImage()) {
                return false
            }

            setZoomAtPoint(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            lastGestureFocus = PointF(detector.focusX, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            lastGestureFocus = PointF(detector.focusX, detector.focusY)
        }
    }
}
