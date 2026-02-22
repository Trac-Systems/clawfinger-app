---
name: clawfinger
description: Rooted Pixel telephony bridge for AI-assisted phone calls. Use this skill when working with the Clawfinger phone app, its sub-skills (root setup, voice bridge), or phone-side call handling. macOS/Linux only.
---

# Clawfinger

> **Platform**: macOS and Linux only. Not compatible with Windows. Primarily tested on macOS (Apple Silicon).

Rooted Pixel telephony bridge for AI-assisted phone calls. The Android app captures/plays call audio via ALSA PCM on a rooted Pixel, connecting to an external voice gateway for ASR/LLM/TTS processing.

## 100% local — no internet, no cloud, no Google

The phone does **not** need internet, a Google account, Google Play Services, or any data connection for the AI assistant to work. The entire voice pipeline (speech recognition, language model, text-to-speech) runs on the Mac. The phone reaches the gateway over USB via ADB reverse port forwarding (`127.0.0.1:8996` on the phone maps to `127.0.0.1:8996` on the Mac). No data ever leaves the local machine.

The only thing that requires a cellular connection is the phone call itself (voice call over the carrier network). Everything else is localhost over USB.

This means:
- No SIM data plan needed (voice-only SIM is fine)
- No Wi-Fi needed on the phone
- No Google account or sign-in required
- Works in airplane mode with "calls only" enabled
- All ASR/LLM/TTS processing stays on the Mac — nothing is sent to any cloud

## Device requirements

> **CRITICAL: No screen lock.** The phone's lock screen MUST be set to **None** or **Swipe**. Do NOT use PIN, pattern, password, fingerprint, or face unlock. Any secure lock screen will block the app from accessing call audio and root services when the screen turns off, and the entire system will stop working. This is not optional.

- **Root**: APatch (or equivalent) with working `su` binary
- **Battery mode**: Set the app to **Unrestricted** in battery settings
- **USB debugging**: Enabled, with the Mac authorized
- **User 0 unlocked**: Verify with `adb shell dumpsys user | grep RUNNING_UNLOCKED`

## Skills

### [Root — Pixel 10 Pro](skills/root-pixel10pro/SKILL.md)
APatch root setup, rootd daemon, su path bootstrap, recovery. Device-specific to Pixel 10 Pro (`blazer`).

### [Voice Bridge](skills/voice-bridge/SKILL.md)
Phone-side runtime: profile structure, endpoint discovery/tuning, capture/playback training loops, incoming call handling.

## Setup order

1. Root the phone: `skills/root-pixel10pro/SKILL.md`
2. Configure and tune endpoints: `skills/voice-bridge/SKILL.md`
