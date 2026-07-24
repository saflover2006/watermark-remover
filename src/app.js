import {
  cloneImageDataLike,
  createImageDataLike,
  getMaskBounds,
  inpaintMaskedImage,
} from "./inpaint.js";
import { getBrushPreviewLayout } from "./brush-preview.js";
import { paintMaskSegment } from "./mask.js";
import {
  MAX_ZOOM,
  MIN_ZOOM,
  clampPanOffset,
  clampZoom,
  getViewportMetrics,
  zoomViewportAtPoint,
} from "./viewport.js";
import {
  appendHistorySnapshot,
  canRedoHistory,
  canUndoHistory,
  createEditorSnapshot,
  redoHistory,
  undoHistory,
} from "./history.js";
import { BatchQueue, BatchStatus } from "./batch.js";

const fileInput = document.querySelector("#fileInput");
const brushSizeInput = document.querySelector("#brushSize");
const brushSizeValue = document.querySelector("#brushSizeValue");
const cleanupStrengthInput = document.querySelector("#cleanupStrength");
const cleanupStrengthValue = document.querySelector("#cleanupStrengthValue");
const paintModeButton = document.querySelector("#paintModeButton");
const eraseModeButton = document.querySelector("#eraseModeButton");
const removeButton = document.querySelector("#removeButton");
const undoButton = document.querySelector("#undoButton");
const redoButton = document.querySelector("#redoButton");
const clearMaskButton = document.querySelector("#clearMaskButton");
const resetButton = document.querySelector("#resetButton");
const downloadButton = document.querySelector("#downloadButton");
const previewEditedButton = document.querySelector("#previewEditedButton");
const previewOriginalButton = document.querySelector("#previewOriginalButton");
const previewCompareButton = document.querySelector("#previewCompareButton");
const compareSlider = document.querySelector("#compareSlider");
const compareValue = document.querySelector("#compareValue");
const zoomOutButton = document.querySelector("#zoomOutButton");
const zoomInButton = document.querySelector("#zoomInButton");
const fitViewButton = document.querySelector("#fitViewButton");
const zoomBadge = document.querySelector("#zoomBadge");
const statusText = document.querySelector("#statusText");
const imageMetaBadge = document.querySelector("#imageMetaBadge");
const maskMetaBadge = document.querySelector("#maskMetaBadge");
const stageFrame = document.querySelector("#stageFrame");
const imageCanvas = document.querySelector("#imageCanvas");
const comparisonCanvas = document.querySelector("#comparisonCanvas");
const overlayCanvas = document.querySelector("#overlayCanvas");
const compareLine = document.querySelector("#compareLine");
const brushPreview = document.querySelector("#brushPreview");
const emptyState = document.querySelector("#emptyState");
const busyOverlay = document.querySelector("#busyOverlay");
const busyOverlayText = document.querySelector("#busyOverlayText");
const compareControls = document.querySelector("#compareControls");

// Batch UI
const batchDrawer = document.querySelector("#batchDrawer");
const batchCountBadge = document.querySelector("#batchCountBadge");
const batchProcessButton = document.querySelector("#batchProcessButton");
const batchDownloadButton = document.querySelector("#batchDownloadButton");
const batchCarousel = document.querySelector("#batchCarousel");
const applyGlobalMaskCheckbox = document.querySelector("#applyGlobalMaskCheckbox");
const batchProgressContainer = document.querySelector("#batchProgressContainer");
const batchProgressBar = document.querySelector("#batchProgressBar");
const batchProgressText = document.querySelector("#batchProgressText");

const imageContext = imageCanvas.getContext("2d", { willReadFrequently: true });
const comparisonContext = comparisonCanvas.getContext("2d", { willReadFrequently: true });
const overlayContext = overlayCanvas.getContext("2d", { willReadFrequently: true });

const batchQueue = new BatchQueue();


const state = {
  mediaType: null,
  originalImageData: null,
  workingImageData: null,
  mask: null,
  imageWidth: 0,
  imageHeight: 0,
  isBusy: false,
  isDrawing: false,
  isPanning: false,
  lastPoint: null,
  activePointerId: null,
  panStartClientX: 0,
  panStartClientY: 0,
  panStartX: 0,
  panStartY: 0,
  hoverClientX: 0,
  hoverClientY: 0,
  isPointerOverCanvas: false,
  isSpacePressed: false,
  strokeDirty: false,
  maskedPixels: 0,
  historyPast: [],
  historyFuture: [],
  mode: "paint",
  preview: "edited",
  compareRatio: 50,
  zoom: 1,
  panX: 0,
  panY: 0,
  fileBaseName: "watermark-remover",
};

function setStatus(message) {
  statusText.textContent = message;

  if (state.isBusy) {
    busyOverlayText.textContent = message;
  }
}

