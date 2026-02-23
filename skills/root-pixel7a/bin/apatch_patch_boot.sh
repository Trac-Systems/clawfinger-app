#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage:
  $0 --boot-img <path> --out-img <path> --superkey-file <path> [--apk <path>] [--serial <adb_serial>]

Example:
  $0 \
    --boot-img root-work/image/boot.img \
    --out-img root-work/patched-boot-apatch.img \
    --superkey-file root-work/APATCH_SUPERKEY.txt \
    --apk root-work/APatch.apk
USAGE
}

BOOT_IMG=""
OUT_IMG=""
SUPERKEY_FILE=""
APK_PATH=""
ADB_SERIAL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --boot-img) BOOT_IMG="${2:-}"; shift 2 ;;
    --out-img) OUT_IMG="${2:-}"; shift 2 ;;
    --superkey-file) SUPERKEY_FILE="${2:-}"; shift 2 ;;
    --apk) APK_PATH="${2:-}"; shift 2 ;;
    --serial) ADB_SERIAL="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -n "$BOOT_IMG" ]] || { echo "--boot-img is required" >&2; exit 2; }
[[ -n "$OUT_IMG" ]] || { echo "--out-img is required" >&2; exit 2; }
[[ -n "$SUPERKEY_FILE" ]] || { echo "--superkey-file is required" >&2; exit 2; }
[[ -f "$BOOT_IMG" ]] || { echo "boot image not found: $BOOT_IMG" >&2; exit 2; }
[[ -f "$SUPERKEY_FILE" ]] || { echo "superkey file not found: $SUPERKEY_FILE" >&2; exit 2; }

SUPERKEY="$(tr -d '\r\n' < "$SUPERKEY_FILE")"
[[ -n "$SUPERKEY" ]] || { echo "superkey file is empty: $SUPERKEY_FILE" >&2; exit 2; }

ADB=(adb)
if [[ -n "$ADB_SERIAL" ]]; then
  ADB=(adb -s "$ADB_SERIAL")
fi

if [[ -z "$APK_PATH" ]]; then
  APK_PATH="${OUT_IMG%/*}/APatch.apk"
fi

mkdir -p "$(dirname "$OUT_IMG")"
mkdir -p "$(dirname "$APK_PATH")"

if [[ ! -f "$APK_PATH" ]]; then
  echo "[apatch] downloading latest APatch.apk"
  apk_url="$(curl -sL https://api.github.com/repos/bmax121/APatch/releases/latest | python3 -c 'import json,sys; r=json.load(sys.stdin); apks=[a for a in r["assets"] if a["name"].endswith(".apk")]; print(apks[0]["browser_download_url"])')"
  curl -sL -o "$APK_PATH" "$apk_url"
fi

tmpdir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmpdir"
}
trap cleanup EXIT

cp "$APK_PATH" "$tmpdir/APatch.apk"
(
  cd "$tmpdir"
  unzip -o APatch.apk \
    assets/boot_patch.sh \
    assets/util_functions.sh \
    assets/kpimg \
    lib/arm64-v8a/libkptools.so \
    lib/arm64-v8a/libmagiskboot.so \
    lib/arm64-v8a/libkpatch.so >/dev/null

  cp assets/boot_patch.sh boot_patch.sh
  cp assets/util_functions.sh util_functions.sh
  cp assets/kpimg kpimg
  cp lib/arm64-v8a/libkptools.so kptools
  cp lib/arm64-v8a/libmagiskboot.so magiskboot
  cp lib/arm64-v8a/libkpatch.so kpatch
  chmod 755 boot_patch.sh kptools magiskboot kpatch
  printf '%s\n' "$SUPERKEY" > superkey.txt
)

echo "[apatch] pushing patch toolchain to device"
"${ADB[@]}" wait-for-device
"${ADB[@]}" shell 'rm -rf /data/local/tmp/apcli && mkdir -p /data/local/tmp/apcli'
"${ADB[@]}" push "$tmpdir/boot_patch.sh" /data/local/tmp/apcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/util_functions.sh" /data/local/tmp/apcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/kpimg" /data/local/tmp/apcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/kptools" /data/local/tmp/apcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/magiskboot" /data/local/tmp/apcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/kpatch" /data/local/tmp/apcli/ >/dev/null
"${ADB[@]}" push "$tmpdir/superkey.txt" /data/local/tmp/apcli/ >/dev/null
"${ADB[@]}" push "$BOOT_IMG" /data/local/tmp/apcli/boot.img >/dev/null
"${ADB[@]}" shell 'chmod 755 /data/local/tmp/apcli/boot_patch.sh /data/local/tmp/apcli/kptools /data/local/tmp/apcli/magiskboot /data/local/tmp/apcli/kpatch'

echo "[apatch] running patch on device"
"${ADB[@]}" shell 'cd /data/local/tmp/apcli && SK=$(cat superkey.txt) && ./boot_patch.sh "$SK" boot.img'

echo "[apatch] pulling patched image"
"${ADB[@]}" pull /data/local/tmp/apcli/new-boot.img "$OUT_IMG" >/dev/null

if command -v shasum >/dev/null 2>&1; then
  sha="$(shasum -a 256 "$OUT_IMG" | awk '{print $1}')"
  echo "[apatch] done: $OUT_IMG"
  echo "[apatch] sha256: $sha"
else
  echo "[apatch] done: $OUT_IMG"
fi
