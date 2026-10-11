# FilterStudio

Complete image + video filter suite. Open this folder in **Android Studio**
(Electrician Eel+ / AGP 8.2), let it sync, and run on API 24+ (best on 33+
for GPU live shaders).

Highlights:
- 60 filters (matrix recipes, convolution kernels, composite ops, duotones,
  spatial remap, baked-curve palettes) across 4 category tabs
- 11 adjustments incl. temperature/tint, highlights/shadows, vignette, grain
- Tone curves (RGB + per-channel), 3D LUTs (`.cube` import + export),
  4 procedural film LUTs
- Non-destructive crop/straighten/rotate/flip, brush masks, undo/redo,
  process-death survival
- Overlays: text, emoji, gallery stickers with magic-wand cutout,
  2-finger gestures, handles, 90° snap, z-order
- 18 built-in presets + user presets (photo covers, JSON import/export)
- Camera viewfinder with live filter thumbnails (scene-change driven)
- Video export via Media3 Transformer (matrix looks + generated GLSL passes;
  texture-bound stages documented as version-sensitive in `video/`)
