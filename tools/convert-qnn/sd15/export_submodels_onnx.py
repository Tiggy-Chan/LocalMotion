#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

import torch
from diffusers import StableDiffusionImg2ImgPipeline


class TextEncoderWrapper(torch.nn.Module):
    def __init__(self, text_encoder: torch.nn.Module) -> None:
        super().__init__()
        self.text_encoder = text_encoder

    def forward(self, input_ids: torch.Tensor) -> torch.Tensor:
        return self.text_encoder(input_ids)[0]


class UnetWrapper(torch.nn.Module):
    def __init__(self, unet: torch.nn.Module) -> None:
        super().__init__()
        self.unet = unet

    def forward(
        self,
        sample: torch.Tensor,
        timestep: torch.Tensor,
        encoder_hidden_states: torch.Tensor,
    ) -> torch.Tensor:
        return self.unet(
            sample=sample,
            timestep=timestep,
            encoder_hidden_states=encoder_hidden_states,
        ).sample


class VaeDecoderWrapper(torch.nn.Module):
    def __init__(self, vae: torch.nn.Module) -> None:
        super().__init__()
        self.vae = vae

    def forward(self, latents: torch.Tensor) -> torch.Tensor:
        return self.vae.decode(latents).sample


class VaeEncoderWrapper(torch.nn.Module):
    def __init__(self, vae: torch.nn.Module) -> None:
        super().__init__()
        self.vae = vae

    def forward(self, image: torch.Tensor) -> torch.Tensor:
        return self.vae.encode(image).latent_dist.mean


def export_model(
    model: torch.nn.Module,
    args: tuple[torch.Tensor, ...],
    output_path: Path,
    input_names: list[str],
    output_names: list[str],
    opset: int,
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    model.eval()
    with torch.inference_mode():
        torch.onnx.export(
            model,
            args,
            str(output_path),
            input_names=input_names,
            output_names=output_names,
            opset_version=opset,
            do_constant_folding=True,
        )


def copy_tokenizer_assets(pipeline_dir: Path, output_dir: Path) -> None:
    tokenizer_dir = pipeline_dir / "tokenizer"
    if not tokenizer_dir.exists():
        return
    target_dir = output_dir / "tokenizer"
    if target_dir.exists():
        shutil.rmtree(target_dir)
    shutil.copytree(tokenizer_dir, target_dir)


def main() -> int:
    parser = argparse.ArgumentParser(description="Export fixed-shape SD1.5 submodels to ONNX.")
    parser.add_argument("--pipeline-dir", required=True, help="Local diffusers pipeline directory.")
    parser.add_argument("--output-dir", required=True, help="Directory for ONNX outputs.")
    parser.add_argument("--opset", type=int, default=18)
    parser.add_argument("--height", type=int, default=512)
    parser.add_argument("--width", type=int, default=512)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--guidance-batch", type=int, default=2)
    args = parser.parse_args()

    pipeline_dir = Path(args.pipeline_dir).expanduser().resolve()
    output_dir = Path(args.output_dir).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    pipe = StableDiffusionImg2ImgPipeline.from_pretrained(
        pipeline_dir,
        local_files_only=True,
        safety_checker=None,
        torch_dtype=torch.float32,
    )

    latent_h = args.height // 8
    latent_w = args.width // 8

    text_encoder = TextEncoderWrapper(pipe.text_encoder)
    unet = UnetWrapper(pipe.unet)
    vae_decoder = VaeDecoderWrapper(pipe.vae)
    vae_encoder = VaeEncoderWrapper(pipe.vae)

    input_ids = torch.zeros((args.batch_size, 77), dtype=torch.int64)
    latent_input = torch.zeros((args.guidance_batch, 4, latent_h, latent_w), dtype=torch.float32)
    timestep = torch.tensor([999], dtype=torch.float32)
    hidden_states = torch.zeros((args.guidance_batch, 77, pipe.text_encoder.config.hidden_size), dtype=torch.float32)
    image_input = torch.zeros((args.batch_size, 3, args.height, args.width), dtype=torch.float32)
    decoder_latents = torch.zeros((args.batch_size, 4, latent_h, latent_w), dtype=torch.float32)

    export_model(
        text_encoder,
        (input_ids,),
        output_dir / "text_encoder.onnx",
        ["input_ids"],
        ["last_hidden_state"],
        args.opset,
    )
    export_model(
        unet,
        (latent_input, timestep, hidden_states),
        output_dir / "unet.onnx",
        ["sample", "timestep", "encoder_hidden_states"],
        ["sample_out"],
        args.opset,
    )
    export_model(
        vae_encoder,
        (image_input,),
        output_dir / "vae_encoder.onnx",
        ["image"],
        ["latent_mean"],
        args.opset,
    )
    export_model(
        vae_decoder,
        (decoder_latents,),
        output_dir / "vae_decoder.onnx",
        ["latents"],
        ["image"],
        args.opset,
    )
    copy_tokenizer_assets(pipeline_dir, output_dir)
    print(output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