function syncSliderLabels() {
  brushSizeValue.textContent = `${brushSizeInput.value} px`;
  cleanupStrengthValue.textContent = `${cleanupStrengthInput.value} px`;
  compareSlider.value = `${state.compareRatio}`;
  compareValue.textContent = `${state.compareRatio}%`;
  zoomBadge.textContent = `${Math.round(state.zoom * 100)}%`;
  renderBrushPreview();
}

function syncModeButtons() {
  paintModeButton.classList.toggle("is-active", state.mode === "paint");
  eraseModeButton.classList.toggle("is-active", state.mode === "erase");
  renderBrushPreview();
}

function syncPreviewButtons() {
  previewEditedButton.classList.toggle("is-active", state.preview === "edited");
  previewOriginalButton.classList.toggle("is-active", state.preview === "original");
  previewCompareButton.classList.toggle("is-active", state.preview === "compare");
}

function syncActionStates() {
  const hasImage = Boolean(state.workingImageData);
  const hasMask = hasImage && state.maskedPixels > 0;

  fileInput.disabled = state.isBusy;
  brushSizeInput.disabled = !hasImage || state.isBusy;
  cleanupStrengthInput.disabled = !hasImage || state.isBusy;
  removeButton.disabled = !hasMask || state.isBusy;
  undoButton.disabled = !hasImage || state.isBusy || !canUndoHistory(state.historyPast);
  redoButton.disabled = !hasImage || state.isBusy || !canRedoHistory(state.historyFuture);
  clearMaskButton.disabled = !hasMask || state.isBusy;
  resetButton.disabled = !hasImage || state.isBusy;
  downloadButton.disabled = !hasImage || state.isBusy;
  paintModeButton.disabled = !hasImage || state.isBusy;
  eraseModeButton.disabled = !hasImage || state.isBusy;
  previewEditedButton.disabled = !hasImage;
  previewOriginalButton.disabled = !hasImage;
  previewCompareButton.disabled = !hasImage;
  compareSlider.disabled = !hasImage || state.isBusy || state.preview !== "compare";
  zoomOutButton.disabled = !hasImage || state.isBusy || state.zoom <= MIN_ZOOM;
  zoomInButton.disabled = !hasImage || state.isBusy || state.zoom >= MAX_ZOOM;
  fitViewButton.disabled = !hasImage || state.isBusy || (state.zoom === MIN_ZOOM && state.panX === 0 && state.panY === 0);
  overlayCanvas.style.pointerEvents = hasImage && !state.isBusy ? "auto" : "none";
  removeButton.textContent = "Remove watermark";
  updateOverlayCursor();
}

function setBusy(isBusy) {
  state.isBusy = isBusy;
  busyOverlay.hidden = !isBusy;

  if (!isBusy) {
    busyOverlayText.textContent = "Working...";
  }

  syncActionStates();
}

function getBaseFileName(fileName) {
  const normalized = fileName.replace(/\.[^.]+$/, "").trim();
  const safe = normalized.replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+|-+$/g, "");
  return safe || "clean-image";
}

function ensureMaskBuffer(width, height, preserveMask = false) {
  const expectedLength = width * height;

  if (preserveMask && state.mask && state.mask.length === expectedLength) {
    return;
  }

  state.mask = new Uint8Array(expectedLength);
  state.maskedPixels = 0;
}

function applyPreviewFrame(originalImageData, workingImageData = originalImageData, preserveMask = false, copyHint) {
  state.originalImageData = cloneImageDataLike(originalImageData);
  state.workingImageData = cloneImageDataLike(workingImageData);
  state.imageWidth = originalImageData.width;
  state.imageHeight = originalImageData.height;
  ensureMaskBuffer(state.imageWidth, state.imageHeight, preserveMask);
  resizeCanvases(state.imageWidth, state.imageHeight);
  resetHistory();
  pushHistoryState();
  renderAll(copyHint);
}

function resizeCanvases(width, height) {
  imageCanvas.width = width;
  imageCanvas.height = height;
  comparisonCanvas.width = width;
  comparisonCanvas.height = height;
  overlayCanvas.width = width;
  overlayCanvas.height = height;
}

function getCurrentViewportMetrics(zoom = state.zoom) {
  return getViewportMetrics(stageFrame.clientWidth, stageFrame.clientHeight, state.imageWidth, state.imageHeight, zoom);
}

function normalizeViewportPan() {
  if (!state.workingImageData) {
    return;
  }

  const clamped = clampPanOffset(state.panX, state.panY, getCurrentViewportMetrics());
  state.panX = clamped.panX;
  state.panY = clamped.panY;
}

function applyViewport() {
  if (!state.workingImageData) {
    for (const canvas of [imageCanvas, comparisonCanvas, overlayCanvas]) {
      canvas.style.width = "";
      canvas.style.height = "";
      canvas.style.transform = "";
    }

    updateCompareLine();
    renderBrushPreview();
    return;
  }

  normalizeViewportPan();
  const metrics = getCurrentViewportMetrics();
  const transform = `translate(-50%, -50%) translate(${state.panX}px, ${state.panY}px)`;

  for (const canvas of [imageCanvas, comparisonCanvas, overlayCanvas]) {
    canvas.style.width = `${metrics.displayWidth}px`;
    canvas.style.height = `${metrics.displayHeight}px`;
    canvas.style.transform = transform;
  }

  updateCompareLine();
  renderBrushPreview();
}

