---
name: root-pixel10pro
description: APatch root setup and persistence for Pixel 10 Pro (blazer). Use this skill when rooting the device, troubleshooting root/su access, managing rootd, or recovering from boot issues. Runs on macOS and Linux.
---

# ROOT-PIXEL10PRO-SKILL

## Purpose
Canonical runbook for Pixel 10 Pro (`blazer`) root setup and persistence.

## Scope boundary
- This file is root-only.
- Voice/call bridge runtime is documented separately in `skills/voice-bridge/SKILL.md`.

## Current known-good state (2026-02-19)
- Root method: **APatch** (Magisk path was not reliable on this device/build).
- Working `su` path: `/data/adb/ap/bin/su`
- Runtime helper daemon for app integrations: **rootd** on `127.0.0.1:48733`.

## Device + build scope
- Device: Pixel 10 Pro (`blazer`)
- Build family used in this project: Android 16 `BD3A.250721.001.E1`
- Local rooting workspace is temporary:
  - `root-work/` (temporary artifacts)
  - `rootd/` (temporary rootd scripts)
  - delete both folders after deployment/verification.

## Non-negotiable runtime requirements
- **No screen lock**: Lock screen MUST be `None` or `Swipe`. Never use PIN, pattern, password, fingerprint, or face unlock. Any secure lock will block root services and call audio access when the screen turns off, breaking the entire system.
- **App permissions**: After installing the Clawfinger APK, the human must grant these permissions on the device (Settings → Apps → Clawfinger → Permissions):
  - **Microphone** (record audio)
  - **Phone** (manage phone calls)
  - **Notifications** (if prompted)
- Keep `Clawfinger` battery mode on **Unrestricted**.
- Keep APatch `su` path file valid:
  - `/data/adb/ap/su_path` contains `/data/adb/ap/bin/su`.

## Root provisioning (APatch) deterministic runbook

### 0) Create temporary local workspace
```bash
mkdir -p root-work rootd
```

### 1) Preflight gate (must pass before any flashing)
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
- `ro.product.device` is `blazer`
- build is expected (`BD3A.250721.001.E1` for this runbook)
- `unlocked: yes`
- current slot is reported (`a` or `b`)

### 2) Prepare exact matching factory package (same build id)
```bash
cd root-work
# Place the exact matching factory zip in this folder first.
unzip -o blazer-*.zip
unzip -o blazer-*/image-*.zip -d image
ls -l image/boot.img image/init_boot.img image/vbmeta.img image/vendor_boot.img image/vendor_kernel_boot.img
```
Pass criteria:
- all listed images exist in `root-work/image/`.

### 3) Patch stock `boot.img` with APatch
Deterministic requirement:
- always patch `root-work/image/boot.img` from the exact matching build.
- resulting artifact must be saved as `root-work/patched-boot-apatch.img`.

Primary path (scripted, no manual APatch tapping):
```bash
# Store superkey in temporary file (do not commit)
printf '%s\n' 'YOUR_SUPERKEY' > root-work/APATCH_SUPERKEY.txt
chmod 600 root-work/APATCH_SUPERKEY.txt

# Generate APatch-patched boot image by script
skills/root-pixel10pro/bin/apatch_patch_boot.sh \
  --boot-img root-work/image/boot.img \
  --out-img root-work/patched-boot-apatch.img \
  --superkey-file root-work/APATCH_SUPERKEY.txt \
  --apk root-work/APatch.apk
```

Validation:
```bash
ls -l root-work/patched-boot-apatch.img
```

Manual fallback (only if scripted patch path is unavailable):
1. Open APatch.
2. On **Enter SuperKey**, tap the field and choose **Android**.
3. Pick `Downloads/boot.img`, enter superkey, tap **Start**.
4. Pull `apatch_patched_*.img` to `root-work/patched-boot-apatch.img`.

### 4) Flash patched boot with guard auto-recovery
```bash
skills/root-pixel10pro/bin/apatch_flash_guarded.sh \
  --patched-img root-work/patched-boot-apatch.img \
  --stock-boot root-work/image/boot.img
```
If the patched boot is unstable, the script automatically restores stock `boot_a` and `boot_b`.

### 5) Bootstrap APatch `su` path
```bash
adb shell '/data/adb/ap/bin/su -c id'
adb shell '/data/adb/ap/bin/su -c "mkdir -p /data/adb/ap; echo /data/adb/ap/bin/su > /data/adb/ap/su_path; chmod 644 /data/adb/ap/su_path"'
adb shell 'cat /data/adb/ap/su_path'
```
Pass criteria:
- `uid=0(root)` is returned.
- `/data/adb/ap/su_path` equals `/data/adb/ap/bin/su`.

