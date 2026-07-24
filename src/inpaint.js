const RGBA_CHANNELS = 4;

export function createImageDataLike(data, width, height) {
  if (typeof ImageData !== "undefined") {
    return new ImageData(data, width, height);
  }

  return { data, width, height };
}

export function cloneImageDataLike(imageData) {
  return createImageDataLike(new Uint8ClampedArray(imageData.data), imageData.width, imageData.height);
}

export function countMaskedPixels(mask, bounds) {
  let count = 0;

  if (!bounds) {
    for (let index = 0; index < mask.length; index += 1) {
      count += mask[index] ? 1 : 0;
    }

    return count;
  }

  for (let y = bounds.minY; y <= bounds.maxY; y += 1) {
    for (let x = bounds.minX; x <= bounds.maxX; x += 1) {
      count += mask[(y * bounds.width) + x] ? 1 : 0;
    }
  }

  return count;
}

export function getMaskBounds(mask, width, height) {
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;

  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      if (!mask[(y * width) + x]) {
        continue;
      }

      if (x < minX) {
        minX = x;
      }

      if (y < minY) {
        minY = y;
      }

      if (x > maxX) {
        maxX = x;
      }

      if (y > maxY) {
        maxY = y;
      }
    }
  }

  if (maxX === -1 || maxY === -1) {
    return null;
  }

  return { minX, minY, maxX, maxY, width, height };
}

function createCircleOffsets(radius) {
  const offsets = [];
  const radiusSquared = radius * radius;

  for (let dy = -radius; dy <= radius; dy += 1) {
    for (let dx = -radius; dx <= radius; dx += 1) {
      if (dx === 0 && dy === 0) {
        continue;
      }

      const distanceSquared = (dx * dx) + (dy * dy);

      if (distanceSquared > radiusSquared) {
        continue;
      }

      offsets.push({ dx, dy, distanceSquared });
    }
  }

  offsets.sort((left, right) => left.distanceSquared - right.distanceSquared);
  return offsets;
}

export function dilateMask(mask, width, height, radius = 0) {
  if (radius <= 0) {
    return Uint8Array.from(mask);
  }

  const result = Uint8Array.from(mask);
  const offsets = createCircleOffsets(radius);

  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      if (!mask[(y * width) + x]) {
        continue;
      }

      for (const offset of offsets) {
        const sampleX = x + offset.dx;
        const sampleY = y + offset.dy;

        if (sampleX < 0 || sampleX >= width || sampleY < 0 || sampleY >= height) {
          continue;
        }

        result[(sampleY * width) + sampleX] = 1;
      }
    }
  }

  return result;
}

function sampleKnownPixelAverage(data, mask, width, height, x, y, radius) {
  const offsets = createCircleOffsets(radius);
  let red = 0;
  let green = 0;
  let blue = 0;
  let alpha = 0;
  let totalWeight = 0;
  let sampleCount = 0;

  for (const offset of offsets) {
    const sampleX = x + offset.dx;
    const sampleY = y + offset.dy;

    if (sampleX < 0 || sampleX >= width || sampleY < 0 || sampleY >= height) {
      continue;
    }

    const sampleIndex = (sampleY * width) + sampleX;

    if (mask[sampleIndex]) {
      continue;
    }

    const weight = 1 / offset.distanceSquared;
    const pixelOffset = sampleIndex * RGBA_CHANNELS;

    red += data[pixelOffset] * weight;
    green += data[pixelOffset + 1] * weight;
    blue += data[pixelOffset + 2] * weight;
    alpha += data[pixelOffset + 3] * weight;
    totalWeight += weight;
    sampleCount += 1;
  }

  if (sampleCount < 3 || totalWeight === 0) {
    return null;
  }

  return [
    Math.round(red / totalWeight),
    Math.round(green / totalWeight),
    Math.round(blue / totalWeight),
    Math.round(alpha / totalWeight),
  ];
}

