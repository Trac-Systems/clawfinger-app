# Clawfinger

Clawfinger is an Android telephony voice-bridge app that can place/answer native calls and route call audio through an external gateway (ASR/LLM/TTS) using a profile-driven configuration.

## Scope

This repository is focused on the Android app stack and profiles:
- Android app: `app-android/`
- Device profile(s): `profiles/`
- Build/install/profile scripts: `scripts/`
- Operational runbooks: `VOICE-BRIDGE-SKILL.md`, `ROOT-PIXEL10PRO-SKILL.md`

## Prerequisites

- Android SDK / `adb`
- Java 17
- A rooted target device (current documented path: Pixel 10 Pro via APatch)
- App set as default dialer on device

## Build

```bash
cd phone
./scripts/android-build-debug.sh
```

## Install

```bash
cd phone
./scripts/android-install-debug.sh
```

## Push profile

```bash
cd phone
./scripts/android-push-profile.sh profiles/pixel10pro-blazer-profile-v1.json
```

## Notes

- Runtime behavior is profile-driven. Avoid hardcoding endpoint tuning in app code.
- `profiles/` may contain sensitive values (for example gateway bearer tokens). Treat profiles as secrets in operational environments.
- Root setup and recovery flow: `ROOT-PIXEL10PRO-SKILL.md`
- Voice bridge tuning flow and endpoint training loop: `VOICE-BRIDGE-SKILL.md`
