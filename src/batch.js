export const BatchStatus = {
  PENDING: "pending",
  PROCESSING: "processing",
  DONE: "done",
  ERROR: "error"
};

export class BatchQueue {
  constructor() {
    this.items = []; 
    this.activeIndex = -1;
  }

  addFiles(files) {
    const added = [];
    for (const file of files) {
      if (file.type.startsWith("image/")) {
        const item = {
          id: Math.random().toString(36).slice(2),
          file,
          name: file.name,
          thumbnailUrl: URL.createObjectURL(file),
          status: BatchStatus.PENDING,
          resultBlob: null,
          mask: null,
          originalImageData: null,
          maskedPixels: 0,
        };
        this.items.push(item);
        added.push(item);
      }
    }
    return added;
  }

  removeItem(index) {
    const item = this.items[index];
    if (item.thumbnailUrl) {
      URL.revokeObjectURL(item.thumbnailUrl);
    }
    if (item.resultBlob) {
      item.resultBlob = null;
    }
    this.items.splice(index, 1);
    if (this.activeIndex === index) {
      this.activeIndex = -1;
    } else if (this.activeIndex > index) {
      this.activeIndex -= 1;
    }
  }

  clear() {
    for (let i = this.items.length - 1; i >= 0; i--) {
      this.removeItem(i);
    }
  }

  scaleMask(sourceMask, sourceWidth, sourceHeight, targetWidth, targetHeight) {
    if (!sourceMask) return { mask: null, maskedPixels: 0 };
    const targetMask = new Uint8Array(targetWidth * targetHeight);
    let maskedPixels = 0;
    
    for (let yb = 0; yb < targetHeight; yb++) {
      for (let xb = 0; xb < targetWidth; xb++) {
        // Nearest neighbor mapping
        const xa = Math.floor(xb * sourceWidth / targetWidth);
        const ya = Math.floor(yb * sourceHeight / targetHeight);
        
        const sourceIndex = ya * sourceWidth + xa;
        if (sourceMask[sourceIndex]) {
          const targetIndex = yb * targetWidth + xb;
          targetMask[targetIndex] = 1;
          maskedPixels++;
        }
      }
    }
    
    return { mask: targetMask, maskedPixels };
  }
  
  async createZipBlob() {
    if (typeof window.JSZip === 'undefined') {
      throw new Error("JSZip is not loaded");
    }
    
    const zip = new window.JSZip();
    let hasFiles = false;
    
    for (const item of this.items) {
      if (item.resultBlob) {
        // Ensure unique names in zip if there are duplicates
        const baseName = item.name.replace(/\.[^.]+$/, "");
        zip.file(`${baseName}-cleaned-${item.id}.png`, item.resultBlob);
        hasFiles = true;
      }
    }
    
    if (!hasFiles) {
      throw new Error("No processed images to download.");
    }
    
    return zip.generateAsync({ type: "blob" });
  }
}
