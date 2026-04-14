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

## 当前推荐入口

如果你是在 WSL / Linux x64 上补环境，优先直接跑：

```bash
bash tools/convert-qnn/sd15/prepare_wsl_sd15.sh
```

它会：

1. 创建 `.venv-sd15`
2. 先安装 CPU-only 的 `torch`，再安装 `requirements.txt`
3. 在缺文件时自动补做导出
4. 运行一次 `check_wsl_sd15_qnn.py`

如果只想单独核对仓库状态：

```bash
python tools/convert-qnn/sd15/check_wsl_sd15_qnn.py
```

## 当前已经验证过的 QNN 中间产物

在 `2026-03-26` 这台 WSL 主机上，下面这条链路已经实测通过：

- `text_encoder.onnx -> QNN cpp/bin/json`
- `unet.onnx -> QNN cpp/bin/json`
- `vae_encoder.onnx -> QNN cpp/bin/json`
- `vae_decoder.onnx -> QNN cpp/bin/json`

仓库里新增了自动化脚本：

```bash
bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh
```

默认行为：

1. 读取 `models/shortlist/converted/sd15-onnx/`
2. 输出到 `models/shortlist/converted/sd15-qnn/`
3. 为四个子模型生成：
   - `*.cpp`
   - `*.bin`
   - `*_net.json`
4. 继续生成 `x86_64-linux-clang` 的 `model.so`

可选参数：

```bash
bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh --skip-model-lib
bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh --models unet,vae_decoder
bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh --float-bitwidth 16 --float-bias-bitwidth 16
bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh --with-android-lib
bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh --probe-htp-context
```

说明：

- `--with-android-lib` 只有在本机存在 `ndk-build` 时才会尝试 `aarch64-android`
- 当前这台 WSL 还没有 Android NDK，所以默认只验证 `x86_64-linux-clang`
- `--probe-htp-context` 会在生成 `x86_64` model lib 后，继续尝试 `libQnnHtp.so` 的离线 context 生成，并把日志写到每个模型目录下

## Android Runtime Bundle Staging

当 `aarch64-android` 的 `model.so` 已经生成后，可以直接把手机端 runtime bundle 组出来：

```bash
bash tools/convert-qnn/sd15/stage_runtime_bundle.sh /path/to/runtime_bundle
```

默认会从下面这些位置取文件：

- `models/shortlist/converted/sd15-qnn/<model>/<model>.bin`
- `models/shortlist/converted/sd15-qnn/<model>/model_libs/aarch64-android/lib<model>.so`
- `models/shortlist/converted/sd15-onnx/tokenizer/`
- `$QNN_SDK_ROOT/lib/aarch64-android/`

输出结构会包含：

- `models/text_encoder.bin` 和 `models/text_encoder.so`
- `models/unet.bin` 和 `models/unet.so`
- `models/vae_encoder.bin` 和 `models/vae_encoder.so`
- `models/vae_decoder.bin` 和 `models/vae_decoder.so`
- `tokenizer/tokenizer.json`
- `tokenizer/tokenizer_config.json`
- `qnn/lib/libQnnSystem.so`
- `qnn/lib/libQnnHtp.so`
- 额外的 `libQnnHtp*.so` / calculator stub / `qnn-net-run`

再配合下面这条命令生成 `runtime-manifest.json`：

```bash
bash tools/convert-qnn/export_sm8650.sh /path/to/runtime_bundle https://example.com/localmotion/sd15-v1 0.1.0
```

## QAIRT 主机环境要求

为了让 `qnn-onnx-converter` 的形状推理和 `onnxsim` 正常工作，当前这套 `QAIRT 2.44` 在 WSL 上至少需要：

```bash
python3.10 -m venv .venv-qairt
source .venv-qairt/bin/activate
python -m pip install --upgrade pip
python -m pip install \
  'numpy<2' \
  onnx==1.16.1 \
  onnxruntime==1.17.1 \
  onnxsim==0.4.36 \
  PyYAML \
  packaging \
  pandas \
  typing_extensions \
  pydantic
source .env.qnn
```

`numpy 2.x` 会让 `onnxruntime 1.17.1` 直接失配，导致 QAIRT 退回到不完整的图简化路径。

## 主机边界

导出和验证最好拆成两种主机：

- 任意能稳定跑 Python 导出的机器，适合：

- 建 Python 虚拟环境
- `safetensors -> diffusers`
- `diffusers -> fixed-shape ONNX`

- `WSL / Linux x64` 更适合继续完成：

- QNN SDK 标准离线编译
- ORT QNN EP 的 x64 量化工作流
- 最终 Android HTP 上的正式 context 产物生成

## 首次导出步骤

### 1. 创建虚拟环境

```bash
python3 -m venv .venv-sd15
source .venv-sd15/bin/activate
pip install --upgrade pip
pip install --index-url https://download.pytorch.org/whl/cpu --extra-index-url https://pypi.org/simple torch
pip install -r tools/convert-qnn/sd15/requirements.txt
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

当前已确认的后续阻塞：

- `text_encoder` 虽然能转成 QNN `cpp/bin`，但直接生成 `HTP context` 时会卡在 `Gather/Select`
- `vae_decoder` 在 float / fp16 图上生成 `HTP context` 时会卡在 `QNN_GroupNorm`
- `aarch64-android` 的 `model.so` 需要 WSL 本地先装 Android NDK

更完整的路线说明看：

- `docs/sd15-first-npu.md`
- `docs/wsl-sd15-qnn-checklist.md`
