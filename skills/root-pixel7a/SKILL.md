---
name: root-pixel7a
description: Magisk root setup and persistence for Pixel 7a (lynx). Use this skill when rooting the device, troubleshooting root/su access, managing rootd, or recovering from boot issues. Runs on macOS and Linux.
---

# ROOT-PIXEL7A-SKILL

## Purpose
Canonical runbook for Pixel 7a (`lynx`) root setup and persistence.

## Scope boundary
- This file is root-only.
- Voice/call bridge runtime is documented separately in `skills/voice-bridge/SKILL.md`.
- Pixel 10 Pro (`blazer`) is intentionally separate and remains APatch-first (`skills/root-pixel10pro/SKILL.md`).

## Current known-good state (2026-02-23)
- Root method: **Magisk** patch on `init_boot`.
- Native Magisk `su` path: `/debug_ramdisk/su`.
- Required compatibility entrypoint for this project: `/data/adb/ap/bin/su`.
- Runtime helper daemon for app integrations: **rootd** on `127.0.0.1:48733`.
- Validated build: `BP4A.260205.001` (Android 16).

## Device + build scope
- Device: Pixel 7a (`lynx`)
- Build family used in this project: Android 16 `BP4A.260205.001`
- OTA images: https://developers.google.com/android/ota#lynx
- Factory images: https://developers.google.com/android/images#lynx
- Local rooting workspace is temporary:
  - `root-work/` (temporary artifacts)
  - `rootd/` (temporary rootd scripts)
  - delete both folders after deployment/verification.

## Non-negotiable runtime requirements
- **No screen lock**: lock screen must be `None` or `Swipe`.
- **App permissions**: After installing the Clawfinger APK, the human must grant these permissions on the device (Settings → Apps → Clawfinger → Permissions):
  - **Microphone** (record audio)
  - **Phone** (manage phone calls)
  - **Notifications** (if prompted)
- **Default dialer**: Clawfinger must be default dialer.
  ```bash
  adb shell '/data/adb/ap/bin/su -c "telecom set-default-dialer com.tracsystems.phonebridge"'
  adb shell '/data/adb/ap/bin/su -c "telecom get-default-dialer"'
  # Must output: com.tracsystems.phonebridge
  ```
- Keep Clawfinger battery mode on **Unrestricted**.
- Keep compatibility `su` path file valid:
  - `/data/adb/ap/su_path` must contain `/data/adb/ap/bin/su`.

## Root provisioning (Magisk) deterministic runbook

### 0) Create temporary local workspace
```bash
mkdir -p root-work rootd
```

### 1) Preflight gate (must pass before flashing)
```bash
adb wait-for-device
adb devices
adb shell getprop ro.product.device
adb shell getprop ro.build.id
adb shell getprop ro.boot.slot_suffix
adb reboot bootloader
fastboot getvar unlocked 2>&1
fastboot getvar current-slot 2>&1
```
Pass criteria:
- `ro.product.device` is `lynx`
- build is expected (`BP4A.260205.001` for this runbook)
- `unlocked: yes`
- current slot is reported (`a` or `b`)

### 2) Prepare exact matching factory package
> **HUMAN ACTION REQUIRED**: Download matching factory zip from https://developers.google.com/android/images to `root-work/`.

```bash
cd root-work
unzip -o lynx-*.zip
unzip -o lynx-*/image-*.zip -d image
ls -l image/boot.img image/init_boot.img image/vbmeta.img image/vendor_boot.img image/vendor_kernel_boot.img
```
Pass criteria:
- all listed images exist in `root-work/image/`.

### 3) Patch stock `init_boot.img` with Magisk (scripted)
Primary path:
```bash
skills/root-pixel7a/bin/magisk_patch_init_boot.sh \
  --init-boot-img root-work/image/init_boot.img \
  --out-img root-work/patched-init-boot-magisk.img \
  --apk root-work/magisk/Magisk-v30.6.apk
```
Notes:
- If `--apk` is omitted, the script downloads latest release and prefers `Magisk-v*.apk` (not `app-debug.apk`).
- Output image is always `root-work/patched-init-boot-magisk.img`.

### 4) Flash patched `init_boot` with guard auto-recovery
```bash
skills/root-pixel7a/bin/magisk_flash_init_boot_guarded.sh \
  --patched-img root-work/patched-init-boot-magisk.img \
  --stock-init-boot root-work/image/init_boot.img
```
If instability is detected, the guard script restores stock `init_boot_a` and `init_boot_b` automatically.

