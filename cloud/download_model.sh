#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
MODEL_DIR="${MODEL_DIR:-models}"
MODEL_NAME="${MODEL_NAME:-b11c768h12nbt3tflrs-fson-silu.bin.gz}"
MODEL_URL="${MODEL_URL:-https://github.com/lightvector/KataGo/releases/download/v1.17.1/b11c768h12nbt3tflrs-fson-silu.bin.gz}"
mkdir -p "$MODEL_DIR"
TARGET="$MODEL_DIR/model.bin.gz"
TMP="$TARGET.part"
trap 'rm -f "$TMP"' EXIT
echo "Downloading official KataGo network: $MODEL_NAME"
curl --fail --location --retry 3 --retry-all-errors --output "$TMP" "$MODEL_URL"
gzip -t "$TMP"
mv "$TMP" "$TARGET"
trap - EXIT
echo "Model installed: $TARGET"
sha256sum "$TARGET"