### Superkey
- Store only in temporary `root-work/APATCH_SUPERKEY.txt` during rooting.
- Required for APatch privileged operations.

## Root runtime architecture (required)
### Why rootd is required
- On this device, app sandbox cannot directly run `/data/adb/ap/bin/su` (`Permission denied`).
- `RootShellRuntime` must use rootd first, then fallback paths.

### rootd install locations
- `/data/adb/service.d/phonebridge-root-handler.sh`
- `/data/adb/service.d/phonebridge-rootd.sh`

### rootd local staging (temporary)
- Create temporary `rootd/` during setup.
- If scripts already exist on device, pull them first (source of truth):
  - `adb shell '/data/adb/ap/bin/su -c test -f /data/adb/service.d/phonebridge-root-handler.sh && echo yes || echo no'`
  - `adb shell '/data/adb/ap/bin/su -c test -f /data/adb/service.d/phonebridge-rootd.sh && echo yes || echo no'`
  - `adb shell '/data/adb/ap/bin/su -c cat /data/adb/service.d/phonebridge-root-handler.sh' > rootd/phonebridge-root-handler.sh`
  - `adb shell '/data/adb/ap/bin/su -c cat /data/adb/service.d/phonebridge-rootd.sh' > rootd/phonebridge-rootd.sh`
- If scripts are missing (fresh root), create local bootstrap templates:
  - `rootd/phonebridge-root-handler.sh`
    ```sh
    #!/system/bin/sh
    line="$(cat)"
    case "$line" in CMD_B64:*) cmd_b64="${line#CMD_B64:}" ;; *) echo "invalid_request"; echo "__PB_EXIT__:2"; exit 0 ;; esac
    cmd="$(echo "$cmd_b64" | /data/adb/ap/bin/busybox base64 -d 2>/dev/null)"
    [ -z "$cmd" ] && { echo "decode_failed"; echo "__PB_EXIT__:3"; exit 0; }
    case "$cmd" in
      id|echo*|pm\ grant*|appops\ set*|cmd\ appops*|settings*|getprop*|setprop*|dumpsys*|ls*|cat*|rm*|mount*|ps*|kill*|pkill*|chmod*|chown*|chcon*|cp*|mv*|mkdir*|touch*|/data/adb/service.d/phonebridge-tinycap*|/data/adb/service.d/phonebridge-tinyplay*|/data/adb/service.d/phonebridge-tinymix*|/data/adb/service.d/phonebridge-tinypcminfo*|am\ *)
        output="$(/system/bin/sh -c "$cmd" 2>&1)"; code=$? ;;
      *) output="blocked_command"; code=126 ;;
    esac
    printf '%s\n' "$output"
    printf '__PB_EXIT__:%d\n' "$code"
    ```
  - `rootd/phonebridge-rootd.sh`
    ```sh
    #!/system/bin/sh
    PORT=48733
    HANDLER=/data/adb/service.d/phonebridge-root-handler.sh
    [ ! -x "$HANDLER" ] && exit 1
    exec /system/bin/nc -L -s 127.0.0.1 -p "$PORT" /system/bin/sh "$HANDLER"
    ```
- Edit/replace only if needed, then push back:
  - `adb shell '/data/adb/ap/bin/su -c \"cat > /data/adb/service.d/phonebridge-root-handler.sh\"' < rootd/phonebridge-root-handler.sh`
  - `adb shell '/data/adb/ap/bin/su -c \"cat > /data/adb/service.d/phonebridge-rootd.sh\"' < rootd/phonebridge-rootd.sh`
  - `adb shell '/data/adb/ap/bin/su -c \"chmod 755 /data/adb/service.d/phonebridge-root-handler.sh /data/adb/service.d/phonebridge-rootd.sh\"'`
- Push both scripts to `/data/adb/service.d/`.
- Delete local `rootd/` after successful deployment.

### rootd health checks
- Endpoint reachable: `127.0.0.1:48733`
- From host:
  - `adb shell '/data/adb/ap/bin/su -c id'`
  - `adb shell 'cat /data/adb/ap/su_path'`
  - `adb shell '/data/adb/ap/bin/su -c "ps -A | grep -E \"phonebridge-rootd|[n]c -L -s 127.0.0.1 -p 48733\""'`

### Tinytools installation (mandatory)

The Clawfinger app requires `tinymix`, `tinycap`, `tinyplay`, and `tinypcminfo` for ALSA audio routing during calls. Pre-built ARM64 binaries are shipped in this skill's `bin/` folder.

