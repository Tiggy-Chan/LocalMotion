#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../.." && pwd)"

onnx_dir="$repo_root/models/shortlist/converted/sd15-onnx"
output_root="$repo_root/models/shortlist/converted/sd15-qnn"
float_bitwidth="32"
float_bias_bitwidth=""
build_model_libs=1
with_android_lib=0
probe_htp_context=0
probe_only=0
models_csv="text_encoder,unet,vae_encoder,vae_decoder"

usage() {
  cat <<'EOF'
Usage:
  bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh [options]

Options:
  --onnx-dir <dir>             Override the ONNX source directory.
  --output-root <dir>          Override the QNN output directory.
  --models <csv>               Comma-separated subset of models to convert.
  --float-bitwidth <32|16>     Convert as FP32 (default) or FP16.
  --float-bias-bitwidth <32|16>
                               Optional float bias bitwidth override.
  --skip-model-lib             Only emit QNN cpp/bin/json.
  --with-android-lib           Also build aarch64-android model libs when ndk-build exists.
  --probe-htp-context          Attempt x86_64 HTP context generation when x86 model libs exist.
  --probe-only                 Skip conversion/build and only probe existing x86 HTP contexts.
  -h, --help                   Show this help.

Default models:
  text_encoder,unet,vae_encoder,vae_decoder

Notes:
  - This script expects .env.qnn to point at a valid QAIRT/QNN SDK.
  - If .venv-qairt exists, the script activates it automatically.
  - x86_64-linux-clang model libs are built by default.
  - aarch64-android model libs need Android NDK with ndk-build in PATH.
  - HTP probing uses x86 host libQnnHtp.so and records logs per model.
  - --probe-only expects existing model_libs/x86_64-linux-clang/lib<model>.so.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --onnx-dir)
      onnx_dir="$2"
      shift 2
      ;;
    --output-root)
      output_root="$2"
      shift 2
      ;;
    --models)
      models_csv="$2"
      shift 2
      ;;
    --float-bitwidth)
      float_bitwidth="$2"
      shift 2
      ;;
    --float-bias-bitwidth)
      float_bias_bitwidth="$2"
      shift 2
      ;;
    --skip-model-lib)
      build_model_libs=0
      shift
      ;;
    --with-android-lib)
      with_android_lib=1
      shift
      ;;
    --probe-htp-context)
      probe_htp_context=1
      shift
      ;;
    --probe-only)
      probe_only=1
      probe_htp_context=1
      build_model_libs=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ "$float_bitwidth" != "32" && "$float_bitwidth" != "16" ]]; then
  echo "Unsupported --float-bitwidth: $float_bitwidth" >&2
  exit 1
fi

if [[ -n "$float_bias_bitwidth" && "$float_bias_bitwidth" != "32" && "$float_bias_bitwidth" != "16" ]]; then
  echo "Unsupported --float-bias-bitwidth: $float_bias_bitwidth" >&2
  exit 1
fi

if [[ -d "$repo_root/.venv-qairt" && -z "${VIRTUAL_ENV:-}" ]]; then
  # shellcheck source=/dev/null
  source "$repo_root/.venv-qairt/bin/activate"
fi

if [[ -f "$repo_root/.env.qnn" ]]; then
  export PYTHONPATH="${PYTHONPATH:-}"
  export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:-}"
  set +u
  # shellcheck source=/dev/null
  source "$repo_root/.env.qnn"
  set -u
fi

required_cmds=(qnn-onnx-converter qnn-model-lib-generator)
if [[ "$probe_htp_context" == "1" ]]; then
  required_cmds+=(qnn-context-binary-generator)
fi

for cmd in "${required_cmds[@]}"; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing required tool: $cmd" >&2
    echo "Hint: source .env.qnn and activate .venv-qairt first." >&2
    exit 1
  fi
done

if [[ "$probe_only" == "1" ]]; then
  for model_name in "${models[@]}"; do
    model_dir="$output_root/$model_name"
    lib_path="$model_dir/model_libs/x86_64-linux-clang/lib${model_name}.so"
    if [[ ! -f "$lib_path" ]]; then
      echo "Missing existing x86 model lib for --probe-only: $lib_path" >&2
      exit 1
    fi
  done
fi

IFS=',' read -r -a models <<<"$models_csv"

mkdir -p "$output_root"

