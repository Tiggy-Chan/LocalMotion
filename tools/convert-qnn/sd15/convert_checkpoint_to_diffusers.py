#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

from diffusers.pipelines.stable_diffusion.convert_from_ckpt import (
    download_from_original_stable_diffusion_ckpt,
)


def main() -> int:
    parser = argparse.ArgumentParser(description="Convert a local SD1.5 checkpoint into a diffusers pipeline.")
    parser.add_argument("--checkpoint", required=True, help="Path to the local .safetensors checkpoint.")
    parser.add_argument("--original-config", required=True, help="Path to the original v1-inference.yaml config.")
    parser.add_argument("--output-dir", required=True, help="Output directory for the diffusers pipeline.")
    parser.add_argument("--extract-ema", action="store_true", help="Prefer EMA weights when available.")
    args = parser.parse_args()

    checkpoint = Path(args.checkpoint).expanduser().resolve()
    original_config = Path(args.original_config).expanduser().resolve()
    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    pipeline = download_from_original_stable_diffusion_ckpt(
        checkpoint_path_or_dict=str(checkpoint),
        original_config_file=str(original_config),
        from_safetensors=True,
        extract_ema=args.extract_ema,
        scheduler_type="ddim",
        image_size=512,
        num_in_channels=4,
        upcast_attention=False,
        load_safety_checker=False,
    )
    pipeline.save_pretrained(output_dir)
    print(output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
