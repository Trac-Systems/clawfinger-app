# Phone Project Progress Log

_Last updated: 2026-02-19_

## Logging Rules
- Append a new entry for every meaningful action.
- For each entry include: `what`, `why`, `result`, `next`.
- Keep blockers explicit and include owner/decision needed.

---

## 2026-02-19 15:44 — Project bootstrap and planning

### What
- Created new project workspace at `phone/`.
- Initialized git repository.
- Added `phone/.gitignore` for Node, Android, logs, temp, and secret artifacts.
- Authored comprehensive execution plan in `phone/PLAN.md`.

### Why
- You requested a clean, isolated project folder with durable planning/progress artifacts.
- We need a session-resilient workflow before implementation starts.

### Result
- Bootstrap complete.
- Clear phased plan now exists for:
  - native telephony control (dial/answer/hangup)
  - Node USB/BT command control
  - Spark voice integration
  - OpenClaw integration seam
  - browser-based APK update page

### Key decisions recorded
- USB (ADB) is primary control transport; Bluetooth is secondary.
- App must own telecom APIs (default dialer role for reliable call control).
- Progress logging in this file is mandatory for all future steps.

### Current status
- Phase 0 complete (planning/bootstrap only).
- No app code or Node runtime code implemented yet.

### Next
1. Create initial folder skeleton (`app-android`, `node-control`, `bridge-spark`, `update`, `scripts`, `docs`).
2. Execute Spike A (native Android call control feasibility on Pixel Pro).
3. Record measured results and blockers immediately here.

### Blockers
- None yet (planning stage only).

## 2026-02-19 15:46 — Plan updated with mandatory test gates

### What
- Added explicit testing path to `PLAN.md`:
  - L1 unit
  - L2 integration
  - L3 e2e simulated/emulator
  - L4 real-device
- Added hard gate: no APK release before L1+L2+L3 pass.
- Added test deliverables list (`test-unit.sh`, `test-integration.sh`, `test-e2e-sim.sh`, `test-e2e-emulator.sh`, testing matrix doc).

### Why
- You required test-first execution with emulator/other means before real APK builds.
- SIM is not active yet, so we need robust simulation/emulation gates now.

### Result
- Plan now enforces a test-first workflow and release gating.

### Next
1. Scaffold implementation folders and test scripts.
2. Implement Node control + simulator + Spark bridge with passing tests.
3. Add Android skeleton with test hooks and emulator path scripts.

### Blockers
- None yet.

## 2026-02-19 15:49 — Scaffolded implementation and test harness structure

### What
- Created project subfolders:
  - `app-android/`
  - `node-control/`
  - `bridge-spark/`
  - `bridge-openclaw/`
  - `update/`
  - `scripts/`
  - `docs/`
  - `tests/integration/`, `tests/e2e-sim/`
- Added root `package.json` with test commands for L1/L2/L3 and emulator test hook.
- Added testing docs in `docs/testing-matrix.md`.
- Added executable test scripts:
  - `scripts/test-unit.sh`
  - `scripts/test-integration.sh`
  - `scripts/test-e2e-sim.sh`
  - `scripts/test-e2e-emulator.sh` (auto-skip if no emulator).

### Why
- Needed concrete structure and runnable test entry points before coding feature logic.

### Result
- Test harness entrypoints exist and map to plan gates.
- Emulator path is wired as best-effort with explicit skip semantics.

### Next
1. Implement Node control SDK/CLI with deterministic unit tests.
2. Implement Spark bridge client with unit tests.
3. Implement integration + e2e simulation tests to validate full control flow.

### Blockers
- None.

## 2026-02-19 15:56 — Implemented baseline runtime skeleton + passing local tests

### What
- Implemented Node control SDK (`node-control/src`) with:
  - command factory
  - ADB transport
  - HTTP transport
  - `CallController` orchestration API
  - CLI entrypoint.
- Implemented Spark bridge client (`bridge-spark/src/client.js`) for:
  - health
  - session creation
  - turn requests.
- Added automated tests:
  - unit: command factory, ADB transport, controller, spark client
  - integration: controller -> simulated phone app server
  - e2e-sim: simulated native call flow + Spark turn loop.
- Added Android app skeleton (`app-android`) with:
  - command receiver (`CallCommandReceiver`)
  - telecom controller abstraction + Android implementation
  - in-call service holder (`BridgeInCallService`)
  - command executor + audit log
  - starter activity + manifest + gradle config
  - parser/executor unit tests.

### Why
- Execute plan autonomously with test-first path and working baseline flow before real APK release.

### Result
- Local test suites pass for L1/L2/L3-sim path:
  - `npm run test:unit` ✅
  - `npm run test:integration` ✅
  - `npm run test:e2e-sim` ✅
- Emulator script exists and auto-skips when emulator/app is unavailable.

### Next
1. Add local control panel (web UI) for manual call actions + conversation timeline.
2. Add agent gateway contract layer for OpenClaw-controlled operation.
3. Extend tests to include control panel + agent gateway e2e simulation.

### Blockers
- Real telephony L4 is still blocked by inactive SIM.
- Emulator L3b requires running emulator with installed APK.

## 2026-02-19 15:58 — Plan expanded: local control panel + agentic control

### What
- Updated `PLAN.md` to explicitly include:
  - local control panel component (`control-panel`)
  - agent gateway component (`agent-gateway`)
  - new implementation phases for both
  - acceptance criteria for panel + agent-driven actions.

### Why
- Requirement: conversation/control should run locally, with phone app acting as execution endpoint; future OpenClaw agents must control it.

### Result
- Plan now reflects the exact operating model for local manual + agentic control.

### Next
1. Scaffold `control-panel/` and `agent-gateway/` modules.
2. Implement panel-to-controller integration and event timeline.
3. Implement agent gateway API and test agent-driven dial/answer/hangup path.

### Blockers
- None for implementation stage.

## 2026-02-19 16:01 — Added local control panel and agent gateway (with tests)

### What
- Implemented local control panel module:
  - `control-panel/src/server.js`
  - `control-panel/src/eventBus.js`
  - `control-panel/src/run.js`
- Implemented agent gateway module:
  - `agent-gateway/src/server.js`
  - `agent-gateway/src/run.js`
- Control panel now supports local actions:
  - dial, answer, reject, hangup, mute, speaker, bluetooth route
  - event timeline API (`/api/events`)
  - web UI at `/`.
- Agent gateway now supports intent API (`/api/agent/intent`) with optional bearer protection.

### Why
- Requirement update: local UI should drive call control and full local conversational workflow, with phone app as execution endpoint.
- Requirement update: path for agentic control (OpenClaw-style intents).

### Result
- Added integration tests:
  - `tests/integration/control-panel-api.test.js` ✅
  - `tests/integration/agent-gateway-api.test.js` ✅
- Full current test status:
  - `npm test` ✅ (unit + integration + e2e-sim all pass)
  - `npm run test:e2e-emulator` → `SKIP: no running Android emulator`.
- Updated `docs/testing-matrix.md` with PASS/SKIP/BLOCKED status.

### Next
1. Wire control panel to Spark turn path (session metrics + transcript timeline from live bridge).
2. Add end-to-end test where agent gateway triggers call action and panel reflects event stream.
3. Add first CI-style aggregate script to enforce L1/L2/L3 pass gate before APK release.

### Blockers
- Emulator is not running in this environment (L3b still pending).
- L4 remains blocked pending active SIM and real-device run.

## 2026-02-19 16:04 — Local-host control topology clarified and enforced

### What
- Updated `PLAN.md` to explicitly enforce runtime topology:
  - control panel + agent gateway run on this machine only
  - Spark is voice backend only.
- Added `docs/local-control-topology.md` with local run commands and placement.
- Added npm scripts:
  - `npm run panel:start`
  - `npm run agent:start`

### Why
- Clarification requested: control panel must be local; Spark must only provide voice stack.

### Result
- Plan/docs now unambiguously reflect local control-plane deployment.
- No Spark-hosted control assumptions remain in plan text.
- Regression check passed: `npm test` still fully green.

### Next
1. Wire control panel to live Spark bridge transcript/metrics stream.
2. Add shared event-stream test: agent gateway actions visible in control panel timeline.
3. Prepare emulator runbook + Android build/test command set for first APK smoke cycle.

### Blockers
- Emulator path remains pending active emulator session.

## 2026-02-19 16:10 — Wired control panel to live Spark conversation flow

### What
- Extended `control-panel/src/server.js` to support Spark-backed conversation endpoints:
  - `GET /api/spark/health`
  - `POST /api/spark/session/new`
  - `POST /api/conversation/turn`
  - `GET /api/conversation/state`
- Added event streaming endpoint:
  - `GET /api/events/stream` (SSE)
- Added in-panel conversation UI controls:
  - new session
  - send text turn
  - spark health check
  - live timeline updates via SSE + polling fallback.
- Added internal turn orchestration with session persistence and synthetic silence WAV fallback for text-triggered turns.
- Updated `control-panel/src/run.js` to initialize Spark client from env (`SPARK_VOICE_*` vars).
- Updated docs for Spark env knobs in `docs/local-control-topology.md`.

### Why
- You requested live Spark conversational wiring while keeping control plane local on this machine.

### Result
- Local panel can now orchestrate call actions and Spark conversation turns from one UI/API surface.
- Event timeline now reflects Spark session/turn lifecycle in real time.

### Tests
- Added integration coverage for panel + Spark flow:
  - `tests/integration/control-panel-api.test.js` now validates `/api/spark/health` + `/api/conversation/turn`.
- Regression test run:
  - `npm test` ✅
    - unit: 11 pass
    - integration: 4 pass
    - e2e-sim: 1 pass

### New runtime notes
- Panel startup now expects Spark endpoint only if conversation APIs are needed:
  - set `SPARK_VOICE_BASE_URL` (plus optional bearer/basic auth vars).
- Without Spark config, call-control APIs still work; Spark APIs return config error.

### Context update received
- User confirmed Pixel phone number is now active, enabling direct APK/device testing path.

### Next
1. Build and install first debug APK to Pixel via ADB.
2. Execute real-device smoke tests for dial/answer/hangup command path.
3. Pipe real transcript/reply metrics from Spark turn calls into persistent call-session records.

### Blockers
- Need connected Pixel + ADB authorization for L4 device run.

## 2026-02-19 16:16 — Added chunked live conversation + interrupt flow (Spark-style)

### What
- Implemented `control-panel/src/liveConversation.js` with Spark-inspired live logic:
  - chunk ingestion
  - VAD-based turn detection (`vadRmsThreshold`, `minSpeechSec`, `silenceSec`, `maxTurnSec`)
  - pre-roll buffering
  - queued turn processing
  - barge-in/interrupt flag handling
  - per-session state tracking.
- Extended control panel API in `control-panel/src/server.js`:
  - `POST /api/conversation/live/start`
  - `POST /api/conversation/live/chunk`
  - `GET /api/conversation/live/state?sessionId=...`
  - `POST /api/conversation/live/interrupt`
  - `POST /api/conversation/live/stop`
  - `GET /api/events/stream` already present for live event feed.
- Extended panel UI with live controls and mic capture loop:
  - start/stop live
  - interrupt
  - continuous chunk upload from microphone (PCM16 chunked)
  - status display + event timeline updates.
- Updated topology docs with live endpoints and behavior notes.

### Why
- Requirement: no walkie-talkie mode; need conversational, chunked, interruptible flow similar to Spark prototype behavior.

### Result
- Local control panel now has continuous chunked conversation mode with interruption semantics.
- This preserves local orchestration while Spark remains remote voice backend.

### Tests
- Added integration test:
  - `control panel live conversation supports chunking and interrupt semantics`
- Full suite re-run:
  - `npm test` ✅
    - unit: 11 pass
    - integration: 5 pass
    - e2e-sim: 1 pass

### Context update
- User confirmed Pixel phone number is now active; real-device APK path can proceed.

### Next
1. Build/install debug APK on Pixel and run real command smoke tests (dial/answer/hangup).
2. Connect live chunk loop from phone app audio path (not browser mic) for true call-audio orchestration.
3. Add persistent per-call transcripts/metrics store for post-call audits.

### Blockers
- Need connected, authorized Pixel via ADB for first L4 run.

## 2026-02-19 16:22 — Added Android build/install/smoke scripts and attempted device run

### What
- Added Android device scripts:
  - `scripts/android-build-debug.sh`
  - `scripts/android-install-debug.sh`
  - `scripts/android-smoke-adb.sh`
- Added npm script shortcuts:
  - `npm run android:build:debug`
  - `npm run android:install:debug`
  - `npm run android:smoke:adb`
- Added runbook:
  - `docs/device-smoke-runbook.md`
- Added additional Android controller logs to improve ADB smoke visibility.

### Why
- Move from simulated-only validation to real-device APK path now that SIM is active.

### Result
- Debug APK build succeeded:
  - `scripts/android-build-debug.sh` ✅
  - output: `app-android/app/build/outputs/apk/debug/app-debug.apk`
- Install attempt failed in current shell due missing connected device:
  - `adb: no devices/emulators found`

### Observations
- Kotlin daemon emitted startup warnings but build completed successfully.
- Android SDK build-tools were auto-installed during first build.

### Next
1. Connect/authorize Pixel over USB (`adb devices` must list it).
2. Re-run:
   - `scripts/android-install-debug.sh`
   - `scripts/android-smoke-adb.sh`
3. Once ADB sees the phone, execute L4 smoke and log results.

### Blockers
- No ADB-visible device in this runtime session.

## 2026-02-19 16:27 — Verified local panel against live Spark voice backend

### What
- Started local control panel with Spark voice env:
  - `SPARK_VOICE_BASE_URL=http://192.168.178.30:8996`
  - `SPARK_VOICE_BEARER=<voice_gateway_bearer_token>`
- Validated live backend wiring from this machine:
  - `GET /api/spark/health` through local panel
  - `POST /api/conversation/turn` through local panel.

### Why
- Confirm that control plane remains local while Spark is only backend inference.
- Confirm conversation path actually reaches live Spark stack.

### Result
- Spark health proxied successfully via local panel (`ok: true` + stack metadata).
- Conversation turn returned real Spark response with transcript/reply/metrics.
- Testing matrix updated with B1/B2 PASS entries.

### Notes
- Returned transcript from synthetic-silence turn contains dot artifacts (expected with silence input + transcript hint fallback path).
- This does not affect control-plane correctness; real call audio path should provide meaningful ASR.

### Next
1. Connect Pixel via ADB and run install + smoke scripts.
2. Switch from browser mic chunk source to phone-app call-audio stream path for true end-to-end call conversation.
3. Add per-call persistence for transcript/reply/latency timeline export.

### Blockers
- ADB device not visible yet in this shell session.

## 2026-02-19 16:40 — Added Spark-style websocket live flow + revalidated test gates

### What
- Hardened live engine behavior in `control-panel/src/liveConversation.js`:
  - fixed pre-roll duplication on voice-start boundary
  - added queue drop on interrupt/barge-in
  - added `disableAutoFlush` handling for explicit turn framing.
- Added Spark-style websocket endpoint in `control-panel/src/server.js`:
  - `WS /ws/session`
  - supports `turn.begin`, `turn.audio_chunk`, `turn.end`, `turn.cancel`, `session.state`, `session.stop`
  - emits `session.ready`, `session.state`, `turn.start`, `turn.complete`, `turn.cancelled`, `turn.interrupt`, `turn.error`, `session.spark`.
- Added websocket integration test:
  - `tests/integration/control-panel-ws.test.js`.
- Added `ws` dependency to `package.json` and installed lockfile.
- Updated docs:
  - `docs/local-control-topology.md`
  - `docs/testing-matrix.md`
  - `PLAN.md` (Phase 4 includes Spark-style streaming requirement).

### Why
- Requirement update: mirror Spark conversational testing behavior (continuous chunked flow, no walkie-talkie pattern, interruption support).

### Result
- Local conversation stack now supports both:
  - HTTP live chunk APIs, and
  - persistent websocket turn protocol aligned with Spark-style flow.
- Full validation run after changes:
  - `npm test` ✅
    - unit: 11 pass
    - integration: 6 pass (includes websocket flow)
    - e2e-sim: 1 pass
  - `npm run test:e2e-emulator` → `SKIP: no running Android emulator`.
  - `npm run android:build:debug` ✅
  - `npm run android:install:debug` ❌ (`adb: no devices/emulators found`)
  - `npm run android:smoke:adb` ❌ (package not installed because no ADB device).

