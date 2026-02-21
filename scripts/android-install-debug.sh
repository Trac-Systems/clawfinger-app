#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$ROOT_DIR/app-android/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE_NAME="com.tracsystems.phonebridge"
MAIN_ACTIVITY="$PACKAGE_NAME/.MainActivity"
SERIAL="${ANDROID_DEVICE_SERIAL:-}"

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB+=( -s "$SERIAL" )
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found"
  exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "ERROR: APK missing at $APK_PATH"
  echo "Run: scripts/android-build-debug.sh"
  exit 1
fi

echo "INFO: installing $APK_PATH"
"${ADB[@]}" install -r "$APK_PATH"

echo "INFO: attempting to assign default dialer role"
"${ADB[@]}" shell cmd role add-role-holder android.app.role.DIALER "$PACKAGE_NAME" >/dev/null 2>&1 || true

echo "INFO: current default dialer holders"
holders="$("${ADB[@]}" shell cmd role get-role-holders android.app.role.DIALER 2>/dev/null | tr -d '\r')"
echo "$holders"
if ! echo "$holders" | grep -q "$PACKAGE_NAME"; then
  echo "WARN: $PACKAGE_NAME is not default dialer yet; open app and accept dialer role prompt"
fi

echo "INFO: launching $MAIN_ACTIVITY"
"${ADB[@]}" shell am start -n "$MAIN_ACTIVITY" >/dev/null

echo "INFO: granting runtime permissions"
"${ADB[@]}" shell pm grant "$PACKAGE_NAME" android.permission.CALL_PHONE >/dev/null 2>&1 || true
"${ADB[@]}" shell pm grant "$PACKAGE_NAME" android.permission.ANSWER_PHONE_CALLS >/dev/null 2>&1 || true
"${ADB[@]}" shell pm grant "$PACKAGE_NAME" android.permission.READ_PHONE_STATE >/dev/null 2>&1 || true
"${ADB[@]}" shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
"${ADB[@]}" shell pm grant "$PACKAGE_NAME" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

echo "PASS: APK installed and app launched"
