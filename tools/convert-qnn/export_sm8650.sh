#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage:"
  echo "  $0 <bundle-dir> <base-url> <version>"
  echo
  echo "Expected bundle layout:"
  echo "  <bundle-dir>/models/sd15_img2img.bin"
  echo "  <bundle-dir>/models/depth_anything_v2_small.bin"
  echo "  <bundle-dir>/models/rife46_lite.bin"
  echo
  echo "Optional support files under qnn/ or assets/ will also be hashed into the manifest."
  exit 1
fi

bundle_dir=$1
base_url=$2
version=$3

python3 "$(dirname "$0")/package_runtime_bundle.py" \
  --bundle-dir "$bundle_dir" \
  --base-url "$base_url" \
  --version "$version" \
  --output "$bundle_dir/runtime-manifest.json"

echo "Generated: $bundle_dir/runtime-manifest.json"