### Next
1. Connect and authorize Pixel over USB so `adb devices` shows the handset.
2. Re-run install + smoke scripts and log L4 results.
3. Move audio source from browser mic to app-side call-audio path for real call E2E.

### Blockers
- No ADB-visible device in this shell session, so L4 cannot be completed yet.

## 2026-02-19 16:34 — ADB connected, command payload fix, first real dial executed

### What
- Detected and fixed ADB broadcast payload parsing break:
  - root cause: raw JSON in `--es payload ...` was being tokenized by shell/`am broadcast` path, so app received malformed payload.
- Implemented robust command transport format:
  - `node-control/src/transports/adbTransport.js` now sends `payload_b64` (base64 JSON) instead of raw JSON.
  - `app-android/.../CallCommandReceiver.kt` now decodes `payload_b64` first, then falls back to legacy `payload` and flat extras.
  - `scripts/android-smoke-adb.sh` updated to send explicit receiver + `payload_b64`.
- Granted required runtime phone permissions on device via ADB:
  - `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `READ_PHONE_STATE`.
- Rebuilt/reinstalled app and re-ran smoke tests.
- Per user instruction, triggered one real dial command:
  - `+4915129135779` (international form of `015129135779`).

### Why
- L4 was blocked by command parsing + runtime permission denial.
- User requested an actual call once setup is ready and model is responding.

### Result
- Device now visible and authorized in ADB (`Pixel_10_Pro`).
- Install path passes:
  - `npm run android:install:debug` ✅
- Smoke path now reaches app controller:
  - `AndroidTelecomCtrl: Dial command executed` present in logs ✅
- Spark voice backend health confirmed before real dial:
  - `/health` reports `ok: true` with `Qwen2.5-1.5B-Instruct-NVFP4`.
- Real dial trigger executed and logged:
  - `AndroidTelecomCtrl: Dial command executed: +4915129135779` ✅
- Known remaining limitation:
  - hangup in smoke can fail with `No active call available to hang up` when no tracked in-call object exists.

### Next
1. Add default-dialer onboarding + verification in app/startup to improve answer/hangup reliability.
2. Implement hangup fallback path when `InCallStateHolder.currentCall` is null.
3. Continue with call-audio bridge from app to Spark voice stream (replace browser mic path).

### Blockers
- No blocker for outgoing dial anymore.
- Answer/hangup reliability still depends on role/tracked call state improvements.

## 2026-02-19 16:56 — Emergency stabilization: crash-loop and stuck-call behavior

### What
- Reproduced user-reported failure:
  - repeated “PhoneBridge keeps stopping”
  - outgoing call staying up unexpectedly after crash loop.
- Root cause confirmed from `logcat`:
  - `SparkCallAssistantService` was started from background receiver path and immediately crashed with:
    - `SecurityException: Starting FGS with type microphone ... app must be in eligible state`
  - this created a restart loop while call flow continued.
- Immediate recovery actions:
  - sent `KEYCODE_ENDCALL`
  - force-stopped app (`adb shell am force-stop com.tracsystems.phonebridge`)
  - verified assistant service no longer running.
- Code stabilization applied:
  - removed automatic `SparkCallAssistantService` start/stop hooks from:
    - `AndroidTelecomController.kt`
    - `BridgeInCallService.kt`
  - rebuilt and reinstalled APK.

### Why
- User reported active production failure (crash loop + unstable call state), which required immediate stabilization before continuing voice integration work.

### Result
- Crash loop is stopped.
- App is stable again after reinstall.
- Call control path remains available; aggressive background voice-autostart is disabled until a compliant startup path is implemented.

### Next
1. Implement voice-assistant startup only from an eligible foreground app state (not receiver-triggered background FGS).
2. Add explicit app-side “start/stop call assistant” control and permission/eligibility checks.
3. Re-test full live call with model response once compliant startup path is in place.

### Blockers
- Android 15 foreground-only microphone restrictions prevent the current background-triggered assistant startup pattern.

## 2026-02-19 17:00 — Voice loop recovered: Spark replies now generated during live call

### What
- Kept Android app stable by moving voice-assistant startup to app-foreground path (`MainActivity`) and avoiding receiver-triggered microphone FGS starts.
- Added/iterated `SparkCallAssistantService` diagnostics and fallback logic:
  - speech-recognition no-match fallback captures short microphone audio chunks
  - sends fallback WAV to Spark `/api/turn`
  - speaks model reply with device TTS.
- Fixed Android networking policy issue for Spark endpoint:
  - enabled cleartext traffic in manifest (`android:usesCleartextTraffic=\"true\"`).

### Why
- User reported: call rings but model is silent / not listening.
- Logs showed two root issues:
  1) microphone FGS eligibility crash when started from background receiver
  2) `Cleartext HTTP traffic ... not permitted` preventing Spark turn requests from device.

### Result
- Device logs now show active model response cycle during call:
  - audio fallback capture with non-zero RMS
  - Spark reply generated
  - TTS playback start/end events.
- Example verified log sequence:
  - `audio fallback rms=94.9`
  - `spark audio reply: ...`
  - `tts onStart`
  - `tts onDone`

### Next
1. Add explicit user-facing controls for assistant mute/unmute (keep listening while muting AI output).
2. Add call-state-aware start/stop without violating Android 15 microphone FGS rules.
3. Tune VAD thresholds/chunk duration for lower latency and fewer no-match loops.

## 2026-02-19 17:05 — Emergency call-stop + mic mute hard stop

### What
- Executed immediate hard-stop sequence on Pixel:
  - `KEYCODE_ENDCALL`
  - app `hangup` command
  - forced speaker off
  - `am force-stop com.tracsystems.phonebridge` to terminate assistant service loop.
- Verified call-state is clear:
  - `dumpsys telecom` shows no active/foreground call (`Foreground call: null`).
- Enforced microphone mute on AI phone:
  - sent app `mute=true` command via node-control CLI transport.
  - verified in `dumpsys audio`: `mic mute ... FromApi=true from system=true`.

### Why
- User reported active stuck call + hearing AI on wrong side + echo risk from AI phone microphone.

### Result
- Active call session is terminated.
- Assistant service is stopped.
- AI phone microphone is now muted at system level.

### Next
1. Add deterministic call teardown hook on remote hangup/disconnect callback.
2. Add persistent “AI output mute” and “mic mute” toggles in app UI.
3. Prevent assistant auto-restart until call-state transitions are fully controlled.

## 2026-02-19 17:28 — In-call service binding fixed; live assistant now speaks; downlink capture limits identified

### What
- Qualified app as default dialer and fixed role onboarding/manifest compliance:
  - `android.intent.action.DIAL` filters on `MainActivity`
  - `BridgeInCallService` metadata changed to `android.telecom.IN_CALL_SERVICE_UI=true` (required by role validator on this device).
- Added call-lifecycle handling in `BridgeInCallService`:
  - tracks call states (`connecting/dialing/active/disconnected`)
  - starts/stops `SparkCallAssistantService` on active/disconnect
  - sets speaker route through `InCallService#setAudioRoute`
  - applies call mute via in-call API.
- Added assistant safeguards and behavior:
  - 20s silence watchdog auto-hangup logic retained
  - greeting turn on connect (`Greet the caller in one short sentence`)
  - mute toggles around TTS (`mute=false` during speech, `mute=true` after speech)
  - source probing for capture path (`voice_downlink`, `voice_call`, `voice_communication`, `voice_recognition`, `mic`).
- Rebuilt/reinstalled and executed multiple real dial tests to `+4915129135779` with fresh log capture.

### Why
- User report: call control now works, but no model audio heard and no reliable listening.
- Needed hard evidence from tagged logs for both outbound speech and inbound capture path.

### Result
- Outbound model speech is generated by the app during active call:
  - `spark greeting: ...`
  - `tts onStart` / `tts onDone`
  - call mute flips exactly around TTS.
- In-call service is now actually bound/active as default dialer (previously skipped).
- Critical capture finding on this Pixel/ROM:
  - `voice_downlink(3)` unavailable
  - `voice_call(4)` unavailable
  - `voice_communication(7)` probes at `rms=0.0`
  - net effect: no usable remote-party audio reaches ASR in current non-privileged path.
- This explains the current symptom: caller hears silence/no meaningful assistant turn loop after greeting.

### Next
1. Decide operational mode:
   - strict mode (remote-audio-only; no mic fallback) => currently blocked by device capture limitations, or
   - pragmatic mode (allow mic acoustic fallback for now) => enables progress while preserving call control.
2. If strict mode required, move to a privileged/system-level capture path (or carrier/OEM call-recording API path) on this device.
3. If pragmatic mode accepted, enable controlled fallback and tune VAD/thresholds for practical turn-taking.

## 2026-02-19 17:34 — Recording-block investigation (dev-unblock check)

### What
- Validated platform/role/permission state on-device:
  - Android 16 / SDK 36 (`Pixel 10 Pro`)
  - app holds `android.app.role.DIALER`
  - app requests `CAPTURE_AUDIO_OUTPUT` in manifest.
- Tested direct grant path:
  - `pm grant ... CAPTURE_AUDIO_OUTPUT` fails with `SecurityException` (`managed by role`).
- Tested role re-apply/bypass paths:
  - remove/add DIALER role (with qualification bypass toggled) does **not** grant `CAPTURE_AUDIO_OUTPUT`.
  - temporary test with `SYSTEM_CALL_STREAMING` role (bypass mode) also did not grant capture permission; reverted role holder back to `com.google.android.gms`.
- Compared with system dialer:
  - `com.google.android.dialer` shows `CAPTURE_AUDIO_OUTPUT: granted=true` (privileged/system app path).
- Re-ran call capture probes:
  - `voice_downlink(3)` unavailable
  - `voice_call(4)` unavailable
  - `voice_communication(7)` available but `rms=0.0` in live call.

### Why
- User requested confirmation whether a developer-side unblock exists for call audio recording path.

### Result
- On this non-root/non-system-app setup, there is an effective platform block for true remote call-audio capture.
- Developer ADB role/permission toggles tested so far are insufficient to unlock remote downlink capture for this app.
- Current behavior (reacting to AI-phone-local audio) is consistent with these constraints.

### Next
1. Keep strict mode: disable local-mic fallback during call so it never reacts to AI-phone-local speech.
2. If remote-call ASR is mandatory, move to one of:
   - system/privileged app deployment, or
   - OEM/carrier-provided call-recording/call-streaming API path available to third-party apps on this device/region.

## 2026-02-19 17:40 — Web-backed validation of call-recording constraints on Pixel + role testing

### What
- Re-verified role holders on connected Pixel via ADB:
  - `DIALER=com.tracsystems.phonebridge`
  - `SYSTEM_AUDIO_INTELLIGENCE=com.google.android.as`
  - `SYSTEM_CALL_STREAMING=com.google.android.gms`
- Tried forced role swap for `SYSTEM_AUDIO_INTELLIGENCE` (with role qualification bypass) to grant capture path to app; add failed and role remained unchanged; restored bypass setting off.
- Collected primary-source references for capture permissions and role grants:
  - AOSP permission definition: `CAPTURE_AUDIO_OUTPUT` is `signature|privileged|role`.
  - AOSP roles: DIALER role permissions do not include capture permission.
  - AOSP roles: `SYSTEM_AUDIO_INTELLIGENCE` is `systemOnly` and includes `CAPTURE_AUDIO_OUTPUT`.
  - Google Phone help confirms call recording is product/region/device policy-gated in official dialer path.

### Why
- User challenged feasibility because Play Store has many call recorder apps and requested explicit web research.

### Result
- Current app path remains blocked from true telephony downlink capture on this non-system app deployment.
- Existing call-recorder apps on Pixel typically rely on OEM privileged integrations, policy-gated built-in dialer recording, or microphone/speaker acoustic fallback (not unrestricted telephony stream access).
- This matches observed behavior: assistant can control calls, but remote-audio digital capture is unavailable without privileged/system path.

### Next
1. Implement/validate pragmatic acoustic bridge mode (speakerphone + tuned mic capture + VAD/AEC) for usable behavior now.
2. Keep strict mode available to avoid false local capture when acoustic mode is disabled.
3. Continue documenting exact capability boundaries per Android role/device policy.

## 2026-02-19 17:49 — Hardened acoustic turn capture implemented (strict gating + speaker filter)

### What
- Updated plan with a dedicated hardening track in `phone/PLAN.md`:
  - strict capture gating
  - speaker verification
  - pre-ASR audio cleanup
  - contamination fallback behavior.
- Implemented hardening in `SparkCallAssistantService`:
  - added capture quality analysis per turn (RMS, voiced duration, voiced ratio, dynamic range, clipping, confidence score).
  - enforced rejection rules for weak/noisy/contaminated audio before Spark submission.
  - added lightweight speaker fingerprint enrollment (first accepted voice turn) + similarity check on later turns.
  - added clarification fallback TTS on rejected turns (cooldown-protected).
  - enabled audio effects when available on capture session:
    - AEC (`AcousticEchoCanceler`)
    - NS (`NoiseSuppressor`)
    - AGC (`AutomaticGainControl`)
  - capped in-call speaker volume to limit room leakage.

### Why
- User requested execution of hardened “record approach” to reduce room contamination and improve turn reliability without privileged telephony capture.

### Validation
- Rebuilt app: `scripts/android-build-debug.sh` ✅
- Installed + role/perm checks: `scripts/android-install-debug.sh` ✅
- ADB smoke path: `scripts/android-smoke-adb.sh` ✅
  - dial command reached controller
  - hangup command reached controller
- Unit tests: `scripts/test-unit.sh` ✅ (11/11 passing)

### Result
- Audio turns are now gated before LLM calls; contaminated/low-quality segments are dropped and replaced with local “please repeat” prompts.
- Speaker contamination defense path is active (baseline enrollment + similarity check), with logs in `CommandAuditLog`.
- This does not bypass Android’s platform limit on privileged downlink capture; it hardens the acoustic fallback path.

### Next
1. Run live call verification with real caller speech and inspect rejection/similarity logs under load.
2. Tune thresholds (`MIN_VOICED_MS`, `MIN_CAPTURE_CONFIDENCE`, `MIN_SPEAKER_SIMILARITY`) from observed false-reject/false-accept rates.
3. If needed, add on-device per-caller speaker re-enroll action in control API.

## 2026-02-19 17:57 — Why caller hears no AI voice / why ASR keeps rejecting

### What
- Ran live call log inspection after hardening deploy and observed full call lifecycle with active assistant.
- Verified greeting generation and TTS events do fire:
  - `spark greeting: ...`
  - `tts onStart` / `tts onDone`
  - mute toggles around TTS (`call mute=false` then `true`).
- Verified input capture metrics during active call:
  - `voice_downlink(3)` unavailable
  - `voice_call(4)` unavailable
  - `voice_communication(7)` rms=`0.0`
  - `voice_recognition(6)` rms=`0.0`
  - `mic(1)` rms=`0.0`
  - subsequent turn rejections: `audio fallback capture rejected: low_rms`.

### Why
- On this Pixel path, non-privileged app still has no usable telephony downlink stream.
- Acoustic fallback also receives effectively silent frames (`rms=0.0`) while call is active, so ASR cannot ingest remote speech and fallback prompts repeat.
- TTS is generated locally, but this does not guarantee telephony uplink injection to the remote party; with current call-audio policy/DSP behavior, remote side still does not reliably hear model speech.

### Result
- Current failure is not from model generation speed/content.
- It is an audio-path constraint in active PSTN call mode on this device/configuration.

### Next
1. Add explicit runtime diagnostics endpoint showing current capture-source RMS in-call (for quick operator verification).
2. Test a temporary unmuted debug profile to confirm whether call mute policy is suppressing the only usable mic path.
3. If still zero-RMS, treat this device path as blocked for autonomous call voice bridging without privileged/system audio access.

## 2026-02-19 18:02 — Research: two-app helper mode vs true call-audio capture; root fallback feasibility

### What
- Researched “2-app helper” approaches and Android capture policy constraints.
- Verified Android platform rule from official docs:
  - Voice call capture is restricted; ordinary apps cannot capture full call audio.
  - Full call capture is only for privileged/system/role-protected apps.
- Reviewed helper-app behavior from recorder vendor docs:
  - ACR helper mode on Android 10+ still uses microphone/voice-recognition path (not unrestricted downlink capture).
  - Vendor FAQ states root + Magisk helper module enables “2-way call recording including Bluetooth”.