Push from the skill's `bin/` directory:
```bash
for f in tinymix tinycap tinyplay tinypcminfo; do
  adb push skills/root-pixel10pro/bin/$f /data/local/tmp/phonebridge-$f
  adb shell "chmod +x /data/local/tmp/phonebridge-$f"
done
adb shell '/data/adb/ap/bin/su -c "for f in tinymix tinycap tinyplay tinypcminfo; do cp /data/local/tmp/phonebridge-\$f /data/adb/service.d/phonebridge-\$f && chmod 755 /data/adb/service.d/phonebridge-\$f; done"'
```

Verify:
```bash
adb shell '/data/adb/ap/bin/su -c "/data/adb/service.d/phonebridge-tinymix -D 0 controls"' | head -5
# Must list mixer controls
```

**Default dialer** must also be set:
```bash
adb shell '/data/adb/ap/bin/su -c "telecom set-default-dialer com.tracsystems.phonebridge"'
# Verify:
adb shell '/data/adb/ap/bin/su -c "telecom get-default-dialer"'
# Must output: com.tracsystems.phonebridge
```

### Rebuilding tinytools from source

Requires Android NDK r27b+ on the host. Clone [tinyalsa](https://github.com/tinyalsa/tinyalsa) and cross-compile with `aarch64-linux-android34-clang`.

**Build commands (from the tinyalsa checkout directory):**

```bash
NDK="$ANDROID_HOME/ndk/<VERSION>"
CC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android34-clang"

# --- Compile object files ---

# PCM objects (with plugin support — required for AoC sound card)
$CC -c -O2 -Iinclude src/pcm.c src/pcm_hw.c src/snd_card_plugin.c

# Mixer objects (no plugin needed — mixer_hw talks directly to /dev/snd/controlC*)
$CC -c -O2 -Iinclude src/mixer.c src/mixer_hw.c

# Shared object
$CC -c -O2 -Iinclude src/limits.c

# --- Link binaries ---

# tinyplay — uses pcm_writei via snd_card_plugin for AoC PCM device routing
$CC -O2 -Iinclude -o tinyplay utils/tinyplay.c pcm.o pcm_hw.o snd_card_plugin.o -ldl

# tinycap — uses pcm_readi via snd_card_plugin
$CC -O2 -Iinclude -o tinycap utils/tinycap.c pcm.o pcm_hw.o snd_card_plugin.o limits.o -ldl

# tinypcminfo — queries PCM device capabilities
$CC -O2 -Iinclude -o tinypcminfo utils/tinypcminfo.c pcm.o pcm_hw.o snd_card_plugin.o limits.o -ldl

# tinymix — mixer only, no PCM plugin needed
$CC -O2 -Iinclude -o tinymix utils/tinymix.c mixer.o mixer_hw.o -ldl
```

**Key notes:**
- Target API is `android34` (matches the `aarch64-linux-android34-clang` toolchain).
- `snd_card_plugin.c` is required for `tinyplay`, `tinycap`, and `tinypcminfo` because the Google AoC sound card uses the tinyalsa plugin system for PCM device discovery. Without it, `tinyplay` will fail with "no device (hw/plugin)" or "Bad address" on write.
- `tinymix` does NOT need the plugin — it uses `mixer_hw.c` which opens `/dev/snd/controlC*` directly.
- Do NOT add `-DTINYALSA_USES_PLUGINS=0`. The default (plugins enabled) is correct.
- All binaries link `-ldl` for `dlopen`/`dlsym` (used by the plugin system to load `libsndcardparser.so` at runtime if available; works fine without it).

**Known-good binary checksums (NDK r27b, android34):**
| Binary | Size | MD5 |
|--------|------|-----|
| tinymix | 26640 | f95881bfb3f729ee90c2fa79e708f107 |
| tinycap | 33456 | e75ad9cbade8edc8859291efa61358d0 |
| tinyplay | 38600 | 68ce2d7dbadefbca3286131403307a51 |
| tinypcminfo | 33208 | 54010444bfcf819fdd1fe41ead098869 |

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
All checks must pass before proceeding to call/voice testing.

## Persistence requirements
- APatch root must survive reboot.
- rootd scripts in `/data/adb/service.d/` must survive reboot.
- Tinytools in `/data/adb/service.d/phonebridge-tiny*` must persist.
- Local temporary folders (`root-work/`, `rootd/`) must not be kept.

## Recovery path (if boot issues return)
- Reflash matching stock partitions from same factory image:
  - `boot_[ab]`, `init_boot_[ab]`, `vbmeta_[ab]`, `vendor_boot_[ab]`, `vendor_kernel_boot_[ab]`
- Boot system fully, verify `sys.boot_completed=1`, then redo APatch flow.

### Recovery commands (host)
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