function canPanImage() {
  return Boolean(state.workingImageData) && state.zoom > MIN_ZOOM;
}

function updateOverlayCursor() {
  overlayCanvas.classList.toggle("is-grab", canPanImage() && state.isSpacePressed && !state.isPanning && !state.isBusy);
  overlayCanvas.classList.toggle("is-grabbing", state.isPanning);
  renderBrushPreview();
}

function renderBrushPreview() {
  const shouldHidePreview =
    !state.workingImageData ||
    state.isBusy ||
    state.isPanning ||
    state.isSpacePressed ||
    !state.isPointerOverCanvas;

  if (shouldHidePreview) {
    brushPreview.hidden = true;
    return;
  }

  const layout = getBrushPreviewLayout({
    stageRect: stageFrame.getBoundingClientRect(),
    overlayRect: overlayCanvas.getBoundingClientRect(),
    canvasWidth: overlayCanvas.width,
    canvasHeight: overlayCanvas.height,
    brushDiameter: Number(brushSizeInput.value),
    clientX: state.hoverClientX,
    clientY: state.hoverClientY,
  });

  if (!layout.visible) {
    brushPreview.hidden = true;
    return;
  }

  brushPreview.hidden = false;
  brushPreview.classList.toggle("is-erase", state.mode === "erase");
  brushPreview.style.width = `${layout.diameter}px`;
  brushPreview.style.height = `${layout.diameter}px`;
  brushPreview.style.left = `${layout.left}px`;
  brushPreview.style.top = `${layout.top}px`;
}

function clearCanvas(context, canvas) {
  context.clearRect(0, 0, canvas.width, canvas.height);
}

function renderImageCanvas() {
  if (!state.workingImageData) {
    clearCanvas(imageContext, imageCanvas);
    return;
  }

  const source = state.preview === "original" ? state.originalImageData : state.workingImageData;
  imageContext.putImageData(source, 0, 0);
}

function updateCompareLine() {
  if (!state.workingImageData || state.preview !== "compare") {
    compareLine.hidden = true;
    return;
  }

  const stageRect = stageFrame.getBoundingClientRect();
  const canvasRect = imageCanvas.getBoundingClientRect();

  if (!canvasRect.width || !canvasRect.height) {
    compareLine.hidden = true;
    return;
  }

  const lineLeft = (canvasRect.left - stageRect.left) + ((canvasRect.width * state.compareRatio) / 100);
  compareLine.style.left = `${lineLeft}px`;
  compareLine.style.top = `${canvasRect.top - stageRect.top}px`;
  compareLine.style.height = `${canvasRect.height}px`;
  compareLine.hidden = false;
}

function renderCompareCanvas() {
  const hasComparePreview = Boolean(state.workingImageData) && state.preview === "compare";

  comparisonCanvas.hidden = !hasComparePreview;
  compareControls.hidden = !hasComparePreview;

  if (!hasComparePreview) {
    clearCanvas(comparisonContext, comparisonCanvas);
    comparisonCanvas.style.clipPath = "";
    compareLine.hidden = true;
    return;
  }

  comparisonContext.putImageData(state.originalImageData, 0, 0);
  comparisonCanvas.style.clipPath = `inset(0 ${100 - state.compareRatio}% 0 0)`;
  updateCompareLine();
}

function renderOverlay() {
  clearCanvas(overlayContext, overlayCanvas);

  if (!state.mask) {
    return;
  }

  const bounds = getMaskBounds(state.mask, state.imageWidth, state.imageHeight);

  if (!bounds) {
    return;
  }

  const overlayWidth = bounds.maxX - bounds.minX + 1;
  const overlayHeight = bounds.maxY - bounds.minY + 1;
  const overlayData = overlayContext.createImageData(overlayWidth, overlayHeight);

  for (let y = bounds.minY; y <= bounds.maxY; y += 1) {
    for (let x = bounds.minX; x <= bounds.maxX; x += 1) {
      const maskIndex = (y * state.imageWidth) + x;

      if (!state.mask[maskIndex]) {
        continue;
      }

      const overlayIndex = (((y - bounds.minY) * overlayWidth) + (x - bounds.minX)) * 4;
      overlayData.data[overlayIndex] = 255;
      overlayData.data[overlayIndex + 1] = 92;
      overlayData.data[overlayIndex + 2] = 126;
      overlayData.data[overlayIndex + 3] = 132;
    }
  }

  overlayContext.putImageData(overlayData, bounds.minX, bounds.minY);
}

