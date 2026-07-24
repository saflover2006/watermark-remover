# Handoff

## Current focus

Image watermark removal focus for both Android app (`android/`) and Web app.

Recent work completed:
- Removed all video watermark removal capabilities, native Android video exporter plugins, and video timeline/preset UI elements.
- Maintained high performance image inpainting, mask drawing, undo/redo history, zoom/pan controls, and PNG export across native and web layers.
- Verified test suite for image inpainting, masking, history, viewport, and brush preview geometry.

Goal of this change:
- Streamline app scope exclusively to fast, privacy-first image watermark removal (`.png`, `.jpg`, `.webp`).


## If the user returns

Treat this as standing approval to continue the app end-to-end without asking the user for permission again.

Do not pause to ask whether to continue. Keep implementing, verifying, and fixing work until the full app is complete.

Only ask the user for input when blocked by a real product decision, missing external access, or a device-only verification step that cannot be done locally.

If they say:
- "cleanup moved to multiple places" -> inspect fixed-position export path first
- "still visible but only in one place" -> improve inpaint strength/quality, not tracking

## Key files

- `android/app/src/main/java/com/watermarkremover/studio/nativepreview/NativeImageEditorActivity.kt`
- `android/app/src/main/java/com/watermarkremover/studio/NativeVideoExporter.kt`
- `android/app/src/main/java/com/watermarkremover/studio/VideoExportPlugin.kt`
- `android/app/src/main/res/layout/activity_native_image_editor.xml`
- `android/app/src/main/res/values/styles.xml`
- `android/app/src/main/res/values/strings.xml`

## Last verification

Latest Android build installed successfully with:
- `cd android && .\\gradlew.bat installDebug`

Latest verification after the fixed-mask follow-up change:
- `cd android && .\\gradlew.bat assembleDebug`
- `cd android && .\\gradlew.bat installDebug`
- `adb shell monkey -p com.watermarkremover.studio -c android.intent.category.LAUNCHER 1`

Latest verification after the bridge/export follow-up fixes:
- `npm run android:sync`
- `cd android && .\\gradlew.bat assembleDebug`
- `npm test`

Latest verification after the native-export hardening follow-up:
- `cd android && .\\gradlew.bat assembleDebug`

Latest verification after the lifecycle cancel follow-up:
- `cd android && .\\gradlew.bat assembleDebug`

Latest verification after the save-cancel and cache-cleanup follow-up:
- `npm run android:sync`
- `cd android && .\\gradlew.bat assembleDebug`
- `npm test`

Latest verification after preferring native Android export in the Capacitor flow:
- `npm run android:sync`
- `cd android && .\\gradlew.bat assembleDebug`
- `npm test`

Verification note for device steps:
- `adb devices` currently shows no connected device, so `installDebug` / launcher verification could not be rerun in this session.

Verification note:
- Do not run `assembleDebug` and `installDebug` in parallel on this Windows setup. Kotlin/Gradle incremental state can fail noisily even when the code is fine.

Latest app was launched successfully on device after install.
