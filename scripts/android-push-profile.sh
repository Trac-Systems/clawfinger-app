#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE_NAME="com.tracsystems.phonebridge"
SERIAL="${ANDROID_DEVICE_SERIAL:-}"
PROFILE_SRC="${1:-$ROOT_DIR/profiles/pixel10pro-blazer-profile-v1.json}"
DEST_DIR="/sdcard/Android/data/$PACKAGE_NAME/files/profiles"
DEST_FILE="$DEST_DIR/profile.json"

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

if [[ ! -f "$PROFILE_SRC" ]]; then
  echo "ERROR: profile not found: $PROFILE_SRC"
  exit 1
fi

echo "INFO: pushing profile to $DEST_FILE"
"${ADB[@]}" shell "mkdir -p '$DEST_DIR'"
"${ADB[@]}" push "$PROFILE_SRC" "$DEST_FILE" >/dev/null

echo "INFO: force-stopping app so next call service reloads profile"
"${ADB[@]}" shell am force-stop "$PACKAGE_NAME" || true

echo "PASS: profile pushed and app stopped"
