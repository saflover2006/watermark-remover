import assert from "node:assert/strict";
import test from "node:test";

import { createImageDataLike, dilateMask, inpaintMaskedImage } from "../src/inpaint.js";

function createSolidImage(width, height, red, green, blue, alpha = 255) {
  const data = new Uint8ClampedArray(width * height * 4);

  for (let offset = 0; offset < data.length; offset += 4) {
    data[offset] = red;
    data[offset + 1] = green;
    data[offset + 2] = blue;
    data[offset + 3] = alpha;
  }

  return createImageDataLike(data, width, height);
}

test("dilateMask expands a single center pixel", () => {
  const mask = new Uint8Array(25);
  mask[12] = 1;

  const dilated = dilateMask(mask, 5, 5, 1);

  assert.equal(dilated[12], 1);
  assert.equal(dilated[7], 1);
  assert.equal(dilated[11], 1);
  assert.equal(dilated[13], 1);
  assert.equal(dilated[17], 1);
  assert.equal(dilated[0], 0);
});

test("inpaintMaskedImage keeps a uniform image stable", () => {
  const image = createSolidImage(3, 3, 42, 84, 126);
  const mask = new Uint8Array(9);
  mask[4] = 1;

  const healed = inpaintMaskedImage(image, mask, 3, 3, {
    dilationRadius: 0,
    sampleRadius: 2,
    maxRadius: 6,
  });

  const pixelOffset = 4 * 4;
  assert.equal(healed.data[pixelOffset], 42);
  assert.equal(healed.data[pixelOffset + 1], 84);
  assert.equal(healed.data[pixelOffset + 2], 126);
  assert.equal(healed.data[pixelOffset + 3], 255);
});

test("inpaintMaskedImage fills a missing gradient sample from nearby pixels", () => {
  const data = new Uint8ClampedArray([
    0, 0, 0, 255,
    50, 0, 0, 255,
    100, 0, 0, 255,
    150, 0, 0, 255,
    200, 0, 0, 255,
  ]);
  const image = createImageDataLike(data, 5, 1);
  const mask = new Uint8Array(5);
  mask[2] = 1;

  const healed = inpaintMaskedImage(image, mask, 5, 1, {
    dilationRadius: 0,
    sampleRadius: 2,
    maxRadius: 6,
  });

  assert.equal(healed.data[0], 0);
  assert.equal(healed.data[16], 200);
  assert.ok(healed.data[8] >= 70 && healed.data[8] <= 130);
});
