export function stampMaskCircle(mask, width, height, centerX, centerY, radius, value) {
  let maskedPixelDelta = 0;
  const minX = Math.max(0, Math.floor(centerX - radius));
  const maxX = Math.min(width - 1, Math.ceil(centerX + radius));
  const minY = Math.max(0, Math.floor(centerY - radius));
  const maxY = Math.min(height - 1, Math.ceil(centerY + radius));
  const radiusSquared = radius * radius;

  for (let y = minY; y <= maxY; y += 1) {
    for (let x = minX; x <= maxX; x += 1) {
      const dx = x - centerX;
      const dy = y - centerY;

      if ((dx * dx) + (dy * dy) > radiusSquared) {
        continue;
      }

      const maskIndex = (y * width) + x;
      const previousValue = mask[maskIndex];

      if (previousValue === value) {
        continue;
      }

      mask[maskIndex] = value;
      maskedPixelDelta += value === 1 ? 1 : -1;
    }
  }

  return maskedPixelDelta;
}

export function paintMaskSegment(mask, width, height, startPoint, endPoint, radius, value) {
  const steps = Math.max(1, Math.ceil(Math.hypot(endPoint.x - startPoint.x, endPoint.y - startPoint.y) / Math.max(1, radius * 0.3)));
  let maskedPixelDelta = 0;

  for (let step = 0; step <= steps; step += 1) {
    const t = step / steps;
    const x = startPoint.x + ((endPoint.x - startPoint.x) * t);
    const y = startPoint.y + ((endPoint.y - startPoint.y) * t);
    maskedPixelDelta += stampMaskCircle(mask, width, height, x, y, radius, value);
  }

  return maskedPixelDelta;
}
