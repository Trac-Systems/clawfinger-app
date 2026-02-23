#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage:
  $0 --patched-img <path> --stock-boot <path> [--serial <adb_serial>] [--monitor-seconds <n>]

Behavior:
- Flashes patched boot to active slot.
- Reboots and monitors ADB stability.
- If instability is detected, auto-recovers by flashing stock boot to both slots.
USAGE
}

PATCHED_IMG=""
STOCK_BOOT=""
ADB_SERIAL=""
MONITOR_SECONDS=120

while [[ $# -gt 0 ]]; do
  case "$1" in
    --patched-img) PATCHED_IMG="${2:-}"; shift 2 ;;
    --stock-boot) STOCK_BOOT="${2:-}"; shift 2 ;;
    --serial) ADB_SERIAL="${2:-}"; shift 2 ;;
    --monitor-seconds) MONITOR_SECONDS="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -n "$PATCHED_IMG" ]] || { echo "--patched-img is required" >&2; exit 2; }
[[ -n "$STOCK_BOOT" ]] || { echo "--stock-boot is required" >&2; exit 2; }
[[ -f "$PATCHED_IMG" ]] || { echo "patched image not found: $PATCHED_IMG" >&2; exit 2; }
[[ -f "$STOCK_BOOT" ]] || { echo "stock boot not found: $STOCK_BOOT" >&2; exit 2; }

ADB=(adb)
if [[ -n "$ADB_SERIAL" ]]; then
  ADB=(adb -s "$ADB_SERIAL")
fi

wait_fastboot() {
  for _ in $(seq 1 200); do
    if fastboot devices | awk 'NF{exit 0} END{exit 1}'; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

recover_stock() {
  echo "[guard] recovering stock boot on both slots"
  for _ in $(seq 1 120); do
    st="$(${ADB[@]} get-state 2>/dev/null || true)"
    if [[ "$st" == "device" ]]; then
      ${ADB[@]} reboot bootloader || true
      break
    fi
    if fastboot devices | awk 'NF{exit 0} END{exit 1}'; then
      break
    fi
    sleep 1
  done

  wait_fastboot || { echo "[guard] failed to reach fastboot for recovery" >&2; return 1; }
  fastboot flash boot_a "$STOCK_BOOT"
  fastboot flash boot_b "$STOCK_BOOT"
  fastboot reboot
  ${ADB[@]} wait-for-device
  echo "[guard] stock recovery complete"
}

echo "[guard] rebooting to bootloader"
${ADB[@]} wait-for-device
${ADB[@]} reboot bootloader || true
wait_fastboot || { echo "failed to reach fastboot" >&2; exit 1; }

slot="$(fastboot getvar current-slot 2>&1 | sed -n 's/.*current-slot: //p' | tr -d '\r')"
[[ -n "$slot" ]] || { echo "unable to read current slot" >&2; exit 1; }
echo "[guard] active slot: $slot"

fastboot flash "boot_${slot}" "$PATCHED_IMG"
fastboot reboot

echo "[guard] monitoring boot stability (${MONITOR_SECONDS}s)"
seen_boot1=0
unstable=0
loops=$(( MONITOR_SECONDS / 2 ))
if [[ $loops -lt 10 ]]; then
  loops=10
fi

for _ in $(seq 1 "$loops"); do
  ts="$(date +%H:%M:%S)"
  st="$(${ADB[@]} get-state 2>/dev/null || echo no-adb)"
  if [[ "$st" == "device" ]]; then
    b="$(${ADB[@]} shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    echo "[$ts] state=device boot=${b:-NA}"
    if [[ "$b" == "1" ]]; then
      seen_boot1=$((seen_boot1 + 1))
    fi
  else
    echo "[$ts] state=no-adb"
    if [[ $seen_boot1 -ge 3 ]]; then
      unstable=1
      break
    fi
  fi
  sleep 2
done

if [[ $unstable -eq 0 && $seen_boot1 -ge 15 ]]; then
  echo "[guard] patched boot looks stable"
  exit 0
fi

echo "[guard] instability detected (seen_boot1=$seen_boot1). Rolling back."
recover_stock
exit 1
