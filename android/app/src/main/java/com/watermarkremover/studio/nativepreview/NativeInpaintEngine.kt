package com.watermarkremover.studio.nativepreview

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object NativeInpaintEngine {

    const val DEFAULT_SAMPLE_RADIUS = 5
    const val DEFAULT_MAX_RADIUS = 28

    data class PreparedMask(
        val roiLeft: Int,
        val roiTop: Int,
        val roiWidth: Int,
        val roiHeight: Int,
        val dilatedMask: ByteArray,
        val maskMinX: Int,
        val maskMinY: Int,
        val maskMaxX: Int,
        val maskMaxY: Int,
    )

    private data class MaskBounds(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
    )

    private data class CircleOffset(
        val dx: Int,
        val dy: Int,
        val distanceSquared: Int,
    )

    private val circleOffsetsCache = mutableMapOf<Int, List<CircleOffset>>()

    fun inpaintMaskedBitmap(
        source: Bitmap,
        mask: ByteArray,
        width: Int,
        height: Int,
        dilationRadius: Int = 2,
        sampleRadius: Int = DEFAULT_SAMPLE_RADIUS,
        maxRadius: Int = DEFAULT_MAX_RADIUS,
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        inpaintMaskedBitmapInPlace(output, mask, width, height, dilationRadius, sampleRadius, maxRadius)
        return output
    }

    fun inpaintMaskedBitmapInPlace(
        target: Bitmap,
        mask: ByteArray,
        width: Int,
        height: Int,
        dilationRadius: Int = 2,
        sampleRadius: Int = DEFAULT_SAMPLE_RADIUS,
        maxRadius: Int = DEFAULT_MAX_RADIUS,
    ) {
        require(target.width == width && target.height == height) {
            "Bitmap dimensions must match the provided mask dimensions."
        }

        val safeStartingRadius = max(2, sampleRadius)
        val safeMaxRadius = max(safeStartingRadius, maxRadius)
        val preparedMask = prepareMask(mask, width, height, dilationRadius, safeMaxRadius) ?: return
        inpaintPreparedMaskInPlace(target, preparedMask, safeStartingRadius, safeMaxRadius)
    }

    fun prepareMask(
        mask: ByteArray,
        width: Int,
        height: Int,
        dilationRadius: Int = 2,
        maxRadius: Int = DEFAULT_MAX_RADIUS,
    ): PreparedMask? {
        val safeDilationRadius = max(0, dilationRadius)
        val safeMaxRadius = max(2, maxRadius)
        val baseBounds = getMaskBounds(mask, width, height) ?: return null
        val roiPadding = safeDilationRadius + safeMaxRadius
        val roiLeft = max(0, baseBounds.minX - roiPadding)
        val roiTop = max(0, baseBounds.minY - roiPadding)
        val roiRight = min(width - 1, baseBounds.maxX + roiPadding)
        val roiBottom = min(height - 1, baseBounds.maxY + roiPadding)
        val roiWidth = roiRight - roiLeft + 1
        val roiHeight = roiBottom - roiTop + 1
        val roiMask = ByteArray(roiWidth * roiHeight)

        for (row in 0 until roiHeight) {
            val sourceOffset = ((roiTop + row) * width) + roiLeft
            val targetOffset = row * roiWidth
            System.arraycopy(mask, sourceOffset, roiMask, targetOffset, roiWidth)
        }

        val dilatedMask = if (safeDilationRadius > 0) {
            dilateMask(roiMask, roiWidth, roiHeight, safeDilationRadius)
        } else {
            roiMask.copyOf()
        }
        val roiBounds = getMaskBounds(dilatedMask, roiWidth, roiHeight) ?: return null

        return PreparedMask(
            roiLeft = roiLeft,
            roiTop = roiTop,
            roiWidth = roiWidth,
            roiHeight = roiHeight,
            dilatedMask = dilatedMask,
            maskMinX = roiBounds.minX,
            maskMinY = roiBounds.minY,
            maskMaxX = roiBounds.maxX,
            maskMaxY = roiBounds.maxY,
        )
    }

    fun inpaintPreparedMaskInPlace(
        target: Bitmap,
        preparedMask: PreparedMask,
        sampleRadius: Int = DEFAULT_SAMPLE_RADIUS,
        maxRadius: Int = DEFAULT_MAX_RADIUS,
    ) {
        val safeStartingRadius = max(2, sampleRadius)
        val safeMaxRadius = max(safeStartingRadius, maxRadius)
        val pixels = IntArray(preparedMask.roiWidth * preparedMask.roiHeight)
        target.getPixels(
            pixels,
            0,
            preparedMask.roiWidth,
            preparedMask.roiLeft,
            preparedMask.roiTop,
            preparedMask.roiWidth,
            preparedMask.roiHeight,
        )

        val workingMask = preparedMask.dilatedMask.copyOf()
        val bounds = MaskBounds(
            minX = preparedMask.maskMinX,
            minY = preparedMask.maskMinY,
            maxX = preparedMask.maskMaxX,
            maxY = preparedMask.maskMaxY,
        )

        var remaining = countMaskedPixels(workingMask, preparedMask.roiWidth, bounds)
        var currentRadius = safeStartingRadius

        while (remaining > 0) {
            var filledThisPass = 0
            val nextMask = workingMask.copyOf()

            for (y in bounds.minY..bounds.maxY) {
                for (x in bounds.minX..bounds.maxX) {
                    val index = (y * preparedMask.roiWidth) + x
                    if (workingMask[index].toInt() == 0) {
                        continue
                    }

                    if (!hasKnownNeighbor(workingMask, preparedMask.roiWidth, preparedMask.roiHeight, x, y)) {
                        continue
                    }

                    val color = sampleKnownPixelAverage(
                        pixels,
                        workingMask,
                        preparedMask.roiWidth,
                        preparedMask.roiHeight,
                        x,
                        y,
                        currentRadius,
                    )
                        ?: continue

                    pixels[index] = color
                    nextMask[index] = 0
                    filledThisPass += 1
                }
            }

            nextMask.copyInto(workingMask)
            remaining -= filledThisPass

            if (remaining <= 0) {
                break
            }

            if (filledThisPass == 0) {
                remaining -= fillRemainingWithNearest(
                    pixels,
                    workingMask,
                    preparedMask.roiWidth,
                    preparedMask.roiHeight,
                    bounds,
                    safeMaxRadius,
                )
                break
            }

            if (currentRadius < safeMaxRadius) {
                currentRadius += 1
            }
        }

        target.setPixels(
            pixels,
            0,
            preparedMask.roiWidth,
            preparedMask.roiLeft,
            preparedMask.roiTop,
            preparedMask.roiWidth,
            preparedMask.roiHeight,
        )
    }

    private fun createCircleOffsets(radius: Int): List<CircleOffset> {
        return circleOffsetsCache.getOrPut(radius) {
            val offsets = mutableListOf<CircleOffset>()
            val radiusSquared = radius * radius

            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (dx == 0 && dy == 0) {
                        continue
                    }

                    val distanceSquared = (dx * dx) + (dy * dy)
                    if (distanceSquared > radiusSquared) {
                        continue
                    }

                    offsets += CircleOffset(dx, dy, distanceSquared)
                }
            }

            offsets.sortedBy { it.distanceSquared }
        }
    }

    private fun getMaskBounds(mask: ByteArray, width: Int, height: Int): MaskBounds? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[(y * width) + x].toInt() == 0) {
                    continue
                }

                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }

        return if (maxX == -1 || maxY == -1) null else MaskBounds(minX, minY, maxX, maxY)
    }

    private fun countMaskedPixels(mask: ByteArray, width: Int, bounds: MaskBounds): Int {
        var count = 0

        for (y in bounds.minY..bounds.maxY) {
            for (x in bounds.minX..bounds.maxX) {
                count += if (mask[(y * width) + x].toInt() != 0) 1 else 0
            }
        }

        return count
    }

    private fun dilateMask(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        if (radius <= 0) {
            return mask.copyOf()
        }

        val result = mask.copyOf()
        val offsets = createCircleOffsets(radius)

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[(y * width) + x].toInt() == 0) {
                    continue
                }

                for (offset in offsets) {
                    val sampleX = x + offset.dx
                    val sampleY = y + offset.dy
                    if (sampleX !in 0 until width || sampleY !in 0 until height) {
                        continue
                    }

                    result[(sampleY * width) + sampleX] = 1
                }
            }
        }

        return result
    }

    private fun hasKnownNeighbor(mask: ByteArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) {
                    continue
                }

                val sampleX = x + dx
                val sampleY = y + dy
                if (sampleX !in 0 until width || sampleY !in 0 until height) {
                    continue
                }

                if (mask[(sampleY * width) + sampleX].toInt() == 0) {
                    return true
                }
            }
        }

        return false
    }

    private fun sampleKnownPixelAverage(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radius: Int,
    ): Int? {
        val offsets = createCircleOffsets(radius)
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var alpha = 0.0
        var totalWeight = 0.0
        var sampleCount = 0

        for (offset in offsets) {
            val sampleX = x + offset.dx
            val sampleY = y + offset.dy
            if (sampleX !in 0 until width || sampleY !in 0 until height) {
                continue
            }

            val index = (sampleY * width) + sampleX
            if (mask[index].toInt() != 0) {
                continue
            }

            val pixel = pixels[index]
            val weight = 1.0 / offset.distanceSquared
            red += ((pixel ushr 16) and 0xFF) * weight
            green += ((pixel ushr 8) and 0xFF) * weight
            blue += (pixel and 0xFF) * weight
            alpha += ((pixel ushr 24) and 0xFF) * weight
            totalWeight += weight
            sampleCount += 1
        }

        if (sampleCount < 3 || totalWeight == 0.0) {
            return null
        }

        val a = (alpha / totalWeight).roundToInt().coerceIn(0, 255)
        val r = (red / totalWeight).roundToInt().coerceIn(0, 255)
        val g = (green / totalWeight).roundToInt().coerceIn(0, 255)
        val b = (blue / totalWeight).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun nearestKnownColor(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        maxRadius: Int,
    ): Int? {
        for (radius in 1..maxRadius) {
            var red = 0
            var green = 0
            var blue = 0
            var alpha = 0
            var sampleCount = 0

            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (abs(dx) != radius && abs(dy) != radius) {
                        continue
                    }

                    val sampleX = x + dx
                    val sampleY = y + dy
                    if (sampleX !in 0 until width || sampleY !in 0 until height) {
                        continue
                    }

                    val index = (sampleY * width) + sampleX
                    if (mask[index].toInt() != 0) {
                        continue
                    }

                    val pixel = pixels[index]
                    red += (pixel ushr 16) and 0xFF
                    green += (pixel ushr 8) and 0xFF
                    blue += pixel and 0xFF
                    alpha += (pixel ushr 24) and 0xFF
                    sampleCount += 1
                }
            }

            if (sampleCount > 0) {
                val a = (alpha / sampleCount).coerceIn(0, 255)
                val r = (red / sampleCount).coerceIn(0, 255)
                val g = (green / sampleCount).coerceIn(0, 255)
                val b = (blue / sampleCount).coerceIn(0, 255)
                return (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return null
    }

    private fun fillRemainingWithNearest(
        pixels: IntArray,
        mask: ByteArray,
        width: Int,
        height: Int,
        bounds: MaskBounds,
        maxRadius: Int,
    ): Int {
        var filled = 0

        for (y in bounds.minY..bounds.maxY) {
            for (x in bounds.minX..bounds.maxX) {
                val index = (y * width) + x
                if (mask[index].toInt() == 0) {
                    continue
                }

                val color = nearestKnownColor(pixels, mask, width, height, x, y, maxRadius) ?: continue
                pixels[index] = color
                mask[index] = 0
                filled += 1
            }
        }

        return filled
    }
}