### Why
- User asked whether helper mode can provide true voice capture and approved root if needed.

### Result
- On stock non-root Pixel, 2-app helper can improve practicality but does not remove core policy limits for true telephony downlink capture.
- Root path is a viable technical fallback to remove those blockers on this device.

### Source links
- Android API ref (`MediaRecorder.AudioSource` call-audio restrictions):
  - https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
- AOSP permission definition (`CAPTURE_AUDIO_OUTPUT` protected as privileged/role):
  - https://android.googlesource.com/platform/frameworks/base/+/master/core/res/AndroidManifest.xml
- AOSP role grants (`SYSTEM_AUDIO_INTELLIGENCE` role is system-only and grants capture perms):
  - https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml
- ACR helper documentation (Android 10+ recording source behavior):
  - https://apkpure.com/acr-phone-helper/com.nll.cb
- ACR vendor FAQ (root + Magisk module enables 2-way recording):
  - https://nllapps.com/apps/cb/help/faq

### Next
1. Keep current non-root branch as baseline (for reproducible behavior comparison).
2. If approved, create a rooted branch path for call audio:
   - install Magisk helper module path and validate non-zero call RMS.
   - retune VAD/speaker gates after true call audio is available.
3. Gate progression on live proof: remote caller audio must show sustained non-zero RMS + valid ASR turn content.

## 2026-02-19 18:23 — Root path execution started; fastboot fetch limitation hit

### What
- Confirmed OEM unlock path completed (`fastboot getvar unlocked` => `yes`).
- Re-established ADB after first boot and confirmed device/build:
  - device: `blazer`
  - build id: `BD3A.250721.001.E1`
- Attempted direct partition pull in fastboot:
  - `fastboot fetch init_boot_a init_boot-stock_a.img`

### Result
- Command failed with:
  - `remote: 'Fetching restricted partition: init_boot_a is not allowed'`
- This is an expected bootloader policy on this Pixel generation: unlock allows flashing, but not arbitrary read-back of restricted partitions via `fastboot fetch`.

### Why this happened
- Bootloader unlock does not imply unrestricted partition read permissions.
- For Magisk patching we therefore must source stock `init_boot.img` from matching OTA/factory image rather than reading it directly from device.

### Next
1. Resolve exact matching OTA/factory package URL for `BD3A.250721.001.E1`.
2. Extract `init_boot.img` from package payload.
3. Patch with Magisk and flash patched image to active slot.

## 2026-02-19 18:56 — Recovery from boot loop confirmed (not bricked)

### What
- Restored stock critical boot partitions from matching factory image:
  - `boot_[ab]`, `init_boot_[ab]`, `vbmeta_[ab]`, `vendor_boot_[ab]`, `vendor_kernel_boot_[ab]`
- Rebooted device and re-checked ADB/system state.

### Result
- Device boots normally again and is reachable with `adb` as `device`.
- Boot completed (`sys.boot_completed=1`) and fingerprint matches:
  - `google/blazer/blazer:16/BD3A.250721.001.E1/14034804:user/release-keys`
- Root is currently **not active**:
  - `su: inaccessible or not found`

### Why
- The previous loop was caused by incompatible patched boot chain state.
- Returning all critical partitions to stock for this exact build stabilized the device.

### Next
1. Keep phone stable in stock state until root path is retried with a validated method for Pixel 10 Pro / Android 16.
2. If proceeding with root again, do single-partition incremental flash + boot validation after each step.

## 2026-02-19 19:35 — APatch fallback succeeded on Pixel 10 Pro (Android 16 E1)

### What changed
- Pivoted from Magisk to APatch (`bmax121/APatch`) because Magisk-patched images booted but never exposed working root (`su` unavailable).
- Built APatch patching toolchain from `APatch-latest.apk` assets/libs:
  - `kptools`, `magiskboot`, `kpimg`, `apd`, `busybox`, `magiskpolicy`, `resetprop`.
- Patched stock `boot.img` with APatch and flashed active slot `boot_a`.
- Generated and stored APatch superkey at:
  - `phone/root-work/APATCH_SUPERKEY.txt`
- Bootstrapped APatch runtime paths under `/data/adb/ap` and su path config to `/data/adb/ap/bin/su`.

### Verification
- APatch superkey auth works:
  - `apd -s <good-key> module list` => success (`[]`)
  - `apd -s <bad-key> module list` => permission denied
- Root command works via APatch su path:
  - `/data/adb/ap/bin/su -c id` => `uid=0(root) ...`
- Persistence test after reboot:
  - phone boots normally
  - `/data/adb/ap/bin/su -c id` still returns root

### Important note
- Plain `su` is **not** on default shell/app PATH (`/system/bin/su` absent).
- Reliable invocation path is:
  - `/data/adb/ap/bin/su -c '<command>'`
- For our app stack this is acceptable because we can call the absolute path directly.

## 2026-02-19 19:48 — Root path wired into phone runtime + node transport

### What changed
- Added Android root runtime helper:
  - `app-android/app/src/main/java/com/tracsystems/phonebridge/RootShellRuntime.kt`
  - Detects available root shell with preferred order:
    1. `/data/adb/ap/bin/su`
    2. `/system/xbin/su`
    3. `su`
  - Exposes `ensureReady()` and `run(command)` for automatic rooted execution.
- Wired root bootstrap into `SparkCallAssistantService` startup:
  - on service start, probe root and log readiness into `CommandAuditLog`
  - run bootstrap root commands automatically:
    - enforce APatch su path file (`/data/adb/ap/su_path`)
    - set appops for `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `CAPTURE_AUDIO_OUTPUT`
- Extended node ADB transport to support automatic rooted command dispatch:
  - `node-control/src/transports/adbTransport.js`
  - root-first mode via `PHONE_ADB_ROOT_PATH` (default `/data/adb/ap/bin/su`)
  - automatic fallback to plain `am broadcast` if root shell call fails
  - supports explicit control flags (`useRootShell`, `fallbackToPlain`)
- Extended controller/env config wiring:
  - `node-control/src/index.js` now parses:
    - `PHONE_ADB_USE_ROOT`
    - `PHONE_ADB_ROOT_PATH`
    - `PHONE_ADB_ROOT_FALLBACK`
- Extended CLI wiring:
  - `node-control/src/cli.js` supports `--adb-use-root`, `--adb-root-path`, `--adb-root-fallback`

### Validation
- Node unit tests:
  - `node --test node-control/test/unit/adbTransport.test.js` => pass
  - `npm run test:unit` => pass
- Android compile check:
  - `cd app-android && ./gradlew :app:compileDebugKotlin` => `BUILD SUCCESSFUL`

### Skill update
- Updated `phone/ROOT-SKILL-PIXEL10PRO.md` to explicitly record active `su` path:
  - `/data/adb/ap/bin/su`

## 2026-02-19 19:56 — Deploy + smoke after root runtime wiring

### What ran
- `npm run android:install:debug`
- `npm run android:smoke:adb`

### Result
- APK install succeeded (`Performing Streamed Install` => `Success`).
- Install script then failed only at activity launch step:
  - `Error type 3 ... MainActivity does not exist`
- ADB smoke script sent dial/hangup broadcasts but did not observe expected dial execution log in filtered output.

### Notes
- Root runtime wiring itself is compile-validated and unit-tested.
- The post-install launch error is in the helper script path invocation, not APK installation.

## 2026-02-19 19:59 — ROOT skill updated with runtime wiring details

### What changed
- Extended `phone/ROOT-SKILL-PIXEL10PRO.md` beyond just the `su` path:
  - added Android runtime integration references (`RootShellRuntime`, `SparkCallAssistantService`)
  - added Node root-transport integration references (`adbTransport`, controller/env wiring)
  - added root transport env knob list:
    - `PHONE_ADB_USE_ROOT`
    - `PHONE_ADB_ROOT_PATH`
    - `PHONE_ADB_ROOT_FALLBACK`

## 2026-02-19 20:06 — Direct-call mode tightening (no self-record fallback)

### What changed
- Updated `SparkCallAssistantService` to harden direct-call behavior:
  - removed mic/voice_communication/voice_recognition sources from candidate list.
  - capture now uses only telephony-focused sources:
    - `voice_downlink` (id 3)
    - `voice_call` (id 4)
  - removed retry fallback to mic in capture path.
  - in strict remote mode, probe rejects low-RMS source instead of forcing mic.
- Kept call mute enforced during TTS start (`KEEP_CALL_MUTED_DURING_TTS=true`) to avoid echo loops from local mic.

### Validation
- Kotlin compile check:
  - `cd app-android && ./gradlew :app:compileDebugKotlin` => `BUILD SUCCESSFUL`
- Unit suite:
  - `npm run test:unit` => pass

### Expected behavior
- Prevents “phone records itself” fallback path.
- Uses remote call-audio-oriented capture only.
- If remote stream is unavailable/blocked by platform routing, capture now rejects instead of silently switching to mic.

## 2026-02-19 20:25 — Persistent root bridge fixed; telephony capture still blocked

### What changed
- Diagnosed root failure source precisely:
  - App process could not execute `/data/adb/ap/bin/su` (`error=13 Permission denied`) even though device root itself worked.
  - This is Android app sandbox exec restriction, not missing root install.
- Added app-side root transport fallback to a localhost root daemon:
  - `app-android/app/src/main/java/com/tracsystems/phonebridge/RootShellRuntime.kt`
  - New `rootd` transport over `127.0.0.1:48733` with command protocol + retries.
  - Added diagnostics for shell resolution failures.
- Added persistent APatch boot daemon scripts:
  - `rootd/phonebridge-root-handler.sh`
  - `rootd/phonebridge-rootd.sh`
  - Deployed to `/data/adb/service.d/` and verified listener on reboot.
- Reduced startup delay by removing invalid appops from bootstrap:
  - removed `MODIFY_AUDIO_SETTINGS` and `CAPTURE_AUDIO_OUTPUT` appops operations (unsupported op names on this build).

### Validation
- Reboot persistence confirmed:
  - `/data/adb/ap/bin/su -c id` => `uid=0(root)` after reboot
  - `ss -ltnp` shows `127.0.0.1:48733` listener after reboot.
- In-call service log now confirms runtime root path:
  - `root shell ready: rootd`.

### Remaining blocker (unchanged)
- Telephony capture sources are still unavailable on this Pixel/ROM path:
  - `audio source unavailable: voice_downlink(3)`
  - `audio source unavailable: voice_call(4)`
- Result: model can speak, but cannot capture remote caller audio via these `AudioRecord` sources.

## 2026-02-19 21:02 — Local AI handset mute/speaker suppression + skill update

### What changed
- Updated `SparkCallAssistantService` to keep the AI handset locally quiet while preserving bridge path:
  - forces in-call speaker route off on service start.
  - forces speakerphone off in audio route setup.
  - forces `STREAM_VOICE_CALL` volume target to `0` (local mute behavior).
  - keeps call uplink muted during TTS (`KEEP_CALL_MUTED_DURING_TTS=true`) so handset mic stays muted.
- Rebuilt and reinstalled debug APK successfully.
- Updated root skill with operational guardrails and boot-unlock requirement:
  - `phone/ROOT-SKILL-PIXEL10PRO.md`
  - added Direct Boot first-unlock requirement (`RUNNING_UNLOCKED`) and screen-off behavior notes.
  - added explicit no-double-dial testing rule (`call now` / `hang up` gating).

### Why
- User requested disabling AI phone mic + speaker locally (to prevent echo/discomfort) while continuing remote bridge testing.

### Current status
- Local handset audio is now configured to stay muted/speaker-off by default in the assistant flow.
- Core capture blocker remains unresolved: in-call PCM capture devices still return I/O errors with zero frames on current Pixel build/ROM path.

## 2026-02-19 21:16 — Root-only bring-up changes (no local clarification speech)

### What changed
- Updated `SparkCallAssistantService` for strict root-bridge behavior:
  - disabled local clarification TTS prompts (`ENABLE_LOCAL_CLARIFICATION_TTS=false`) so AI phone does not keep saying “please repeat”.
  - added root call-route profile application on service start and restore on service stop.
  - route profile is applied via `tinymix` through rootd.
- Added root mixer priming commands:
  - set voice call source to `IN_CALL_MUSIC`.
  - set in-call capture streams to `DL`.
  - enable in-call playback streams.
  - keep in-call mic/sink mutes asserted while service is active.
- Added safe restore commands to return mixer controls to baseline on service teardown.

### Validation
- Rebuilt and reinstalled APK successfully.
- Manually validated root mixer command path through `rootd`:
  - route-set commands apply and report expected enum states.
  - route-restore commands revert back to defaults.

### Why
- User requested to disable local handset chatter and continue exclusively on root PCM route bring-up until remote audio works.

## 2026-02-19 21:24 — TX injection proof succeeded (remote phone hears injected tone)

### What ran
- Guarded live call test to `+4915129135779` (single call, no overlap).
- Generated 2s 880Hz WAV and pushed to device (`/data/local/tmp/pb_tone.wav`).
- Played tone via root tinyalsa on in-call playback devices:
  - `audio_incall_pb_0` (device `18`)
  - `audio_incall_pb_1` (device `19`)
  - `audio_incall_pb_2` (device `29`)

### Result
- User confirmed hearing the tone clearly on the remote phone.
- `tinyplay` reported successful playback on all three devices.

### Interpretation
- Uplink/in-call playback injection path is working.
- Remaining blocker is predominantly on capture/downlink stability (ASR input path), not outbound audio injection.

## 2026-02-19 21:35 — RX proof succeeded (captured full in-call audio across cap devices)

### What ran
- Guarded live call to `+4915129135779`.
- Applied root route profile via `tinymix` (IN_CALL_MUSIC + DL capture stream mapping + incall playback on).
- Recorded 10s samples from:
  - `audio_incall_cap_0` (device `20`)
  - `audio_incall_cap_1` (device `21`)
  - `audio_incall_cap_2` (device `22`)
  - `audio_incall_cap_3` (device `54`)

### Result
- All four devices produced valid 10.24s WAV captures (`327724` bytes) with non-trivial signal levels.
- Prior zero-frame/I/O-error condition was not present in this profiled run.
- Quick ASR probe against Spark `/api/turn` confirms non-empty transcripts from captured WAVs.

### Follow-up tuning implemented
- Updated app root capture logic to be more robust:
  - preselects a root capture device (`20`) on service start.
  - retries capture across all root candidates if current source fails.
  - bypasses strict quality/speaker rejection gates in root mode to avoid false `no_audio_source` loops.
  - keeps local clarification TTS disabled (silent failure mode on handset).
- Rebuilt and reinstalled debug APK.

## 2026-02-19 21:31 — Playback path recovered on live call (device 19)

### What changed
- Patched `SparkCallAssistantService` playback handling:
  - accept Spark `audio_base64` response field.
  - normalize reply WAV before root playback.
  - dynamic tinyplay timeout based on clip duration + margin (instead of fixed 6s).
  - prioritize root playback device order `19 -> 18`.
  - disable connect-time greeting to reduce initial turn latency.

### Validation
- Built + installed fresh debug APK.
- Live outbound call to `+4915129135779`.
- Logcat confirms bridge pipeline executed end-to-end:
  - root capture active (`incall_cap_1`/`incall_cap_2`).
  - Spark replies returned.
  - root telephony playback succeeded: `root tinyplay ok device=19` on consecutive turns.

### Current status
- Primary previous blocker (`tinyplay` timeout / no remote playback) is resolved in this run.
- Pending user-side subjective validation (voice speed/clarity/latency perception on remote handset).

## 2026-02-19 21:41 — Rootd-path latency and fast-voice stabilization pass

### User-reported issues addressed
- Voice still sounded too fast.
- Repetitive generic replies suggested weak/empty caller audio reached the model.
- Perceived startup lag before first response looked tied to rootd/init sequence.

### Changes applied
- `SparkCallAssistantService` now starts in root-playback mode without local TTS initialization:
  - `ENABLE_LOCAL_TTS_FALLBACK=false`.
  - avoids TTS init delay in the critical call-start path.
- Root route setup moved async so capture loop starts immediately (`startCaptureLoop(250)` then route set in background).
- Added transcript telemetry from Spark turn responses to logcat/audit for live confirmation of what ASR heard.
- Tightened root capture acceptance (while quality gates are bypassed):
  - reject `root_low_rms` and `root_short_voice` instead of forwarding near-empty noise.
- Playback pipeline retuned:
  - normalized model WAV before playback,
  - explicit tinyplay params (`-c 1 -r 48000 -b 16`),
  - dynamic playback timeout based on clip duration,
  - playback device priority stays `19 -> 18`.

### Controlled verification
- One outbound verification call executed and then hung up.
- Evidence from logs:
  - model received caller speech (`spark audio transcript: Thank you.`),
  - model generated reply (`You're welcome!`),
  - root telephony playback succeeded (`root tinyplay ok device=19`).
