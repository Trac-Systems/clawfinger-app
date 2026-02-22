---
name: clawfinger
description: Rooted Pixel telephony bridge for AI-assisted phone calls. Use this skill when working with the Clawfinger phone app, its sub-skills (root setup, voice bridge), or phone-side call handling. Runs on macOS and Linux.
---

# Clawfinger

> **Platform**: macOS and Linux. Not compatible with Windows.

> **Repo**: [Trac-Systems/clawfinger-app](https://github.com/Trac-Systems/clawfinger-app/)
> **Companion repo**: [Trac-Systems/clawfinger-gateway](https://github.com/Trac-Systems/clawfinger-gateway/) — the voice gateway that runs on the host machine. Set up the gateway AFTER completing the phone setup below.

Rooted Pixel telephony bridge for AI-assisted phone calls. The Android app captures/plays call audio via ALSA PCM on a rooted Pixel, connecting to an external voice gateway for ASR/LLM/TTS processing.

## 100% local — no internet, no cloud, no Google

The phone does **not** need internet, a Google account, Google Play Services, or any data connection for the AI assistant to work. The entire voice pipeline (speech recognition, language model, text-to-speech) runs on the host machine. The phone reaches the gateway over USB via ADB reverse port forwarding (`127.0.0.1:8996` on the phone maps to `127.0.0.1:8996` on the host). No data ever leaves the local machine.

The only thing that requires a cellular connection is the phone call itself (voice call over the carrier network). Everything else is localhost over USB.

This means:
- No SIM data plan needed (voice-only SIM is fine)
- No Wi-Fi needed on the phone
- No Google account or sign-in required
- Works in airplane mode with "calls only" enabled
- All ASR/LLM/TTS processing stays on the host machine — nothing is sent to any cloud

## Host setup (one-time)

Install Android Platform Tools on the machine that will be connected to the phone via USB:

**macOS:**
```bash
brew install --cask android-platform-tools
```

**Linux (Debian/Ubuntu):**
```bash
sudo apt install android-tools-adb android-tools-fastboot
```

**Linux (Arch):**
```bash
sudo pacman -S android-tools
```

Verify:
```bash
adb version
fastboot --version
```

That's it for the host. Everything else (APatch, factory images, profile pushing, gateway) works over ADB from these two tools.

### First-time phone connection

1. On the phone: Settings → About phone → tap "Build number" 7 times to enable Developer Options
2. Settings → Developer options → enable **USB debugging**
3. Plug the phone into the host via USB
4. Accept the "Allow USB debugging?" prompt on the phone (check "Always allow from this computer")
5. Verify: `adb devices` should show the device as `device` (not `unauthorized`)

## Device requirements

> **CRITICAL: No screen lock.** The phone's lock screen MUST be set to **None** or **Swipe**. Do NOT use PIN, pattern, password, fingerprint, or face unlock. Any secure lock screen will block the app from accessing call audio and root services when the screen turns off, and the entire system will stop working. This is not optional.

- **Root**: APatch (or equivalent) with working `su` binary
- **Battery mode**: Set the app to **Unrestricted** in battery settings
- **USB debugging**: Enabled, with the host machine authorized
- **User 0 unlocked**: Verify with `adb shell dumpsys user | grep RUNNING_UNLOCKED`

## Skills

### [Root — Pixel 7a](skills/root-pixel7a/SKILL.md)
APatch root setup, rootd daemon, su path bootstrap, recovery. Device-specific to Pixel 7a (`lynx`).

### [Root — Pixel 10 Pro](skills/root-pixel10pro/SKILL.md)
APatch root setup, rootd daemon, su path bootstrap, recovery. Device-specific to Pixel 10 Pro (`blazer`).

### [Voice Bridge](skills/voice-bridge/SKILL.md)
Phone-side runtime: profile structure, endpoint discovery/tuning, capture/playback training loops, incoming call handling.

## Setup order

1. **Host setup**: Install ADB/fastboot on the host (see "Host setup" above)
2. **Root the phone**: Follow the device-specific root skill:
   - Pixel 7a: `skills/root-pixel7a/SKILL.md`
   - Pixel 10 Pro: `skills/root-pixel10pro/SKILL.md`
   - Other Pixel: copy the closest root skill, adjust device codename and build ID
3. **Install the app**: Build and install the Clawfinger APK onto the rooted phone
4. **Set up the gateway**: Clone and configure [clawfinger-gateway](https://github.com/Trac-Systems/clawfinger-gateway/) on the host — follow its SKILL.md for installation, model setup, and startup
5. **Push a profile and connect**: Select or create a device profile, push it to the phone, set up ADB reverse port forwarding, and verify the gateway connection
6. **Tune endpoints**: Follow `skills/voice-bridge/SKILL.md` for capture/playback endpoint discovery and training. If your device is not a Pixel 10 Pro or Pixel 7a, create a new blank profile (copy an existing one, change `profile_id`, empty out `route_profile`, `playback`, and `capture`) and run the full training loop.
