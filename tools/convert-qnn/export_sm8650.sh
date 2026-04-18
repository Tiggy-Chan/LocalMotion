#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage:"
  echo "  $0 <bundle-dir> <base-url> <version>"
  echo
  echo "Expected bundle layout:"
  echo "  <bundle-dir>/models/text_encoder.bin"
  echo "  <bundle-dir>/models/text_encoder.so"
  echo "  <bundle-dir>/models/unet.bin"
  echo "  <bundle-dir>/models/unet.so"
  echo "  <bundle-dir>/models/vae_encoder.bin"
  echo "  <bundle-dir>/models/vae_encoder.so"
  echo "  <bundle-dir>/models/vae_decoder.bin"
  echo "  <bundle-dir>/models/vae_decoder.so"
  echo "  <bundle-dir>/tokenizer/tokenizer.json"
  echo "  <bundle-dir>/tokenizer/tokenizer_config.json"
  echo "  <bundle-dir>/qnn/lib/libQnnSystem.so"
  echo "  <bundle-dir>/qnn/lib/libQnnHtp.so"
  echo
  echo "Additional support files under qnn/ or assets/ will also be hashed into the manifest."
  exit 1
fi

bundle_dir=$1
base_url=$2
version=$3

python3 "$(dirname "$0")/package_runtime_bundle.py" \
  --profile sd15 \
  --bundle-dir "$bundle_dir" \
  --base-url "$base_url" \
  --version "$version" \
  --output "$bundle_dir/runtime-manifest.json"

echo "Generated: $bundle_dir/runtime-manifest.json"