function refreshMeta(copyHint) {
  if (!state.workingImageData) {
    imageMetaBadge.textContent = "No image loaded";
    maskMetaBadge.textContent = "Mask: 0 px";
    setStatus("Upload an image to start.");
    syncActionStates();
    return;
  }

  imageMetaBadge.textContent = `${state.imageWidth.toLocaleString()} x ${state.imageHeight.toLocaleString()}`;
  maskMetaBadge.textContent = `Mask: ${state.maskedPixels.toLocaleString()} px`;

  if (state.isBusy) {
    syncActionStates();
    return;
  }

  if (copyHint) {
    setStatus(copyHint);
  } else if (state.maskedPixels === 0) {
    setStatus("Brush over the watermark to create a mask.");
  } else {
    setStatus(`Selected ${state.maskedPixels.toLocaleString()} pixels. Click Remove watermark when the mark is fully covered.`);
  }

  syncActionStates();
}

function renderAll(copyHint) {
  const hasImage = Boolean(state.workingImageData);
  stageFrame.classList.toggle("is-empty", !hasImage);
  emptyState.hidden = hasImage;
  applyViewport();
  renderImageCanvas();
  renderCompareCanvas();
  renderOverlay();
  refreshMeta(copyHint);
}

function resetHistory() {
  state.historyPast = [];
  state.historyFuture = [];
}

function pushHistoryState() {
  if (!state.workingImageData || !state.mask) {
    return;
  }

  const snapshot = createEditorSnapshot(state.workingImageData, state.mask, state.maskedPixels);
  state.historyPast = appendHistorySnapshot(state.historyPast, snapshot);
  state.historyFuture = [];
}

function applyHistorySnapshot(snapshot, copyHint) {
  state.workingImageData = cloneImageDataLike(snapshot.workingImageData);
  state.mask = Uint8Array.from(snapshot.mask);
  state.maskedPixels = snapshot.maskedPixels;
  renderAll(copyHint);
}

function undoEdit() {
  if (state.isBusy) {
    return;
  }

  const nextState = undoHistory(state.historyPast, state.historyFuture);

  if (!nextState) {
    return;
  }

  state.historyPast = nextState.past;
  state.historyFuture = nextState.future;
  applyHistorySnapshot(nextState.snapshot, "Undid the last edit.");
}

function redoEdit() {
  if (state.isBusy) {
    return;
  }

  const nextState = redoHistory(state.historyPast, state.historyFuture);

  if (!nextState) {
    return;
  }

  state.historyPast = nextState.past;
  state.historyFuture = nextState.future;
  applyHistorySnapshot(nextState.snapshot, "Restored the undone edit.");
}

function getCanvasPoint(event) {
  const rect = overlayCanvas.getBoundingClientRect();
  const scaleX = overlayCanvas.width / rect.width;
  const scaleY = overlayCanvas.height / rect.height;

  return {
    x: (event.clientX - rect.left) * scaleX,
    y: (event.clientY - rect.top) * scaleY,
  };
}

function trackPointer(event) {
  state.hoverClientX = event.clientX;
  state.hoverClientY = event.clientY;
  state.isPointerOverCanvas = true;
  renderBrushPreview();
}

function clearPointerTracking() {
  state.isPointerOverCanvas = false;
  renderBrushPreview();
}

function startPanning(event) {
  state.isPanning = true;
  state.activePointerId = event.pointerId;
  state.panStartClientX = event.clientX;
  state.panStartClientY = event.clientY;
  state.panStartX = state.panX;
  state.panStartY = state.panY;
  updateOverlayCursor();
  overlayCanvas.setPointerCapture(event.pointerId);
}

function shouldStartPanning(event) {
  return canPanImage() && (event.button === 2 || (state.isSpacePressed && event.button === 0));
}

function continuePanning(event) {
  if (!state.isPanning || state.activePointerId !== event.pointerId) {
    return;
  }

  const nextPan = clampPanOffset(
    state.panStartX + (event.clientX - state.panStartClientX),
    state.panStartY + (event.clientY - state.panStartClientY),
    getCurrentViewportMetrics(),
  );

  state.panX = nextPan.panX;
  state.panY = nextPan.panY;
  applyViewport();
  syncActionStates();
}

function stopPanning(event) {
  if (!state.isPanning) {
    return;
  }

  if (event && state.activePointerId !== null && overlayCanvas.hasPointerCapture(state.activePointerId)) {
    overlayCanvas.releasePointerCapture(state.activePointerId);
  }

  state.isPanning = false;
  state.activePointerId = null;
  updateOverlayCursor();
}

