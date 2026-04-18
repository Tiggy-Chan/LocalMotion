# WSL 侧 SD1.5 QNN 准备清单

这份清单只做一件事：

把当前仓库整理到“可以继续做 `SD1.5 txt2img/img2img` NPU 路线”的状态。

它不直接完成最终 QNN 推理接入，但会把 WSL 环境、模型文件、ONNX 产物和 QNN SDK 的准备动作走完。

## 完成标志

完成时，至少应满足：

- 仓库源码已在 WSL 中可用
- `SD15-Base` 原始权重已到位
- 固定 shape 的 `sd15-onnx/` 已存在
- Python 虚拟环境已重建
- `QNN_SDK_ROOT` 已指向你本地解压好的 SDK

## 1. 基础依赖

先在 WSL 里安装基础工具：

```bash
sudo apt update
sudo apt install -y \
  python3 \
  python3-venv \
  python3-pip \
  git \
  build-essential \
  cmake \
  ninja-build \
  unzip \
  zip \
  zstd \
  wget \
  curl
```

## 2. 一条命令准备 SD1.5 导出环境

仓库里已经提供了 WSL 侧准备脚本：

```bash
cd /path/to/LocalMotion
bash tools/convert-qnn/sd15/prepare_wsl_sd15.sh
```

这条命令会：

1. 创建 `.venv-sd15`
2. 先安装 CPU-only 的 `torch`，再安装 `tools/convert-qnn/sd15/requirements.txt`
3. 复用已存在的导出物，或在缺失时自动补做：
   - `checkpoint -> diffusers`
   - `diffusers -> ONNX`
4. 最后运行一次仓库内校验脚本

常用参数：

```bash
bash tools/convert-qnn/sd15/prepare_wsl_sd15.sh --skip-pip
bash tools/convert-qnn/sd15/prepare_wsl_sd15.sh --force-diffusers --force-onnx
```

## 3. 单独校验当前状态

如果你已经手动复制过导出物，或者只想做核对，不必重新导出：

```bash
python tools/convert-qnn/sd15/check_wsl_sd15_qnn.py
```

这个脚本会检查：

- `SD15-Base` 原始权重是否存在
- `sd15-onnx/` 核心文件是否齐全，包括 `.onnx` 和 `.onnx.data`
- `tokenizer/` 是否在位
- 如果当前 Python 环境里装了 `onnx`，会继续检查输入输出 shape
- `QNN_SDK_ROOT` 是否已设置，以及目录是否存在

如果你已经放好了 SDK，希望把它也作为必检项：

```bash
python tools/convert-qnn/sd15/check_wsl_sd15_qnn.py --require-qnn-sdk
```

## 4. 需要的最小输入

最少必须有：

- `models/shortlist/candidates/SD15-Base/v1-5-pruned-emaonly.safetensors`
- `models/shortlist/candidates/SD15-Base/v1-inference.yaml`

如果你已经复制了这些目录，准备脚本会直接复用，不会重复导出：

- `models/shortlist/converted/sd15-diffusers/`
- `models/shortlist/converted/sd15-onnx/`

不建议从别的主机复制这些环境目录：

- `.android-sdk/`
- `.toolchains/`
- `.venv-sd15/`
- `.gradle-home/`

## 5. QNN SDK 目录

仓库不会提交 QNN SDK，本地预留目录已经固定为：

```bash
third_party/qnn/
```

把你下载好的 Qualcomm AI Runtime SDK / QNN SDK 解压到这里，例如：

```text
third_party/qnn/<sdk-directory>/
```

然后生成本地环境文件：

```bash
cp .env.qnn.example .env.qnn
```

把 `.env.qnn` 里的 `<sdk-directory>` 改成你的实际目录，再执行：

```bash
source .env.qnn
python tools/convert-qnn/sd15/check_wsl_sd15_qnn.py --require-qnn-sdk
```

`.env.qnn` 默认已加入 `.gitignore`，不建议提交。

## 6. 最后建议跑一遍

```bash
pwd
source .venv-sd15/bin/activate
python --version
echo "$QNN_SDK_ROOT"
ls -lh models/shortlist/candidates/SD15-Base/
ls -lh models/shortlist/converted/sd15-onnx/
python tools/convert-qnn/sd15/check_wsl_sd15_qnn.py --require-qnn-sdk
```

只要这组命令正常，下一步就可以正式进入：

1. ONNX Runtime QNN EP 量化准备
2. `QNNExecutionProvider` 首轮验证
3. `session.disable_cpu_ep_fallback=1` 的真 HTP 检查
4. `ep.context_enable=1` 的 context cache 生成路径

## 7. 当前真实进度

截至 `2026-03-26`，这台 WSL 主机上已经实测完成：

- `checkpoint -> diffusers`
- `diffusers -> fixed-shape ONNX`
- `SD1.5` 四个 ONNX 子模型到 QNN `cpp/bin/json`

对应脚本：

```bash
bash tools/convert-qnn/sd15/convert_onnx_to_qnn.sh
```

当前已经验证通过的子模型：

- `text_encoder`
- `unet`
- `vae_encoder`
- `vae_decoder`

当前还没完全打通的部分：

- 直接生成 `HTP context` 仍有算子阻塞
  - `text_encoder`: `Gather/Select`
  - `vae_decoder`: `QNN_GroupNorm`
- `aarch64-android` 的 `model.so` 还需要本机 Android NDK
- Android app 侧真实 `txt2img` native 推理链路还没有接完

## 8. 现在先不要做的事

在这一步之前，先不要把时间花在这些方向上：

- Android SDK / JDK 重新配置
- 手机 APK 构建
- `LTX-Video`
- `AnimateDiff`
- `Wan`

当前第一目标只有一个：

- 把 `SD1.5 txt2img/img2img` 的 `ONNX -> QNN/HTP` 路线推通
