#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


PROFILE_FILES = {
    "sd15": {
        "text_encoder_bin": "models/text_encoder.bin",
        "text_encoder_lib": "models/text_encoder.so",
        "unet_bin": "models/unet.bin",
        "unet_lib": "models/unet.so",
        "vae_encoder_bin": "models/vae_encoder.bin",
        "vae_encoder_lib": "models/vae_encoder.so",
        "vae_decoder_bin": "models/vae_decoder.bin",
        "vae_decoder_lib": "models/vae_decoder.so",
        "tokenizer_json": "tokenizer/tokenizer.json",
        "tokenizer_config": "tokenizer/tokenizer_config.json",
        "qnn_system": "qnn/lib/libQnnSystem.so",
        "qnn_htp": "qnn/lib/libQnnHtp.so",
    },
    "video-demo": {
        "stylizer": "models/sd15_img2img.bin",
        "depth": "models/depth_anything_v2_small.bin",
        "interpolator": "models/rife46_lite.bin",
    },
}

PROFILE_DEFAULTS = {
    "sd15": {
        "bundle_id": "localmotion-sd15-v1-sm8650",
        "display_name": "LocalMotion SD15 V1 SM8650",
        "workload": "sd15",
    },
    "video-demo": {
        "bundle_id": "localmotion-video-v1-sm8650",
        "display_name": "LocalMotion Video V1 SM8650",
        "workload": "video-demo",
    },
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(args: argparse.Namespace) -> dict:
    bundle_dir = args.bundle_dir.resolve()
    required_files = PROFILE_FILES[args.profile]
    defaults = PROFILE_DEFAULTS[args.profile]
    files = []
    for role, relative_path in required_files.items():
        file_path = bundle_dir / relative_path
        if not file_path.exists():
            raise FileNotFoundError(f"missing required file: {relative_path}")
        files.append(
            {
                "relativePath": relative_path,
                "url": f"{args.base_url.rstrip('/')}/{relative_path}",
                "sha256": sha256(file_path),
                "sizeBytes": file_path.stat().st_size,
                "role": role,
                "required": True,
            }
        )

    extra_files = sorted(
        path
        for path in bundle_dir.rglob("*")
        if path.is_file()
        and path.relative_to(bundle_dir).as_posix() not in required_files.values()
        and path.name != "runtime-manifest.json"
    )
    for file_path in extra_files:
        relative_path = file_path.relative_to(bundle_dir).as_posix()
        files.append(
            {
                "relativePath": relative_path,
                "url": f"{args.base_url.rstrip('/')}/{relative_path}",
                "sha256": sha256(file_path),
                "sizeBytes": file_path.stat().st_size,
                "role": "support",
                "required": True,
            }
        )

    return {
        "bundleId": args.bundle_id or defaults["bundle_id"],
        "displayName": args.display_name or defaults["display_name"],
        "version": args.version,
        "workload": defaults["workload"],
        "backend": "qnn",
        "minSoc": args.min_soc,
        "supportedSocs": [value.strip() for value in args.supported_socs.split(",") if value.strip()],
        "createdAt": args.created_at,
        "files": files,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate LocalMotion runtime-manifest.json")
    parser.add_argument("--profile", choices=sorted(PROFILE_FILES.keys()), default="sd15")
    parser.add_argument("--bundle-dir", type=Path, required=True, help="Directory containing models/ and support files")
    parser.add_argument("--base-url", required=True, help="Public base URL used by the Android downloader")
    parser.add_argument("--bundle-id", default=None)
    parser.add_argument("--display-name", default=None)
    parser.add_argument("--version", required=True)
    parser.add_argument("--min-soc", default="SM8650")
    parser.add_argument("--supported-socs", default="SM8650,SM8650P")
    parser.add_argument("--created-at", default="")
    parser.add_argument("--output", type=Path, default=None)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    manifest = build_manifest(args)
    payload = json.dumps(manifest, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(payload + "\n", encoding="utf-8")
    else:
        print(payload)


if __name__ == "__main__":
    main()
