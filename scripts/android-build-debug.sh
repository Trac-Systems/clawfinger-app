#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT_DIR/app-android"

cd "$APP_DIR"

if [[ ! -x "./gradlew" ]]; then
  if command -v gradle >/dev/null 2>&1; then
    echo "INFO: gradle wrapper missing, generating wrapper..."
    gradle wrapper --gradle-version 8.11
  else
    echo "ERROR: gradle wrapper missing and gradle command not found."
    echo "Install Android Studio or gradle, then rerun."
    exit 1
  fi
fi

./gradlew :app:assembleDebug

APK_PATH="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: expected APK not found at $APK_PATH"
  exit 1
fi

echo "PASS: debug APK built at $APK_PATH"
