import { cloneImageDataLike } from "./inpaint.js";

export const DEFAULT_HISTORY_LIMIT = 12;

export function createEditorSnapshot(workingImageData, mask, maskedPixels) {
  return {
    workingImageData: cloneImageDataLike(workingImageData),
    mask: Uint8Array.from(mask),
    maskedPixels,
  };
}

export function appendHistorySnapshot(past, snapshot, limit = DEFAULT_HISTORY_LIMIT) {
  const nextPast = [...past, snapshot];

  if (nextPast.length <= limit) {
    return nextPast;
  }

  return nextPast.slice(nextPast.length - limit);
}

export function canUndoHistory(past) {
  return past.length > 1;
}

export function canRedoHistory(future) {
  return future.length > 0;
}

export function undoHistory(past, future) {
  if (!canUndoHistory(past)) {
    return null;
  }

  const currentSnapshot = past[past.length - 1];
  const nextPast = past.slice(0, -1);

  return {
    past: nextPast,
    future: [currentSnapshot, ...future],
    snapshot: nextPast[nextPast.length - 1],
  };
}

export function redoHistory(past, future, limit = DEFAULT_HISTORY_LIMIT) {
  if (!canRedoHistory(future)) {
    return null;
  }

  const [snapshot, ...nextFuture] = future;

  return {
    past: appendHistorySnapshot(past, snapshot, limit),
    future: nextFuture,
    snapshot,
  };
}
