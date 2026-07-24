export const MIN_ZOOM = 1;
export const MAX_ZOOM = 6;
export const VIEWPORT_PADDING = 32;

export function clampZoom(zoom, minZoom = MIN_ZOOM, maxZoom = MAX_ZOOM) {
  return Math.min(maxZoom, Math.max(minZoom, zoom));
}

export function getViewportMetrics(stageWidth, stageHeight, imageWidth, imageHeight, zoom, padding = VIEWPORT_PADDING) {
  const safeStageWidth = Math.max(1, stageWidth);
  const safeStageHeight = Math.max(1, stageHeight);
  const availableWidth = Math.max(1, safeStageWidth - padding);
  const availableHeight = Math.max(1, safeStageHeight - padding);
  const fitScale = Math.min(availableWidth / imageWidth, availableHeight / imageHeight);
  const baseWidth = imageWidth * fitScale;
  const baseHeight = imageHeight * fitScale;

  return {
    stageWidth: safeStageWidth,
    stageHeight: safeStageHeight,
    availableWidth,
    availableHeight,
    baseWidth,
    baseHeight,
    displayWidth: baseWidth * zoom,
    displayHeight: baseHeight * zoom,
  };
}

export function clampPanOffset(panX, panY, metrics) {
  const limitX = Math.max(0, (metrics.displayWidth - metrics.availableWidth) / 2);
  const limitY = Math.max(0, (metrics.displayHeight - metrics.availableHeight) / 2);

  return {
    panX: Math.min(limitX, Math.max(-limitX, panX)),
    panY: Math.min(limitY, Math.max(-limitY, panY)),
    limitX,
    limitY,
  };
}

export function zoomViewportAtPoint({
  currentZoom,
  nextZoom,
  panX,
  panY,
  anchorX,
  anchorY,
  stageWidth,
  stageHeight,
  imageWidth,
  imageHeight,
  padding = VIEWPORT_PADDING,
}) {
  const clampedNextZoom = clampZoom(nextZoom);
  const currentMetrics = getViewportMetrics(stageWidth, stageHeight, imageWidth, imageHeight, currentZoom, padding);
  const nextMetrics = getViewportMetrics(stageWidth, stageHeight, imageWidth, imageHeight, clampedNextZoom, padding);
  const ratio = clampedNextZoom / currentZoom;
  const currentCenterX = (currentMetrics.stageWidth / 2) + panX;
  const currentCenterY = (currentMetrics.stageHeight / 2) + panY;
  const nextCenterX = anchorX - ((anchorX - currentCenterX) * ratio);
  const nextCenterY = anchorY - ((anchorY - currentCenterY) * ratio);

  return {
    zoom: clampedNextZoom,
    ...clampPanOffset(nextCenterX - (currentMetrics.stageWidth / 2), nextCenterY - (currentMetrics.stageHeight / 2), nextMetrics),
    metrics: nextMetrics,
  };
}
