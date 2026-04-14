#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import os
from pathlib import Path


REQUIRED_SOURCE_FILES = [
    "models/shortlist/candidates/SD15-Base/v1-5-pruned-emaonly.safetensors",
    "models/shortlist/candidates/SD15-Base/v1-inference.yaml",
]

RECOMMENDED_DIFFUSERS_FILES = [
    "models/shortlist/converted/sd15-diffusers/model_index.json",
]

REQUIRED_ONNX_FILES = [
    "models/shortlist/converted/sd15-onnx/text_encoder.onnx",
    "models/shortlist/converted/sd15-onnx/text_encoder.onnx.data",
    "models/shortlist/converted/sd15-onnx/unet.onnx",
    "models/shortlist/converted/sd15-onnx/unet.onnx.data",
    "models/shortlist/converted/sd15-onnx/vae_encoder.onnx",
    "models/shortlist/converted/sd15-onnx/vae_encoder.onnx.data",
    "models/shortlist/converted/sd15-onnx/vae_decoder.onnx",
    "models/shortlist/converted/sd15-onnx/vae_decoder.onnx.data",
    "models/shortlist/converted/sd15-onnx/tokenizer/tokenizer.json",
    "models/shortlist/converted/sd15-onnx/tokenizer/tokenizer_config.json",
]

EXPECTED_SHAPES = {
    "text_encoder.onnx": {
        "inputs": {"input_ids": [1, 77]},
        "outputs": {"last_hidden_state": [1, 77, 768]},
    },
    "unet.onnx": {
        "inputs": {
            "sample": [2, 4, 64, 64],
            "timestep": [1],
            "encoder_hidden_states": [2, 77, 768],
        },
        "outputs": {"sample_out": [2, 4, 64, 64]},
    },
    "vae_encoder.onnx": {
        "inputs": {"image": [1, 3, 512, 512]},
        "outputs": {"latent_mean": [1, 4, 64, 64]},
    },
    "vae_decoder.onnx": {
        "inputs": {"latents": [1, 4, 64, 64]},
        "outputs": {"image": [1, 3, 512, 512]},
    },
}


class CheckContext:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.warnings: list[str] = []

    def ok(self, message: str) -> None:
        print(f"[ok] {message}")

    def warn(self, message: str) -> None:
        self.warnings.append(message)
        print(f"[warn] {message}")

    def fail(self, message: str) -> None:
        self.failures.append(message)
        print(f"[fail] {message}")


def default_repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def extract_dims(value: object) -> list[int | str]:
    dims: list[int | str] = []
    tensor_shape = value.type.tensor_type.shape.dim
    for dim in tensor_shape:
        if dim.HasField("dim_value"):
            dims.append(dim.dim_value)
        elif dim.HasField("dim_param"):
            dims.append(f"dyn:{dim.dim_param}")
        else:
            dims.append("?")
    return dims


def load_shape_map(model_path: Path) -> tuple[dict[str, list[int | str]], dict[str, list[int | str]]]:
    import onnx  # Imported lazily so the script still works before dependencies are installed.

    model = onnx.load(str(model_path), load_external_data=False)
    inputs = {value.name: extract_dims(value) for value in model.graph.input}
    outputs = {value.name: extract_dims(value) for value in model.graph.output}
    return inputs, outputs


def check_files(root: Path, relative_paths: list[str], ctx: CheckContext, *, required: bool) -> bool:
    missing = []
    for relative_path in relative_paths:
        file_path = root / relative_path
        if file_path.exists():
            ctx.ok(relative_path)
        else:
            missing.append(relative_path)

    if not missing:
        return True

    for relative_path in missing:
        message = f"missing file: {relative_path}"
        if required:
            ctx.fail(message)
        else:
            ctx.warn(message)
    return False


def check_onnx_shapes(root: Path, ctx: CheckContext) -> None:
    if importlib.util.find_spec("onnx") is None:
        ctx.warn("Python package 'onnx' is not installed; ONNX shape verification skipped")
        return

    onnx_dir = root / "models/shortlist/converted/sd15-onnx"
    for file_name, expected in EXPECTED_SHAPES.items():
        model_path = onnx_dir / file_name
        if not model_path.exists():
            continue

        actual_inputs, actual_outputs = load_shape_map(model_path)

        for value_name, expected_dims in expected["inputs"].items():
            actual_dims = actual_inputs.get(value_name)
            if actual_dims == expected_dims:
                ctx.ok(f"{file_name} input {value_name}: {actual_dims}")
            else:
                ctx.fail(
                    f"{file_name} input {value_name}: expected {expected_dims}, got {actual_dims}"
                )

        for value_name, expected_dims in expected["outputs"].items():
            actual_dims = actual_outputs.get(value_name)
            if actual_dims == expected_dims:
                ctx.ok(f"{file_name} output {value_name}: {actual_dims}")
            else:
                ctx.fail(
                    f"{file_name} output {value_name}: expected {expected_dims}, got {actual_dims}"
                )


def check_qnn_sdk(root: Path, ctx: CheckContext, require_qnn_sdk: bool) -> None:
    qnn_sdk_root = os.environ.get("QNN_SDK_ROOT", "").strip()
    if not qnn_sdk_root:
        message = "QNN_SDK_ROOT is not set"
        if require_qnn_sdk:
            ctx.fail(message)
        else:
            ctx.warn(message)
        return

    sdk_path = Path(qnn_sdk_root).expanduser()
    if not sdk_path.is_absolute():
        sdk_path = (root / sdk_path).resolve()

    if sdk_path.is_dir():
        ctx.ok(f"QNN_SDK_ROOT={sdk_path}")
    else:
        ctx.fail(f"QNN_SDK_ROOT points to a missing directory: {sdk_path}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate the WSL-side SD1.5/QNN preparation status.")
    parser.add_argument("--repo-root", type=Path, default=default_repo_root())
    parser.add_argument("--require-qnn-sdk", action="store_true")
    parser.add_argument("--skip-onnx-shapes", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = args.repo_root.expanduser().resolve()
    ctx = CheckContext()

    print(f"repo_root={repo_root}")
    check_files(repo_root, REQUIRED_SOURCE_FILES, ctx, required=True)
    check_files(repo_root, RECOMMENDED_DIFFUSERS_FILES, ctx, required=False)
    onnx_ready = check_files(repo_root, REQUIRED_ONNX_FILES, ctx, required=True)

    if onnx_ready and not args.skip_onnx_shapes:
        check_onnx_shapes(repo_root, ctx)

    check_qnn_sdk(repo_root, ctx, args.require_qnn_sdk)

    print()
    print(f"failures={len(ctx.failures)} warnings={len(ctx.warnings)}")
    if ctx.failures:
        print("status=not-ready")
        return 1

    if ctx.warnings:
        print("status=ready-with-warnings")
    else:
        print("status=ready")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