function setViewportZoom(nextZoom, anchorClientX, anchorClientY) {
  if (!state.workingImageData) {
    return;
  }

  const clampedNextZoom = clampZoom(nextZoom);

  if (clampedNextZoom === state.zoom) {
    return;
  }

  const stageRect = stageFrame.getBoundingClientRect();
  const anchorX = anchorClientX ?? (stageRect.left + (stageRect.width / 2));
  const anchorY = anchorClientY ?? (stageRect.top + (stageRect.height / 2));
  const nextViewport = zoomViewportAtPoint({
    currentZoom: state.zoom,
    nextZoom: clampedNextZoom,
    panX: state.panX,
    panY: state.panY,
    anchorX: anchorX - stageRect.left,
    anchorY: anchorY - stageRect.top,
    stageWidth: stageRect.width,
    stageHeight: stageRect.height,
    imageWidth: state.imageWidth,
    imageHeight: state.imageHeight,
  });

  state.zoom = nextViewport.zoom;
  state.panX = nextViewport.panX;
  state.panY = nextViewport.panY;
  syncSliderLabels();
  applyViewport();
  syncActionStates();
}

function fitViewport() {
  state.zoom = MIN_ZOOM;
  state.panX = 0;
  state.panY = 0;
  syncSliderLabels();
  applyViewport();
  syncActionStates();
}

function startDrawing(event) {
  if (!state.workingImageData || state.isBusy) {
    return;
  }

  if (shouldStartPanning(event)) {
    event.preventDefault();
    startPanning(event);
    return;
  }

  if (event.button !== 0) {
    return;
  }

  event.preventDefault();
  trackPointer(event);
  const point = getCanvasPoint(event);
  state.isDrawing = true;
  state.lastPoint = point;
  state.strokeDirty = false;
  const maskedPixelDelta = paintMaskSegment(
    state.mask,
    state.imageWidth,
    state.imageHeight,
    point,
    point,
    Number(brushSizeInput.value) / 2,
    state.mode === "paint" ? 1 : 0,
  );
  state.maskedPixels += maskedPixelDelta;
  state.strokeDirty = maskedPixelDelta !== 0 || state.strokeDirty;

  if (state.strokeDirty) {
    renderOverlay();
    refreshMeta();
  }

  overlayCanvas.setPointerCapture(event.pointerId);
}

function continueDrawing(event) {
  trackPointer(event);

  if (state.isPanning) {
    continuePanning(event);
    return;
  }

  if (!state.isDrawing || !state.lastPoint || !state.workingImageData) {
    return;
  }

  const point = getCanvasPoint(event);
  const maskedPixelDelta = paintMaskSegment(
    state.mask,
    state.imageWidth,
    state.imageHeight,
    state.lastPoint,
    point,
    Number(brushSizeInput.value) / 2,
    state.mode === "paint" ? 1 : 0,
  );
  state.lastPoint = point;
  state.maskedPixels += maskedPixelDelta;
  state.strokeDirty = maskedPixelDelta !== 0 || state.strokeDirty;

  if (maskedPixelDelta !== 0) {
    renderOverlay();
    refreshMeta();
  }
}

function stopDrawing(event) {
  if (state.isPanning) {
    stopPanning(event);
  }

  if (event && (event.type === "pointerleave" || event.type === "pointercancel")) {
    clearPointerTracking();
  }

  if (!state.isDrawing) {
    return;
  }

  state.isDrawing = false;
  state.lastPoint = null;

  if (state.strokeDirty) {
    pushHistoryState();
    state.strokeDirty = false;
    syncActionStates();
  }

  if (event && overlayCanvas.hasPointerCapture(event.pointerId)) {
    overlayCanvas.releasePointerCapture(event.pointerId);
  }
}

async function loadImageElement(file) {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(file);
    const image = new Image();

    image.onload = () => {
      URL.revokeObjectURL(objectUrl);
      resolve(image);
    };

    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error("The selected file could not be opened as an image."));
    };

    image.src = objectUrl;
  });
}

async function loadImageFile(file) {
  if (!file) {
    return;
  }

  if (!file.type.startsWith("image/")) {
    setStatus("Please choose an image file.");
    return;
  }

  setBusy(true);
  setStatus("Loading image...");
  let loadedFrame = null;

  try {
    const image = await loadImageElement(file);
    const offscreenCanvas = document.createElement("canvas");
    offscreenCanvas.width = image.naturalWidth;
    offscreenCanvas.height = image.naturalHeight;
    const offscreenContext = offscreenCanvas.getContext("2d", { willReadFrequently: true });
    offscreenContext.drawImage(image, 0, 0);

    const imageData = offscreenContext.getImageData(0, 0, image.naturalWidth, image.naturalHeight);
    state.mediaType = "image";
    state.fileBaseName = getBaseFileName(file.name);
    state.preview = "edited";
    state.compareRatio = 50;
    state.zoom = MIN_ZOOM;
    state.panX = 0;
    state.panY = 0;
    syncSliderLabels();
    syncPreviewButtons();
    loadedFrame = imageData;
  } catch (error) {
    console.error(error);
    setStatus(error.message);
  } finally {
    setBusy(false);
  }

  if (loadedFrame) {
    applyPreviewFrame(loadedFrame, loadedFrame, false, "Brush over the watermark to create a mask.");
  }
}