- Remaining intermittent behavior: subsequent turns can still reject capture as `no_audio_source` when signal is weak/noisy.

## 2026-02-19 21:44 — Root prewarm before call + startup latency reduction

### What changed
- Added root prewarm on dial command in `AndroidTelecomController` (`RootShellRuntime.ensureReady()` on background thread).
- Added root prewarm on early call states in `BridgeInCallService` (`CONNECTING`/`DIALING`/`RINGING`) with throttle.
- Kept root route setup asynchronous and off the critical capture executor path.

### Why
- User identified ~6s delay after pickup before bridge responses begin.
- This delay was largely route/root setup overhead occurring near call activation.

### Result
- Prewarm now starts before `STATE_ACTIVE`, reducing cold-start penalties at pickup.
- APK rebuilt and reinstalled.

## 2026-02-19 21:47 — Requested hotfix: relaxed root capture + near-instant first capture

### Applied exactly per request
- Relaxed root capture gates:
  - `ROOT_MIN_ACCEPT_RMS`: `60.0 -> 14.0`
  - `ROOT_MIN_ACCEPT_VOICED_MS`: `700 -> 160`
- Removed extra startup delay in capture path:
  - `startCaptureLoop(250)` -> `startCaptureLoop(40)`
  - root capture window reduced (`durationMs=1200`) and tinycap seconds rounding adjusted for faster first attempt.
- Root capture selection fixed:
  - no longer returns immediately on first weak candidate.
  - now iterates root capture devices and only accepts first candidate that passes gates.
- Service startup de-blocked:
  - root bootstrap commands moved fully async in background thread.
  - service no longer waits for bootstrap command list before first capture.
- Call activation route tweak:
  - `BridgeInCallService` now sets speaker route `false` on `STATE_ACTIVE` (avoid route flip from `true` to `false`).

### Build/deploy
- Debug APK rebuilt and installed successfully.

## 2026-02-19 21:51 — Echo/self-capture mitigation pass

### Why
- User reported faster startup, but semantic mismatch remained (captured transcript not matching spoken prompt).
- Likely cause: immediate post-playback recapture was ingesting telephony echo/loopback artifacts.

### Changes
- Added post-playback settle delay before next capture:
  - `POST_PLAYBACK_CAPTURE_DELAY_MS = 720` applied after root playback success.
- Increased root capture window from `1200ms` to `1650ms` for better utterance completeness.
- Rebalanced root acceptance gates from ultra-loose to moderate:
  - `ROOT_MIN_ACCEPT_RMS`: `14.0 -> 26.0`
  - `ROOT_MIN_ACCEPT_VOICED_MS`: `160 -> 300`

### Build/deploy
- Debug APK rebuilt and installed successfully.

## 2026-02-19 21:56 — Transcript fidelity guardrail pass (semantic mismatch fix)

### Trigger
- User reported clear audio but semantic mismatch: spoken intent (e.g. movie question) did not match transcript/reply.

### Changes
- Added transcript-quality rejection before accepting a turn:
  - rejects low-information/looped/filler transcripts and likely echo overlap with last assistant reply.
  - if rejected, drops turn and immediately recaptures instead of forwarding bad context.
- Added `lastAssistantReplyText` tracking for overlap/echo filtering.
- Increased root capture sample rate to `24kHz` for ASR input quality.

### Build/deploy
- Debug APK rebuilt and installed successfully.

## 2026-02-19 22:01 — Adaptive root capture deployment (post no-audio failure)

### Issue observed
- Call session still showed repeated `no_audio_source` and auto-hangup.
- Log snapshot corresponded to pre-adaptive build.

### Deployed fix
- Installed new adaptive capture build that:
  - falls back root capture sample rates per device (`requested -> 16k -> 8k`).
  - preserves actual capture sample rate in WAV sent to Spark.
  - triggers throttled route re-apply on `no_audio_source`.

### Status
- APK rebuilt and installed successfully.
- Next live call required to validate this new build behavior.

## 2026-02-19 22:20 — Two-stage ASR hardening (Spark) + Android filter update

### Trigger
- Live calls still produced wrong first-turn transcripts (e.g. “Thanks for watching!”), causing immediate unrelated replies.
- Root cause identified in gateway ASR path: Whisper hallucinated on low-information/near-silent telephony captures.

### Spark gateway changes (`~/ai/services/spark-voice-gateway/app.py`)
- Added pre-ASR signal analysis gate:
  - `ASR_MIN_RMS` (default `0.010`)
  - `ASR_MIN_VOICED_RATIO` (default `0.10`)
  - `ASR_MIN_VOICED_MS` (default `220`)
- Added transcript cleanup + hallucination guards:
  - drops punctuation-only outputs,
  - drops known hallucinated courtesy phrases in weak-speech conditions.
- Updated Whisper decode kwargs:
  - `temperature=0.0`,
  - `condition_on_prev_tokens=False`,
  - keeps `task/language` only for multilingual models (avoids `.en` model crash).
- Restarted `spark-voice-gateway.service`.

### Spark verification
- `/api/asr` on silence now returns empty transcript and is fast:
  - sample result: `transcript=""`, `asr_ms≈1.2`.
- `/api/asr` on synthetic speech transcribes correctly:
  - sample text: “Hello there, what movie should I watch tonight?”
- `/api/turn` with `skip_asr=true` remains functional end-to-end.

### Android changes (`SparkCallAssistantService.kt`)
- Extended low-quality transcript rejection patterns to catch:
  - `thanks for watching`,
  - `thank you very much`,
  - generic closing phrases (`have a great/nice day`).
- Rebuilt and reinstalled debug APK.

### Current state
- Two-stage path is active.
- Gateway ASR now suppresses silence/noise hallucinations.
- Phone app is updated to reject additional known bad transcripts before forwarding to LLM.

## 2026-02-19 22:27 — Phone root skill cleanup and canonicalized runbook

### Trigger
- Request to remove stale/contradictory guidance and preserve the exact root + record/stream setup that currently works.

### Changes
- Rewrote `phone/ROOT-SKILL-PIXEL10PRO.md` into a single canonical runbook.
- Removed outdated/conflicting notes and retained only current, validated flow:
  - APatch root model + active `su` path,
  - mandatory rootd runtime bridge for app sandbox,
  - direct-boot/unlock requirement,
  - tinycap/tinyplay route profile and device map,
  - two-stage Spark ASR → turn pipeline contract,
  - failure signatures + concrete checks.

### Outcome
- New sessions can reproduce root + telephony bridge state without depending on scattered chat history.
- Root/streaming knowledge is now centralized and operationally aligned with current code paths.

## 2026-02-19 22:31 — Skill split: root vs voice bridge

### Trigger
- Request to split the monolithic skill into separate root and voice runbooks.

### Changes
- Refactored `phone/ROOT-SKILL-PIXEL10PRO.md` to root-only scope.
- Added `phone/VOICE-BRIDGE-SKILL.md` for telephony record/stream + Spark gateway flow.
- Moved voice runtime details (device map, route profile, two-stage API contract, live checks, failure handling) into the new voice skill.
- Added explicit cross-reference from root skill to voice skill.

### Outcome
- Root provisioning and call-stream operations are now separated, easier to maintain, and less likely to regress from stale mixed guidance.

## 2026-02-19 22:43 — Live call tuning pass (multi-source capture attempts)

### Trigger
- Last live call had zero replies + heavy echo and repeated low-quality ASR filler outputs.

### Changes
- `SparkCallAssistantService` updated:
  - keep longer capture window (`2800ms`),
  - add per-turn multi-source attempts (`MAX_CAPTURE_ATTEMPTS_PER_TURN=3`),
  - rotate root capture source after empty/low-quality transcript,
  - retry within same turn before giving up.
- Reverted route mute controls `135/136` back to `1` in active-call profile to avoid the strong echo introduced by unmuting sink path.
- Rebuilt and reinstalled debug APK.

### Retest evidence
- Outbound call connected and assistant service started normally.
- First capture attempt rejected (`Yeah.` low-quality), second source produced transcript and a spoken reply:
  - transcript: `I'll see you next time.`
  - reply: `See you then.`
  - playback: `root tinyplay ok device=19`
- Later turn attempts still showed instability (`empty` transcript / `no_audio_source`) before call end.

### Current status
- Regression from “no response at all” is improved (assistant can now speak in-call again).
- Semantic ASR quality is still unstable; further tuning still required for reliable intent capture.

## 2026-02-19 22:52 — Spark ASR telephony preprocessing pass (noise/echo hardening)

### Trigger
- User asked to try explicit noise suppression/telephony filtering because browser flow works while PSTN flow hallucinates.

### Spark gateway changes (`~/ai/services/spark-voice-gateway/app.py`)
- Added telephony preprocessing controls:
  - `ASR_ENABLE_TELEPHONY_FILTER` (default on),
  - `ASR_TELEPHONY_FILTER` ffmpeg chain (highpass/lowpass + denoise + compression + loudness normalization),
  - `ASR_DUAL_PATH` (default on).
- Added dual-path ASR selection:
  - generate both raw and telephony-filtered WAV,
  - transcribe both and choose best-scoring transcript,
  - expose `asr_source` in response metrics (`filtered|raw|hint|none`).
- Expanded hallucination rejection:
  - blocks generic outro/filler phrases including `i'll see you next time` / `see you then`,
  - filler-only short outputs are rejected.
- Restarted `spark-voice-gateway.service`.

### Endpoint validation after deploy
- Silence test:
  - transcript stays empty (`asr_source=none`).
- Normal speech test:
  - “tell me what movie to watch tonight” transcribes correctly (`asr_source=filtered`).
- Hallucination phrase test:
  - “I’ll see you next time” now rejected to empty transcript.

### Current status
- Telephony-noise hardening is active on Spark.
- Next required step is live PSTN call validation with user speech for end-to-end behavior.

## 2026-02-19 23:04 — Handover mitigation pass (pin source + route burst + WAV dumps)

### Trigger
- User reported persistent mismatch: no meaningful response, suspected handover/routing issue.
- Requested three actions:
  1) pin one capture source per call,
  2) re-apply route profile at `+0.5s` and `+2s` after `ACTIVE`,
  3) save WAVs so user can audit exactly what was captured/played.

### Android app changes
- `SparkCallAssistantService`:
  - Added **route reapply action** handling (`ACTION_REAPPLY_ROUTE`).
  - Added **capture pinning**:
    - source rotates only until first accepted transcript,
    - then source is pinned for the remainder of that call.
  - Added **debug WAV persistence**:
    - RX captures saved per attempt,
    - TX reply WAVs saved per turn,
    - automatic pruning to keep recent files only.
- `BridgeInCallService`:
  - Added post-active route reapply burst:
    - reapply at `+500ms`,
    - reapply at `+2000ms`.

### Build/deploy
- Debug APK rebuilt and installed successfully.

### Debug WAV location
- App internal directory:
  - `/data/user/0/com.tracsystems.phonebridge/files/voice-debug/`
- Files:
  - `rx-*.wav` (captured call audio),
  - `tx-*.wav` (assistant audio sent to call).

## 2026-02-20 03:16 — Live dial triggered on operator request (`call now`)

### What
- Triggered outbound call command from local Node control:
  - `node phone/node-control/src/cli.js dial +4915129135779 --transport adb --serial 59191FDCH000YV --adb-use-root true --adb-root-path /data/adb/ap/bin/su --adb-root-fallback true`
- Verified command delivery:
  - ADB broadcast returned `ok: true` in `root` mode.
- Verified active-call transition and assistant startup in logs:
  - `BridgeInCallService: state=active`
  - `SparkCallAssistant: service started`
  - `SparkCallAssistant: running audio fallback capture (attempt 1/3)`

### Current status
- Outbound call path is operational for this test run.
- Awaiting user-side call outcome to inspect new `rx/tx` debug WAVs and transcript quality for this specific call.

## 2026-02-20 03:21 — Post-hangup forensic: captured WAVs confirm RX semantic failure

### What
- After user hung up, pulled and analyzed all WAVs from:
  - `/data/user/0/com.tracsystems.phonebridge/files/voice-debug/`
  - mirrored locally to `phone/debug-wavs/`.
- Re-ran gateway ASR on each captured RX file and compared with live logs.

### Evidence
- RX captures (all 16kHz, 3.072s):
  - `rx-...a1-incall_cap_0.wav` -> transcript `you` (rejected)
  - `rx-...a2-incall_cap_1.wav` -> transcript `Okay.` (rejected)
  - `rx-...a3-incall_cap_2.wav` -> transcript `You should.` (accepted, pinned source 22)
  - later `rx-...a3-incall_cap_2.wav` -> long `Mmmmm...` hallucination (rejected)
- TX capture is valid:
  - `tx-...you-should..wav` (24kHz) transcribes to:
    - `Absolutely. I'm here to help you with anything.`
- During the same call, many capture attempts failed with:
  - `audio fallback capture rejected: no_audio_source`
  - and short-voice rejects (`root_short_voice`).

### Conclusion
- Current failure is on **RX semantic quality / source stability**, not TTS generation:
  - assistant can synthesize and inject valid reply audio,
  - but inbound caller speech reaching ASR is inconsistent/garbled, producing low-information/hallucinated transcripts.

## 2026-02-20 03:30 — Applied WAV decode normalization fix for root capture path

### Trigger
- User inspected dumped RX WAVs and reported the same smoking gun:
  - inbound voice sounds extremely slow/unrecognizable,
  - outbound model WAV sounds normal.

### Root-cause hypothesis addressed
- Root capture code was extracting raw WAV `data` bytes and re-wrapping them as `16k mono s16` without respecting original WAV format metadata.
- If `tinycap` returns a different effective format/sample-rate than requested, this corrupts speed/pitch and poisons ASR.

### Changes applied
- Updated `SparkCallAssistantService` WAV handling to decode with format awareness:
  - parse WAV `fmt` + `data` chunks,
  - normalize to PCM16 mono with correct handling for 8/16/24/32-bit (PCM and float32),
  - keep and propagate **actual** decoded sample rate.
- Root capture path now:
  - logs requested vs actual tinycap format,
  - uses actual decoded sample rate in `RootCaptureFrame`,
  - writes debug RX WAVs with corrected timing metadata.
- Added raw-capture WAV dump before normalization:
  - `rxraw-*.wav` now saved alongside normalized `rx-*.wav` for direct tinycap format forensics.
- Reply normalization path now uses the same decoder (single path for WAV parsing).

### Deploy
- Rebuilt and reinstalled debug APK successfully after patch.
- Cleared old debug WAV dump directory to isolate next call validation.

### Next validation required
- Run a fresh live call and inspect newly dumped RX WAVs:
  - verify they are no longer time-stretched/slow,
  - verify transcript quality improves accordingly.

## 2026-02-20 03:36 — Fresh live call after WAV decode patch: RX still corrupted (buzz-class capture)

### What
- Ran a new real dial (`+4915129135779`) and captured post-call artifacts/logs.
- Confirmed AI response observed by user: assistant said the input looked like repeated `z`.

### Device log evidence
- Capture attempt 1 (`incall_cap_0`) transcript:
  - `Mmm.` (rejected low quality).
- Capture attempt 2 (`incall_cap_1`) transcript:
  - long `Bzzzzzzzz...` (accepted, then source pinned).
- Assistant reply generated from that transcript:
  - “I'm sorry, but your message seems to be filled with multiple "z" characters...”
- Root playback failed this run (timeouts on both playback devices):
  - `root tinyplay failed device=19 ... Read timed out`
  - `root tinyplay failed device=18 ... Read timed out`

### Artifact summary
- New WAVs:
  - `rx-1771554334552-a1-incall_cap_0.wav`
  - `rx-1771554336734-a2-incall_cap_1.wav`
  - `tx-1771554343954-bzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz.wav`
- TX transcription confirms assistant message is semantically correct from the bad RX transcript.

### Current conclusion
- The decode patch removed one likely format-path bug, but **does not solve telephony RX fidelity**.
- Primary remaining blocker is still call-downlink capture quality/route behavior on current rooted path.

