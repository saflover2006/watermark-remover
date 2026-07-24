import assert from "node:assert/strict";
import test from "node:test";

import { getBrushPreviewLayout } from "../src/brush-preview.js";

test("getBrushPreviewLayout returns a visible ring sized from the current canvas scale", () => {
  const layout = getBrushPreviewLayout({
    stageRect: { left: 100, top: 50, width: 900, height: 700 },
    overlayRect: { left: 160, top: 90, width: 400, height: 300 },
    canvasWidth: 200,
    canvasHeight: 150,
    brushDiameter: 20,
    clientX: 260,
    clientY: 190,
  });

  assert.equal(layout.visible, true);
  assert.equal(layout.diameter, 40);
  assert.equal(layout.centerX, 160);
  assert.equal(layout.centerY, 140);
  assert.equal(layout.left, 140);
  assert.equal(layout.top, 120);
});

test("getBrushPreviewLayout hides the ring when the pointer is outside the overlay canvas", () => {
  const layout = getBrushPreviewLayout({
    stageRect: { left: 100, top: 50, width: 900, height: 700 },
    overlayRect: { left: 160, top: 90, width: 400, height: 300 },
    canvasWidth: 200,
    canvasHeight: 150,
    brushDiameter: 20,
    clientX: 120,
    clientY: 120,
  });

  assert.deepEqual(layout, { visible: false });
});

test("getBrushPreviewLayout uses the current zoomed overlay size for the brush diameter", () => {
  const layout = getBrushPreviewLayout({
    stageRect: { left: 20, top: 30, width: 1200, height: 900 },
    overlayRect: { left: 80, top: 100, width: 900, height: 675 },
    canvasWidth: 300,
    canvasHeight: 225,
    brushDiameter: 36,
    clientX: 530,
    clientY: 430,
  });

  assert.equal(layout.visible, true);
  assert.equal(layout.diameter, 108);
  assert.equal(layout.centerX, 510);
  assert.equal(layout.centerY, 400);
});
