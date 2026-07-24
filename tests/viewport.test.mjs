import assert from "node:assert/strict";
import test from "node:test";

import { clampPanOffset, clampZoom, getViewportMetrics, zoomViewportAtPoint } from "../src/viewport.js";

test("getViewportMetrics fits the image within the padded stage", () => {
  const metrics = getViewportMetrics(1000, 800, 2000, 1000, 1);

  assert.equal(metrics.availableWidth, 968);
  assert.equal(metrics.availableHeight, 768);
  assert.equal(metrics.baseWidth, 968);
  assert.equal(metrics.baseHeight, 484);
  assert.equal(metrics.displayWidth, 968);
  assert.equal(metrics.displayHeight, 484);
});

test("clampPanOffset keeps panning inside the visible bounds", () => {
  const metrics = getViewportMetrics(1000, 800, 1000, 1000, 2);
  const result = clampPanOffset(500, -500, metrics);

  assert.equal(result.panX, 284);
  assert.equal(result.panY, -384);
  assert.equal(result.limitX, 284);
  assert.equal(result.limitY, 384);
});

test("clampZoom respects the configured zoom limits", () => {
  assert.equal(clampZoom(0.25), 1);
  assert.equal(clampZoom(2.5), 2.5);
  assert.equal(clampZoom(9), 6);
});

test("zoomViewportAtPoint preserves the cursor anchor while zooming in", () => {
  const result = zoomViewportAtPoint({
    currentZoom: 1,
    nextZoom: 2,
    panX: 0,
    panY: 0,
    anchorX: 250,
    anchorY: 200,
    stageWidth: 1000,
    stageHeight: 800,
    imageWidth: 1000,
    imageHeight: 1000,
  });

  assert.equal(result.zoom, 2);
  assert.equal(result.panX, 250);
  assert.equal(result.panY, 200);
});