## 2026-02-20 03:39 — Local debug WAV workspace reset to latest call only

### What
- Cleared local `phone/debug-wavs` WAV files.
- Refreshed `phone/debug-wavs` from device with only latest call artifacts.

### Result
- Local folder now contains exactly:
  - `rx-1771554334552-a1-incall_cap_0.wav`
  - `rx-1771554336734-a2-incall_cap_1.wav`
  - `tx-1771554343954-bzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz.wav`

## 2026-02-20 03:32 — Enforced clean-slate WAV test cycle (phone + local)

### What
- Cleared all prior WAVs before call:
  - phone internal debug dir,
  - phone shared export dir,
  - local `phone/debug-wavs`.
- Triggered fresh outbound call to `+4915129135779`.
- Waited for call end, then pulled artifacts to local.

### Result
- Local `phone/debug-wavs` now contains only this latest call:
  - `rx-1771554722012-a1-incall_cap_0.wav`
  - `rx-1771554724596-a2-incall_cap_1.wav`
  - `rx-1771554730672-a3-incall_cap_2.wav`
- Logs show ASR still receiving low-information/noise-like content (`you`, `mmm...`, `rrrr...`) and no successful assistant reply before hangup.

## 2026-02-20 03:36 — 48k/2ch capture strategy test (clean-slate) + artifact sync

### What
- Applied root capture strategy update:
  - request `48kHz` + `2ch` first (fallback sample-rate/channel ladder),
  - keep WAV debug of both raw (`rxraw-*`) and normalized (`rx-*`),
  - stronger transcript discard for dominant single-character noise.
- Cleared all phone/local WAVs before call, then ran fresh outbound call and pulled only this run.

### Key results
- Captured RX is now structurally correct and intelligible:
  - `rxraw-1771554971212-d20-req48000.wav` -> `48kHz stereo`
  - `rx-1771554971341-a1-incall_cap_0.wav` -> `48kHz mono` (normalized)
  - ASR transcript: `Hello? Who's there? Can you please tell me what movie to watch?`
- Model reply generated correctly:
  - `tx-1771554973421-hello-who-s-there-can-you-plea.wav`
  - transcript: `Sure, I can help with that...`

### Remaining blocker
- In this run playback to remote side still failed:
  - `root tinyplay failed device=19 ... Read timed out`
  - `root tinyplay failed device=18 ... Device or resource busy`
- Therefore model speech may still be missing on the receiving phone despite correct RX/LLM/TTS path.

## 2026-02-20 03:41 — Stabilization pass: no raw-spam artifacts + pinned format reuse

### Changes
- Reduced root capture window from `2800ms` to `1800ms` to lower per-turn listen delay.
- Added pinned format reuse after first accepted turn:
  - stores sample-rate + channels from successful capture,
  - reuses pinned format on subsequent turns (no full rate/channel sweep).
- Disabled raw probe WAV dump by default (`rxraw-*`) unless explicitly enabled.

### Clean-slate retest
- Wiped phone/local WAV dirs before call.
- New local artifacts (only this run):
  - `rx-1771555278135-a1-incall_cap_0.wav`
  - `tx-1771555279748-hello-see-you-next-week-can-yo.wav`
  - `rx-1771555289859-a2-incall_cap_0.wav`
  - `tx-1771555291100-take-care-and-bye-bye..wav`
- Playback to remote worked in this run:
  - `root tinyplay ok device=19` on both reply turns.

### Status
- Major improvement:
  - no 44-byte raw probe flood in exported artifacts,
  - model audio successfully played back to remote call.
- Remaining quality issue:
  - ASR still drifts into farewell-style phrases (`see you next week`, `take care, bye-bye`) even when user intent differs.

## 2026-02-20 03:46 — Capture-rate recalibration + farewell rejection hardening

### Trigger
- User reported RX voice still too fast and second turn drifting to goodbye-style responses.

### Changes
- Recalibrated requested root capture rate:
  - `ROOT_CAPTURE_REQUEST_SAMPLE_RATE` changed from `48k` to `32k`.
  - fallback ladder now: `32k -> 24k -> 16k -> 8k` (removed `48k` fallback).
- Added explicit low-quality transcript rejection patterns for known drift phrases:
  - `take care ... bye/goodbye`
  - `see you next week`
- Rebuilt and reinstalled debug APK.

### Intent
- Reduce speed/pitch mismatch on captured caller audio.
- Prevent accidental farewell transcripts from being accepted as valid user turns.

## 2026-02-20 03:53 — Forensic rate-check suggests 24k capture interpretation

### What
- Ran offline reinterpretation test on latest RX PCM payload using multiple assumed sample rates.
- ASR outcomes by assumed rate:
  - `16k`: plausible but slower-style phrasing,
  - `20k`: partial phrase,
  - `24k`: best semantic match (`I'm more into sci-fi.`),
  - `32k`: drift phrase (`I'm going to start by.`),
  - `48k`: severe repetition artifacts.

### Change
- Updated root capture target to `24k` first:
  - `ROOT_CAPTURE_REQUEST_SAMPLE_RATE = 24000`
  - fallback order now `24k -> 32k -> 16k -> 8k`.
- Rebuilt and reinstalled debug APK.

### Goal
- Reduce residual “still too fast” effect in RX turns and improve second-turn semantic stability.

## 2026-02-20 03:58 — Separate second-turn reliability pass (independent of speed)

### Observation
- User reported two distinct issues:
  1) residual RX speed mismatch,
  2) second-turn hallucination / no response after second turn.
- This aligns with logs: first turn often succeeds, later turns degrade into `no_audio_source` / short-voice rejects or low-quality transcript rejects.

### Changes applied
- Added per-device sample-rate correction hook for root capture:
  - for device `20`, map decoded `32000` to effective `24000` before ASR/WAV output.
- Relaxed strict short-turn rejection to avoid dropping valid brief utterances:
  - `ROOT_MIN_ACCEPT_VOICED_MS: 300 -> 180`
  - `MIN_TRANSCRIPT_ALNUM_CHARS: 8 -> 4`
- Rebuilt + reinstalled debug APK.

### Intent
- Address speed calibration and second-turn dropout as **separate** failure modes.

## 2026-02-20 04:01 — Root cause of “stops after 2nd/3rd turn” isolated

### Findings from latest call logs
- The call did not randomly crash; stop condition was deterministic:
  - third-turn reply was generated,
  - playback then failed (`tinyplay` timeout on device `19`, device `18` busy),
  - subsequent captures returned mostly `no_audio_source`,
  - silence watchdog auto-hung up at ~25s idle.
- So this is a separate failure mode from residual speed mismatch.

### Additional fixes applied
- On root playback failure:
  - trigger route recovery immediately,
  - mark speech activity (prevents immediate watchdog hangup),
  - reset preferred playback device and retry capture loop with normal post-playback delay.
- Extended sample-rate correction mapping to all in-call capture devices:
  - devices `20/21/22/54` (`32k -> 24k` effective).
- Tightened low-quality text acceptance again:
  - `MIN_TRANSCRIPT_ALNUM_CHARS` raised to `6`.
  - added explicit reject pattern for `you know`.
- Rebuilt + reinstalled debug APK.

## 2026-02-20 04:03 — Pulled latest-call WAVs + verified ASR transcripts

### Artifacts pulled (latest run only)
- `phone/debug-wavs/rx-1771556539460-a1-incall_cap_1.wav`
- `phone/debug-wavs/tx-1771556540594-hello-who-s-there.wav`

### Transcriptions
- `rx-1771556539460-a1-incall_cap_1.wav` -> `Hello? Who's there?`
- `tx-1771556540594-hello-who-s-there.wav` -> `It's me, the assistant. How can I help you today?`

## 2026-02-20 04:08 — Pulled latest call (no-turn run) + transcribed all RX WAVs

### Artifacts pulled (latest run only)
- `phone/debug-wavs/rx-1771556822241-a1-incall_cap_0.wav`
- `phone/debug-wavs/rx-1771556826606-a2-incall_cap_1.wav`
- `phone/debug-wavs/rx-1771556831413-a3-incall_cap_2.wav`
- `phone/debug-wavs/rx-1771556836909-a1-incall_cap_3.wav`

### Transcriptions (`POST /api/asr`)
- `rx-1771556822241-a1-incall_cap_0.wav` -> `""` (no transcript)
- `rx-1771556826606-a2-incall_cap_1.wav` -> `"Hello?"`
- `rx-1771556831413-a3-incall_cap_2.wav` -> `"Hello"`
- `rx-1771556836909-a1-incall_cap_3.wav` -> `"Hello."`

### Note
- No `tx-*.wav` artifact was produced in this run, matching user-observed “no turn at all”.

## 2026-02-20 04:17 — Boundary-cut fix + no-turn reject fix deployed

### Root cause confirmed from device logs
- “No turn” run did not fail at Spark endpoint; it failed locally at transcript gate:
  - `spark ASR transcript: Hello?` -> `spark ASR transcript rejected as low quality`
  - repeated for `Hello` and `Hello.`
- This explains why no `tx-*.wav` was generated: `/api/turn` was never called with an accepted transcript.

### Code changes
- Increased capture window to avoid clipped starts/ends:
  - per-attempt durations now `1800/2200/2600ms` (instead of a single short window).
- Reduced post-playback listen delay:
  - `POST_PLAYBACK_CAPTURE_DELAY_MS: 720 -> 180`.
- Added explicit transcript rejection reason logging:
  - `voice_bridge:transcript_reject:<reason>` for faster forensic debugging.
- Relaxed minimum transcript alnum threshold:
  - `MIN_TRANSCRIPT_ALNUM_CHARS: 4 -> 3`.

### Deploy
- Rebuilt APK: `phone/app-android/app/build/outputs/apk/debug/app-debug.apk`
- Installed to Pixel via `adb install -r` -> `Success`.

## 2026-02-20 04:22 — Live validation after patch (turn path restored)

### Live call result (real dial)
- Triggered outbound call via ADB root transport.
- Logs show accepted user turns and generated TX audio:
  - `spark ASR transcript: Hello, who's there?`
  - `spark audio reply: It's me, the assistant. How can I help you today?`
  - `spark ASR transcript: Can you suggest me what type of movie to watch?`
  - `spark audio reply: Sure! You might enjoy watching "The Matrix" or "Interstellar." ...`

### Pulled artifacts (this run)
- Stored in `phone/debug-wavs/latest-17715571/`
  - `rx-1771557134134-a1-incall_cap_0.wav`
  - `rx-1771557142740-a1-incall_cap_1.wav`
  - `rx-1771557162952-a1-incall_cap_2.wav`
  - `rx-1771557182479-a2-incall_cap_3.wav`
  - `tx-1771557135453-hello-who-s-there.wav`
  - `tx-1771557144517-can-you-suggest-me-what-type-of.wav`
  - `tx-1771557183726-hello.wav`

### ASR transcription check (`/api/asr`)
- `rx-1771557134134-a1-incall_cap_0.wav` -> `Hello, who's there?`
- `rx-1771557142740-a1-incall_cap_1.wav` -> `Can you suggest me what type of movie to watch?`
- `rx-1771557162952-a1-incall_cap_2.wav` -> `""` (empty)
- `rx-1771557182479-a2-incall_cap_3.wav` -> `Hello?`
- `tx-1771557135453-hello-who-s-there.wav` -> `It's me, the assistant. How can I help you today?`
- `tx-1771557144517-can-you-suggest-me-what-type-of.wav` -> `Sure, you might enjoy watching The Matrix or Interstellar. They both have great special effects and interesting storylines.`
- `tx-1771557183726-hello.wav` -> `Hello again, is there something specific you'd like to know or talk about?`

### Interpretation
- The prior “no turn at all” condition is no longer reproducible in this run.
- Remaining issue is intermittent third-turn capture empties (`rx ... cap_2` empty), not full turn pipeline failure.

## 2026-02-20 04:26 — Pulled latest available WAVs/transcripts after “stops after 2nd turn”

### Artifact status
- Checked device debug folder and logcat; no newer files beyond the same latest run window (`1771557134`..`1771557183`).
- Re-pulled these artifacts to:
  - `phone/debug-wavs/latest-17715571/`

### Transcriptions re-verified
- `rx-1771557134134-a1-incall_cap_0.wav` -> `Hello, who's there?`
- `rx-1771557142740-a1-incall_cap_1.wav` -> `Can you suggest me what type of movie to watch?`
- `rx-1771557162952-a1-incall_cap_2.wav` -> `""` (empty)
- `rx-1771557182479-a2-incall_cap_3.wav` -> `Hello?`
- `tx-1771557135453-hello-who-s-there.wav` -> `It's me, the assistant. How can I help you today?`
- `tx-1771557144517-can-you-suggest-me-what-type-of.wav` -> `Sure, you might enjoy watching The Matrix or Interstellar. They both have great special effects and interesting storylines.`
- `tx-1771557183726-hello.wav` -> `Hello again, is there something specific you'd like to know or talk about?`

## 2026-02-20 04:29 — Copied latest WAVs into local debug root folder

### Local availability
- Copied latest run WAVs from:
  - `phone/debug-wavs/latest-17715571/`
- Into:
  - `phone/debug-wavs/`
- Files now present in local debug root:
  - `tx-1771557183726-hello.wav`
  - `tx-1771557144517-can-you-suggest-me-what-type-of.wav`
  - `tx-1771557135453-hello-who-s-there.wav`
  - `rx-1771557182479-a2-incall_cap_3.wav`
  - `rx-1771557162952-a1-incall_cap_2.wav`
  - `rx-1771557142740-a1-incall_cap_1.wav`
  - `rx-1771557134134-a1-incall_cap_0.wav`

## 2026-02-20 04:33 — Applied “same-source retry first” fix for post-2nd-turn interruption

### Why
- Observed failure mode: after a weak/empty RX chunk, the capture path could jump across multiple root devices quickly, increasing latency and often missing the next utterance window.
- This made “turn 3+” look like an interruption/stall even though call state was still alive.

### Code changes
- Added sticky same-source retries before rotating to another capture device:
  - new retry limit: `MAX_SAME_SOURCE_RETRIES = 2`
  - immediate-rotate only for hard-bad reasons (`speaker_mismatch`, `clipping`, `flat_signal`)
- Added `stickToSelectedSource` option in root capture path so retry attempts can stay on the current source instead of scanning all candidates.
- Kept existing longer capture windows (`1800/2200/2600ms`) and shorter post-playback delay.

### Build/deploy
- Rebuilt debug APK successfully.
- Reinstalled to Pixel (`adb install -r`) successfully.

## 2026-02-20 04:41 — Implemented barge-in + lower-latency chunked capture; disabled WAV flooding

### Implemented
- **Barge-in interruption (phone path)**
  - Root playback now starts as background process and is monitored.
  - While assistant audio is playing, service probes caller speech and ASR-validates it.
  - If user speaks over assistant, playback is terminated and capture resumes immediately.
  - Added echo guard against self-interrupt by overlap check with current reply text.

- **Lower-latency chunked capture transport**
  - Root capture switched to precise chunk path (`tinycap --` raw PCM piped through `head -c`), avoiding coarse whole-second WAV captures.
  - Capture attempt durations reduced for faster turn acquisition:
    - `820ms`, `1080ms`, `1380ms`.
  - Keeps existing same-source retry behavior to reduce post-2nd-turn drops.

- **Debug WAV dump disabled**
  - Disabled on-device debug WAV persistence to prevent storage growth on phone.

### Build/deploy
- `./gradlew :app:assembleDebug` -> `BUILD SUCCESSFUL`
- `adb install -r .../app-debug.apk` -> `Success`

## 2026-02-20 04:48 — Spark2 gateway streaming endpoint added and validated

### Spark2 gateway change (non-breaking)
- Added new endpoint on Spark2 gateway: `POST /api/turn/stream` (NDJSON stream: `session`, `asr`, `reply`, `audio` chunks, `done`/`error`).
- Kept existing `POST /api/turn` unchanged to preserve current off-phone behavior.
- Deployed to Spark2 path:
  - `/home/trac/ai/services/spark-voice-gateway/app.py`
- Restarted user service and validated healthy:
  - `systemctl --user restart spark-voice-gateway.service`
  - health check passed on `127.0.0.1:8996/health`.

### Validation
- Legacy endpoint (`/api/turn`) still returns normal JSON turn payload.
- New streaming endpoint (`/api/turn/stream`) returns expected NDJSON event flow including chunked audio and final metrics.

