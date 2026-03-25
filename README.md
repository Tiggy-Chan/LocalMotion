# LocalMotion

LocalMotion is a clean-room Android prototype for on-device image-to-video generation on Snapdragon 8 Gen 3 devices.

## Current State

- New Android project rooted at `/app`
- Native sidecar backend rooted at `/native`
- Runtime bundle installer with HTTPS download, SHA-256 verification, and atomic swap into `files/models/video-v1/`
- Native sidecar `/health` now probes runtime bundle completeness and reports whether the APK can enter QNN mode
- First-pass `SD1.5` conversion scaffolding under `tools/convert-qnn/sd15/`
- `SD1.5` checkpoint has already been converted to:
  - `models/shortlist/converted/sd15-diffusers`
  - `models/shortlist/converted/sd15-onnx`
- Placeholder local fallback pipeline for:
  - image import and square crop
  - prompt-driven lightweight stylization
  - staged SSE progress reporting
  - local AVC/MP4 encoding

## Repository Layout

- `app/`: Compose UI, services, local client, artifact storage, export/playback
- `native/`: sidecar process with `/health`, `/generate_clip`, `/cancel`
- `tools/convert-qnn/`: SD1.5 export helpers and runtime manifest packager
- `docs/architecture.md`: implementation notes and next integration steps
- `docs/sd15-first-npu.md`: first real NPU milestone for `txt2img` / `img2img`

## Important Notes

- This repository does not ship QNN SDK assets or model weights.
- The app can now install and validate a real runtime bundle, but the native inference core is still gated by Qualcomm SDK-bound code that is not committed in this repository.
- The first realistic NPU milestone is `SD1.5 txt2img/img2img`, not video generation.
- Set `localmotion.runtimeManifestUrl=<https-url>` in `local.properties` to bake a default runtime bundle manifest URL into the APK.