### 5) Install full Magisk app (manager)
```bash
adb install -r root-work/magisk/Magisk-v30.6.apk
```
Validation:
```bash
adb shell 'dumpsys package com.topjohnwu.magisk | grep -E "versionName=|versionCode="'
# Expect versionCode around 30600 for v30.6; not versionCode=1 stub package
```

### 6) Create required `/data/adb/ap/bin/su` compatibility path (link + wrapper)
Project runtime expects `/data/adb/ap/bin/su`, while Magisk native path is `/debug_ramdisk/su`.

Apply compatibility chain:
```bash
# 1) Link `/data/local/tmp/su` -> `/debug_ramdisk/su`
adb shell 'ln -sfn /debug_ramdisk/su /data/local/tmp/su'

# 2) Wrapper at `/data/adb/ap/bin/su` to preserve CLI args (`-c`, etc.)
cat > root-work/su-wrapper.sh <<'SH'
#!/system/bin/sh
exec /data/local/tmp/su "$@"
SH
adb push root-work/su-wrapper.sh /data/local/tmp/phonebridge-su-wrapper.sh

# 3) Install wrapper and su_path using native Magisk su
adb shell '/debug_ramdisk/su -c "mkdir -p /data/adb/ap/bin /data/adb/ap"'
adb shell '/debug_ramdisk/su -c "cp /data/local/tmp/phonebridge-su-wrapper.sh /data/adb/ap/bin/su && chmod 755 /data/adb/ap/bin/su"'
adb shell '/debug_ramdisk/su -c "echo /data/adb/ap/bin/su > /data/adb/ap/su_path && chmod 644 /data/adb/ap/su_path"'
```

Validation:
```bash
adb shell '/data/adb/ap/bin/su -c id'
adb shell '/data/adb/ap/bin/su -v'
adb shell '/debug_ramdisk/su -c id'
```
Pass criteria:
- all three return success.

## Root runtime architecture (required)
### Why rootd is required
- App sandbox cannot directly run `su` in all contexts.
- `RootShellRuntime` must use rootd first, then fallback paths.

### rootd install locations
- `/data/adb/service.d/phonebridge-root-handler.sh`
- `/data/adb/service.d/phonebridge-rootd.sh`

### rootd local staging (temporary)
- Create temporary `rootd/` during setup.
- If scripts already exist on device, pull them first (source of truth).
- If scripts are missing, create bootstrap templates:

`rootd/phonebridge-root-handler.sh`
```sh
#!/system/bin/sh
line="$(cat)"
case "$line" in
  CMD_B64:*) cmd_b64="${line#CMD_B64:}" ;;
  *) echo "invalid_request"; echo "__PB_EXIT__:2"; exit 0 ;;
esac

cmd="$(echo "$cmd_b64" | base64 -d 2>/dev/null)"
if [ -z "$cmd" ]; then
  cmd="$(echo "$cmd_b64" | toybox base64 -d 2>/dev/null)"
fi
[ -z "$cmd" ] && { echo "decode_failed"; echo "__PB_EXIT__:3"; exit 0; }

case "$cmd" in
  id|echo*|pm\ grant*|appops\ set*|cmd\ appops*|settings*|getprop*|setprop*|dumpsys*|ls*|cat*|rm*|mount*|ps*|kill*|pkill*|chmod*|chown*|chcon*|cp*|mv*|mkdir*|touch*|/data/adb/service.d/phonebridge-tinycap*|/data/adb/service.d/phonebridge-tinyplay*|/data/adb/service.d/phonebridge-tinymix*|/data/adb/service.d/phonebridge-tinypcminfo*|am\ *)
    output="$(/system/bin/sh -c "$cmd" 2>&1)"; code=$? ;;
  *)
    output="blocked_command"; code=126 ;;
esac

printf '%s\n' "$output"
printf '__PB_EXIT__:%d\n' "$code"
```

`rootd/phonebridge-rootd.sh`
```sh
#!/system/bin/sh
PORT=48733
HANDLER=/data/adb/service.d/phonebridge-root-handler.sh
[ ! -x "$HANDLER" ] && exit 1
exec /system/bin/nc -L -s 127.0.0.1 -p "$PORT" /system/bin/sh "$HANDLER"
```