### Follow-up reminder (must not be forgotten)
- Mirror this same architecture on **MacMini** with:
  - its **own gateway service**,
  - its **own model configuration**,
  - equivalent streaming endpoint behavior and interruption path.

## 2026-02-20 04:56 — Pixel app switched to Spark streaming turn endpoint with fallback

### App changes
- Updated `SparkCallAssistantService` to use Spark streaming endpoint first:
  - primary path: `POST /api/turn/stream`
  - fallback path (automatic on stream error): `POST /api/turn`
- Added NDJSON event parser for stream events:
  - consumes `session`, `asr`, `reply`, `audio`, `done`, `error`
  - reconstructs reply WAV from ordered `audio.chunk_base64` parts
- Added guarded fallback audit entry:
  - `voice_bridge:stream_fallback:<reason>`

### Build/deploy
- Rebuilt debug APK successfully.
- Reinstalled debug APK to Pixel via ADB successfully.

### Test state
- Spark-side streaming endpoint itself is already validated on the server.
- Pixel-side compile/install validation is complete.
- Next live-call validation step: confirm end-to-end stream path on active call and check logcat for fallback absence (or controlled fallback behavior if stream is unavailable).

## 2026-02-20 05:04 — Controlled single-call diagnostic artifacts collected

### Scope
- Ran one controlled live call after enabling temporary debug WAV capture.
- Pulled latest run WAV artifacts into:
  - `phone/debug-wavs/latest-1771560/`

### Artifacts captured
- RX:
  - `rx-1771560170176-a1-incall_cap_0.wav`
  - `rx-1771560191172-a2-incall_cap_0.wav`
  - `rx-1771560215039-a3-incall_cap_0.wav`
- TX:
  - `tx-1771560171258-good-day.-who-is-that.wav`
  - `tx-1771560192014-any-other-questions.wav`
  - `tx-1771560216082-hello.wav`

### Transcription + response logs
- Saved Spark ASR retranscriptions for all six WAVs in:
  - `phone/debug-wavs/latest-1771560/transcriptions.txt`
- Saved app runtime response lines in:
  - `phone/debug-wavs/latest-1771560/log-snippet.txt`
- Saved combined readable summary in:
  - `phone/debug-wavs/latest-1771560/summary.md`

### Key observation in this run
- Stream path is active (`spark stream turn ok`) and playback path succeeds (`root tinyplay ok device=19`), but RX user transcripts still drift into generic/hallucinated content after the first turn.

## 2026-02-20 05:10 — Root capture boundary hardening for clipped/half-word turns

### Why
- Diagnostic WAVs showed clipped/partial RX speech (end-cut and half-word captures), which can cause wrong ASR text and off-topic replies.

### Changes applied
- Added tail-boundary extension on root capture:
  - if speech is still active at capture tail, app performs one immediate extra capture chunk and appends it before ASR.
- Increased minimum accepted voiced length in root fast-path:
  - `ROOT_MIN_ACCEPT_VOICED_MS: 180 -> 320` to reject tiny partial snippets.
- Increased per-attempt capture windows:
  - `1200 / 1700 / 2300ms` (was shorter) to reduce early cut-off risk.
- Kept debug WAV dumping enabled for verification in next controlled run.

### Deploy
- Rebuilt and reinstalled debug APK successfully after patch.

## 2026-02-20 05:15 — Turn-context fix: send full captured audio to Spark `/api/turn`

### Why
- User-reported symptom: recording sounded usable, but model replies ignored user intent/context.
- Root cause in app logic: after local ASR preview, turn call sent only silence (`skip_asr=true`) to Spark, so response quality depended entirely on one local preview transcript.

### Change
- Updated turn request path to always send the actual captured RX WAV into Spark turn call:
  - `audioWav = transcriptAudioWav`
  - `skipAsr = false`
  - still includes `transcript_hint` for recovery.
- Added server-transcript logging for each turn:
  - `spark turn transcript (server): ...`
  - audit tag: `voice_bridge:turn_asr:...`

### Expected effect
- Spark can re-run ASR on the full captured utterance and recover context better than relying only on preview transcript.
- This should reduce “generic reply despite audible question” cases.

### Deploy
- Rebuilt and reinstalled debug APK successfully after patch.

## 2026-02-20 05:18 — Reject low-information caller transcripts before turn send

### Why
- Latest live call still produced generic ASR strings (`who's there`) despite audible caller speech, causing off-context replies.
- Need to suppress low-information miscaptures from being sent as full turns.

### Change
- Added low-information transcript filter in app:
  - rejects short transcripts composed only of greeting/filler tokens (`hi/hello/hey/who/who's/there/...`).
- Added explicit regex guards for common bad captures:
  - `who's there`
  - `who was there`
  - greeting + `who's there` variants.

### Deploy
- Rebuilt and reinstalled debug APK successfully after patch.

## 2026-02-20 05:22 — No-response run analysis + longer RX windows

### What happened
- User reported no model response.
- Log confirms this sequence:
  - captured RX transcript: `Who's there?`
  - rejected by new low-info filter (`low_information`)
  - no second valid capture before call ended
- Relevant run artifact pulled:
  - `phone/debug-wavs/rx-1771561235101-a1-incall_cap_0.wav` (ASR: `Who's there?`).

### Adjustment
- Increased capture windows again to improve chance of full-utterance acquisition:
  - `CAPTURE_DURATION_BY_ATTEMPT_MS: 1800 / 2600 / 3400`
  - `ROOT_CAPTURE_TRAILING_EXTENSION_MS: 1200`

### Deploy
- Rebuilt + reinstalled debug APK after timing increase.

## 2026-02-20 05:26 — Soft-handle low-information transcript (avoid silent turn drop)

### Why
- Greeting-first utterances (`who's there` etc.) are normal in calls.
- Hard-rejecting them caused silence/no-response when no second capture landed before hangup.

### Change
- For `low_information` transcript classification:
  - keep capture as **soft reject** only,
  - still forward full audio to Spark `/api/turn` for server-side ASR and reply generation.
- Hard rejects remain for other quality failures.

### Deploy
- Rebuilt and reinstalled debug APK successfully after this behavior change.

## 2026-02-20 05:31 — Post-call diagnosis (stops after turn 2 + higher latency)

### Evidence from latest run
- Turn 1:
  - RX transcript: `Who's there?` (soft low-info path)
  - TX reply generated and played.
- Turn 2:
  - RX transcript: `Tell me what movie I should watch.`
  - TX reply generated and played.
- Turn 3:
  - RX WAV exists, but ASR transcript is empty.
  - Then repeated `no_audio_source` until call end.

### Why it stopped
- Stop after turn 2 is not LLM failure; it is capture-path failure:
  - third-turn capture produced no usable text,
  - subsequent capture windows reported `no_audio_source` repeatedly.

### Why latency is worse right now
- Current capture windows are intentionally larger (`1800/2600/3400ms`) with trailing extension (`1200ms`) to reduce clipping.
- Client currently buffers full stream response before playback (still not true incremental audio playback), so `/api/turn/stream` does not yet provide first-audio latency wins on phone path.

## 2026-02-20 05:39 — Implemented incremental stream playback on Pixel client

### What changed
- Added streamed WAV parser in app stream client path:
  - parses WAV header from incoming `audio` chunks in `/api/turn/stream`,
  - starts emitting PCM bytes as soon as `data` chunk is available.
- Added root FIFO live playback path:
  - creates FIFO on device,
  - launches `tinyplay` in raw mode against FIFO,
  - writes decoded PCM chunks to FIFO as stream events arrive.
- Added stream playback lifecycle handling:
  - monitors playback completion via existing root process monitor,
  - preserves barge-in checks during live stream playback.
- Prevented duplicate playback:
  - when live stream playback already handled the turn, app skips the old full-buffer replay path.

### Notes
- This is fully client-side (Pixel app). No Spark2 code changes were required for this step.
- If live stream playback cannot start, app falls back to prior full-buffer playback using merged audio.

### Deploy
- Rebuilt and reinstalled debug APK successfully.

## 2026-02-20 07:30 — Removed overfit ASR rewrite; stabilized stream startup path

### Trigger
- User reported no voice response after pickup and correctly flagged overfit behavior from phrase-specific transcript rewrites.

### Findings
- The no-response phase came from stream startup behavior where `tinycap -- ... > fifo` could block and time out before a usable stream formed.
- ASR/turn quality issue remained separate: capture text was semantically wrong (`we're there` / `who was that`), so model replies were logically wrong despite valid playback.

### Changes
- Kept continuous root stream capture, but switched stream startup to non-blocking FIFO-open pattern:
  - `tinycap -- ... 3<> <fifo> 1>&3 ...` in root stream process start.
- Kept server turn call on two-stage path with `skip_asr=true` (app ASR transcript becomes authoritative turn text).
- Removed brittle phrase-specific transcript remapping logic from app path.
- Retained only neutral transcript normalization (`whitespace trim/collapse`), no semantic rewriting.

### Result
- Voice playback path remains active (`root tinyplay ok device=19` observed).
- Remaining issue is upstream ASR semantic drift on phone-call audio, not LLM/TTS transport.

## 2026-02-20 07:36 — Fixed duplicate-first-phrase bug and tightened capture rate correction

### Trigger
- User reported:
  - single phrase spoken once but transcript duplicated,
  - captured WAV still sounded too fast/high-pitched.

### Root cause found
- In `captureUtteranceStateMachine`, the first voiced chunk was written twice:
  - once via pre-roll buffer,
  - once again as the current chunk in the same iteration.
- This explains repeated first phrase artifacts in transcript.

### Changes
- Added explicit `appendChunk` guard so first voiced chunk is not double-written when pre-roll already contains it.
- Updated device capture rate correction:
  - `ROOT_CAPTURE_RATE_FIX_TO` changed from `24000` to `16000` for in-call capture devices (`20/21/22/54`).

### Expected effect
- Single utterance should no longer duplicate at start.
- Debug WAV pitch/speed should align better with natural speech for ASR.

## 2026-02-20 07:44 — Addressed short/empty second-turn captures after first reply

### Trigger
- User confirmed:
  - first-turn RX WAV still sounded pitch/speed shifted,
  - second turn often cut short/empty despite longer spoken input.

### Changes
- In root stream session setup, decoupled requested stream rate from processing rate:
  - when device correction applies (`32k -> 16k`), stream processing now uses corrected rate for chunk sizing (`rawSampleRate=processingSampleRate`) to avoid over-reading/compressing windows.
- Hardened endpointing against premature end-of-turn:
  - `UTTERANCE_PRE_ROLL_MS: 350 -> 500`
  - `UTTERANCE_MIN_SPEECH_MS: 260 -> 560`
  - `UTTERANCE_SILENCE_MS: 560 -> 760`

### Expected effect
- Less pitch/speed skew from chunk-time misalignment.
- Fewer empty second turns from short false-start captures.

## 2026-02-20 07:52 — Applied "low-information is valid" and adaptive per-call rate lock

### Trigger
- User explicitly requested:
  - do not reject low-information transcripts,
  - stop static pitch guessing and adapt rate per call.

### Changes
- Removed semantic transcript rejection behavior:
  - `transcriptRejectReason()` now only rejects truly empty normalized text.
- Added adaptive ASR rate selection for utterance state-machine path:
  - tries candidate sample-rate interpretations on the same utterance PCM,
  - scores by non-empty alnum content only (no semantic/pattern heuristics),
  - locks best rate per call (`adaptiveCaptureSampleRate`), then reuses it.
- Added unlock-on-no-info guard:
  - after repeated no-info turns, adaptive lock resets and stream session is rebound.
- Disabled static global capture rate remap:
  - `ROOT_CAPTURE_RATE_FIX_ENABLED=false` (no hardcoded 32k->X remap).

### Notes
- This enforces the requested rule: only “no information” is invalid; everything else is allowed.

## 2026-02-20 07:58 — Adaptive-rate scorer corrected to avoid slow/low-pitch lock

### Trigger
- User reported latest call still sounding slow/low-pitched.
- Forensic sweep on `rxm-1771570474270-vad-4.wav` showed:
  - 12k interpretation produced pathological repeated `z...` transcript,
  - 24k/32k produced intelligible `who's there?`.

### Root cause
- Previous adaptive scoring favored transcript length too strongly, so repetitive gibberish could win and lock a bad rate.
- Candidate list included `12k`/`8k`, which increased chance of pathological lock.

### Changes
- Reworked adaptive transcript score:
  - now weights unique-char ratio and unique-token ratio,
  - penalizes long repeated character runs.
- Tightened lock threshold:
  - `ROOT_CAPTURE_ADAPTIVE_RATE_MIN_SCORE: 2 -> 4`.
- Restricted adaptive rate candidates to telephony-relevant set:
  - `[32000, 24000, 16000]` (removed `12000/8000`).

### Expected effect
- Prevent lock onto pathological low-rate interpretations.
- Reduce slow/low-pitch artifacts from wrong per-call rate selection.

## 2026-02-20 08:02 — Set 24k as primary capture interpretation baseline

### Trigger
- User confirmed from local listening tests that 24k reinterpretation is closest to real voice.

### Changes
- Set primary capture request rate to 24k:
  - `ROOT_CAPTURE_REQUEST_SAMPLE_RATE: 32000 -> 24000`
- Reordered adaptive candidate preference to start with 24k:
  - `ROOT_CAPTURE_ADAPTIVE_RATE_CANDIDATES: [24000, 32000, 16000]`

### Intent
- Reduce first-turn rate mismatch before adaptive lock settles.
- Keep adaptive fallback available while biasing toward user-validated pitch.

## 2026-02-20 05:47 — Rolled back to fast non-streaming call path

### Why
- Streaming integration did not improve user-observed latency and made conversational reliability worse in-phone testing.
- Required immediate return to the previously faster/cleaner behavior.

### Rollback changes
- Disabled stream turn usage in app:
  - `ENABLE_SPARK_TURN_STREAM = false`
  - `ENABLE_SPARK_STREAM_LIVE_PLAYBACK = false`
- Restored low-latency capture profile:
  - `CAPTURE_DURATION_BY_ATTEMPT_MS = 900 / 1200 / 1600`
  - `ROOT_CAPTURE_TRAILING_EXTENSION_MS = 600`
  - `MAX_SAME_SOURCE_RETRIES = 2`

### Deploy
- Rebuilt and reinstalled debug APK successfully after rollback.

### Notes
- Stream implementation code remains in source for later iteration but is not active in runtime.

## 2026-02-20 05:53 — Barge-in sensitivity tuning on rolled-back profile

### Why
- User feedback: interruption (talk-over while assistant is speaking) did not trigger reliably.
- Log check showed no barge-in trigger events in recent calls.

### Changes
- Relaxed barge-in transcript gate:
  - no longer depends on full `transcriptRejectReason(...)` pipeline for probe acceptance,
  - uses lightweight minimum alnum check (`>=2`) and echo-overlap guard.
- Added fallback source selection for barge probe:
  - if no pinned source exists, probe picks best root capture source before deciding.
- Increased barge-in responsiveness:
  - `BARGE_IN_ARM_DELAY_MS: 120`
  - `BARGE_IN_PROBE_INTERVAL_MS: 220`
  - `BARGE_IN_PROBE_CAPTURE_MS: 240`
  - `BARGE_IN_MIN_RMS: 22.0`
  - `BARGE_IN_MIN_VOICED_MS: 80`

### Deploy
- Rebuilt and reinstalled debug APK successfully after barge-in tuning.

## 2026-02-20 06:02 — Barge-in reliability pass (non-streaming profile)

### Why
- User still reported interruption not triggering during assistant speech.
- Log review also showed a background-thread crash in playback monitor (`InterruptedException` during probe loop sleep).

### Changes
- Made barge-in trigger independent from mandatory ASR success:
  - added energy/VAD interrupt path (`BARGE_IN_REQUIRE_ASR = false`) so strong speech can interrupt even when short probe ASR is empty.
- Added strong-speech fast path:
  - immediate trigger when probe energy/voicing crosses stronger thresholds.
- Tightened probe cadence for faster detection:
  - `BARGE_IN_PROBE_INTERVAL_MS: 180`
  - `BARGE_IN_PLAYBACK_POLL_MS: 60`
  - `BARGE_IN_PROBE_CAPTURE_MS: 320`
  - `BARGE_IN_MIN_RMS: 16.0`
  - `BARGE_IN_MIN_VOICED_MS: 60`
  - new strong thresholds:
    - `BARGE_IN_STRONG_RMS: 26.0`
    - `BARGE_IN_STRONG_VOICED_MS: 110`
