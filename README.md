# Watermark Remover Studio

Watermark Remover Studio is a lightweight browser and native Android app for removing simple watermarks from images.
You can upload a photo, brush over the watermark, let the local healing pass fill the selected area,
and then save or download a cleaned PNG.

The app is fully local. Your image never leaves the device.

## What it includes

- Brush-based masking for the watermark area
- Undo and redo for iterative cleanup passes
- Paint and erase modes for precise selection
- Adjustable brush size and edge cleanup radius
- Live brush preview ring that stays accurate while zoomed
- Before/after compare mode with a split slider
- Zoom in, zoom out, fit view, wheel zoom, and pan for precise masking
- One-click healing pass that fills the masked region from nearby pixels
- Capacitor Android app for running as a native Android app
- Downloadable PNG export
- Node test coverage for masking, brush preview geometry, viewport math, inpainting core, and edit-history helpers

## Project structure

```text
.
|- .github/workflows/ci.yml
|- android/
|- capacitor.config.json
|- index.html
|- styles.css
|- server.mjs
|- scripts/
|- src/
|  |- app.js
|  |- brush-preview.js
|  |- inpaint.js
|  |- mask.js
|  `- viewport.js
|- tests/
|  |- brush-preview.test.mjs
|  |- history.test.mjs
|  |- inpaint.test.mjs
|  |- mask.test.mjs
|  `- viewport.test.mjs
|- package.json
`- README.md
```

## Requirements

- Node.js 20+
- npm 10+

## Run locally

1. Install project metadata:

   ```bash
   npm install
   ```

2. Start the local server:

   ```bash
   npm start
   ```

3. Open `http://localhost:4173` in your browser.

## Android app

This project includes a Capacitor Android wrapper in `android/`.

Available commands:

- `npm run android:sync`
  Rebuilds the static web bundle into `www/` and syncs it into the Android project.
- `npm run android:open`
  Opens the Android project in Android Studio.
- `npm run android:build:debug`
  Rebuilds and creates a debug APK with the Gradle wrapper.

Typical Android workflow:

1. Run `npm install`
2. Run `npm run android:sync`
3. Run `npm run android:open`
4. In Android Studio, let Gradle finish syncing
5. Build or run the app from Android Studio

If your Android SDK is already configured locally, you can also try:

```bash
npm run android:build:debug
```

The generated debug APK will typically be written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## Editor shortcuts

- `Ctrl/Cmd + Z`: undo
- `Ctrl/Cmd + Shift + Z`: redo
- `Ctrl/Cmd + Y`: redo
- `0`: fit the image back to view

## View controls

- Use the mouse wheel over the canvas to zoom in and out
- Hold `Space` and drag to pan when zoomed in
- Or drag with the right mouse button to pan when zoomed in

## Run tests

```bash
npm test
```

## How the remover works

The remover uses a manual mask plus a browser-side healing pass.

1. You paint over the watermark area.
2. The app slightly expands the mask to cover watermark edges.
3. It fills the masked area from the outside in using nearby unmasked pixels.
4. If a small interior gap remains, it falls back to the closest surrounding colors.

For editing, the app also keeps a short in-browser history so you can compare, undo, and refine multiple passes without reloading the image.

This works best on:

- text watermarks
- corner logos
- light overlays on smooth backgrounds
- simple marks on photos that can be blended from nearby detail

## Known limitations

- It is not a generative AI model, so heavily textured backgrounds may need multiple passes.
- Large watermarks that cover important subject detail may need a more advanced backend model later.
- Image output is exported as PNG to avoid extra quality loss.

## Next ideas

- Add an interactive before/after split view
- Add undo history for mask strokes
- Add an optional model-backed cleanup route for more complex removals
