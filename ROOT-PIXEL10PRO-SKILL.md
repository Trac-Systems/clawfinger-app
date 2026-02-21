# ROOT-PIXEL10PRO-SKILL

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
- Keep `Clawfinger` battery mode on **Unrestricted**.
- Keep APatch `su` path file valid:
  - `/data/adb/ap/su_path` contains `/data/adb/ap/bin/su`.

## Root provisioning (APatch) deterministic runbook

### 0) Create temporary local workspace
```bash
cd phone
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
cd phone/root-work
# Place the exact matching factory zip in this folder first.
unzip -o blazer-*.zip
unzip -o blazer-*/image-*.zip -d image
ls -l image/boot.img image/init_boot.img image/vbmeta.img image/vendor_boot.img image/vendor_kernel_boot.img
```
Pass criteria:
- all listed images exist in `phone/root-work/image/`.

### 3) Patch stock `boot.img` with APatch
Deterministic requirement:
- always patch `phone/root-work/image/boot.img` from the exact matching build.
- resulting artifact must be saved as `phone/root-work/patched-boot-apatch.img`.

Operational path:
1. Install/open APatch on device.
2. Patch `boot.img`.
3. Pull patched result to host and rename to:
   - `phone/root-work/patched-boot-apatch.img`

Validation:
```bash
ls -l phone/root-work/patched-boot-apatch.img
```

### 4) Flash patched boot to active slot only
```bash
cd phone/root-work
SLOT="$(fastboot getvar current-slot 2>&1 | sed -n 's/.*current-slot: //p' | tr -d '\r')"
echo "active slot=${SLOT}"
fastboot flash "boot_${SLOT}" patched-boot-apatch.img
fastboot reboot
adb wait-for-device
```

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
- If scripts already exist on device, pull them first (source of truth):
  - `adb shell '/data/adb/ap/bin/su -c test -f /data/adb/service.d/phonebridge-root-handler.sh && echo yes || echo no'`
  - `adb shell '/data/adb/ap/bin/su -c test -f /data/adb/service.d/phonebridge-rootd.sh && echo yes || echo no'`
  - `adb shell '/data/adb/ap/bin/su -c cat /data/adb/service.d/phonebridge-root-handler.sh' > phone/rootd/phonebridge-root-handler.sh`
  - `adb shell '/data/adb/ap/bin/su -c cat /data/adb/service.d/phonebridge-rootd.sh' > phone/rootd/phonebridge-rootd.sh`
- If scripts are missing (fresh root), create local bootstrap templates:
  - `phone/rootd/phonebridge-root-handler.sh`
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
  - `phone/rootd/phonebridge-rootd.sh`
    ```sh
    #!/system/bin/sh
    PORT=48733
    HANDLER=/data/adb/service.d/phonebridge-root-handler.sh
    [ ! -x "$HANDLER" ] && exit 1
    exec /system/bin/nc -L -s 127.0.0.1 -p "$PORT" /system/bin/sh "$HANDLER"
    ```
- Edit/replace only if needed, then push back:
  - `adb shell '/data/adb/ap/bin/su -c \"cat > /data/adb/service.d/phonebridge-root-handler.sh\"' < phone/rootd/phonebridge-root-handler.sh`
  - `adb shell '/data/adb/ap/bin/su -c \"cat > /data/adb/service.d/phonebridge-rootd.sh\"' < phone/rootd/phonebridge-rootd.sh`
  - `adb shell '/data/adb/ap/bin/su -c \"chmod 755 /data/adb/service.d/phonebridge-root-handler.sh /data/adb/service.d/phonebridge-rootd.sh\"'`
- Push both scripts to `/data/adb/service.d/`.
- Delete local `phone/rootd/` after successful deployment.

### rootd health checks
- Endpoint reachable: `127.0.0.1:48733`
- From host:
  - `adb shell '/data/adb/ap/bin/su -c id'`
  - `adb shell 'cat /data/adb/ap/su_path'`
  - `adb shell '/data/adb/ap/bin/su -c "ps -A | grep -E \"phonebridge-rootd|[n]c -L -s 127.0.0.1 -p 48733\""'`

## Post-root deterministic verification gate
```bash
adb reboot
adb wait-for-device
adb shell '/data/adb/ap/bin/su -c id'
adb shell 'cat /data/adb/ap/su_path'
adb shell '/data/adb/ap/bin/su -c "test -x /data/adb/service.d/phonebridge-root-handler.sh && echo handler_ok || echo handler_missing"'
adb shell '/data/adb/ap/bin/su -c "test -x /data/adb/service.d/phonebridge-rootd.sh && echo rootd_ok || echo rootd_missing"'
adb shell '/data/adb/ap/bin/su -c "ps -A | grep -E \"phonebridge-rootd|[n]c -L -s 127.0.0.1 -p 48733\""' 
```
All checks must pass before proceeding to call/voice testing.

## Persistence requirements
- APatch root must survive reboot.
- rootd scripts in `/data/adb/service.d/` must survive reboot.
- Local temporary folders (`phone/root-work/`, `phone/rootd/`) must not be kept.

## Recovery path (if boot issues return)
- Reflash matching stock partitions from same factory image:
  - `boot_[ab]`, `init_boot_[ab]`, `vbmeta_[ab]`, `vendor_boot_[ab]`, `vendor_kernel_boot_[ab]`
- Boot system fully, verify `sys.boot_completed=1`, then redo APatch flow.

### Recovery commands (host)
```bash
cd phone/root-work/image
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
cd phone
rm -rf root-work rootd
```