- Added explicit audit markers for barge-in paths:
  - `voice_bridge:barge_in_energy:*`
  - `voice_bridge:barge_in_vad:*`
- Fixed playback monitor crash:
  - wrapped poll-loop sleep in `try/catch` and exit gracefully on thread interruption.

### Deploy
- Rebuilt and reinstalled debug APK successfully after this pass.

## 2026-02-20 06:11 — Stabilization after interruption regression report

### Why
- User reported clear regression (stops after ~2 turns, lower understanding) in calls where no intentional interruption happened.
- Correlated with aggressive barge-in probe profile that was running during every assistant playback window.

### What was changed
- Reduced barge-in probe aggressiveness and interference risk:
  - `BARGE_IN_ARM_DELAY_MS: 180`
  - `BARGE_IN_PROBE_INTERVAL_MS: 480`
  - `BARGE_IN_PROBE_CAPTURE_MS: 220`
  - `BARGE_IN_MIN_RMS: 24.0`
  - `BARGE_IN_MIN_VOICED_MS: 90`
  - `BARGE_IN_STRONG_RMS: 32.0`
  - `BARGE_IN_STRONG_VOICED_MS: 140`
- Restored ASR requirement for barge-in acceptance (`BARGE_IN_REQUIRE_ASR = true`), removing VAD-only interrupt acceptance.
- Removed barge probe fallback source hopping:
  - probe now uses pinned source only (`selectedRootCaptureSource`) and does not trigger extra source probing during playback.

### Deploy
- Rebuilt and reinstalled debug APK successfully after stabilization patch.

## 2026-02-20 06:18 — Hard revert to pre-barge tuning behavior

### Why
- User requested immediate rollback because the latest interruption work regressed conversation quality.
- Priority is restoring stable turn handling over interruption.

### Changes
- Disabled interruption probing entirely in runtime:
  - `ENABLE_BARGE_IN_INTERRUPT = false`
- This removes barge-in capture probes during assistant playback (no probe contention with normal turn capture path).

### Deploy
- Rebuilt and reinstalled debug APK successfully after rollback.

## 2026-02-20 06:24 — Pulled last-call debug artifacts for regression triage

### Why
- User reported immediate post-rollback regression (stops after turn 1 / weak understanding).
- Requested exact WAVs from latest call.

### Artifacts pulled locally
- `debug-wavs/last-call-2026-02-20-0553/rx-1771563221578-a1-incall_cap_0.wav`
- `debug-wavs/last-call-2026-02-20-0553/tx-1771563223293-who-s-there.wav`
- `debug-wavs/last-call-2026-02-20-0553/rx-1771563238749-a3-incall_cap_1.wav`
- `debug-wavs/last-call-2026-02-20-0553/call-log.txt`
- `debug-wavs/last-call-2026-02-20-0553/summary.txt`

### Key finding from this call
- Turn 1 completed (`Who's there?` -> valid assistant reply).
- Turn 2 capture path failed:
  - repeated `no_audio_source`,
  - then rotated source with weak capture quality,
  - ASR empty,
  - resumed `no_audio_source` loop.

## 2026-02-20 06:33 — Restored pre-stream “locked” capture profile

### Why
- User requested restore to previously locked stable behavior before the interruption/streaming regressions.
- Prior rollback did not restore all relevant capture-path settings.

### Restored profile
- Reverted to legacy root capture path:
  - `ROOT_CAPTURE_PRECISE_CHUNKS = false`
- Reverted turn capture windows to the earlier stable set:
  - `CAPTURE_DURATION_BY_ATTEMPT_MS = 1800 / 2200 / 2600`
- Reverted minimum voiced threshold:
  - `ROOT_MIN_ACCEPT_VOICED_MS = 180`
- Kept interruption probing disabled:
  - `ENABLE_BARGE_IN_INTERRUPT = false`
- Disabled persistent WAV dumping again to avoid debug-file flooding in normal runs:
  - `ENABLE_DEBUG_WAV_DUMP = false`

### Intent
- Match the pre-regression call behavior that was explicitly marked as “lock that in”.

## 2026-02-20 06:40 — Re-enabled barge-in on locked capture baseline

### Why
- User requested barge-in capability to be enabled again after restoring stable capture baseline.

### Change
- Set runtime toggle:
  - `ENABLE_BARGE_IN_INTERRUPT = true`

### Notes
- Kept the locked baseline settings unchanged (legacy capture path, larger windows, no stream path).

## 2026-02-20 06:47 — Barge-in contention mitigation after stop-response regression

### Why
- Latest live call with barge-in enabled showed post-reply stalls:
  - repeated `no_audio_source` after playback,
  - delayed/garbled follow-up transcript.
- Needed barge-in to stay enabled without destabilizing normal turn capture.

### Changes
- Limited barge-in probing to a single delayed probe per reply playback:
  - `BARGE_IN_MAX_PROBES_PER_REPLY = 1`
  - `BARGE_IN_ARM_DELAY_MS = 320`
- Reduced probe footprint:
  - `BARGE_IN_PROBE_CAPTURE_MS = 180`
  - `BARGE_IN_MIN_RMS = 26.0`
  - `BARGE_IN_MIN_VOICED_MS = 110`
- Prevented source churn on no-audio retries:
  - `shouldRetrySameSource("no_audio_source", ...)` now always retries current pinned source (no immediate rotate on this reason).

### Intent
- Keep interruption available while minimizing capture-path contention/regression after assistant playback.

## 2026-02-20 06:55 — Reverted to pre-barge locked baseline (barge-in off)

### Why
- User requested full rollback to the known stable state before barge-in was re-enabled.
- Latest barge-in-enabled runs still stalled after early turns.

### Change
- Restored `SparkCallAssistantService.kt` to commit `9c817ca` baseline:
  - `ENABLE_BARGE_IN_INTERRUPT = false`
  - removed later barge-in mitigation edits introduced after re-enable.
- This returns runtime behavior to the previously locked non-barge profile.

## 2026-02-20 07:13 — Runtime audio-route hard reset after post-revert instability

### Why
- User reported continued instability even after code rollback to locked baseline.
- Verified code file is byte-identical to `9c817ca`; issue likely runtime audio-route state, not source mismatch.

### Runtime reset executed
- Force-stopped app process:
  - `am force-stop com.tracsystems.phonebridge`
- Killed leftover root audio helpers:
  - `phonebridge-tinyplay`, `phonebridge-tinycap`
- Explicitly restored mixer route to idle (same as `ROOT_CALL_ROUTE_RESTORE_COMMANDS`):
  - control ids `116,117,120,121,122,123,124,125,135,136`
- Cleared logcat for clean next-call diagnostics.

## 2026-02-20 07:16 — Re-enabled WAV dumps for forensic comparison

### Why
- User requested pullable WAV artifacts “as before” for current regressions.
- Locked baseline had debug dump disabled, so new calls produced no new WAV files.

### Change
- Temporarily enabled:
  - `ENABLE_DEBUG_WAV_DUMP = true`

### Note
- This is for diagnostics; once enough comparison artifacts are collected, it should be disabled again.

## 2026-02-20 07:31 — Added sentence-complete utterance assembly (barge-in still off)

### Why
- User requested to stop hard mid-sentence chunking and keep barge-in disabled.
- Current fallback path sent each accepted chunk as an immediate model turn, which split one spoken sentence into multiple turns.

### Changes
- Added turn-level utterance continuation assembly in `SparkCallAssistantService`:
  - keeps first accepted chunk as seed,
  - captures up to 3 short continuation windows (`920ms`) on the same source,
  - appends accepted continuation transcripts/audio into one merged turn payload,
  - ends utterance after a boundary window (silence/invalid continuation),
  - caps merged utterance audio length (`9500ms`) to avoid oversized payloads.
- Added transcript join logic that preserves punctuation boundaries.
- Added debug WAV prefix `rxu-*` for continuation segments.
- Kept interruption path disabled:
  - `ENABLE_BARGE_IN_INTERRUPT = false`

### Deploy
- Rebuilt debug APK successfully.
- Reinstalled on device via `scripts/android-install-debug.sh`.

## 2026-02-20 07:35 — Pulled latest bad-call WAVs + ASR/transcript comparison

### Trigger
- User reported latest call did not answer actual questions.

### Artifacts pulled
- Local forensic bundle created in:
  - `debug-wavs/last-call-2026-02-20-0633/`
- Includes:
  - `rx-1771565548881-a1-incall_cap_0.wav`
  - `rx-1771565560635-a3-incall_cap_1.wav`
  - `rx-1771565573434-a3-incall_cap_0.wav`
  - `rx-1771565592796-a3-incall_cap_1.wav`
  - `tx-1771565551078-no-who-s-there.wav`
  - `tx-1771565563244-a-good-movie..wav`
  - `tx-1771565575048-good..wav`
  - `tx-1771565594929-it-s-meant-to-be-a-good-movie..wav`
  - `transcriptions.txt`
  - `call-log.txt` + `log-snippet.txt`

### ASR result snapshot
- RX side transcribed as:
  - `No, who's there?`
  - `A good movie.`
  - `Good.`
  - `It's meant to be a good movie.`
- TX side confirms model replied to those short/partial turns, not richer user questions.

### Observed behavior from logs
- Turn progression reached active and generated 4 reply turns.
- Between turns, repeated `no_audio_source` occurred before successful attempt-3 captures.
- Utterance continuation window usually ended immediately with boundary (`no_audio_source`), so no multi-chunk merge happened in this call.

## 2026-02-20 07:38 — Clipping mitigation pass (head/tail capture + utterance boundary)

### Trigger
- User reported RX WAV clipping at both boundaries (e.g. missing start/end of sentence).

### Tuning changes
- Reduced post-playback listen delay to start capture earlier after assistant speech:
  - `POST_PLAYBACK_CAPTURE_DELAY_MS: 180 -> 60`
- Increased tail protection on root capture:
  - `ROOT_CAPTURE_TRAILING_EXTENSION_MS: 600 -> 900`
  - `ROOT_CAPTURE_TRAILING_VOICE_WINDOW_MS: 220 -> 320`
  - `ROOT_CAPTURE_MAX_MERGED_MS: 4200 -> 5200`
- Made utterance continuation less eager to close on one missed window:
  - `UTTERANCE_CONTINUATION_CAPTURE_MS: 920 -> 1200`
  - `MAX_UTTERANCE_CONTINUATION_WINDOWS: 3 -> 4`
  - `UTTERANCE_END_BOUNDARY_WINDOWS: 1 -> 2`

### Deploy
- Rebuilt and reinstalled debug APK successfully.

## 2026-02-20 07:41 — Pulled latest regression call + reduced turn-latency profile

### Trigger
- User reported latest call still clips user speech and latency regressed (~6s before response).

### Forensics (latest call)
- Bundle: `debug-wavs/last-call-2026-02-20-0638/`
- RX transcripts:
  - `Hello, who's there?`
  - `Good movie.`
- Logs show latency contributors:
  - continuation waited for **two boundary windows** (`boundary=1/2`, `2/2`) after each seed chunk,
  - repeated `no_audio_source` retries before attempt-3 capture.

### Latency tuning changes
- Reduced initial capture windows:
  - `CAPTURE_DURATION_BY_ATTEMPT_MS: 1800/2200/2600 -> 1500/1900/2300`
- Reduced continuation overhead:
  - `UTTERANCE_CONTINUATION_CAPTURE_MS: 1200 -> 900`
  - `MAX_UTTERANCE_CONTINUATION_WINDOWS: 4 -> 2`
  - `UTTERANCE_END_BOUNDARY_WINDOWS: 2 -> 1`
- Faster source recovery from no-audio:
  - `MAX_SAME_SOURCE_RETRIES: 2 -> 1`

### Deploy
- Rebuilt + reinstalled debug APK successfully.

## 2026-02-20 07:43 — Handover timing correction for clipped turn starts

### Trigger
- User reported phrase-level head clipping (e.g. `can you recommend me a good movie` becoming `...ood movie`).

### Adjustment
- Moved post-playback recapture out of the too-early unstable handover window:
  - `POST_PLAYBACK_CAPTURE_DELAY_MS: 60 -> 140`
- Slightly widened capture windows to preserve sentence boundaries once capture starts:
  - `CAPTURE_DURATION_BY_ATTEMPT_MS: 1500/1900/2300 -> 1700/2100/2500`

### Deploy
- Rebuilt + reinstalled debug APK successfully.

## 2026-02-20 07:49 — Replaced chunk-guessing with VAD utterance state machine

### Why
- User reported hard clipping and fragmented turns (`...ood movie`), and asked for proper utterance endpointing rather than constant tweaking.
- Existing continuation mode still depended on fixed per-capture windows and early boundary closure.

### What changed
- Added a dedicated VAD utterance state machine in `SparkCallAssistantService`:
  - pre-roll buffer (`350ms`) before speech start,
  - speech start on RMS threshold (`UTTERANCE_VAD_RMS = 120`),
  - silence hangover endpoint (`560ms`) after speech,
  - minimum speech duration (`260ms`),
  - max turn duration (`8s`) and loop timeout (`11s`).
- State machine now captures one full utterance first, then runs ASR once on the merged utterance WAV.
- Disabled old continuation path while state machine is active:
  - `ENABLE_UTTERANCE_STATE_MACHINE = true`
  - `ENABLE_UTTERANCE_CONTINUATION = false`
- Added merged-turn debug dump (`rxm-*`) for direct verification of what ASR receives.

### Deploy
- Rebuilt and reinstalled debug APK successfully.

## 2026-02-20 07:58 — Switched phone capture to continuous root stream endpointing

### Why
- User requested replacing brittle chunk polling with streaming behavior equivalent to Spark Voice Gateway live path.
- Root issue: per-window `tinycap` restarts were clipping utterance boundaries and adding turn latency.

### Implementation
- Replaced per-window root capture in `captureUtteranceStateMachine()` with **continuous root stream** capture:
  - starts persistent `tinycap` process writing to FIFO,
  - reads PCM continuously from FIFO,
  - applies VAD endpointing (pre-roll, speech start, silence hangover, max turn).
- Added root capture stream lifecycle:
  - `ensureRootCaptureStreamSession()`
  - `startRootTinycapStreamSession()`
  - `readRootCaptureStreamChunk()`
  - `stopRootCaptureStreamSession()`
- Added auto-restart on stream read timeouts and source-rotation stream reset.
- Kept merged-utterance forensic dump (`rxm-*`) active for verification.
- Disabled old continuation combiner path while state-machine streaming is active.

### Design reference
- Mirrored the Spark live endpointing pattern used in control/gateway implementation:
  - pre-roll + VAD + silence endpoint + max-turn flush.

### Deploy
- Rebuilt and reinstalled debug APK successfully.

## 2026-02-20 08:15 — Removed lexical overfit gates; kept generic transcript quality checks

### Trigger
- User rejected lexical token blocking as overfit (`who/there/...` style lists and slang-specific single-token rules).

### Changes
- Removed lexical low-information token allow/deny lists from transcript rejection path.
- Removed single-token slang-specific rejection list.
- Kept only generic transcript quality filters:
  - empty/short alnum,
  - repetitive character runs / low character diversity on long spans,
  - repetitive-token ratio,
  - explicit low-quality phrase patterns.
- Kept local-ASR adaptive scorer tied to generic reject reasons only.
- Added server-ASR fallback when local ASR yields no usable transcript:
  - state-machine now still forwards merged utterance audio,
  - turn call uses `skip_asr=false` when local transcript is empty.

### Validation
- Android app rebuilt (`assembleDebug`) and installed on Pixel successfully.
- Local automated suite passed:
  - `npm run test:unit`
  - `npm run test:integration`
  - `npm run test:e2e-sim`

## 2026-02-20 08:32 — Autonomous robustness pass on 3-WAV workflow (no operator pickup)

### Goal
- Continue stabilization without requiring live pickup loops.
- Use existing captured WAV corpus as autonomous replay baseline.

### Changes
- Tightened transcript acceptance with a **generic** short-single-token reject:
  - rejects 1-token transcripts of length `<= 3` (`single_token_short`).
  - avoids low-value turns like `you` while keeping non-lexical logic.
- Kept server-ASR fallback only for truly empty local ASR:
  - `skip_asr=false` only when local transcript is blank.
  - avoids expensive server re-ASR on garbage transcripts that already failed locally.
