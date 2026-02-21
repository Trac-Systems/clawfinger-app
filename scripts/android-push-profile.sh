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

echo "INFO: attempting internal app-profile sync (run-as)"
if "${ADB[@]}" shell "run-as '$PACKAGE_NAME' sh -c 'mkdir -p files/profiles'" >/dev/null 2>&1; then
  if "${ADB[@]}" shell "run-as '$PACKAGE_NAME' sh -c 'cat > files/profiles/profile.json'" < "$PROFILE_SRC"; then
    echo "INFO: internal profile synced to /data/data/$PACKAGE_NAME/files/profiles/profile.json"
  else
    echo "WARN: internal profile sync failed; external profile remains active"
  fi
else
  echo "WARN: run-as unavailable; skipping internal profile sync"
fi

echo "INFO: force-stopping app so next call service reloads profile"
"${ADB[@]}" shell am force-stop "$PACKAGE_NAME" || true

echo "PASS: profile pushed and app stopped"
