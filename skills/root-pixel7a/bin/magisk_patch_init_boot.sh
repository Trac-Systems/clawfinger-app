#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage:
  $0 --init-boot-img <path> --out-img <path> [--apk <path>] [--serial <adb_serial>]

Example:
  $0 \
    --init-boot-img root-work/image/init_boot.img \
    --out-img root-work/patched-init-boot-magisk.img \
    --apk root-work/magisk/Magisk.apk
USAGE
}

INIT_BOOT_IMG=""
OUT_IMG=""
APK_PATH=""
ADB_SERIAL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --init-boot-img) INIT_BOOT_IMG="${2:-}"; shift 2 ;;
    --out-img) OUT_IMG="${2:-}"; shift 2 ;;
    --apk) APK_PATH="${2:-}"; shift 2 ;;
    --serial) ADB_SERIAL="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -n "$INIT_BOOT_IMG" ]] || { echo "--init-boot-img is required" >&2; exit 2; }
[[ -n "$OUT_IMG" ]] || { echo "--out-img is required" >&2; exit 2; }
[[ -f "$INIT_BOOT_IMG" ]] || { echo "init_boot image not found: $INIT_BOOT_IMG" >&2; exit 2; }

ADB=(adb)
if [[ -n "$ADB_SERIAL" ]]; then
  ADB=(adb -s "$ADB_SERIAL")
fi

if [[ -z "$APK_PATH" ]]; then
  APK_PATH="${OUT_IMG%/*}/Magisk.apk"
fi
mkdir -p "$(dirname "$OUT_IMG")"
mkdir -p "$(dirname "$APK_PATH")"

if [[ ! -f "$APK_PATH" ]]; then
  echo "[magisk] downloading latest Magisk apk"
  read -r apk_name apk_url < <(python3 - <<'PY'
import json,urllib.request
import re
r=json.load(urllib.request.urlopen('https://api.github.com/repos/topjohnwu/Magisk/releases/latest'))
assets=[a for a in r.get('assets',[]) if a['name'].endswith('.apk')]
preferred=[a for a in assets if re.match(r'^Magisk-v.*\.apk$', a.get('name',''))]
if not preferred:
  preferred=[a for a in assets if 'debug' not in a.get('name','').lower()]
if not preferred:
  preferred=assets
if preferred:
  print(preferred[0].get('name',''), preferred[0].get('browser_download_url',''))
PY
)
  echo "[magisk] selected asset: ${apk_name:-unknown}"
  [[ -n "$apk_url" ]] || { echo "failed to resolve Magisk APK url" >&2; exit 1; }
  curl -sL -o "$APK_PATH" "$apk_url"
fi

tmpdir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmpdir"
}
trap cleanup EXIT

cp "$APK_PATH" "$tmpdir/Magisk.apk"
(
  cd "$tmpdir"
  unzip -o Magisk.apk \
    assets/boot_patch.sh \
    assets/util_functions.sh \
    assets/stub.apk \
    lib/arm64-v8a/libmagiskboot.so \
    lib/arm64-v8a/libmagisk.so \
    lib/arm64-v8a/libmagiskinit.so \
    lib/arm64-v8a/libinit-ld.so >/dev/null

  cp assets/boot_patch.sh boot_patch.sh
  cp assets/util_functions.sh util_functions.sh
  cp assets/stub.apk stub.apk
  cp lib/arm64-v8a/libmagiskboot.so magiskboot
  cp lib/arm64-v8a/libmagisk.so magisk
  cp lib/arm64-v8a/libmagiskinit.so magiskinit
  cp lib/arm64-v8a/libinit-ld.so init-ld
  chmod 755 boot_patch.sh magiskboot magisk magiskinit init-ld
)

echo "[magisk] pushing patch toolchain to device"
"${ADB[@]}" wait-for-device
"${ADB[@]}" shell 'rm -rf /data/local/tmp/mgcli && mkdir -p /data/local/tmp/mgcli'
"${ADB[@]}" push "$tmpdir/boot_patch.sh" /data/local/tmp/mgcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/util_functions.sh" /data/local/tmp/mgcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/stub.apk" /data/local/tmp/mgcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/magiskboot" /data/local/tmp/mgcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/magisk" /data/local/tmp/mgcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/magiskinit" /data/local/tmp/mgcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/init-ld" /data/local/tmp/mgcli/ >/dev/null
"${ADB[@]}" push "$INIT_BOOT_IMG" /data/local/tmp/mgcli/init_boot.img >/dev/null
"${ADB[@]}" shell 'chmod 755 /data/local/tmp/mgcli/boot_patch.sh /data/local/tmp/mgcli/magiskboot /data/local/tmp/mgcli/magisk /data/local/tmp/mgcli/magiskinit /data/local/tmp/mgcli/init-ld'

echo "[magisk] running patch on device"
"${ADB[@]}" shell 'cd /data/local/tmp/mgcli && ./boot_patch.sh init_boot.img'

echo "[magisk] pulling patched image"
"${ADB[@]}" pull /data/local/tmp/mgcli/new-boot.img "$OUT_IMG" >/dev/null

if command -v shasum >/dev/null 2>&1; then
  sha="$(shasum -a 256 "$OUT_IMG" | awk '{print $1}')"
  echo "[magisk] done: $OUT_IMG"
  echo "[magisk] sha256: $sha"
else
  echo "[magisk] done: $OUT_IMG"
fi
