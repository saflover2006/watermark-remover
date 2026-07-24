function getRectRight(rect) {
  return rect.right ?? (rect.left + rect.width);
}

function getRectBottom(rect) {
  return rect.bottom ?? (rect.top + rect.height);
}

export function getBrushPreviewLayout({
  stageRect,
  overlayRect,
  canvasWidth,
  canvasHeight,
  brushDiameter,
  clientX,
  clientY,
}) {
  if (canvasWidth <= 0 || canvasHeight <= 0 || brushDiameter <= 0) {
    return { visible: false };
  }

  if (overlayRect.width <= 0 || overlayRect.height <= 0) {
    return { visible: false };
  }

  const overlayRight = getRectRight(overlayRect);
  const overlayBottom = getRectBottom(overlayRect);
  const isInsideOverlay =
    clientX >= overlayRect.left &&
    clientX <= overlayRight &&
    clientY >= overlayRect.top &&
    clientY <= overlayBottom;

  if (!isInsideOverlay) {
    return { visible: false };
  }

  const scaleX = overlayRect.width / canvasWidth;
  const scaleY = overlayRect.height / canvasHeight;
  const diameter = brushDiameter * Math.min(scaleX, scaleY);
  const centerX = clientX - stageRect.left;
  const centerY = clientY - stageRect.top;

  return {
    visible: true,
    centerX,
    centerY,
    diameter,
    left: centerX - (diameter / 2),
    top: centerY - (diameter / 2),
  };
}
