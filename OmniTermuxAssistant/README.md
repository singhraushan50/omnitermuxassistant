# Omni Termux Assistant — hybrid Android app source

## Intended architecture

- Android app imports the main `.gguf` and matching `mmproj` files.
- It keeps a private copy and publishes copies under `Download/OmniTermuxAssistant/` so Termux can read them.
- Local-first text and image inference talks to a `llama-server` listening only on `127.0.0.1:8080` in Termux.
- If local inference is unavailable or fails, the app can fall back to a configured OpenAI-compatible HTTPS API.
- Tool calls use a fixed allow-list and are shown for explicit confirmation before execution: web search in browser, open URL, dialer, WhatsApp with a prefilled message (the user taps Send), and SMS through Termux:API.
- GitHub Actions attempts to build an Android `arm64-v8a` `llama-server` from the LFM3-Omni support PR and packages it as an APK asset.

## Important compatibility note

The published [LFM3-Omni GGUF model card](https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF) says LFM3-Audio/Omni support depends on llama.cpp PR #31257 until merged/released and that image/audio use needs the matching `mmproj` file. Video input is not supported by that llama.cpp path as of the model card. The build workflow therefore targets PR #31257, but upstream changes may cause CI to fail; treat a successful GitHub Actions run as a build result, not a guarantee of device-level performance.

## Online build

1. Upload the contents of this folder to a GitHub repository (including `.github/workflows/build-android.yml`).
2. Open **Actions → Build hybrid Android APK** and run the workflow, or push to `main`.
3. Download the `OmniTermuxAssistant-debug-apk` artifact from the successful run.
4. Install the APK on an Android `arm64-v8a` phone. The workflow's model runtime build currently targets 64-bit ARM Android only.

The APK build includes `llama-runtime.zip` as an asset. This workflow has not been executed from this workspace, so the build is not verified here. The PR's CMake target/options may need adjustment if upstream changes.

## One-time Termux setup

1. Install Termux and Termux:API from trusted compatible sources.
2. Open Termux and run:
   ```sh
   pkg update
   pkg install python curl unzip termux-api
   termux-setup-storage
   mkdir -p ~/omni-tools ~/bin
   ```
3. Open the app once. It exports `dispatch.sh` and the runtime ZIP (if included in the APK) to `Download/OmniTermuxAssistant/`. The app launches the dispatcher through Termux's own Bash, so you do not need to copy the script into Termux's private home. Shared-storage access is still required.
4. In Termux settings, enable **Allow external apps to run commands**. Only enable it for this trusted app. The app uses an allow-listed dispatcher; it does not pass arbitrary model-generated shell commands.
5. The first time you tap **Start local AI**, the dispatcher extracts `llama-server` from `Download/OmniTermuxAssistant/llama-runtime.zip` into `~/bin/` if needed. If the ZIP is absent, download the `llama-runtime` artifact from the same Actions run and place `llama-runtime.zip` in `Download/OmniTermuxAssistant/`.
6. In the app, import the main LFM3-Omni model GGUF and its matching `mmproj` GGUF. Both are published to `Download/OmniTermuxAssistant/`. The app checks the GGUF magic header but cannot guarantee that every checkpoint is compatible with the selected runtime.
7. Tap **Start local AI** and wait for the server to load. Initial load can take time and requires sufficient free RAM and storage. Then test with a simple text prompt before trying images.

## Hybrid settings

Configure an OpenAI-compatible HTTPS base URL, model identifier, and API key. Cloud fallback transmits prompts to that provider; leave it disabled for sensitive data. Keys are currently stored in app preferences and should be moved to Android Keystore-backed encrypted storage before a production release.

## Current limitations

- This is an actively assembled source project, not a certified/production release. The workflow and runtime integration have not been executed or tested on a device in this workspace.
- Termux RunCommand is asynchronous; the current implementation does not yet provide a secure, authenticated return channel for structured tool output, so it reports dispatch status rather than a verified command result.
- Web search opens the browser's search page; it does not scrape results or return a structured search summary into chat.
- Audio capture/transcription, audio generation/playback, and video input are not wired into the app UI yet. The current chat path supports text and optional image input only. Accessibility-based UI automation is intentionally not enabled by default.
- The server build workflow builds for `arm64-v8a`; x86_64/32-bit ABI support is not included.
- LFM3-Omni-2B requires sufficient RAM; Q4_K_M model size is roughly 1.1 GB plus the projector, runtime, context/KV cache, and Android overhead. Actual memory requirements depend on context and input modality.

## Safety

Never execute model-generated shell strings. Keep the dispatcher allow-listed. Require confirmation for sending SMS or other external side effects. Localhost server is bound to loopback only; do not expose it to the LAN.
