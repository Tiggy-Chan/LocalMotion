#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/../../.." && pwd)

usage() {
  cat <<'EOF'
Usage:
  stage_runtime_bundle.sh <bundle-dir> [--sdk-root PATH] [--qnn-dir PATH] [--onnx-dir PATH]

Stages an Android runtime bundle for LocalMotion SD1.5.

Expected inputs:
  - Android model libs under:
      <qnn-dir>/<model>/model_libs/aarch64-android/lib<model>.so
  - QNN weights under:
      <qnn-dir>/<model>/<model>.bin
  - Tokenizer files under:
      <onnx-dir>/tokenizer/

The script copies:
  - models/*.bin
  - models/*.so
  - tokenizer/tokenizer.json
  - tokenizer/tokenizer_config.json
  - qnn/lib/*.so support libraries
  - qnn/bin/qnn-net-run (when present)
EOF
}

bundle_dir=
sdk_root=${QNN_SDK_ROOT:-}
qnn_dir="$repo_root/models/shortlist/converted/sd15-qnn"
onnx_dir="$repo_root/models/shortlist/converted/sd15-onnx"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sdk-root)
      sdk_root=$2
      shift 2
      ;;
    --qnn-dir)
      qnn_dir=$2
      shift 2
      ;;
    --onnx-dir)
      onnx_dir=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ -z "$bundle_dir" ]]; then
        bundle_dir=$1
        shift
      else
        echo "Unexpected argument: $1" >&2
        usage >&2
        exit 1
      fi
      ;;
  esac
done

if [[ -z "$bundle_dir" ]]; then
  usage >&2
  exit 1
fi

if [[ -z "$sdk_root" ]]; then
  echo "QNN SDK root is not set. Use --sdk-root or export QNN_SDK_ROOT." >&2
  exit 1
fi

android_lib_dir="$sdk_root/lib/aarch64-android"
android_bin_dir="$sdk_root/bin/aarch64-android"

for path in "$android_lib_dir" "$qnn_dir" "$onnx_dir/tokenizer"; do
  if [[ ! -d "$path" ]]; then
    echo "Missing required directory: $path" >&2
    exit 1
  fi
done

bundle_dir=$(realpath -m "$bundle_dir")
mkdir -p "$bundle_dir/models" "$bundle_dir/tokenizer" "$bundle_dir/qnn/lib" "$bundle_dir/qnn/bin"

copy_file() {
  local source=$1
  local target=$2
  if [[ ! -f "$source" ]]; then
    echo "Missing required file: $source" >&2
    exit 1
  fi
  install -m 0644 "$source" "$target"
}

copy_optional_executable() {
  local source=$1
  local target=$2
  if [[ -f "$source" ]]; then
    install -m 0755 "$source" "$target"
  fi
}

copy_pattern() {
  local pattern=$1
  local target_dir=$2
  local matched=1
  shopt -s nullglob
  for source in $pattern; do
    matched=0
    install -m 0644 "$source" "$target_dir/$(basename "$source")"
  done
  shopt -u nullglob
  return $matched
}

for model in text_encoder unet vae_encoder vae_decoder; do
  copy_file "$qnn_dir/$model/$model.bin" "$bundle_dir/models/$model.bin"
  copy_file "$qnn_dir/$model/model_libs/aarch64-android/lib$model.so" "$bundle_dir/models/$model.so"
done

copy_file "$onnx_dir/tokenizer/tokenizer.json" "$bundle_dir/tokenizer/tokenizer.json"
copy_file "$onnx_dir/tokenizer/tokenizer_config.json" "$bundle_dir/tokenizer/tokenizer_config.json"

copy_file "$android_lib_dir/libQnnSystem.so" "$bundle_dir/qnn/lib/libQnnSystem.so"
copy_file "$android_lib_dir/libQnnHtp.so" "$bundle_dir/qnn/lib/libQnnHtp.so"

copy_pattern "${android_lib_dir}/libQnnHtpPrepare.so" "$bundle_dir/qnn/lib" || true
copy_pattern "${android_lib_dir}/libQnnHtpNetRunExtensions.so" "$bundle_dir/qnn/lib" || true
copy_pattern "${android_lib_dir}/libQnnHtpV*Stub.so" "$bundle_dir/qnn/lib" || true
copy_pattern "${android_lib_dir}/libQnnHtpV*CalculatorStub.so" "$bundle_dir/qnn/lib" || true
copy_pattern "${android_lib_dir}/libcalculator.so" "$bundle_dir/qnn/lib" || true

copy_optional_executable "$android_bin_dir/qnn-net-run" "$bundle_dir/qnn/bin/qnn-net-run"

echo "Staged runtime bundle at $bundle_dir"
