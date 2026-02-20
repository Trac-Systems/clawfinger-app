#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RAW_DIR="$ROOT_DIR/tests/audio-corpus/web/raw"
PROC_DIR="$ROOT_DIR/tests/audio-corpus/web/processed"

mkdir -p "$RAW_DIR" "$PROC_DIR"

download_if_missing() {
  local url="$1"
  local out="$2"
  if [[ -s "$out" ]]; then
    echo "SKIP download: $(basename "$out")"
    return 0
  fi
  echo "DOWNLOAD: $url"
  curl -L --fail --retry 2 --retry-delay 1 -o "$out" "$url"
}

download_if_missing "https://raw.githubusercontent.com/openai/whisper/main/tests/jfk.flac" "$RAW_DIR/jfk.flac"
download_if_missing "https://raw.githubusercontent.com/Uberi/speech_recognition/master/examples/english.wav" "$RAW_DIR/english.wav"
download_if_missing "https://huggingface.co/datasets/Narsil/asr_dummy/resolve/main/mlk.flac" "$RAW_DIR/mlk.flac"
download_if_missing "https://huggingface.co/datasets/Narsil/asr_dummy/resolve/main/canterville.ogg" "$RAW_DIR/canterville.ogg"
download_if_missing "https://huggingface.co/datasets/Narsil/asr_dummy/resolve/main/i-know-kung-fu.mp3" "$RAW_DIR/i-know-kung-fu.mp3"

to_16k_mono_wav() {
  local in_file="$1"
  local out_file="$2"
  ffmpeg -y -loglevel error -i "$in_file" -ac 1 -ar 16000 -sample_fmt s16 "$out_file"
}

to_16k_mono_wav "$RAW_DIR/jfk.flac" "$PROC_DIR/jfk-16k.wav"
to_16k_mono_wav "$RAW_DIR/english.wav" "$PROC_DIR/english-16k.wav"
to_16k_mono_wav "$RAW_DIR/mlk.flac" "$PROC_DIR/mlk-16k.wav"
to_16k_mono_wav "$RAW_DIR/i-know-kung-fu.mp3" "$PROC_DIR/kungfu-16k.wav"

ffmpeg -y -loglevel error -ss 00:02:00 -t 35 -i "$RAW_DIR/canterville.ogg" -ac 1 -ar 16000 -sample_fmt s16 "$PROC_DIR/canterville-35s-16k.wav"
ffmpeg -y -loglevel error -ss 00:20:00 -t 120 -i "$RAW_DIR/canterville.ogg" -ac 1 -ar 16000 -sample_fmt s16 "$PROC_DIR/canterville-120s-16k.wav"

ffmpeg -y -loglevel error -f lavfi -i "anullsrc=r=16000:cl=mono" -t 1.2 -ac 1 -ar 16000 -sample_fmt s16 "$PROC_DIR/silence-1200ms-16k.wav"
ffmpeg -y -loglevel error -f lavfi -i "anullsrc=r=16000:cl=mono" -t 0.6 -ac 1 -ar 16000 -sample_fmt s16 "$PROC_DIR/silence-600ms-16k.wav"

cat > "$PROC_DIR/stream-mix-120s.concat.txt" <<EOF
file '$PROC_DIR/silence-600ms-16k.wav'
file '$PROC_DIR/english-16k.wav'
file '$PROC_DIR/silence-1200ms-16k.wav'
file '$PROC_DIR/jfk-16k.wav'
file '$PROC_DIR/silence-1200ms-16k.wav'
file '$PROC_DIR/mlk-16k.wav'
file '$PROC_DIR/silence-1200ms-16k.wav'
file '$PROC_DIR/kungfu-16k.wav'
file '$PROC_DIR/silence-1200ms-16k.wav'
file '$PROC_DIR/canterville-35s-16k.wav'
file '$PROC_DIR/silence-1200ms-16k.wav'
file '$PROC_DIR/jfk-16k.wav'
file '$PROC_DIR/silence-1200ms-16k.wav'
file '$PROC_DIR/canterville-35s-16k.wav'
file '$PROC_DIR/silence-1200ms-16k.wav'
EOF

ffmpeg -y -loglevel error -f concat -safe 0 -i "$PROC_DIR/stream-mix-120s.concat.txt" -c copy "$PROC_DIR/stream-mix-120s-16k.wav"

echo "Built web corpus:"
for f in \
  "$PROC_DIR/jfk-16k.wav" \
  "$PROC_DIR/english-16k.wav" \
  "$PROC_DIR/mlk-16k.wav" \
  "$PROC_DIR/kungfu-16k.wav" \
  "$PROC_DIR/canterville-35s-16k.wav" \
  "$PROC_DIR/canterville-120s-16k.wav" \
  "$PROC_DIR/stream-mix-120s-16k.wav"; do
  d="$(ffprobe -v error -show_entries format=duration -of default=nokey=1:noprint_wrappers=1 "$f")"
  echo "  - $(basename "$f") duration=${d}s"
done

