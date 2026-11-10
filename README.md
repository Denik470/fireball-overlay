# Fireball overlay

Android overlay that plans a Warden fireball + invisibility/quake placements over the live game screen.

- `src/lab/coc/fireball/` — Solver (port of the studio planner), Detector (ONNX YOLO + lattice fit), OverlayService (MediaProjection capture, tracking), OverlayView, MainActivity.
- `build.py` — builds and signs the APK (needs Android SDK, onnxruntime-android aar, model assets from `layout-recognition/mobile`; paths are local).

The APK is attached to releases, not committed.
