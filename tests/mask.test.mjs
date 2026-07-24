import assert from "node:assert/strict";
import test from "node:test";

import { paintMaskSegment, stampMaskCircle } from "../src/mask.js";

test("stampMaskCircle paints a circular brush footprint and returns the pixel delta", () => {
  const mask = new Uint8Array(9);
  const maskedPixelDelta = stampMaskCircle(mask, 3, 3, 1, 1, 1, 1);

  assert.equal(maskedPixelDelta, 5);
  assert.deepEqual([...mask], [0, 1, 0, 1, 1, 1, 0, 1, 0]);
});

test("stampMaskCircle does not double-count pixels that are already painted", () => {
  const mask = new Uint8Array(9);

  stampMaskCircle(mask, 3, 3, 1, 1, 1, 1);
  const maskedPixelDelta = stampMaskCircle(mask, 3, 3, 1, 1, 1, 1);

  assert.equal(maskedPixelDelta, 0);
  assert.deepEqual([...mask], [0, 1, 0, 1, 1, 1, 0, 1, 0]);
});

test("stampMaskCircle clips correctly at the image edge", () => {
  const mask = new Uint8Array(9);
  const maskedPixelDelta = stampMaskCircle(mask, 3, 3, 0, 0, 1, 1);

  assert.equal(maskedPixelDelta, 3);
  assert.deepEqual([...mask], [1, 1, 0, 1, 0, 0, 0, 0, 0]);
});

test("stampMaskCircle erases painted pixels and returns a negative delta", () => {
  const mask = new Uint8Array(9);

  stampMaskCircle(mask, 3, 3, 1, 1, 1, 1);
  const maskedPixelDelta = stampMaskCircle(mask, 3, 3, 1, 1, 1, 0);

  assert.equal(maskedPixelDelta, -5);
  assert.deepEqual([...mask], [0, 0, 0, 0, 0, 0, 0, 0, 0]);
});

test("paintMaskSegment fills a continuous stroke between two points", () => {
  const mask = new Uint8Array(7);
  const maskedPixelDelta = paintMaskSegment(mask, 7, 1, { x: 1, y: 0 }, { x: 5, y: 0 }, 0.6, 1);

  assert.equal(maskedPixelDelta, 5);
  assert.deepEqual([...mask], [0, 1, 1, 1, 1, 1, 0]);
});
