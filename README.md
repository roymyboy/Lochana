# Lochana – Real-Time Vision Copilot

> Updated November 2025 – reflects the current development branch with YOLOv11 object detection, conversational UI, OCR mode, and GPT-4 Omni integrations.

## 🎯 Overview

Lochana turns an Android device into a multimodal assistant that combines:

- **On-device vision** powered by a YOLOv11 nano ONNX model for continuous object detection overlays.
- **Conversational AI** with GPT-4 Omni for rich scene descriptions, custom prompts, and video summaries.
- **Accessibility tooling** including live OCR, text-to-speech playback, adjustable chat typography, and haptic feedback.
- **Camera-first UX** featuring gesture controls, smart capture flows, and snapshots embedded directly inside the chat timeline.

The result is a responsive copilot that can describe what it sees, answer follow-up questions, read text it finds in the world, and keep users in control of every request.

---

## ✨ Highlights

**On-Device Vision**
- YOLOv11n (ONNX Runtime Mobile) draws real-time detections with class labels, confidences, and temporal smoothing via `DetectionTracker`.
- `DetectionOverlayView` matches the `PreviewView` crop and renders anti-aliased bounding boxes at 20 fps+ on modern hardware.
- Camera-side optimisations (auto focus gating, zoom smoothing, extension selection) live in `CameraManager`.

**Conversational Analysis**
- Manual capture flow: tap the capture button to send a single stabilized frame; long-press to collect a 5-second burst (up to 5 frames) for aggregated GPT-4 Omni video analysis.
- `SnapshotManager` stores downsized thumbnails so responses include tappable image previews inside the chat.
- Chat bubbles support typewriter playback, copy-to-clipboard, per-message TTS, and animated status indicators.

**Dual Capture Modes**
- `Analysis` mode pipes still frames or multi-frame clips to GPT-4 Omni with project-specific instructions.
- `OCR` mode invokes ML Kit Text Recognition (Latin) for high-confidence transcription of signage, documents, or packaging.
- Mode switching is instant from a Material exposed dropdown in the capture toolbar.

**Voice & Accessibility**
- `SpeechController` offers push-to-talk prompting (speech-to-text) and natural text-to-speech playback of assistant messages.
- Adjustable chat font scaling, persistent typing indicators, haptic confirmations, and animated focus reticules improve usability in the field.
- Status keywords keep operational updates ("Analyzing…", "Capturing…") distinct from assistant answers.

**Resilient Interaction Loop**
- `DetectionManager` isolates OpenAI capture from YOLO inference and protects active responses from being overwritten until a meaningful scene change occurs.
- `CrashHandler` surfaces fatal errors with user-friendly toasts and logging breadcrumbs.
- Rich diagnostics (camera readiness, preview sizing, throughput) simplify debugging on real hardware.

---

## 🏗 Architecture at a Glance

```
MainActivity
├─ CameraManager              → CameraX lifecycle, focus, zoom, device extensions
├─ DetectionManager           → Manual capture flow, scene differencing, YOLO smoothing
│  └─ YOLOv11Manager          → ONNX Runtime session + post processing
├─ OpenAIManager              → GPT-4 Omni chat completions (single-frame & multi-frame)
│  └─ ConfigLoader            → Loads API key from assets or shared prefs
│  └─ OpenAIKeyManager        → Lightweight persisted storage for runtime keys
├─ OcrProcessor               → ML Kit Text Recognition (Latin)
├─ PermissionManager          → Camera, microphone, and storage permission UX
└─ UIManager                  → Gesture handling, capture UX, status HUD
   ├─ ChatController          → Chat timeline, snapshots, copy/TTS actions
   ├─ PromptController        → Prompt entry, keyboard orchestration, font scaling
   ├─ SpeechController        → Speech recognition + text-to-speech pipeline
   ├─ SnapshotManager         → Ephemeral JPEG cache for chat previews
   └─ PreviewDialogController → Full-screen preview of captured imagery
```

