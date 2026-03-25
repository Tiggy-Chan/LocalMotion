#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from huggingface_hub import snapshot_download


@dataclass(frozen=True)
class HuggingFaceSpec:
    key: str
    repo_id: str
    relative_dir: str
    allow_patterns: list[str]
    notes: str


HF_SPECS: list[HuggingFaceSpec] = [
    HuggingFaceSpec(
        key="ltx-video-distilled",
        repo_id="Lightricks/LTX-Video",
        relative_dir="candidates/LTX-Video-2B-distilled",
        allow_patterns=[
            "ltxv-2b-0.9.8-distilled.safetensors",
            "README.md",
            "LTX-Video-Open-Weights-License-0.X.txt",
        ],
        notes="先下载 2B distilled 主权重；完整 diffusers 仓库体量过大。",
    ),
    HuggingFaceSpec(
        key="wan-vace-1.3b",
        repo_id="Wan-AI/Wan2.1-VACE-1.3B",
        relative_dir="candidates/Wan2.1-VACE-1.3B",
        allow_patterns=[
            "google/*",
            "config.json",
            "Wan2.1_VAE.pth",
            "diffusion_pytorch_model.safetensors",
            "models_t5_umt5-xxl-enc-bf16.pth",
            "README.md",
            "LICENSE.txt",
        ],
        notes="下载核心推理权重与文本编码器。",
    ),
    HuggingFaceSpec(
        key="animatediff-lightning",
        repo_id="ByteDance/AnimateDiff-Lightning",
        relative_dir="candidates/AnimateDiff-Lightning",
        allow_patterns=[
            "animatediff_lightning_4step_diffusers.safetensors",
            "README.md",
        ],
        notes="选择 4-step diffusers 变体，后续更适合转换。",
    ),
    HuggingFaceSpec(
        key="animatediff-sparsectrl",
        repo_id="guoyww/animatediff",
        relative_dir="candidates/AnimateDiff-SparseCtrl",
        allow_patterns=[
            "mm_sd_v15_v2.ckpt",
            "v3_sd15_sparsectrl_rgb.ckpt",
            "README.md",
            "LICENSE.txt",
        ],
        notes="用于 SD 系图控视频路线。",
    ),
    HuggingFaceSpec(
        key="sd15-base",
        repo_id="stable-diffusion-v1-5/stable-diffusion-v1-5",
        relative_dir="candidates/SD15-Base",
        allow_patterns=[
            "v1-5-pruned-emaonly.safetensors",
            "v1-inference.yaml",
            "README.md",
        ],
        notes="只取精简底模，不拉完整 diffusers 目录。",
    ),
]


def run_git_clone(base_dir: Path) -> dict[str, str]:
    target = base_dir / "candidates" / "FOMM-first-order-model"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        return {
            "status": "exists",
            "path": str(target),
            "notes": "官方仓库已存在。原始 checkpoint 仍需按 README 中的 Google Drive/Yandex 链接单独获取，Qualcomm AI Hub 包通常需要账号操作。",
        }

    subprocess.run(
        [
            "git",
            "clone",
            "--depth",
            "1",
            "https://github.com/AliaksandrSiarohin/first-order-model.git",
            str(target),
        ],
        check=True,
    )
    return {
        "status": "downloaded",
        "path": str(target),
        "notes": "仅下载官方代码仓库。原始 checkpoint 不在 Git 仓库内。",
    }


def dir_size_bytes(root: Path) -> int:
    total = 0
    if not root.exists():
        return total
    for path in root.rglob("*"):
        if path.is_file():
            total += path.stat().st_size
    return total


def download_hf(spec: HuggingFaceSpec, base_dir: Path) -> dict[str, str | int]:
    target = base_dir / spec.relative_dir
    target.mkdir(parents=True, exist_ok=True)
    snapshot_download(
        repo_id=spec.repo_id,
        repo_type="model",
        local_dir=target,
        allow_patterns=spec.allow_patterns,
        max_workers=4,
    )
    return {
        "status": "downloaded",
        "path": str(target),
        "size_bytes": dir_size_bytes(target),
        "notes": spec.notes,
    }


def write_manifest(base_dir: Path, payload: dict[str, object]) -> Path:
    manifest = base_dir / "download-manifest.json"
    manifest.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    return manifest


def load_existing_manifest(base_dir: Path) -> dict[str, object] | None:
    manifest = base_dir / "download-manifest.json"
    if not manifest.exists():
        return None
    return json.loads(manifest.read_text())


def main() -> int:
    parser = argparse.ArgumentParser(description="Download LocalMotion candidate video models.")
    parser.add_argument(
        "--base-dir",
        default="/Volumes/backup/LocalMotion-models",
        help="Target directory for downloaded models.",
    )
    parser.add_argument(
        "--keys",
        nargs="*",
        default=[],
        help="Optional subset of download keys. Defaults to all specs plus fomm-ref.",
    )
    args = parser.parse_args()

    base_dir = Path(args.base_dir).expanduser().resolve()
    base_dir.mkdir(parents=True, exist_ok=True)

    existing = load_existing_manifest(base_dir)
    if existing is None:
        results: dict[str, object] = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "base_dir": str(base_dir),
            "downloads": {},
        }
    else:
        results = existing
        results["generated_at"] = datetime.now(timezone.utc).isoformat()
        results["base_dir"] = str(base_dir)

    downloads = results.setdefault("downloads", {})
    assert isinstance(downloads, dict)

    selected_keys = set(args.keys)
    selected_specs = [spec for spec in HF_SPECS if not selected_keys or spec.key in selected_keys]

    for spec in selected_specs:
        downloads[spec.key] = download_hf(spec, base_dir)

    if not selected_keys or "fomm-ref" in selected_keys:
        downloads["fomm-ref"] = run_git_clone(base_dir)

    manifest = write_manifest(base_dir, results)
    print(json.dumps({"base_dir": str(base_dir), "manifest": str(manifest)}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
