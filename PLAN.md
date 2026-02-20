# Phone Native Calling + Spark Voice — Recovery Plan (V2)

_Last updated: 2026-02-20_

## 0) Objective
Recover the Pixel native-call voice bridge by replacing the unstable capture/endpointing path with a clean, deterministic pipeline that:
- preserves pitch/sample-rate correctness,
- avoids start/end clipping,
- supports short valid turns (including one-word replies),
- keeps sentence-end turn detection stable,
- keeps latency suitable for conversational flow.

## 1) Chosen micro preprocessor (fixed)
**Model/engine**: **WebRTC VAD** (on-device, frame-level)  
**Reason**:
- tiny footprint (~KB-level native core),
- deterministic frame-based speech decisions,
- no network dependency,
- fast enough for real-time turn gating on Pixel.

**Reference parameters (initial)**:
- input: mono PCM16 @ 16 kHz (resampled from call capture),
- frame: 20 ms (`320` samples @ 16 kHz),
- mode: `AGGRESSIVE` (can tune to `VERY_AGGRESSIVE` if false positives persist),
- start gate: >= 2 voiced frames within 120 ms,
- end gate: >= 350–450 ms silence after speech start.

## 2) Hard architecture reset (must remove broken-path artifacts)
We will keep only one production call path:
1. **Root capture stream** (single source, single effective rate per call, no mid-call hopping).
2. **Deterministic resampler** to VAD/ASR rate.
3. **WebRTC VAD utterance segmenter** with rolling prebuffer + trailing extension.
4. **ASR -> LLM -> TTS** on Spark (existing backend contract).
5. **Root playback** to remote party.

The following classes of behavior are removed from production path:
- multi-heuristic transcript suppression lists,
- aggressive source/rate thrash during active turns,
- mixed “state-machine vs continuation” competing turn logic.

## 3) Execution steps

### Step A — Planning + traceability
- Update `PLAN.md` with this V2 design.
- Log every implementation decision in `PROGRESS.md`.

### Step B — Remove obsolete artifacts
- Remove debugging/simulation artifacts tied to failed path from tracked files.
- Disable dead code paths and obsolete toggles in active runtime path.

### Step C — Implement VAD turn engine
- Add WebRTC VAD dependency.
- Implement a dedicated turn segmenter module:
  - frame iterator,
  - voiced/silence state machine,
  - pre-roll buffer prepend,
  - tail extension before finalize.
- Replace prior turn-finalization logic with this segmenter.

### Step D — Deterministic capture/rate handling
- Lock per-call capture source and effective sample rate after warmup probe.
- Keep rate fixed for the call unless hard failure threshold is reached.
- Enforce explicit resampling chain to 16 kHz for VAD/ASR payload.

### Step E — Short-turn correctness
- Remove broad “low-information” rejection behavior for normal short utterances.
- Keep only strict rejects for true garbage:
  - empty/near-empty,
  - extreme repeated char noise,
  - non-speech artifact bursts.

### Step F — Validation gates
- Build + install debug APK.
- Live call validation (multi-turn):
  - pitch correctness on first and later turns,
  - no leading/trailing clipping on user speech,
  - one-word answers accepted,
  - no dead-after-first-turn behavior.
- Pull only current-call WAV/transcript artifacts for each test.

### Step G — Stabilization and lock-in
- Finalize tuned thresholds.
- Remove now-unused fallback code.
- Commit locked working state and update both skills docs if needed.

## 4) Acceptance criteria for V2
V2 is accepted only if all pass:
1. First turn and subsequent turns use correct pitch (no fast/chipmunk or slow/low artifacts).
2. Start/end clipping is not observed in pulled WAVs for normal speech.
3. One-word user turns (eg. “romance”, “yes”, “no”) are accepted and routed.
4. Multi-turn call survives at least 8 turns without deadlock.
5. The model’s responses remain semantically aligned with user transcripts.

## 5) Reference material used
- Android audio playback capture constraints and usage limitations:
  - https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
  - https://developer.android.com/media/platform/av-capture
- `VOICE_CALL` and privileged permission constraints:
  - https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
  - https://developer.android.com/reference/android/Manifest.permission#CAPTURE_AUDIO_OUTPUT
- Production-root recorder behavior and constraints (BCR):
  - https://github.com/chenxiaolong/BCR
- WebRTC/Silero VAD Android implementation details:
  - https://github.com/gkonovalov/android-vad

## 6) Operating rule
Every meaningful action is appended to `PROGRESS.md` with:
- what changed,
- why,
- result,
- next step.
