#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


REQUIRED_FILES = {
    "stylizer": "models/sd15_img2img.bin",
    "depth": "models/depth_anything_v2_small.bin",
    "interpolator": "models/rife46_lite.bin",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(args: argparse.Namespace) -> dict:
    bundle_dir = args.bundle_dir.resolve()
    files = []
    for role, relative_path in REQUIRED_FILES.items():
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
        and path.relative_to(bundle_dir).as_posix() not in REQUIRED_FILES.values()
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
        "bundleId": args.bundle_id,
        "displayName": args.display_name,
        "version": args.version,
        "backend": "qnn",
        "minSoc": args.min_soc,
        "supportedSocs": [value.strip() for value in args.supported_socs.split(",") if value.strip()],
        "createdAt": args.created_at,
        "files": files,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate LocalMotion runtime-manifest.json")
    parser.add_argument("--bundle-dir", type=Path, required=True, help="Directory containing models/ and support files")
    parser.add_argument("--base-url", required=True, help="Public base URL used by the Android downloader")
    parser.add_argument("--bundle-id", default="localmotion-video-v1-sm8650")
    parser.add_argument("--display-name", default="LocalMotion Video V1 SM8650")
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