convert_model() {
  local model_name=$1
  local model_dir="$output_root/$model_name"
  local input_model="$onnx_dir/$model_name.onnx"
  local converter_log="$model_dir/converter.log"

  if [[ ! -f "$input_model" ]]; then
    echo "Missing ONNX model: $input_model" >&2
    exit 1
  fi

  mkdir -p "$model_dir"

  local converter_args=(
    --input_network "$input_model"
    --output_path "$model_dir/$model_name.cpp"
  )

  if [[ "$float_bitwidth" != "32" ]]; then
    converter_args+=(--float_bitwidth "$float_bitwidth")
  fi

  if [[ -n "$float_bias_bitwidth" ]]; then
    converter_args+=(--float_bias_bitwidth "$float_bias_bitwidth")
  fi

  echo "Converting $model_name ..."
  if ! qnn-onnx-converter "${converter_args[@]}" >"$converter_log" 2>&1; then
    echo "QNN conversion failed for $model_name. See $converter_log" >&2
    tail -n 20 "$converter_log" >&2 || true
    exit 1
  fi

  echo "  wrote $model_dir/$model_name.cpp"
  echo "  wrote $model_dir/$model_name.bin"
}

build_model_lib() {
  local model_name=$1
  local model_dir="$output_root/$model_name"
  local lib_dir="$model_dir/model_libs"
  local lib_log="$model_dir/model_libs.log"
  local lib_targets=("x86_64-linux-clang")

  if [[ "$with_android_lib" == "1" ]]; then
    if command -v ndk-build >/dev/null 2>&1; then
      lib_targets+=("aarch64-android")
    else
      echo "  skipping aarch64-android for $model_name: ndk-build not found"
    fi
  fi

  mkdir -p "$lib_dir"

  echo "Building model libs for $model_name ..."
  if ! qnn-model-lib-generator \
    -c "$model_dir/$model_name.cpp" \
    -b "$model_dir/$model_name.bin" \
    -l "$model_name" \
    -t "${lib_targets[@]}" \
    -o "$lib_dir" >"$lib_log" 2>&1; then
    echo "Model lib generation failed for $model_name. See $lib_log" >&2
    tail -n 20 "$lib_log" >&2 || true
    return 1
  fi

  echo "  wrote $lib_dir"
}

model_lib_failures=()
htp_context_failures=()

probe_model_htp_context() {
  local model_name=$1
  local model_dir="$output_root/$model_name"
  local lib_path="$model_dir/model_libs/x86_64-linux-clang/lib${model_name}.so"
  local context_dir="$model_dir/htp_context"
  local context_log="$model_dir/htp_context.log"

  if [[ ! -f "$lib_path" ]]; then
    echo "Skipping HTP context probe for $model_name: missing $lib_path"
    return 1
  fi

  if [[ -z "${QNN_SDK_ROOT:-}" ]]; then
    echo "Skipping HTP context probe for $model_name: QNN_SDK_ROOT is not set"
    return 1
  fi

  mkdir -p "$context_dir"

  echo "Probing HTP context for $model_name ..."
  if ! qnn-context-binary-generator \
    --model "$lib_path" \
    --backend "$QNN_SDK_ROOT/lib/x86_64-linux-clang/libQnnHtp.so" \
    --output_dir "$context_dir" \
    --binary_file "${model_name}.serialized.bin" >"$context_log" 2>&1; then
    echo "HTP context probe failed for $model_name. See $context_log" >&2
    tail -n 20 "$context_log" >&2 || true
    return 1
  fi

  echo "  wrote $context_dir/${model_name}.serialized.bin"
}

for model_name in "${models[@]}"; do
  if [[ "$probe_only" != "1" ]]; then
    convert_model "$model_name"
    if [[ "$build_model_libs" == "1" ]]; then
      if ! build_model_lib "$model_name"; then
        model_lib_failures+=("$model_name")
      fi
    fi
  fi
  if [[ "$probe_htp_context" == "1" ]]; then
    if ! probe_model_htp_context "$model_name"; then
      htp_context_failures+=("$model_name")
    fi
  fi
done

echo
echo "SD1.5 ONNX -> QNN export finished."
echo "Output root: $output_root"

if [[ ${#model_lib_failures[@]} -gt 0 ]]; then
  echo "Model lib failures: ${model_lib_failures[*]}"
  echo "QNN cpp/bin/json were still generated for those models."
fi

if [[ "$probe_htp_context" == "1" ]]; then
  if [[ ${#htp_context_failures[@]} -gt 0 ]]; then
    echo "HTP context probe failures: ${htp_context_failures[*]}"
  else
    echo "HTP context probe passed for all requested models."
  fi
fi
