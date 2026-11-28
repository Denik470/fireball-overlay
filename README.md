# Fireball overlay

Android overlay that plans a Warden fireball + invisibility/quake placements over the live game screen.

- `src/lab/coc/fireball/` — Solver (port of the studio planner), Detector (ONNX YOLO + lattice fit), OverlayService (MediaProjection capture, tracking), OverlayView, MainActivity.
- `build.py` — builds and signs the APK (needs Android SDK, onnxruntime-android aar, model assets from `layout-recognition/mobile`; paths are local).

The APK is attached to releases, not committed.

## 0.9 (QC-no2)

- The overlay panel fits the screen, defaults to the top right, and exposes names and Super Balloon cleanup in the compact menu.
- Choose a target in the centre area and confirm, or use auto selection. Manual editing accepts building types and levels.
- Tracking can recover from multiple saved views after a pan or zoom. The Warden zone uses a single colour.
- Fireball splash markers estimate damage against building HP and nearby Builder's Hut repairs. First-invisibility timing and live testing are pending.

## 0.8

- Fireball level picker 1–27 with radius and damage from the 19.32.4 game tables (radius 4/5/6 tiles changes only at levels 9 and 24).
- Pick step: «Правка» (retype, delete or add a building; suggestions: types the model also saw there, then same-size types),
  Clan Castle as a target (planned through a defence whose splash reaches it), filled squares, «Имена» on/off.
- «Скан +» adds to the current scan, «Скан новый» starts over; survey labels follow the majority of sightings.
- «ИИ: всегда / при движ. / выкл» replaces «Трек»; wider zoom search in the tracker and a scale re-probe after a lost zoom.
- «◀ ▶» for plan variants, «?» help per step, «Свернуть», ✕ asks for a second tap.
- `build.py` paths can be overridden by environment variables (FB_*), e.g. for a Linux build.
