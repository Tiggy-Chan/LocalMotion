# SD1.5 First-Pass Conversion

这一层只做首个真实 NPU 实例的前置准备：

1. 从 `SD1.5` checkpoint 导出本地 diffusers 管线
2. 产出固定 shape 的 `txt2img / img2img` ONNX 子模型
3. 为后续 Linux / WSL / Windows x64 上的 QNN 编译做输入

## 当前已经完成

本仓库已经生成了：

- `models/shortlist/converted/sd15-diffusers`
- `models/shortlist/converted/sd15-onnx/text_encoder.onnx`
- `models/shortlist/converted/sd15-onnx/unet.onnx`
- `models/shortlist/converted/sd15-onnx/vae_encoder.onnx`
- `models/shortlist/converted/sd15-onnx/vae_decoder.onnx`
- `models/shortlist/converted/sd15-onnx/tokenizer/`

固定 shape 如下：

- `text_encoder`: `input_ids [1, 77]`
- `unet`: `sample [2, 4, 64, 64]`, `timestep [1]`, `encoder_hidden_states [2, 77, 768]`
- `vae_encoder`: `image [1, 3, 512, 512]`
- `vae_decoder`: `latents [1, 4, 64, 64]`

## 当前主机边界

当前工作机是 `macOS ARM`。

这台机器适合：

- 建 Python 虚拟环境
- `safetensors -> diffusers`
- `diffusers -> fixed-shape ONNX`

这台机器不适合直接完成：

- QNN SDK 标准离线编译
- ORT QNN EP 的 x64 量化工作流
- 最终 Android HTP 上的正式 context 产物生成

## 首次导出步骤

### 1. 创建虚拟环境

```bash
python3 -m venv .venv-sd15
source .venv-sd15/bin/activate
pip install --upgrade pip
pip install torch diffusers transformers accelerate safetensors onnx optimum onnxruntime onnxscript
```

### 2. 转成 diffusers 管线

```bash
python3 tools/convert-qnn/sd15/convert_checkpoint_to_diffusers.py \
  --checkpoint models/shortlist/candidates/SD15-Base/v1-5-pruned-emaonly.safetensors \
  --original-config models/shortlist/candidates/SD15-Base/v1-inference.yaml \
  --output-dir models/shortlist/converted/sd15-diffusers
```

### 3. 导出 ONNX 子模型

```bash
python3 tools/convert-qnn/sd15/export_submodels_onnx.py \
  --pipeline-dir models/shortlist/converted/sd15-diffusers \
  --output-dir models/shortlist/converted/sd15-onnx
```

默认导出 `opset 18`。

原因是当前 PyTorch 导出器在 `SD1.5` 的部分 `Resize/Pad` 节点上，把模型从更高 opset 回退到 `17` 时容易出现版本转换问题。

## 下一步不在这台机器上做

继续往 NPU 方向走时，应切到：

- `Linux x86_64`
- `WSL`
- 或 `Windows x64`

然后再做：

1. 量化成 QDQ ONNX
2. 用 `QNNExecutionProvider` + `HTP` 验证
3. 打开 `session.disable_cpu_ep_fallback=1`
4. 打开 `ep.context_enable=1` 生成 context cache

更完整的路线说明看：

- `docs/sd15-first-npu.md`
