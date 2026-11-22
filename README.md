# Fireball overlay

Android overlay that plans a Warden fireball + invisibility/quake placements over the live game screen.

- `src/lab/coc/fireball/` — Solver (port of the studio planner), Detector (ONNX YOLO + lattice fit), OverlayService (MediaProjection capture, tracking), OverlayView, MainActivity.
- `build.py` — builds and signs the APK (needs Android SDK, onnxruntime-android aar, model assets from `layout-recognition/mobile`; paths are local).

The APK is attached to releases, not committed.

## 0.8

- Fireball level picker 1–27 with radius and damage from the 19.32.4 game tables (radius 4/5/6 tiles changes only at levels 9 and 24).
- Pick step: «Правка» (retype, delete or add a building; suggestions: types the model also saw there, then same-size types),
  Clan Castle as a target (planned through a defence whose splash reaches it), filled squares, «Имена» on/off.
- «Скан +» adds to the current scan, «Скан новый» starts over; survey labels follow the majority of sightings.
- «ИИ: всегда / при движ. / выкл» replaces «Трек»; wider zoom search in the tracker and a scale re-probe after a lost zoom.
- «◀ ▶» for plan variants, «?» help per step, «Свернуть», ✕ asks for a second tap.
- `build.py` paths can be overridden by environment variables (FB_*), e.g. for a Linux build.
