package com.watermarkremover.studio.nativepreview

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import com.watermarkremover.studio.AboutActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.watermarkremover.studio.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.NumberFormat
import kotlin.math.roundToInt

class NativeImageEditorActivity : AppCompatActivity() {

    private enum class StatusTone {
        INFO,
        SUCCESS,
        ERROR,
    }

    companion object {
        private const val DEFAULT_BRUSH_SIZE = 36
        private const val DEFAULT_CLEANUP_STRENGTH = 2

        // Overflow menu item IDs
        private const val MENU_ABOUT = 1001
        private const val MENU_CLEAR_MASK = 1002
        private const val MENU_RESET_IMAGE = 1003
        private const val MENU_SAVE_PNG = 1004
        private const val MENU_NEW_SESSION = 1005
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var editorView: NativeMaskEditorView
    private lateinit var emptyState: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText: TextView
    private lateinit var brushLabel: TextView
    private lateinit var cleanupLabel: TextView
    private lateinit var zoomValueText: TextView
    private lateinit var gestureHintText: TextView
    private lateinit var rootScrollView: ScrollView
    private lateinit var editorSummaryGroup: View
    private lateinit var previewSection: View
    private lateinit var previewControlsGroup: View
    private lateinit var previewToggleButton: Button
    private lateinit var editorCanvasActions: View
    private lateinit var editorWorkspaceCard: View
    private lateinit var compareControls: View
    private lateinit var compareValueText: TextView
    private lateinit var pickImageButton: Button
    private lateinit var saveImageButton: Button
    private lateinit var retouchToggleButton: Button
    private lateinit var undoButton: Button
    private lateinit var redoButton: Button
    private lateinit var fitViewButton: Button
    private lateinit var removeButton: Button
    private lateinit var clearMaskButton: Button
    private lateinit var resetImageButton: Button
    private lateinit var brushControlsCard: View
    private lateinit var retouchControlsGroup: View
    private lateinit var paintModeButton: RadioButton
    private lateinit var eraseModeButton: RadioButton
    private lateinit var previewEditedButton: RadioButton
    private lateinit var previewOriginalButton: RadioButton
    private lateinit var previewCompareButton: RadioButton
    private lateinit var brushSeekBar: SeekBar
    private lateinit var cleanupSeekBar: SeekBar
    private lateinit var compareSeekBar: SeekBar
    private lateinit var sessionStore: NativeEditorSessionStore
    private lateinit var bottomActionBar: View
    private lateinit var editActionBarGroup: View
    private lateinit var imageMetaText: TextView
    private lateinit var maskMetaText: TextView
    private lateinit var modeMetaText: TextView
    private lateinit var previewMetaText: TextView
    private lateinit var menuButton: ImageButton

    private var isBusy = false
    private var fileBaseName = "watermark-remover-native"
    private var isPreviewExpanded = false
    private var isRetouchExpanded = true

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }

        uiScope.launch {
            loadImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_native_image_editor)
        sessionStore = NativeEditorSessionStore(applicationContext)

