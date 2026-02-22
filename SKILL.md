# Clawfinger

Rooted Pixel telephony bridge for AI-assisted phone calls. The Android app captures/plays call audio via ALSA PCM on a rooted Pixel, connecting to an external voice gateway for ASR/LLM/TTS processing.

## Skills

### [Root — Pixel 10 Pro](skills/root-pixel10pro/SKILL.md)
APatch root setup, rootd daemon, su path bootstrap, recovery. Device-specific to Pixel 10 Pro (`blazer`).

### [Voice Bridge](skills/voice-bridge/SKILL.md)
Phone-side runtime: profile structure, endpoint discovery/tuning, capture/playback training loops, incoming call handling.

## Setup order

1. Root the phone: `skills/root-pixel10pro/SKILL.md`
2. Configure and tune endpoints: `skills/voice-bridge/SKILL.md`
