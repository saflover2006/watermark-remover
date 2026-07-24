import { test } from "node:test";
import assert from "node:assert";
import { BatchQueue, BatchStatus } from "../src/batch.js";

// Mock global URL for testing
globalThis.URL = {
  createObjectURL: () => "mock-url",
  revokeObjectURL: () => {},
};

test("BatchQueue: initializes correctly", () => {
  const queue = new BatchQueue();
  assert.strictEqual(queue.items.length, 0);
  assert.strictEqual(queue.activeIndex, -1);
});

test("BatchQueue: adds image files correctly", () => {
  const queue = new BatchQueue();
  const mockFile1 = { name: "img1.png", type: "image/png" };
  const mockFile2 = { name: "txt1.txt", type: "text/plain" }; // should be ignored
  
  const added = queue.addFiles([mockFile1, mockFile2]);
  
  assert.strictEqual(added.length, 1);
  assert.strictEqual(queue.items.length, 1);
  assert.strictEqual(queue.items[0].name, "img1.png");
  assert.strictEqual(queue.items[0].status, BatchStatus.PENDING);
});

test("BatchQueue: removes item and updates activeIndex", () => {
  const queue = new BatchQueue();
  queue.addFiles([{ name: "1.png", type: "image/png" }, { name: "2.png", type: "image/png" }, { name: "3.png", type: "image/png" }]);
  
  queue.activeIndex = 2; // Active is the 3rd item (index 2)
  queue.removeItem(0); // Remove 1st item
  
  assert.strictEqual(queue.items.length, 2);
  assert.strictEqual(queue.items[0].name, "2.png");
  assert.strictEqual(queue.activeIndex, 1); // Active index shifts down
  
  queue.removeItem(1); // Remove the currently active item
  assert.strictEqual(queue.items.length, 1);
  assert.strictEqual(queue.activeIndex, -1); // Active index resets
});

test("BatchQueue: scales mask correctly", () => {
  const queue = new BatchQueue();
  
  // 2x2 source mask
  const sourceMask = new Uint8Array([
    1, 0,
    0, 1
  ]);
  
  // Scale to 4x4
  const { mask: targetMask, maskedPixels } = queue.scaleMask(sourceMask, 2, 2, 4, 4);
  
  assert.strictEqual(targetMask.length, 16);
  assert.strictEqual(maskedPixels, 8); // Each source pixel becomes a 2x2 block (4 pixels)
  
  // Check specific pixels in the 4x4
  assert.strictEqual(targetMask[0], 1);
  assert.strictEqual(targetMask[1], 1);
  assert.strictEqual(targetMask[4], 1);
  assert.strictEqual(targetMask[5], 1);
  
  assert.strictEqual(targetMask[2], 0);
  assert.strictEqual(targetMask[3], 0);
});
