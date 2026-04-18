# SD1.5 First NPU Milestone

这条路线只解决第一件真正可验证的事：

把 `SD1.5 txt2img/img2img` 的 `ONNX -> QNN/HTP` 路线推通，并且能确认不是 CPU fallback。

## Why SD1.5 First

优先做 `SD1.5` 而不是视频模型，有三个原因：

1. 模型边界清晰，子模型拆分成熟
2. `txt2img / img2img` 的输入输出更容易做固定 shape
3. 出问题时更容易区分是导出、量化、EP 配置还是 HTP 上下文缓存的问题

## Target ONNX Artifacts

当前仓库围绕以下固定 shape 导出：

- `text_encoder`
  - `input_ids [1, 77]`
  - `last_hidden_state [1, 77, 768]`
- `unet`
  - `sample [2, 4, 64, 64]`
  - `timestep [1]`
  - `encoder_hidden_states [2, 77, 768]`
  - `sample_out [2, 4, 64, 64]`
- `vae_encoder`
  - `image [1, 3, 512, 512]`
  - `latent_mean [1, 4, 64, 64]`
- `vae_decoder`
  - `latents [1, 4, 64, 64]`
  - `image [1, 3, 512, 512]`

这些 shape 与仓库里的导出脚本和校验脚本保持一致。

## What The Repository Already Has

- `tools/convert-qnn/sd15/convert_checkpoint_to_diffusers.py`
- `tools/convert-qnn/sd15/export_submodels_onnx.py`
- `tools/convert-qnn/sd15/prepare_wsl_sd15.sh`
- `tools/convert-qnn/sd15/check_wsl_sd15_qnn.py`
- `models/shortlist/candidates/SD15-Base/`
- 示例导出物：
  - `models/shortlist/converted/sd15-diffusers/`
  - `models/shortlist/converted/sd15-onnx/`

## Host Split

推荐把职责拆成两段：

- `macOS ARM` 或任意能稳定跑 Python 导出的机器
  - `checkpoint -> diffusers`
  - `diffusers -> ONNX`
- `WSL / Linux x86_64`
  - QDQ 量化准备
  - `QNNExecutionProvider` 验证
  - `disable_cpu_ep_fallback=1`
  - context cache 生成

## Concrete Next Steps

1. 先完成 [WSL checklist](/home/tiggy/LocalMotion/docs/wsl-sd15-qnn-checklist.md)
2. 基于固定 shape ONNX 做首轮量化
3. 用 ORT QNN EP 跑通 `text_encoder` 和 `vae_decoder`
4. 再扩大到 `unet`
5. 最后再回到 Android 集成与 bundle 协议收敛
