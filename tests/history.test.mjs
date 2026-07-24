import assert from "node:assert/strict";
import test from "node:test";

import {
  appendHistorySnapshot,
  createEditorSnapshot,
  redoHistory,
  undoHistory,
} from "../src/history.js";
import { createImageDataLike } from "../src/inpaint.js";

function createSolidImage(width, height, red, green, blue, alpha = 255) {
  const data = new Uint8ClampedArray(width * height * 4);

  for (let index = 0; index < data.length; index += 4) {
    data[index] = red;
    data[index + 1] = green;
    data[index + 2] = blue;
    data[index + 3] = alpha;
  }

  return createImageDataLike(data, width, height);
}

test("createEditorSnapshot makes deep copies of image and mask data", () => {
  const image = createSolidImage(2, 1, 10, 20, 30);
  const mask = new Uint8Array([1, 0]);
  const snapshot = createEditorSnapshot(image, mask, 1);

  image.data[0] = 99;
  mask[0] = 0;

  assert.equal(snapshot.workingImageData.data[0], 10);
  assert.equal(snapshot.mask[0], 1);
  assert.equal(snapshot.maskedPixels, 1);
});

test("undoHistory returns the previous snapshot and stores the current one for redo", () => {
  const first = { id: "first" };
  const second = { id: "second" };
  const third = { id: "third" };

  const result = undoHistory([first, second, third], []);

  assert.deepEqual(result.past, [first, second]);
  assert.deepEqual(result.future, [third]);
  assert.equal(result.snapshot, second);
});

test("redoHistory restores a future snapshot and respects the history limit", () => {
  const first = { id: "first" };
  const second = { id: "second" };
  const third = { id: "third" };
  const fourth = { id: "fourth" };

  const result = redoHistory([first, second], [third, fourth], 3);

  assert.deepEqual(result.past, [first, second, third]);
  assert.deepEqual(result.future, [fourth]);
  assert.equal(result.snapshot, third);

  const trimmed = appendHistorySnapshot(result.past, fourth, 3);
  assert.deepEqual(trimmed, [second, third, fourth]);
});