Key asset pipeline:

- `app/src/main/assets/yolov11n.onnx` – 640 px YOLOv11 nano model.
- `app/src/main/assets/openai_instructions.json` – system & user prompt templates for single frame vs. video analysis.
- `app/src/main/assets/config.properties` – optional (gitignored) OpenAI API key container.

---

## 📋 Requirements

- **Android**: API 24 (Nougat) or higher. 64-bit devices recommended for ONNX performance.
- **Permissions**: Camera, internet, microphone (for voice prompts & TTS).
- **Connectivity**: Stable network for OpenAI calls; OCR and YOLO run locally.
- **Memory**: 2 GB RAM minimum; 4 GB+ recommended for smooth multi-frame captures.
- **Tooling**: Android Studio (latest stable), Gradle Wrapper bundled with repo.

---

## ⚙️ Setup & Configuration

### 1. Clone & Open
```bash
git clone <repository-url>
cd Lochana
```
Open the project in Android Studio and let Gradle sync.

### 2. Configure the OpenAI API Key

Use one of the options described in `docs/CONFIG_SETUP.md`. The typical workflow:

1. Copy the template into place (the destination file is ignored by git):
   ```bash
   cp app/src/main/assets/config.properties.template app/src/main/assets/config.properties
   ```
2. Add your key:
   ```
   OPENAI_API_KEY=sk-your-api-key
   ```
3. (Optional) At runtime you can also inject a key via `OpenAIKeyManager` (stored obfuscated in `SharedPreferences`).

### 3. Build & Install

Quick script (Windows PowerShell):
```powershell
.\quick-install.bat
```

