# ROOT-SKILL-PIXEL10PRO

## Purpose
Canonical runbook for Pixel 10 Pro (`blazer`) root setup and persistence.

## Scope boundary
- This file is root-only.
- Voice/call bridge runtime is documented separately in `phone/VOICE-BRIDGE-SKILL.md`.

## Current known-good state (2026-02-19)
- Root method: **APatch** (Magisk path was not reliable on this device/build).
- Working `su` path: `/data/adb/ap/bin/su`
- Runtime helper daemon for app integrations: **rootd** on `127.0.0.1:48733`.

## Device + build scope
- Device: Pixel 10 Pro (`blazer`)
- Build family used in this project: Android 16 `BD3A.250721.001.E1`
- Local rooting workspace is temporary:
  - `phone/root-work/` (temporary artifacts)
  - `phone/rootd/` (temporary rootd scripts)
  - delete both folders after deployment/verification.

## Non-negotiable runtime requirements
- Do not PIN/Password the device.
  - Prefer lock screen set to `None` or `Swipe`.
- Keep `PhoneBridge` battery mode on **Unrestricted**.
- Keep APatch `su` path file valid:
  - `/data/adb/ap/su_path` contains `/data/adb/ap/bin/su`.

## Root provisioning (APatch) summary
1. Unlock bootloader.
2. Use matching factory image for current build.
3. Patch stock `boot.img` with APatch tooling.
4. Flash patched boot for active slot.
5. Bootstrap APatch runtime binaries and symlinks.
6. Verify:
   - `/data/adb/ap/bin/su -c id` returns `uid=0(root)`.
   - Reboot and verify again.

### Superkey
- Store only in temporary `phone/root-work/APATCH_SUPERKEY.txt` during rooting.
- Required for APatch privileged operations.

## Root runtime architecture (required)
### Why rootd is required
- On this device, app sandbox cannot directly run `/data/adb/ap/bin/su` (`Permission denied`).
- `RootShellRuntime` must use rootd first, then fallback paths.

### rootd install locations
- `/data/adb/service.d/phonebridge-root-handler.sh`
- `/data/adb/service.d/phonebridge-rootd.sh`

### rootd local staging (temporary)
- Create temporary `phone/rootd/` during setup.
- Stage:
  - `phone/rootd/phonebridge-root-handler.sh`
  - `phone/rootd/phonebridge-rootd.sh`
- Push both scripts to `/data/adb/service.d/`.
- Delete local `phone/rootd/` after successful deployment.

### rootd health checks
- Endpoint reachable: `127.0.0.1:48733`
- From host:
  - `adb shell '/data/adb/ap/bin/su -c id'`
  - `adb shell 'cat /data/adb/ap/su_path'`

## Persistence requirements
- APatch root must survive reboot.
- rootd scripts in `/data/adb/service.d/` must survive reboot.
- Local temporary folders (`phone/root-work/`, `phone/rootd/`) must not be kept.

## Recovery path (if boot issues return)
- Reflash matching stock partitions from same factory image:
  - `boot_[ab]`, `init_boot_[ab]`, `vbmeta_[ab]`, `vendor_boot_[ab]`, `vendor_kernel_boot_[ab]`
- Boot system fully, verify `sys.boot_completed=1`, then redo APatch flow.