Push scripts:
```bash
adb push rootd/phonebridge-root-handler.sh /data/local/tmp/
adb push rootd/phonebridge-rootd.sh /data/local/tmp/
adb shell '/data/adb/ap/bin/su -c "cp /data/local/tmp/phonebridge-root-handler.sh /data/adb/service.d/ && cp /data/local/tmp/phonebridge-rootd.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/phonebridge-root-handler.sh /data/adb/service.d/phonebridge-rootd.sh"'
```

### rootd health checks
```bash
adb shell '/data/adb/ap/bin/su -c id'
adb shell 'cat /data/adb/ap/su_path'
adb shell '/data/adb/ap/bin/su -c "ps -A | grep -E \"phonebridge-rootd|[n]c -L -s 127.0.0.1 -p 48733\""'
echo "CMD_B64:$(echo -n id | base64)" | adb shell "nc 127.0.0.1 48733"
```
Pass criteria:
- root identity returned.
- rootd endpoint returns `__PB_EXIT__:0`.

### Tinytools installation (mandatory)
```bash
for f in tinymix tinycap tinyplay tinypcminfo; do
  adb push skills/root-pixel7a/bin/$f /data/local/tmp/phonebridge-$f
  adb shell "chmod +x /data/local/tmp/phonebridge-$f"
done
adb shell '/data/adb/ap/bin/su -c "for f in tinymix tinycap tinyplay tinypcminfo; do cp /data/local/tmp/phonebridge-\$f /data/adb/service.d/phonebridge-\$f && chmod 755 /data/adb/service.d/phonebridge-\$f; done"'
```

Verify:
```bash
adb shell '/data/adb/ap/bin/su -c "/data/adb/service.d/phonebridge-tinymix -D 0 controls"' | head -5
```

## Post-root deterministic verification gate
```bash
adb reboot
adb wait-for-device
adb shell '/data/adb/ap/bin/su -c id'
adb shell 'cat /data/adb/ap/su_path'
adb shell '/data/adb/ap/bin/su -c "test -x /data/adb/service.d/phonebridge-root-handler.sh && echo handler_ok || echo handler_missing"'
adb shell '/data/adb/ap/bin/su -c "test -x /data/adb/service.d/phonebridge-rootd.sh && echo rootd_ok || echo rootd_missing"'
adb shell '/data/adb/ap/bin/su -c "ps -A | grep -E \"phonebridge-rootd|[n]c -L -s 127.0.0.1 -p 48733\""'
adb shell '/data/adb/ap/bin/su -c "test -x /data/adb/service.d/phonebridge-tinymix && echo tinymix_ok || echo tinymix_missing"'
adb shell '/data/adb/ap/bin/su -c "/data/adb/service.d/phonebridge-tinymix -D 0 controls | head -1"'
adb shell '/data/adb/ap/bin/su -c "telecom get-default-dialer"'
# Must output: com.tracsystems.phonebridge
```

## Persistence requirements
- Magisk root must survive reboot.
- `/data/adb/ap/bin/su` compatibility wrapper must survive reboot.
- `/data/local/tmp/su -> /debug_ramdisk/su` link must be valid after reboot.
- rootd scripts in `/data/adb/service.d/` must survive reboot.
- Tinytools in `/data/adb/service.d/phonebridge-tiny*` must persist.
- Local temporary folders (`root-work/`, `rootd/`) must not be kept.

## Recovery path (if boot issues return)
Reflash matching stock partitions from same factory image:
- `boot_[ab]`, `init_boot_[ab]`, `vbmeta_[ab]`, `vendor_boot_[ab]`, `vendor_kernel_boot_[ab]`

Recovery commands:
```bash
cd root-work/image
fastboot flash boot_a boot.img
fastboot flash boot_b boot.img
fastboot flash init_boot_a init_boot.img
fastboot flash init_boot_b init_boot.img
fastboot flash vbmeta_a vbmeta.img
fastboot flash vbmeta_b vbmeta.img
fastboot flash vendor_boot_a vendor_boot.img
fastboot flash vendor_boot_b vendor_boot.img
fastboot flash vendor_kernel_boot_a vendor_kernel_boot.img
fastboot flash vendor_kernel_boot_b vendor_kernel_boot.img
fastboot reboot
adb wait-for-device
adb shell getprop sys.boot_completed
```

## Cleanup (mandatory)
After successful deployment and verification:
```bash
rm -rf root-work rootd
```