        // Initialize views
        editorView = findViewById(R.id.nativeEditorView)
        emptyState = findViewById(R.id.nativeEmptyState)
        titleText = findViewById(R.id.nativeTitle)
        subtitleText = findViewById(R.id.nativeSubtitle)
        statusText = findViewById(R.id.nativeStatusText)
        brushLabel = findViewById(R.id.brushSizeLabel)
        cleanupLabel = findViewById(R.id.cleanupStrengthLabel)
        zoomValueText = findViewById(R.id.zoomValueText)
        gestureHintText = findViewById(R.id.nativeGestureHint)
        rootScrollView = findViewById(R.id.rootScrollView)
        editorSummaryGroup = findViewById(R.id.editorSummaryGroup)
        previewSection = findViewById(R.id.previewSection)
        previewControlsGroup = findViewById(R.id.previewControlsGroup)
        previewToggleButton = findViewById(R.id.previewToggleButton)
        editorCanvasActions = findViewById(R.id.editorCanvasActions)
        editorWorkspaceCard = findViewById(R.id.editorWorkspaceCard)
        compareControls = findViewById(R.id.compareControls)
        compareValueText = findViewById(R.id.compareValueText)
        pickImageButton = findViewById(R.id.pickImageButton)
        saveImageButton = findViewById(R.id.saveImageButton)
        retouchToggleButton = findViewById(R.id.retouchToggleButton)
        undoButton = findViewById(R.id.undoButton)
        redoButton = findViewById(R.id.redoButton)
        fitViewButton = findViewById(R.id.fitViewButton)
        removeButton = findViewById(R.id.removeButton)
        clearMaskButton = findViewById(R.id.clearMaskButton)
        resetImageButton = findViewById(R.id.resetImageButton)
        brushControlsCard = findViewById(R.id.brushControlsCard)
        retouchControlsGroup = findViewById(R.id.retouchControlsGroup)
        paintModeButton = findViewById(R.id.paintModeButton)
        eraseModeButton = findViewById(R.id.eraseModeButton)
        previewEditedButton = findViewById(R.id.previewEditedButton)
        previewOriginalButton = findViewById(R.id.previewOriginalButton)
        previewCompareButton = findViewById(R.id.previewCompareButton)
        brushSeekBar = findViewById(R.id.brushSizeSeekBar)
        cleanupSeekBar = findViewById(R.id.cleanupStrengthSeekBar)
        compareSeekBar = findViewById(R.id.compareSeekBar)
        bottomActionBar = findViewById(R.id.bottomActionBar)
        editActionBarGroup = findViewById(R.id.editActionBarGroup)
        imageMetaText = findViewById(R.id.imageMetaText)
        maskMetaText = findViewById(R.id.maskMetaText)
        modeMetaText = findViewById(R.id.modeMetaText)
        previewMetaText = findViewById(R.id.previewMetaText)
        menuButton = findViewById(R.id.menuButton)