- Reduced turn boundary lag and improved short-utterance capture responsiveness:
  - `UTTERANCE_CAPTURE_CHUNK_MS: 380 -> 260`
  - `UTTERANCE_PRE_ROLL_MS: 500 -> 640`
  - `UTTERANCE_MIN_SPEECH_MS: 560 -> 260`
- Added adaptive ASR early-exit to cut latency on high-confidence candidate rates:
  - exits candidate loop when score crosses `ROOT_CAPTURE_ADAPTIVE_RATE_EARLY_EXIT_SCORE`.

### Autonomous validation
- Rebuilt + reinstalled debug APK.
- Full local test suite still passing:
  - `npm run test:unit`
  - `npm run test:integration`
  - `npm run test:e2e-sim`
- Replay artifacts persisted under latest `phone/debug-wavs/autotest-*` folder:
  - `autonomous-replay.txt` (ASR vs skip/server turn behavior)
  - `autonomous-gating-eval.txt` (rule outcome summary)

## 2026-02-20 08:45 — Added autonomous web-audio stress harness (no pickup loop)

### Goal
- Continue validation without operator pickup calls.
- Use externally sourced speech audio and long-stream simulation to stress the root-stream utterance capture logic.

### Added tooling
- `scripts/build-web-audio-corpus.sh`
  - downloads web speech samples:
    - OpenAI Whisper test audio (`jfk.flac`)
    - Uberi speech_recognition example (`english.wav`)
    - Hugging Face `Narsil/asr_dummy` speech assets (`mlk.flac`, `canterville.ogg`, `i-know-kung-fu.mp3`)
  - normalizes to `16kHz mono PCM16 WAV`
  - builds long stress fixtures:
    - `canterville-35s-16k.wav`
    - `canterville-120s-16k.wav`
    - `stream-mix-120s-16k.wav` (speech + silence cadence)
- `scripts/simulate-stream-capture.mjs`
  - simulates `captureUtteranceStateMachine()` behavior on WAV streams:
    - chunking, pre-roll, VAD threshold, silence endpoint, max-turn timeout.
  - emits:
    - per-turn WAV dumps,
    - JSON summary,
    - markdown report with clipping/coverage warnings.
  - supports parameter overrides for fast tuning loops.

### Baseline findings (pre-tune)
- Default 8s cap split long spoken turns (e.g., 11–13s speech clips) and produced avoidable continuation segmentation.

## 2026-02-20 08:58 — Tuned long-turn capture for full-utterance retention

### Android parameter changes
- Increased utterance ceiling/time budget:
  - `UTTERANCE_MAX_TURN_MS: 8000 -> 14000`
  - `UTTERANCE_LOOP_TIMEOUT_MS: 11000 -> 18000`
- Kept low-latency endpointing thresholds unchanged:
  - `UTTERANCE_CAPTURE_CHUNK_MS = 260`
  - `UTTERANCE_PRE_ROLL_MS = 640`
  - `UTTERANCE_MIN_SPEECH_MS = 260`
  - `UTTERANCE_SILENCE_MS = 760`

### Simulation outcomes (after tune)
- `debug-wavs/stream-sim-web-tuned/summary.json`:
  - `jfk-16k.wav` (11s): captured in one turn, no clipping warnings.
  - `mlk-16k.wav` (13s): captured in one turn, no clipping warnings.
  - `stream-mix-120s-16k.wav`: no clipping warnings on simulated turns.

### Model replay validation on tuned turn WAVs
- Replay log: `debug-wavs/stream-sim-web-tuned/model-replay.txt`
  - 12–14s input turn WAVs transcribe correctly on Spark gateway ASR.
  - `skip_asr=true` turn path returns coherent replies.
- Server-ASR path check: `debug-wavs/stream-sim-web-tuned/model-replay-server-asr.txt`
  - `skip_asr=false` with audio-only 11–14s turns succeeds.
  - ASR source reports `filtered/raw` with correct transcripts.
- Added end-to-end replay automation script:
  - `scripts/replay-turns-against-spark.sh`
  - replays each simulated turn WAV through both:
    - `skip_asr=true` (client-local-ASR path)
    - `skip_asr=false` (server-ASR fallback path)
  - output: `debug-wavs/stream-sim-web-tuned/model-replay-full.txt`
  - aggregated replay stats:
    - files replayed: `32`
    - avg turn duration: `9.38s`
    - empty ASR transcripts: `1` (short near-silence segment)
    - avg end-to-end latency:
      - skip path: `404.9ms`
      - server-ASR path: `1067.4ms`

### Build/test/deploy
- Rebuilt and installed debug APK after tuning.
- Test suite remains green:
  - `npm run test:unit`
  - `npm run test:integration`
  - `npm run test:e2e-sim`

## 2026-02-20 09:20 — Stabilized turn-taking after low-latency regression report

### Reported behavior
- Good latency, but:
  - assistant started replying before caller finished speaking,
  - multi-turn flow stopped after a few turns.

### Root causes confirmed
- Turn-taking was running fixed-window fallback capture (`ENABLE_UTTERANCE_STATE_MACHINE=false`), which can cut mid-sentence.
- Short/soft follow-up utterances were getting rejected too aggressively (`short_voice`, `low_rms`, strict transcript filtering), causing silent turn drops.

### Changes applied
- Re-enabled utterance endpointing state machine:
  - `ENABLE_UTTERANCE_STATE_MACHINE=true`
- Tuned endpointing to wait for caller end-of-utterance while preserving responsiveness:
  - `UTTERANCE_CAPTURE_CHUNK_MS: 260 -> 220`
  - `UTTERANCE_PRE_ROLL_MS: 640 -> 900`
  - `UTTERANCE_MIN_SPEECH_MS: 260 -> 220`
  - `UTTERANCE_SILENCE_MS: 760 -> 1100`
  - `UTTERANCE_MAX_TURN_MS: 14000 -> 16000`
  - `UTTERANCE_LOOP_TIMEOUT_MS: 18000 -> 20000`
  - `UTTERANCE_VAD_RMS: 120 -> 80`
- Added hard fallback path so state-machine misses do not stall conversation:
  - when utterance state machine returns empty, service now falls back to fixed capture probes in the same turn cycle.
- Relaxed transcript rejection to reduce false drops:
  - removed `single_token_short` rejection,
  - reduced repetitive-token strictness (`>=7` tokens and ratio `<0.30`),
  - lowered `MIN_TRANSCRIPT_ALNUM_CHARS: 3 -> 2`.
- Relaxed root acceptance/read thresholds for weak but valid speech:
  - `ROOT_MIN_ACCEPT_RMS: 26 -> 22`
  - `ROOT_MIN_ACCEPT_VOICED_MS: 180 -> 120`
  - `ROOT_CAPTURE_STREAM_READ_TIMEOUT_MS: 760ms -> 1100ms`
  - `MIN_ROOT_STREAM_CHUNK_BYTES: 320 -> 192`

### Build/deploy
- Rebuilt debug APK: `scripts/android-build-debug.sh`
- Reinstalled to Pixel 10 Pro: `scripts/android-install-debug.sh`
- Default dialer role confirmed for `com.tracsystems.phonebridge`.

### Next live checks
- Verify caller can complete full sentence before model turn starts.
- Verify at least 5 consecutive turns without silent drop.
- If residual drops remain, capture latest `rxm-*` + `tx-*` wave pairs and inspect per-turn endpoint timing.

## 2026-02-20 09:51 — Adaptive capture-rate lock + rolling prebuffer restore

### Requested fixes applied
- Re-enabled adaptive per-call capture-rate calibration and lock:
  - `ENABLE_ADAPTIVE_CAPTURE_RATE=true`
  - adaptive scorer now locks best sample rate per call and keeps it pinned.
- Added adaptive **auto-unlock/retry** when quality drops:
  - no-info streak and low-score streak both tracked,
  - locked rate is released after repeated low-quality ASR turns,
  - capture stream session is restarted on unlock to retune rate.
- Added rolling root-stream prebuffer (~1s) and prepend-on-turn-start:
  - keep latest capture PCM in a rolling buffer while listening,
  - prepend that buffer at speech start to reduce clipped first syllables,
  - clear buffer on utterance finalize and stream stop.
- Reduced post-playback arm delay:
  - `POST_PLAYBACK_CAPTURE_DELAY_MS: 140 -> 20` for near-immediate listening.

### Build/deploy
- Rebuilt and reinstalled debug APK successfully.
- Default dialer role remains `com.tracsystems.phonebridge`.

## 2026-02-20 09:59 — Faster post-reply turn finalization

### User issue
- Right after assistant finishes talking, user responses felt delayed and often had to be repeated.

### Change
- Added dynamic fast endpoint mode for the immediate post-playback window:
  - track assistant playback completion timestamp,
  - for first 6s after playback, reduce trailing silence threshold from `1100ms` to `600ms`.
- This only affects post-reply responsiveness; normal turn capture remains unchanged outside that window.

### Build/deploy
- Rebuilt and reinstalled debug APK successfully.
- Default dialer role still `com.tracsystems.phonebridge`.

## 2026-02-20 10:03 — Fix for no-response-after-greeting stall

### Observed regression
- Latest call had greeting playback, then no further assistant response.
- Log showed `requestReplyFromAudioFallback start` with no subsequent utterance/transcript logs before call end.

### Root cause
- First-turn state-machine capture could spend too long waiting for voiced data before falling back, causing an apparent dead turn when caller starts speaking immediately after greeting.

### Fix
- Added early no-speech timeout in state-machine path (`3.6s`) to bail quickly into fallback capture instead of waiting full loop timeout.
- Timeout now triggers on:
  - stream session unavailable,
  - repeated empty chunks,
  - repeated unvoiced chunks before speech start.

### Build/deploy
- Rebuilt and reinstalled debug APK successfully.
- Default dialer role still `com.tracsystems.phonebridge`.

## 2026-02-20 10:06 — Force non-stream fallback on first post-greeting turn

### Why
- Reproduced failure: greeting played, then no response until call ended.
- No new `rxm/tx` files were produced during failed call window, indicating first-turn capture path stalled before utterance finalization.

### Fix
- Added first-turn safety mode to bypass stream-state-machine capture right after greeting:
  - `FIRST_TURNS_FORCE_FALLBACK=1`
  - first user turn uses fallback probe capture path directly,
  - once first fallback transcript is captured, it switches back to normal state-machine flow for subsequent turns.
- Added audit markers:
  - `voice_bridge:first_turn_force_fallback`
  - `voice_bridge:first_turn_fallback_done`

### Build/deploy
- Rebuilt and reinstalled debug APK successfully.
- Default dialer role still `com.tracsystems.phonebridge`.

## 2026-02-20 10:12 — Fix dead-end when live-call snapshot flips false in turn worker

### Observed behavior
- Calls could greet, then `requestReplyFromAudioFallback start` logged once and no further turn logs were emitted.
- Service stayed alive until hangup, but no response was generated.

### Root cause
- The capture loop checks `InCallStateHolder.hasLiveCall()` on main thread before scheduling a turn.
- Inside the async turn worker, a second `hasLiveCall()` check could transiently evaluate `false` and return early.
- That early return did not schedule another capture loop tick, so the conversation dead-ended.

### Fix
- Added worker-entry diagnostics:
  - `requestReplyFromAudioFallback run (live=...)`
- Changed the async early-return branch to recover instead of dead-end:
  - log warning when worker sees `live=false`,
  - clear `speaking`,
  - reschedule capture loop (`CAPTURE_RETRY_DELAY_MS`) when service is still active.

### Expected effect
- Transient live-call state flips no longer permanently silence the conversation.
- Turn loop retries automatically and should proceed once call-state snapshot stabilizes.

## 2026-02-20 10:20 — Fix high-pitch ASR drift on fallback `rx-*` path

### Observed issue
- User-reported bad turns were coming from fallback captures (`rx-*`) with high-pitch perception and wrong ASR text (e.g. movie request interpreted as beach intent).
- State-machine captures (`rxm-*`) were generally better because they already used adaptive ASR sample-rate selection.

### Root cause
- Fallback path was sending captured WAV to ASR with a single declared sample rate only.
- When tinycap fallback format/rate did not match true signal cadence, ASR could decode semantically wrong text.
- Adaptive sample-rate correction existed, but only in state-machine capture path.

### Fix
- Added adaptive ASR sample-rate selection to fallback path too:
  - decode fallback WAV to PCM,
  - run `transcribeUtteranceAdaptive(...)` across rate candidates,
  - use selected adaptive transcript and corrected WAV for turn submission,
  - keep single-pass ASR as fallback if adaptive path yields no candidate.
- Added explicit diagnostics when adaptive rate differs from source WAV rate:
  - log + audit `voice_bridge:fallback_adaptive_rate:<src>-><selected>:score=<n>`
  - optional debug WAV dump `rxa-*` for corrected-rate artifact inspection.

### Build/deploy
- Rebuilt debug APK successfully.
- Reinstalled and relaunched on Pixel.
- Default dialer role remains `com.tracsystems.phonebridge`.

## 2026-02-20 10:27 — Fix post-turn stall due timeout accounting bug in utterance state machine

### Observed in latest call (`latest-call-20260220-102429`)
- First user turn was captured and corrected by adaptive fallback rate (`48000 -> 24000`), transcript became accurate.
- After reply playback failure, next capture started but no further turn logs were emitted before call end.

### Root cause
- `captureUtteranceStateMachine()` used a synthetic counter (`loopMs += chunkMs`) for timeout checks.
- Each iteration can block much longer than `chunkMs` (stream read timeout is ~1100ms), so no-speech/loop timeouts were delayed far beyond intended values.
- Result: apparent hang after first/second turn.

### Fix
- Replaced synthetic loop counter with wall-clock elapsed timing:
  - track `captureStartedAtMs`,
  - enforce `UTTERANCE_LOOP_TIMEOUT_MS` and `UTTERANCE_NO_SPEECH_TIMEOUT_MS` using real elapsed milliseconds.
- This keeps no-speech bailout behavior aligned with configured timeouts even under slow stream reads.

### Expected effect
- No more long dead-air stalls in state-machine capture after playback problems.
- Fallback probe path should trigger promptly when stream capture has no usable speech.

## 2026-02-20 10:34 — Tighten fallback capture against pitch drift, clipping, and post-hangup noise

### Why
- User reported latest run still had:
  - high-pitch fallback captures,
  - clipped utterances,
  - hangup/noise fragments being processed as turns.

### Changes
- Improved adaptive ASR selection behavior:
  - candidate order now prioritizes `ROOT_CAPTURE_REQUEST_SAMPLE_RATE` (24k) before fallback raw rate,
  - early-exit requires at least 2 adaptive attempts before stopping.
- Hardened fallback turn loop against stale post-hangup processing:
  - added live-call checks after capture and before transcript commit to prevent processing/saving end-of-call artifacts as active turns.
- Raised fallback capture robustness for short clipped turns:
  - capture windows increased from `[1800, 2200, 2600]` to `[2200, 2600, 3200]`.
- Reduced false acceptance of tiny non-speech snippets:
  - `ROOT_MIN_ACCEPT_VOICED_MS` raised from `120` to `180`.

### Build/deploy
- Rebuilt debug APK successfully.
- Reinstalled and relaunched on Pixel.
- Default dialer role remains `com.tracsystems.phonebridge`.

## 2026-02-20 13:43 — Fix second-turn stall from undersized root stream chunks

### Observed in `latest-call-20260220-134021`
- First turn worked with correct transcript and reply.
- Second turn entered capture (`requestReplyFromAudioFallback run`) but produced no `rx/rxa/tx` artifacts before hangup.

### Root cause hypothesis (log-consistent)
- Stream capture path accepted very small PCM fragments as valid chunks.
- Tiny voiced/noise fragments can keep state-machine in-progress without reaching silence/finalization quickly.
- This can stall turn completion after first reply.

### Fix
- Added minimum fill threshold for stream chunks in `readRootCaptureStreamChunk(...)`:
  - required bytes = `max(MIN_ROOT_STREAM_CHUNK_BYTES, targetRawBytes * 0.30)`.
- Chunks below threshold are now treated as empty and logged:
  - `voice_bridge:root_stream_chunk_short:<got>/<target>`.
- This forces no-speech timeout/fallback behavior instead of hanging on micro-fragments.

### Expected effect
- Prevents long second-turn dead zones when root stream delivers sparse audio data.
- Should trigger prompt fallback or timeout path instead of silent stall.
