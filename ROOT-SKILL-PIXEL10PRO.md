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

## Persistence requirements
- APatch root must survive reboot.
- rootd scripts in `/data/adb/service.d/` must survive reboot.
- Local temporary folders (`phone/root-work/`, `phone/rootd/`) must not be kept.

## Recovery path (if boot issues return)
- Reflash matching stock partitions from same factory image:
  - `boot_[ab]`, `init_boot_[ab]`, `vbmeta_[ab]`, `vendor_boot_[ab]`, `vendor_kernel_boot_[ab]`
- Boot system fully, verify `sys.boot_completed=1`, then redo APatch flow.