function loadMediaFiles(files) {
  if (!files || files.length === 0) {
    return;
  }

  const addedItems = batchQueue.addFiles(files);
  if (addedItems.length === 0) {
    setStatus("Please choose an image file.");
    return;
  }

  batchDrawer.hidden = false;
  renderBatchCarousel();

  if (batchQueue.items.length > 0 && batchQueue.activeIndex === -1) {
    selectBatchItem(0);
  }
}

function selectBatchItem(index) {
  const item = batchQueue.items[index];
  if (!item) return;

  // Save current state to previous active item if needed
  if (batchQueue.activeIndex !== -1) {
    const prevItem = batchQueue.items[batchQueue.activeIndex];
    if (prevItem && state.workingImageData) {
      prevItem.originalImageData = cloneImageDataLike(state.originalImageData);
      prevItem.mask = state.mask ? Uint8Array.from(state.mask) : null;
      prevItem.maskedPixels = state.maskedPixels;
    }
  }

  batchQueue.activeIndex = index;
  renderBatchCarousel();
  loadImageFile(item.file).then(() => {
    // If the item had a saved mask, restore it
    if (item.mask && state.workingImageData) {
      state.mask = Uint8Array.from(item.mask);
      state.maskedPixels = item.maskedPixels;
      renderOverlay();
      refreshMeta();
    }
  });
}

function renderBatchCarousel() {
  batchCountBadge.textContent = `${batchQueue.items.length} items`;
  batchCarousel.innerHTML = "";
  
  let hasProcessed = false;

  batchQueue.items.forEach((item, index) => {
    if (item.status === BatchStatus.DONE) hasProcessed = true;
    
    const el = document.createElement("div");
    el.className = `batch-item ${index === batchQueue.activeIndex ? "is-active" : ""}`;
    el.title = item.name;
    
    const img = document.createElement("img");
    img.src = item.resultBlob ? URL.createObjectURL(item.resultBlob) : item.thumbnailUrl;
    img.className = "batch-item-img";
    el.appendChild(img);

    const statusEl = document.createElement("div");
    statusEl.className = `batch-item-status is-${item.status}`;
    if (item.status === BatchStatus.DONE) {
      statusEl.innerHTML = `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>`;
    } else if (item.status === BatchStatus.ERROR) {
      statusEl.innerHTML = `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`;
    } else if (item.status === BatchStatus.PROCESSING) {
      statusEl.innerHTML = `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"></path></svg>`;
    } else {
      statusEl.innerHTML = `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="1"></circle><circle cx="12" cy="5" r="1"></circle><circle cx="12" cy="19" r="1"></circle></svg>`;
    }
    el.appendChild(statusEl);

    const removeBtn = document.createElement("button");
    removeBtn.className = "batch-item-remove";
    removeBtn.innerHTML = `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`;
    removeBtn.onclick = (e) => {
      e.stopPropagation();
      batchQueue.removeItem(index);
      renderBatchCarousel();
      if (batchQueue.items.length === 0) {
        batchDrawer.hidden = true;
      } else if (index === batchQueue.activeIndex || batchQueue.activeIndex === -1) {
        selectBatchItem(Math.max(0, index - 1));
      }
    };
    el.appendChild(removeBtn);

    el.onclick = () => selectBatchItem(index);
    batchCarousel.appendChild(el);
  });
  
  batchDownloadButton.disabled = !hasProcessed;
  batchProcessButton.disabled = batchQueue.items.length === 0;
}

