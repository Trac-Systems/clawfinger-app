# VOICE-BRIDGE-SKILL

## Purpose
Canonical runbook for Pixel call audio capture/playback + Spark voice gateway integration (ASR/LLM/TTS) with streaming-safe guardrails.

## Dependency
- Root prerequisites are defined in `phone/ROOT-SKILL-PIXEL10PRO.md`.
- Voice bridge assumes APatch root + `rootd` are already working.

## Current known-good flow (2026-02-21)
- Root PCM bridge handles both directions:
  - RX: tinycap capture from in-call downlink.
  - TX: tinyplay injects synthesized assistant audio to remote caller.
- Active profile file controls runtime routing/tuning:
  - device path: `/sdcard/Android/data/com.tracsystems.phonebridge/files/profiles/profile.json`
  - source file: `phone/profiles/pixel10pro-blazer-profile-v1.json`
- Gateway turn flow is server-ASR authoritative:
  1) capture utterance on phone,
  2) ASR on Spark,
  3) `POST /api/turn` with transcript and audio,
  4) root tinyplay output to remote caller.

## Runtime prerequisites
- Keep lock screen set to `None` or `Swipe` (no PIN/password).
- Confirm user 0 is unlocked:
  - `adb shell dumpsys user | grep RUNNING_UNLOCKED`
- Keep `PhoneBridge` battery mode on `Unrestricted`.

## Telephony audio device map
### Capture (RX from remote caller)
- Candidate capture devices:
  - `20`, `21`, `22`, `54`
- Runtime behavior:
  - adaptive sample-rate fallback,
  - selected source carried forward between turns.

### Playback (TX to remote caller)
- Playback devices:
  - primary `29`
  - fallback candidates from profile.

## Route profile (critical)
- Apply at call start:
  - `Voice Call Mic Source` -> `IN_CALL_MUSIC`
  - `Incall Capture Stream0..3` -> `DL`
  - `Incall Playback Stream0..1` -> `On`
  - `Incall Mic Mute` -> `On`
  - `Incall Sink Mute` -> `On`
- Restore all to baseline at teardown.

## Spark gateway contract
### Base
- Gateway URL: `http://192.168.178.30:8996`
- Bearer token source: `spark2.txt` (`voice_gateway_bearer_token`)

### Endpoints
- `POST /api/asr`
  - input: WAV
  - output: transcript + ASR metrics
  - includes silence/noise hallucination suppression.
- `POST /api/turn`
  - input: short WAV + `transcript_hint` + `skip_asr=true`
  - output: reply text + TTS WAV + per-stage metrics.

## App files that must stay aligned
- `phone/app-android/app/src/main/java/com/tracsystems/phonebridge/SparkCallAssistantService.kt`
- `phone/app-android/app/src/main/java/com/tracsystems/phonebridge/RootShellRuntime.kt`
- `phone/app-android/app/src/main/java/com/tracsystems/phonebridge/BridgeInCallService.kt`
- `phone/app-android/app/src/main/java/com/tracsystems/phonebridge/AndroidTelecomController.kt`

## Live validation checklist
1. Root runtime checks:
   - `adb shell '/data/adb/ap/bin/su -c id'`
   - `adb shell dumpsys user | grep RUNNING_UNLOCKED`
2. Gateway check:
   - `curl -H "Authorization: Bearer <token>" http://192.168.178.30:8996/health`
3. Place a controlled call.
4. Follow device logs:
   - `adb logcat -s SparkCallAssistant BridgeInCallService`
5. Confirm this chain:
   - root capture source selected,
   - ASR transcript is meaningful,
   - root tinyplay success on `29`.

## Profile-driven PCM training loop (mandatory)
- Do not hardcode endpoint tuning in app code.
- Keep the known-good endpoint in profile; add newly activated endpoints as secondary.

### Procedure
1. Ensure profile is loaded:
   - `./scripts/android-push-profile.sh profiles/pixel10pro-blazer-profile-v1.json`
2. Start a real call with human pickup (required).
3. Pull latest debug wavs + transcripts for that call.
4. Identify active capture endpoint from logs:
   - `capture shift ...`
   - `root capture source pinned: ...`
5. Evaluate quality on pulled RX WAVs (`rxm-*`):
   - pitch natural,
   - no major clipping left/right,
   - transcript semantically matches what human said.
6. If active endpoint quality is bad:
   - update `profiles/pixel10pro-blazer-profile-v1.json` for that endpoint,
   - keep baseline endpoint unchanged,
   - add/adjust endpoint in `capture.validated_secondary`,
   - reorder `capture.candidate_order_in_app` and `recommended_strict_mode` as needed.
7. Push profile again and retest with another human call.
8. Repeat until quality is stable.

### Acceptance criteria
- Greeting audible on remote side.
- At least 3 turns without capture dropout.
- RX WAVs are natural pitch.
- No persistent `no_audio_source` loop on active call.
- Captured transcript stays in-context for user requests.

## Failure signatures and fixes
### Symptom: immediate generic replies (“you’re welcome”, “goodbye”)
- Likely cause: low-information/echo ASR hallucination.
- Fix:
  - keep two-stage path enabled,
  - keep transcript quality rejection enabled,
  - keep gateway ASR hallucination filters enabled.

### Symptom: call connected but remote side hears no assistant voice
- Check:
  - `root tinyplay ok device=19|18` in logs,
  - route profile applied before first turn,
  - device user state is `RUNNING_UNLOCKED`.

### Symptom: no capture / wrong capture source
- Check:
  - route recovery logs,
  - capture candidates `20/21/22/54`,
  - no parallel active call or duplicate dial.

### Symptom: duplicate calls or call collisions
- Keep operator-gated actions only (`call now`, `hang up`).
- Never dial while an active call already exists.
