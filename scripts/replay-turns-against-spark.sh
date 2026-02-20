#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TURN_DIR="${1:-$ROOT_DIR/debug-wavs/stream-sim-web-tuned/turns}"
OUT_FILE="${2:-$ROOT_DIR/debug-wavs/stream-sim-web-tuned/model-replay-full.txt}"
SPARK_BASE_URL="${SPARK_BASE_URL:-http://192.168.178.30:8996}"
SPARK_BEARER="${SPARK_BEARER:-41154c137d0225c8a8d1abc6f659a39811e6a40fd3851bce40da2604ae37ddf3}"

if [[ ! -d "$TURN_DIR" ]]; then
  echo "Missing turn directory: $TURN_DIR" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUT_FILE")"
: > "$OUT_FILE"

turn_files=()
while IFS= read -r wav; do
  turn_files+=("$wav")
done < <(find "$TURN_DIR" -type f -name '*.wav' | sort)
if [[ "${#turn_files[@]}" -eq 0 ]]; then
  echo "No WAV files in $TURN_DIR" >&2
  exit 1
fi

for wav in "${turn_files[@]}"; do
  duration="$(ffprobe -v error -show_entries format=duration -of default=nokey=1:noprint_wrappers=1 "$wav")"

  asr_json="$(
    curl -sS \
      -H "Authorization: Bearer $SPARK_BEARER" \
      -F "audio=@$wav;type=audio/wav" \
      "$SPARK_BASE_URL/api/asr"
  )"
  asr_text="$(echo "$asr_json" | jq -r '.transcript // ""' | tr '\n' ' ')"
  asr_ms="$(echo "$asr_json" | jq -r '.metrics.asr_ms // 0')"

  turn_skip_json="$(
    curl -sS \
      -H "Authorization: Bearer $SPARK_BEARER" \
      -F "skip_asr=true" \
      -F "transcript_hint=$asr_text" \
      -F "audio=@$wav;type=audio/wav" \
      "$SPARK_BASE_URL/api/turn"
  )"
  turn_skip_reply="$(echo "$turn_skip_json" | jq -r '.reply // ""' | tr '\n' ' ')"
  turn_skip_total="$(echo "$turn_skip_json" | jq -r '.metrics.total_ms // 0')"
  turn_skip_llm="$(echo "$turn_skip_json" | jq -r '.metrics.llm_ms // 0')"
  turn_skip_tts="$(echo "$turn_skip_json" | jq -r '.metrics.tts_ms // 0')"

  turn_srv_json="$(
    curl -sS \
      -H "Authorization: Bearer $SPARK_BEARER" \
      -F "skip_asr=false" \
      -F "audio=@$wav;type=audio/wav" \
      "$SPARK_BASE_URL/api/turn"
  )"
  turn_srv_transcript="$(echo "$turn_srv_json" | jq -r '.transcript // ""' | tr '\n' ' ')"
  turn_srv_reply="$(echo "$turn_srv_json" | jq -r '.reply // ""' | tr '\n' ' ')"
  turn_srv_total="$(echo "$turn_srv_json" | jq -r '.metrics.total_ms // 0')"
  turn_srv_asr_source="$(echo "$turn_srv_json" | jq -r '.metrics.asr_source // ""')"

  {
    echo "FILE: $(basename "$wav")"
    echo "DURATION_S: $duration"
    echo "ASR_MS: $asr_ms"
    echo "ASR_TEXT: $asr_text"
    echo "TURN_SKIP_TOTAL_MS: $turn_skip_total"
    echo "TURN_SKIP_LLM_MS: $turn_skip_llm"
    echo "TURN_SKIP_TTS_MS: $turn_skip_tts"
    echo "TURN_SKIP_REPLY: $turn_skip_reply"
    echo "TURN_SERVER_ASR_SOURCE: $turn_srv_asr_source"
    echo "TURN_SERVER_TOTAL_MS: $turn_srv_total"
    echo "TURN_SERVER_TRANSCRIPT: $turn_srv_transcript"
    echo "TURN_SERVER_REPLY: $turn_srv_reply"
    echo
  } >> "$OUT_FILE"
done

echo "Replay report written to: $OUT_FILE"