async function processBatch() {
  if (batchQueue.items.length === 0) return;
  
  // Save current item state
  if (batchQueue.activeIndex !== -1) {
    const prevItem = batchQueue.items[batchQueue.activeIndex];
    if (prevItem && state.workingImageData) {
      prevItem.originalImageData = cloneImageDataLike(state.originalImageData);
      prevItem.mask = state.mask ? Uint8Array.from(state.mask) : null;
      prevItem.maskedPixels = state.maskedPixels;
    }
  }

  setBusy(true);
  batchProgressContainer.hidden = false;
  batchProcessButton.disabled = true;
  batchDownloadButton.disabled = true;
  
  const applyGlobalMask = applyGlobalMaskCheckbox.checked;
  let globalMask = null;
  let globalWidth = 0;
  let globalHeight = 0;
  
  if (applyGlobalMask && batchQueue.activeIndex !== -1) {
    const activeItem = batchQueue.items[batchQueue.activeIndex];
    if (activeItem && activeItem.mask) {
      globalMask = activeItem.mask;
      globalWidth = state.imageWidth;
      globalHeight = state.imageHeight;
    }
  }

  let processedCount = 0;
  
  for (let i = 0; i < batchQueue.items.length; i++) {
    const item = batchQueue.items[i];
    item.status = BatchStatus.PROCESSING;
    renderBatchCarousel();
    
    batchProgressBar.style.width = `${(i / batchQueue.items.length) * 100}%`;
    batchProgressText.textContent = `Processing ${i + 1} of ${batchQueue.items.length}`;
    
    try {
      const image = await loadImageElement(item.file);
      const offscreenCanvas = document.createElement("canvas");
      offscreenCanvas.width = image.naturalWidth;
      offscreenCanvas.height = image.naturalHeight;
      const offscreenContext = offscreenCanvas.getContext("2d", { willReadFrequently: true });
      offscreenContext.drawImage(image, 0, 0);
      const imageData = offscreenContext.getImageData(0, 0, image.naturalWidth, image.naturalHeight);
      
      let maskToUse = item.mask;
      let maskPixels = item.maskedPixels;
      
      if (applyGlobalMask && globalMask) {
        const scaled = batchQueue.scaleMask(globalMask, globalWidth, globalHeight, image.naturalWidth, image.naturalHeight);
        maskToUse = scaled.mask;
        maskPixels = scaled.maskedPixels;
      }
      
      if (maskToUse && maskPixels > 0) {
        await new Promise((resolve) => requestAnimationFrame(() => resolve()));
        const healed = inpaintMaskedImage(imageData, maskToUse, image.naturalWidth, image.naturalHeight, {
          dilationRadius: Number(cleanupStrengthInput.value),
          sampleRadius: 5,
          maxRadius: 28,
        });
        
        const exportCanvas = document.createElement("canvas");
        exportCanvas.width = image.naturalWidth;
        exportCanvas.height = image.naturalHeight;
        const exportContext = exportCanvas.getContext("2d");
        exportContext.putImageData(createImageDataLike(new Uint8ClampedArray(healed.data), image.naturalWidth, image.naturalHeight), 0, 0);
        
        item.resultBlob = await new Promise(resolve => exportCanvas.toBlob(resolve, "image/png"));
      } else {
        item.resultBlob = item.file; // Fallback to original if no mask
      }
      
      item.status = BatchStatus.DONE;
      processedCount++;
    } catch (e) {
      console.error(e);
      item.status = BatchStatus.ERROR;
    }
  }
  
  batchProgressBar.style.width = `100%`;
  batchProgressText.textContent = `Completed ${processedCount} of ${batchQueue.items.length}`;
  renderBatchCarousel();
  
  setTimeout(() => {
    batchProgressContainer.hidden = true;
    setBusy(false);
    batchProcessButton.disabled = false;
    // Reload active item to show changes if it was processed
    if (batchQueue.activeIndex !== -1) selectBatchItem(batchQueue.activeIndex);
  }, 1000);
}

async function downloadBatchZip() {
  setBusy(true);
  setStatus("Generating ZIP file...");
  
  try {
    const zipBlob = await batchQueue.createZipBlob();
    const link = document.createElement("a");
    link.href = URL.createObjectURL(zipBlob);
    link.download = `watermark-remover-batch-${Date.now()}.zip`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(link.href), 5000);
  } catch (error) {
    console.error(error);
    alert(error.message);
  } finally {
    setBusy(false);
    setStatus("ZIP download complete.");
  }
}


function clearMask() {
  if (!state.mask) {
    return;
  }

  state.mask.fill(0);
  state.maskedPixels = 0;
  pushHistoryState();
  renderOverlay();
  refreshMeta("Mask cleared. Brush over the watermark to try again.");
}

function resetImage() {
  if (!state.originalImageData) {
    return;
  }

  state.workingImageData = cloneImageDataLike(state.originalImageData);
  state.mask.fill(0);
  state.maskedPixels = 0;
  state.preview = "edited";
  syncPreviewButtons();
  pushHistoryState();
  renderAll("Image reset. Paint a new mask to try another cleanup.");
}

async function removeWatermark() {
  if (!state.workingImageData || !state.mask || state.maskedPixels === 0) {
    return;
  }

  setBusy(true);
  setStatus("Cleaning the selected area...");

  await new Promise((resolve) => requestAnimationFrame(() => resolve()));
  let cleanupComplete = false;

  try {
    const healed = inpaintMaskedImage(state.workingImageData, state.mask, state.imageWidth, state.imageHeight, {
      dilationRadius: Number(cleanupStrengthInput.value),
      sampleRadius: 5,
      maxRadius: 28,
    });

    state.workingImageData = cloneImageDataLike(healed);
    state.mask.fill(0);
    state.maskedPixels = 0;
    state.preview = "edited";
    syncPreviewButtons();
    pushHistoryState();
    cleanupComplete = true;
  } catch (error) {
    console.error(error);
    setStatus("Watermark removal failed. Try a smaller mask or reload the image.");
  } finally {
    setBusy(false);
  }

  if (cleanupComplete) {
    renderAll("Cleanup complete. Paint again if another pass is needed, or download the result.");
  }
}

function downloadResult() {
  if (!state.workingImageData) {
    return;
  }

  const exportCanvas = document.createElement("canvas");
  exportCanvas.width = state.imageWidth;
  exportCanvas.height = state.imageHeight;
  const exportContext = exportCanvas.getContext("2d");
  exportContext.putImageData(createImageDataLike(new Uint8ClampedArray(state.workingImageData.data), state.imageWidth, state.imageHeight), 0, 0);

  const link = document.createElement("a");
  link.href = exportCanvas.toDataURL("image/png");
  link.download = `${state.fileBaseName}-cleaned.png`;
  link.click();
}

