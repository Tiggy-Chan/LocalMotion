#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/../../.." && pwd)

python_bin=python3
venv_dir="$repo_root/.venv-sd15"
torch_index_url="https://download.pytorch.org/whl/cpu"
skip_pip=0
skip_check=0
force_diffusers=0
force_onnx=0

usage() {
  cat <<'EOF'
Usage:
  bash tools/convert-qnn/sd15/prepare_wsl_sd15.sh [options]

Options:
  --python <path>         Python executable to use for the virtual environment
  --venv-dir <path>       Virtual environment directory (default: .venv-sd15)
  --torch-index-url <url> Torch wheel index URL (default: https://download.pytorch.org/whl/cpu)
  --skip-pip              Skip pip upgrade and dependency installation
  --skip-check            Skip the final validation step
  --force-diffusers       Re-run checkpoint -> diffusers export
  --force-onnx            Re-run diffusers -> ONNX export
  -h, --help              Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --python)
      python_bin=$2
      shift 2
      ;;
    --venv-dir)
      venv_dir=$2
      shift 2
      ;;
    --torch-index-url)
      torch_index_url=$2
      shift 2
      ;;
    --skip-pip)
      skip_pip=1
      shift
      ;;
    --skip-check)
      skip_check=1
      shift
      ;;
    --force-diffusers)
      force_diffusers=1
      shift
      ;;
    --force-onnx)
      force_onnx=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

checkpoint="$repo_root/models/shortlist/candidates/SD15-Base/v1-5-pruned-emaonly.safetensors"
original_config="$repo_root/models/shortlist/candidates/SD15-Base/v1-inference.yaml"
diffusers_dir="$repo_root/models/shortlist/converted/sd15-diffusers"
onnx_dir="$repo_root/models/shortlist/converted/sd15-onnx"
requirements_file="$script_dir/requirements.txt"

if [[ ! -f "$checkpoint" ]]; then
  echo "Missing checkpoint: $checkpoint" >&2
  exit 1
fi

if [[ ! -f "$original_config" ]]; then
  echo "Missing config: $original_config" >&2
  exit 1
fi

if [[ ! -d "$venv_dir" ]]; then
  "$python_bin" -m venv "$venv_dir"
fi

# shellcheck disable=SC1091
source "$venv_dir/bin/activate"

if [[ $skip_pip -eq 0 ]]; then
  python -m pip install --upgrade pip
  python -m pip install --index-url "$torch_index_url" --extra-index-url https://pypi.org/simple torch
  python -m pip install -r "$requirements_file"
fi

need_diffusers=0
if [[ $force_diffusers -eq 1 || ! -f "$diffusers_dir/model_index.json" ]]; then
  need_diffusers=1
fi

need_onnx=0
for file_name in text_encoder.onnx unet.onnx vae_encoder.onnx vae_decoder.onnx; do
  if [[ $force_onnx -eq 1 || ! -f "$onnx_dir/$file_name" ]]; then
    need_onnx=1
    break
  fi
done

if [[ $need_diffusers -eq 1 ]]; then
  python "$script_dir/convert_checkpoint_to_diffusers.py" \
    --checkpoint "$checkpoint" \
    --original-config "$original_config" \
    --output-dir "$diffusers_dir"
fi

if [[ $need_onnx -eq 1 ]]; then
  python "$script_dir/export_submodels_onnx.py" \
    --pipeline-dir "$diffusers_dir" \
    --output-dir "$onnx_dir"
fi

if [[ $skip_check -eq 0 ]]; then
  python "$script_dir/check_wsl_sd15_qnn.py" --repo-root "$repo_root"
fi

echo "WSL SD1.5 preparation complete."