        menuButton.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, MENU_ABOUT, 0, R.string.menu_about)
            popup.menu.add(0, MENU_NEW_SESSION, 1, R.string.menu_new_session)
            popup.menu.add(0, MENU_CLEAR_MASK, 2, R.string.menu_clear_mask)
            popup.menu.add(0, MENU_RESET_IMAGE, 3, R.string.menu_reset_image)
            popup.menu.add(0, MENU_SAVE_PNG, 4, R.string.menu_save_png)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ABOUT -> {
                        startActivity(Intent(this, AboutActivity::class.java))
                        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
                        true
                    }
                    MENU_NEW_SESSION -> {
                        confirmNewSession()
                        true
                    }
                    MENU_CLEAR_MASK -> {
                        editorView.clearMask()
                        setStatus("Mask cleared.", StatusTone.INFO)
                        true
                    }
                    MENU_RESET_IMAGE -> {
                        editorView.resetImage()
                        setStatus("Image reset to original.", StatusTone.INFO)
                        true
                    }
                    MENU_SAVE_PNG -> {
                        uiScope.launch { saveCurrentImage() }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        editorView.onMaskChanged = {
            if (it > 0) {
                setStatus("Selected ${NumberFormat.getIntegerInstance().format(it)} pixels. Cleanup is ready.")
            } else if (editorView.hasImage() && !isBusy) {
                setStatus("Paint over the watermark area to remove it.")
            }
        }
        editorView.onStateChanged = {
            updateControls()
            updateZoomLabel()
            updatePreviewControls()
            updateMetadata()
        }

        pickImageButton.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("image/*"))
        }

        saveImageButton.setOnClickListener {
            uiScope.launch {
                saveCurrentImage()
            }
        }

        previewToggleButton.setOnClickListener {
            isPreviewExpanded = !isPreviewExpanded
            updatePreviewControls()
        }

        retouchToggleButton.setOnClickListener {
            isRetouchExpanded = !isRetouchExpanded
            updateControls()
        }

        undoButton.setOnClickListener {
            if (editorView.undo()) {
                setStatus("Undo applied.", StatusTone.SUCCESS)
            }
        }

        redoButton.setOnClickListener {
            if (editorView.redo()) {
                setStatus("Redo applied.", StatusTone.SUCCESS)
            }
        }

        fitViewButton.setOnClickListener {
            if (editorView.fitToView()) {
                setStatus("View reset to fit.", StatusTone.SUCCESS)
            }
        }

        removeButton.setOnClickListener {
            if (!editorView.hasMask()) {
                return@setOnClickListener
            }

            setBusy(true)
            setStatus("Running native image cleanup...")
            editorView.applyCleanup(cleanupSeekBar.progress)
            setBusy(false)
            setStatus("Cleanup complete. Save the PNG or paint again for another pass.", StatusTone.SUCCESS)
            updateControls()
        }

        clearMaskButton.setOnClickListener {
            editorView.clearMask()
            setStatus("Mask cleared.", StatusTone.SUCCESS)
            updateControls()
        }

        resetImageButton.setOnClickListener {
            editorView.resetImage()
            setStatus("Image reset to the original upload.", StatusTone.SUCCESS)
            updateControls()
        }

        brushSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val brushSize = progress + 8
                brushLabel.text = "Brush size: ${brushSize} px"
                editorView.setBrushRadius(brushSize.toFloat())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        cleanupSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                cleanupLabel.text = "Edge cleanup: ${progress} px"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        paintModeButton.setOnClickListener {
            editorView.setMode(NativeMaskEditorView.Mode.PAINT)
        }

        eraseModeButton.setOnClickListener {
            editorView.setMode(NativeMaskEditorView.Mode.ERASE)
        }

        previewEditedButton.setOnClickListener {
            editorView.setPreviewMode(NativeMaskEditorView.PreviewMode.EDITED)
        }

        previewOriginalButton.setOnClickListener {
            editorView.setPreviewMode(NativeMaskEditorView.PreviewMode.ORIGINAL)
        }

        previewCompareButton.setOnClickListener {
            editorView.setPreviewMode(NativeMaskEditorView.PreviewMode.COMPARE)
        }

        compareSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                compareValueText.text = "${progress}%"
                editorView.setCompareRatio(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        editorView.setBrushRadius(DEFAULT_BRUSH_SIZE.toFloat())
        editorView.setMode(NativeMaskEditorView.Mode.PAINT)
        brushSeekBar.progress = DEFAULT_BRUSH_SIZE - 8
        cleanupSeekBar.progress = DEFAULT_CLEANUP_STRENGTH
        updateControls()
        updateZoomLabel()
        updatePreviewControls()
        updateMetadata()

        uiScope.launch {
            restoreSessionIfAvailable()
        }
    }

    override fun onStop() {
        persistSession()
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private suspend fun loadImage(uri: Uri) {
        setBusy(true)
        setStatus("Loading image into the native editor...")

        try {
            val bitmap = withContext(Dispatchers.IO) {
                decodeBitmap(uri)
            }
            fileBaseName = sanitizeBaseName(getDisplayName(uri))
            isPreviewExpanded = false
            isRetouchExpanded = true
            editorView.setImage(bitmap)
            brushSeekBar.progress = DEFAULT_BRUSH_SIZE - 8
            cleanupSeekBar.progress = DEFAULT_CLEANUP_STRENGTH
            paintModeButton.isChecked = true
            eraseModeButton.isChecked = false
            emptyState.visibility = View.GONE
            setStatus("Image loaded. Paint over the watermark area to remove it.", StatusTone.SUCCESS)
        } catch (error: Exception) {
            setStatus(error.message ?: "Failed to load the selected image.", StatusTone.ERROR)
        } finally {
            setBusy(false)
            updateControls()
        }
    }

    private suspend fun saveCurrentImage() {
        val bitmap = editorView.outputBitmapCopy() ?: return
        setBusy(true)
        setStatus("Saving native PNG...")

        try {
            val destination = withContext(Dispatchers.IO) {
                saveBitmap(bitmap, "${fileBaseName}-native-cleaned.png")
            }
            setStatus("Saved native PNG to $destination", StatusTone.SUCCESS)
        } catch (error: Exception) {
            setStatus(error.message ?: "Failed to save the cleaned PNG.", StatusTone.ERROR)
        } finally {
            setBusy(false)
            updateControls()
        }
    }

    private fun setBusy(busy: Boolean) {
        isBusy = busy
        updateControls()
    }

    private suspend fun restoreSessionIfAvailable() {
        val restoredSession = withContext(Dispatchers.IO) {
            sessionStore.restore()
        } ?: return

        // Ask the user whether to resume or start fresh — never silently restore.
        val shouldResume = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.session_dialog_title))
                .setMessage(getString(R.string.session_dialog_message))
                .setPositiveButton(getString(R.string.session_dialog_resume)) { _, _ ->
                    if (cont.isActive) cont.resume(true)
                }
                .setNegativeButton(getString(R.string.session_dialog_discard)) { _, _ ->
                    if (cont.isActive) cont.resume(false)
                }
                .setOnCancelListener {
                    if (cont.isActive) cont.resume(false)
                }
                .show()
        }

        if (!shouldResume) {
            withContext(Dispatchers.IO) { sessionStore.clear() }
            return
        }

        fileBaseName = restoredSession.fileBaseName
        isPreviewExpanded = restoredSession.snapshot.previewMode == NativeMaskEditorView.PreviewMode.COMPARE
        isRetouchExpanded = true
        brushSeekBar.progress = (restoredSession.snapshot.brushRadiusPx.roundToInt() - 8).coerceIn(0, brushSeekBar.max)
        cleanupSeekBar.progress = restoredSession.cleanupStrength.coerceIn(0, cleanupSeekBar.max)
        editorView.restoreSessionSnapshot(restoredSession.snapshot)
        paintModeButton.isChecked = editorView.currentMode() == NativeMaskEditorView.Mode.PAINT
        eraseModeButton.isChecked = editorView.currentMode() == NativeMaskEditorView.Mode.ERASE
        emptyState.visibility = View.GONE
        setStatus(getString(R.string.session_restored), StatusTone.SUCCESS)
        updateControls()
        updateZoomLabel()
        updatePreviewControls()
        updateMetadata()
    }

    /** Clears the session and resets the editor to a blank state. */
    private fun confirmNewSession() {
        if (!editorView.hasImage()) {
            setStatus(getString(R.string.session_already_empty), StatusTone.INFO)
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_session_dialog_title))
            .setMessage(getString(R.string.new_session_dialog_message))
            .setPositiveButton(getString(R.string.new_session_dialog_confirm)) { _, _ ->
                sessionStore.clear()
                editorView.resetImage()
                editorView.clearMask()
                fileBaseName = "watermark-remover-native"
                isPreviewExpanded = false
                isRetouchExpanded = false
                emptyState.visibility = View.VISIBLE
                setStatus(getString(R.string.session_cleared), StatusTone.INFO)
                updateControls()
                updateMetadata()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persistSession() {
        val snapshot = editorView.captureSessionSnapshot()

        if (snapshot == null) {
            sessionStore.clear()
            return
        }

        sessionStore.save(
            snapshot = snapshot,
            fileBaseName = fileBaseName,
            cleanupStrength = cleanupSeekBar.progress,
        )
    }

    private fun updateControls() {
        val hasImage = editorView.hasImage()
        val hasMask = editorView.hasMask()
        val showEditorWorkspace = hasImage

        emptyState.visibility = if (hasImage) View.GONE else View.VISIBLE
        zoomValueText.visibility = if (hasImage) View.VISIBLE else View.GONE
        gestureHintText.visibility = if (hasImage) View.VISIBLE else View.GONE
        editorSummaryGroup.visibility = if (hasImage) View.VISIBLE else View.GONE
        editorWorkspaceCard.visibility = if (showEditorWorkspace) View.VISIBLE else View.GONE
        previewSection.visibility = if (showEditorWorkspace) View.VISIBLE else View.GONE
        editorCanvasActions.visibility = if (showEditorWorkspace) View.VISIBLE else View.GONE
        brushControlsCard.visibility = if (showEditorWorkspace) View.VISIBLE else View.GONE
        bottomActionBar.visibility = if (hasImage) View.VISIBLE else View.GONE
        editActionBarGroup.visibility = if (showEditorWorkspace) View.VISIBLE else View.GONE

        pickImageButton.isEnabled = !isBusy
        pickImageButton.text = if (hasImage) getString(R.string.native_editor_change_media) else getString(R.string.native_editor_open_media)
        saveImageButton.isEnabled = hasImage && !isBusy
        undoButton.isEnabled = hasImage && !isBusy && editorView.canUndo()
        redoButton.isEnabled = hasImage && !isBusy && editorView.canRedo()
        fitViewButton.isEnabled = hasImage && !isBusy && editorView.canFitView()
        removeButton.isEnabled = hasMask && !isBusy
        clearMaskButton.isEnabled = hasMask && !isBusy
        resetImageButton.isEnabled = hasImage && !isBusy
        paintModeButton.isEnabled = hasImage && !isBusy
        eraseModeButton.isEnabled = hasImage && !isBusy
        brushSeekBar.isEnabled = hasImage && !isBusy
        cleanupSeekBar.isEnabled = hasImage && !isBusy
        previewToggleButton.isEnabled = hasImage && !isBusy
        retouchToggleButton.isEnabled = hasImage && !isBusy
        previewEditedButton.isEnabled = hasImage && !isBusy
        previewOriginalButton.isEnabled = hasImage && !isBusy
        previewCompareButton.isEnabled = hasImage && !isBusy
        compareSeekBar.isEnabled = hasImage && !isBusy && editorView.currentPreviewMode() == NativeMaskEditorView.PreviewMode.COMPARE

        retouchControlsGroup.visibility = if (hasImage && isRetouchExpanded) View.VISIBLE else View.GONE
        retouchToggleButton.isSelected = isRetouchExpanded
        retouchToggleButton.text = getString(
            if (isRetouchExpanded) R.string.native_editor_hide_retouch else R.string.native_editor_show_retouch,
        )

        updateContextHeader(hasImage)
        updateMetadata()
    }

    private fun updateZoomLabel() {
        zoomValueText.text = "${editorView.zoomPercent()}%"
    }

    private fun updatePreviewControls() {
        if (editorView.currentPreviewMode() == NativeMaskEditorView.PreviewMode.COMPARE) {
            isPreviewExpanded = true
        }
        previewEditedButton.isChecked = editorView.currentPreviewMode() == NativeMaskEditorView.PreviewMode.EDITED
        previewOriginalButton.isChecked = editorView.currentPreviewMode() == NativeMaskEditorView.PreviewMode.ORIGINAL
        previewCompareButton.isChecked = editorView.currentPreviewMode() == NativeMaskEditorView.PreviewMode.COMPARE
        previewControlsGroup.visibility = if (editorView.hasImage() && isPreviewExpanded) View.VISIBLE else View.GONE
        previewToggleButton.isSelected = isPreviewExpanded
        previewToggleButton.text = getString(
            if (isPreviewExpanded) R.string.native_editor_hide_preview else R.string.native_editor_show_preview,
        )
        compareControls.visibility = if (editorView.currentPreviewMode() == NativeMaskEditorView.PreviewMode.COMPARE && editorView.hasImage() && isPreviewExpanded) {
            View.VISIBLE
        } else {
            View.GONE
        }
        compareSeekBar.progress = editorView.compareRatioPercent()
        compareValueText.text = "${editorView.compareRatioPercent()}%"
    }

    private fun updateMetadata() {
        if (!editorView.hasImage()) {
            imageMetaText.text = "No media"
            maskMetaText.text = "Mask 0"
            modeMetaText.text = formatMetaLabel(editorView.currentMode().name)
            previewMetaText.text = formatMetaLabel(editorView.currentPreviewMode().name)
            return
        }

        imageMetaText.text = "${editorView.imageWidthPx()} x ${editorView.imageHeightPx()}"
        maskMetaText.text = "Mask ${NumberFormat.getIntegerInstance().format(editorView.maskedPixelCount())}"
        modeMetaText.text = formatMetaLabel(editorView.currentMode().name)
        previewMetaText.text = formatMetaLabel(editorView.currentPreviewMode().name)
    }

    private fun updateContextHeader(hasImage: Boolean) {
        when {
            !hasImage -> {
                titleText.text = getString(R.string.title_activity_native_preview)
                subtitleText.text = getString(R.string.native_editor_subtitle)
            }

            else -> {
                titleText.text = getString(R.string.native_editor_title_photo)
                subtitleText.text = getString(R.string.native_editor_subtitle_photo_mode)
            }
        }
    }

    private fun formatMetaLabel(label: String): String {
        return label.split("_").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    private fun setStatus(message: String, tone: StatusTone = StatusTone.INFO) {
        statusText.text = message

        if (isBusy && tone != StatusTone.ERROR) {
            statusText.setTextColor(
                ContextCompat.getColor(
                    this,
                    when (tone) {
                        StatusTone.SUCCESS -> android.R.color.holo_green_light
                        StatusTone.ERROR -> android.R.color.holo_red_light
                        else -> android.R.color.white
                    }
                )
            )
        }
    }

    private suspend fun saveBitmap(bitmap: Bitmap, displayName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Watermark Remover")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create MediaStore entry")

        return try {
            contentResolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Failed to write bitmap to output stream")
                }
            }
            displayName
        } catch (error: Exception) {
            contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private suspend fun decodeBitmap(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open input stream for URI: $uri")
            inputStream.use {
                BitmapFactory.decodeStream(it) ?: throw IOException("Failed to decode bitmap")
            }
        }
    }

    private fun getDisplayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        } ?: "image"
    }

    private fun sanitizeBaseName(displayName: String): String {
        return displayName.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9-_]"), "-").take(50)
    }
}