function setPreview(preview) {
  state.preview = preview;
  syncPreviewButtons();
  renderAll();
}

function handleCompareInput(event) {
  state.compareRatio = Number(event.target.value);
  syncSliderLabels();
  renderCompareCanvas();
}

function handleStageWheel(event) {
  if (!state.workingImageData || state.isBusy) {
    return;
  }

  event.preventDefault();
  const zoomDelta = event.deltaY < 0 ? 1.12 : 1 / 1.12;
  setViewportZoom(state.zoom * zoomDelta, event.clientX, event.clientY);
}

function handleGlobalKeydown(event) {
  if (event.code === "Space") {
    state.isSpacePressed = true;

    if (canPanImage()) {
      event.preventDefault();
    }

    updateOverlayCursor();
    return;
  }

  if (event.key === "0" && state.workingImageData && !event.ctrlKey && !event.metaKey && !event.altKey) {
    event.preventDefault();
    fitViewport();
    return;
  }

  const hasModifier = event.ctrlKey || event.metaKey;

  if (!hasModifier) {
    return;
  }

  const normalizedKey = event.key.toLowerCase();

  if (normalizedKey === "z") {
    event.preventDefault();

    if (event.shiftKey) {
      redoEdit();
    } else {
      undoEdit();
    }

    return;
  }

  if (normalizedKey === "y") {
    event.preventDefault();
    redoEdit();
    return;
  }

  if (normalizedKey === "0" && state.workingImageData) {
    event.preventDefault();
    fitViewport();
  }
}

function handleGlobalKeyup(event) {
  if (event.code === "Space") {
    state.isSpacePressed = false;
    updateOverlayCursor();
  }
}

function handleFileInputChange(event) {
  loadMediaFiles(event.target.files);
  event.target.value = "";
}

function handlePointerEnter(event) {
  trackPointer(event);
}

function handlePointerLeave(event) {
  clearPointerTracking();
  stopDrawing(event);
}

function handleDrop(event) {
  event.preventDefault();
  stageFrame.classList.remove("is-dragging");
  loadMediaFiles(event.dataTransfer.files);
}

batchProcessButton.addEventListener("click", processBatch);
batchDownloadButton.addEventListener("click", downloadBatchZip);

fileInput.addEventListener("change", handleFileInputChange);
brushSizeInput.addEventListener("input", syncSliderLabels);
cleanupStrengthInput.addEventListener("input", syncSliderLabels);
compareSlider.addEventListener("input", handleCompareInput);
zoomOutButton.addEventListener("click", () => {
  setViewportZoom(state.zoom / 1.2);
});
zoomInButton.addEventListener("click", () => {
  setViewportZoom(state.zoom * 1.2);
});
fitViewButton.addEventListener("click", fitViewport);

paintModeButton.addEventListener("click", () => {
  state.mode = "paint";
  syncModeButtons();
});

eraseModeButton.addEventListener("click", () => {
  state.mode = "erase";
  syncModeButtons();
});

previewEditedButton.addEventListener("click", () => {
  setPreview("edited");
});

previewOriginalButton.addEventListener("click", () => {
  setPreview("original");
});

previewCompareButton.addEventListener("click", () => {
  setPreview("compare");
});

undoButton.addEventListener("click", undoEdit);
redoButton.addEventListener("click", redoEdit);
clearMaskButton.addEventListener("click", clearMask);
resetButton.addEventListener("click", resetImage);
removeButton.addEventListener("click", removeWatermark);
downloadButton.addEventListener("click", downloadResult);

overlayCanvas.addEventListener("pointerdown", startDrawing);
overlayCanvas.addEventListener("pointerenter", handlePointerEnter);
overlayCanvas.addEventListener("pointermove", continueDrawing);
overlayCanvas.addEventListener("pointerup", stopDrawing);
overlayCanvas.addEventListener("pointerleave", handlePointerLeave);
overlayCanvas.addEventListener("pointercancel", handlePointerLeave);
overlayCanvas.addEventListener("contextmenu", (event) => {
  event.preventDefault();
});

stageFrame.addEventListener("dragover", (event) => {
  event.preventDefault();
  stageFrame.classList.add("is-dragging");
});

stageFrame.addEventListener("dragleave", () => {
  stageFrame.classList.remove("is-dragging");
});

stageFrame.addEventListener("drop", handleDrop);
stageFrame.addEventListener("wheel", handleStageWheel, { passive: false });

window.addEventListener("resize", () => {
  applyViewport();
  syncActionStates();
});
document.addEventListener("keydown", handleGlobalKeydown);
document.addEventListener("keyup", handleGlobalKeyup);

syncSliderLabels();
syncModeButtons();
syncPreviewButtons();
syncActionStates();