function nearestKnownColor(data, mask, width, height, x, y, maxRadius) {
  for (let radius = 1; radius <= maxRadius; radius += 1) {
    let red = 0;
    let green = 0;
    let blue = 0;
    let alpha = 0;
    let sampleCount = 0;

    for (let dy = -radius; dy <= radius; dy += 1) {
      for (let dx = -radius; dx <= radius; dx += 1) {
        if (Math.abs(dx) !== radius && Math.abs(dy) !== radius) {
          continue;
        }

        const sampleX = x + dx;
        const sampleY = y + dy;

        if (sampleX < 0 || sampleX >= width || sampleY < 0 || sampleY >= height) {
          continue;
        }

        const sampleIndex = (sampleY * width) + sampleX;

        if (mask[sampleIndex]) {
          continue;
        }

        const pixelOffset = sampleIndex * RGBA_CHANNELS;
        red += data[pixelOffset];
        green += data[pixelOffset + 1];
        blue += data[pixelOffset + 2];
        alpha += data[pixelOffset + 3];
        sampleCount += 1;
      }
    }

    if (sampleCount > 0) {
      return [
        Math.round(red / sampleCount),
        Math.round(green / sampleCount),
        Math.round(blue / sampleCount),
        Math.round(alpha / sampleCount),
      ];
    }
  }

  return null;
}

function fillRemainingWithNearest(data, mask, width, height, bounds, maxRadius) {
  let filled = 0;

  for (let y = bounds.minY; y <= bounds.maxY; y += 1) {
    for (let x = bounds.minX; x <= bounds.maxX; x += 1) {
      const index = (y * width) + x;

      if (!mask[index]) {
        continue;
      }

      const color = nearestKnownColor(data, mask, width, height, x, y, maxRadius);

      if (!color) {
        continue;
      }

      const pixelOffset = index * RGBA_CHANNELS;
      data[pixelOffset] = color[0];
      data[pixelOffset + 1] = color[1];
      data[pixelOffset + 2] = color[2];
      data[pixelOffset + 3] = color[3];
      mask[index] = 0;
      filled += 1;
    }
  }

  return filled;
}

export function inpaintMaskedImage(sourceImage, mask, width, height, options = {}) {
  const sourceData = new Uint8ClampedArray(sourceImage.data);
  const dilationRadius = Math.max(0, Math.floor(options.dilationRadius ?? 2));
  const startingRadius = Math.max(2, Math.floor(options.sampleRadius ?? 5));
  const maxRadius = Math.max(startingRadius, Math.floor(options.maxRadius ?? 24));
  const workingMask = dilationRadius > 0 ? dilateMask(mask, width, height, dilationRadius) : Uint8Array.from(mask);
  const bounds = getMaskBounds(workingMask, width, height);

  if (!bounds) {
    return createImageDataLike(sourceData, width, height);
  }

  let remaining = countMaskedPixels(workingMask, bounds);
  let sampleRadius = startingRadius;

  // Fill the masked region from the outside in so each pass can build on the last one.
  while (remaining > 0) {
    let filledThisPass = 0;
    const nextMask = workingMask.slice();

    for (let y = bounds.minY; y <= bounds.maxY; y += 1) {
      for (let x = bounds.minX; x <= bounds.maxX; x += 1) {
        const index = (y * width) + x;

        if (!workingMask[index]) {
          continue;
        }

        const color = sampleKnownPixelAverage(sourceData, workingMask, width, height, x, y, sampleRadius);

        if (!color) {
          continue;
        }

        const pixelOffset = index * RGBA_CHANNELS;
        sourceData[pixelOffset] = color[0];
        sourceData[pixelOffset + 1] = color[1];
        sourceData[pixelOffset + 2] = color[2];
        sourceData[pixelOffset + 3] = color[3];
        nextMask[index] = 0;
        filledThisPass += 1;
      }
    }

    for (let index = 0; index < workingMask.length; index += 1) {
      workingMask[index] = nextMask[index];
    }

    remaining -= filledThisPass;

    if (remaining <= 0) {
      break;
    }

    if (filledThisPass === 0) {
      remaining -= fillRemainingWithNearest(sourceData, workingMask, width, height, bounds, maxRadius);
      break;
    }

    if (sampleRadius < maxRadius) {
      sampleRadius += 1;
    }
  }

  return createImageDataLike(sourceData, width, height);
}