Manual Gradle flow:
```bash
.\gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 Using Lochana

1. **Launch & Permit**  
   Grant camera (and microphone if you plan to use voice input) permissions when prompted.

2. **Live Vision Feed**  
   The YOLO overlay appears immediately; detections persist with temporal smoothing. Use pinch to zoom, single tap to focus (with animated reticule), and double tap to swap cameras.

3. **Compose Prompts (Optional)**  
   Type into the prompt field or tap the microphone icon to dictate a question. The prompt area supports multiline text and shift+enter for new lines.

4. **Capture for GPT Analysis**  
   - *Single tap* the capture button (blue ring) to grab a stabilized still. The app buffers frames until conditions are steady, then submits one snapshot to GPT-4 Omni together with your prompt and displays a thumbnail in the chat.
   - *Long press* (Analysis mode only) to collect a 5-second burst. Up to five frames are aggregated into a single GPT video request for richer motion awareness. A progress animation plays while frames buffer.

5. **Switch Modes**  
   Open the mode dropdown beside the capture button:
   - `Scene Analysis` → GPT-4 Omni (vision) responses.
   - `Text OCR` → ML Kit text recognition. Results appear instantly in the chat; long-press capture is disabled and the UI warns if attempted.

6. **Review & Interact**  
   - Assistant replies stream in with a typewriter effect and status indicator.  
   - Tap image previews to open a full-resolution dialog.  
   - Copy responses or trigger text-to-speech per message using the inline action row.  
   - Adjust chat font size via pinch gesture inside the chat pane.

7. **Voice Output & Input**  
   The microphone toggles speech recognition; the speaker icon on each message hands off text-to-speech with proper lifecycle cleanup when playback ends or errors.

8. **Resetting & Stability**  
   The detection pipeline protects completed responses until meaningful scene movement (>10 % pixel delta) is observed. If you need to force a new capture, tap the capture button again or double tap to switch cameras (which flushes history).

---

## 🔬 Implementation Notes

- **Scene Differencing**: `DetectionManager` performs YUV → NV21 → JPEG conversion, rotates frames, and compares pixel deltas with a 10 % movement threshold before allowing new GPT captures.
- **Temporal Tracking**: `DetectionTracker` associates YOLO detections across frames, clearing stale tracks after a single empty frame to avoid ghost boxes.
- **OpenAI Safeguards**: Requests are serialized (`isProcessing` flag). Errors (timeouts, 401/429, SSL) bubble up with contextual toasts and chat status messages. Video captures obey a 60 s HTTP timeout and support up to 5 frames.
- **Snapshot Lifecycle**: Previews are sized to ≤720px, compressed at 80 % JPEG, stored in the app’s cache (`chat_snapshots`). The cache trims older entries to the most recent 30.
- **Voice Pipeline**: Speech recognition checks microphone permission at runtime, provides partial results, and animates the mic button while active. TTS uses `AudioManager.STREAM_MUSIC` and cleans up utterances when playback stops.
- **Error Handling**: `CrashHandler` intercepts uncaught exceptions to display user guidance before terminating, improving resilience during field testing.

---

## 📂 Project Structure (Condensed)

```
app/
├── src/main/java/com/lochana/app/
│   ├── MainActivity.kt
│   ├── CameraManager.kt
│   ├── DetectionManager.kt
│   ├── DetectionOverlayView.kt
│   ├── DetectionTracker.kt
│   ├── YOLOv11Manager.kt
│   ├── OpenAIManager.kt
│   ├── OpenAIKeyManager.kt
│   ├── ConfigLoader.kt
│   ├── UIManager.kt
│   ├── PermissionManager.kt
│   ├── CrashHandler.kt
│   ├── ui/
│   │   ├── ChatController.kt
│   │   ├── PromptController.kt
│   │   ├── SpeechController.kt
│   │   ├── PreviewDialogController.kt
│   │   ├── SnapshotManager.kt
│   └── ocr/
│       └── OcrProcessor.kt
├── src/main/assets/
│   ├── config.properties.template
│   ├── openai_instructions.json
│   └── yolov11n.onnx
└── src/main/res/… (layouts, drawables, styles)
```

---

## 🛠 Troubleshooting

- **Black Preview / No Camera Feed**  
  Confirm no other app is using the camera. The logcat tag `CameraManager` surfaces retry attempts; the UI presents troubleshooting text if binding fails.

- **Missing YOLO Detections**  
  Ensure the ONNX model exists in `app/src/main/assets`. Older or 32-bit devices may struggle with ONNX Runtime 1.18.0—monitor `YOLOv11Manager` logs for `session` errors.

- **GPT Requests Failing**  
  Verify `OPENAI_API_KEY` is set, device has internet, and your account has access to GPT-4o. The app reports HTTP status-derived guidance in the chat.

- **OCR Mode Returns Empty Text**  
  Good lighting and legible fonts are required. The app emits `toast_ocr_no_text` when ML Kit returns an empty payload.

- **Speech Features Disabled**  
  You must grant microphone permission. Check `SpeechController` logs for permission or recognizer failures.

- **Out of Memory During Capture**  
  The app aggressively recycles bitmaps, but older devices may need smaller capture resolutions. Watch for `OutOfMemoryError` in `DetectionManager` logs and reduce usage of prolonged long-press captures.

---

## 🚧 Roadmap Ideas

- Configurable YOLO class lists and thresholds.
- Offline small-language-model fallback for summary generation.
- Persistent analysis history with export and share actions.
- Multi-language OCR/TTS pipelines and translation overlays.
- Guided capture workflows (e.g., "scan document" vs "describe scene" presets).

---

## 📄 License & Attribution

Lochana is distributed for educational and demonstration purposes showcasing modern Android multimodal design. External assets include:

- YOLOv11 ONNX model (COCO classes) – see original license terms.
- OpenAI GPT-4 Omni API – subject to OpenAI usage policies.
- Google ML Kit Text Recognition – governed by Google Play Services terms.

---

**Built with ❤️ using Kotlin, CameraX, ONNX Runtime, ML Kit, and GPT-4 Omni.**
