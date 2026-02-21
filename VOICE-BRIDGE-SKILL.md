# VOICE-BRIDGE-SKILL

## Purpose
Runbook for rooted Pixel telephony audio bridge + Spark voice gateway (ASR/LLM/TTS), with profile-driven tuning only.

## Dependencies
- Root setup: `phone/ROOT-SKILL-PIXEL10PRO.md`
- App service: `phone/app-android/app/src/main/java/com/tracsystems/phonebridge/SparkCallAssistantService.kt`

## Runtime architecture
- RX path: in-call downlink PCM (`tinycap`) -> gateway ASR/turn -> TTS WAV.
- TX path: TTS WAV -> in-call uplink PCM (`tinyplay`) -> remote caller.
- Call flow is server-ASR authoritative (`/api/turn`); no app-side hardcoded intent logic.

## Profile source of truth
- Local profile file: `phone/profiles/pixel10pro-blazer-profile-v1.json`
- Device active profile path: `/sdcard/Android/data/com.tracsystems.phonebridge/files/profiles/profile.json`
- No hardcoded per-endpoint tuning in app code.

### Required profile sections
- `root_binaries`
- `route_profile.set` / `route_profile.restore`
- `playback.candidate_order_in_app`
- `playback.endpoint_settings.<pcm_index>`
  - `sample_rate`, `channels`, optional `speed_compensation`
- `playback.tuning`
  - `persistent_session`, `lock_device_for_call`, `prebuffer_ms`, `period_size`, `period_count`, `mmap`
- `capture.candidate_order_in_app`
- `capture.endpoint_settings.<pcm_index>`
  - `request_sample_rate`, `request_channels`, `effective_sample_rate`
- `capture.tuning.strict_stream_only`
- `beep`
- `logging`
- `policy`

## Deploy profile
1. Push: `./scripts/android-push-profile.sh profiles/pixel10pro-blazer-profile-v1.json`
2. Confirm on device:
   - `adb shell cat /sdcard/Android/data/com.tracsystems.phonebridge/files/profiles/profile.json`
3. Restart app/service if needed and place a call.

## Operational prerequisites
- Lock screen must be `None` or `Swipe`.
- User 0 unlocked:
  - `adb shell dumpsys user | grep RUNNING_UNLOCKED`
- Battery mode for app: `Unrestricted`.

## Gateway contract
- Health:
  - `curl -H "Authorization: Bearer <token>" http://192.168.178.30:8996/health`
- Voice endpoints:
  - `POST /api/asr`
  - `POST /api/turn`

## Endpoint training workflow
Use this when modem/call session shifts to another PCM endpoint.

1. Start a real call (human pickup required).
2. Pull logs and debug wavs for that call.
3. Identify active endpoints from logs:
   - capture shift / capture pinned
   - playback shift / playback selected
4. Tune only that endpoint in local profile:
   - capture: `request_sample_rate`, `request_channels`, `effective_sample_rate`
   - playback: `sample_rate`, `channels`, optional `speed_compensation`
5. Push updated profile and retest.
6. Repeat until quality and transcription are stable.

### Capture training loop (current operating method)
- Gate condition:
  - If active capture endpoint is `20`, do not run capture training.
  - Run capture training only when active capture endpoint is not `20`.
- Required precondition:
  - profile must enable wav logging: `logging.debug_wav_dump.enabled=true`,
  - updated profile must be pushed and loaded on device before starting the loop.
- Mandatory artifacts each loop:
  - pull call WAVs (`rxm-*`, `rx-*`, `tx-*`) to local `phone/debug-wavs/latest-call-*`
  - pull transcription outputs for the same call.
- Human QA step (required):
  - listen to the pulled `rxm-*` files,
  - classify pitch quality (`normal`, `too high/fast`, `too low/slow`),
  - report this back to AI with the exact filenames.
- AI tuning step:
  - adjust only the active capture endpoint settings in `capture.endpoint_settings.<pcm_index>`,
  - push profile, retest, pull artifacts again.
- Stop condition:
  - keep iterating until human confirms natural pitch and transcript alignment on the pulled files.
- This is the exact process currently used for capture tuning.

## Acceptance criteria
- Greeting audible to remote caller.
- Minimum 3 stable turns without losing capture.
- No persistent high/low pitch artifacts in pulled `rxm-*` wavs.
- Transcripts remain semantically aligned with what caller said.

## Minimal troubleshooting
- No assistant audio heard remotely:
  - verify route set was applied, playback endpoint selected, and `tinyplay` success logs.
- No capture after greeting:
  - verify active capture endpoint and its profile settings; retune endpoint-specific capture rates/channels.
- Mis-transcriptions with pitch artifacts:
  - retune `effective_sample_rate` for active capture endpoint; keep endpoint-index mapping in profile.
- Duplicate/overlapping call behavior:
  - do not start a new dial while one live call exists.
