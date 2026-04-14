# LocalMotion Architecture

## Current Boundary

LocalMotion 目前还是一个 Android 原型工程。

已经存在的主线是：

1. Android App 能下载和安装运行时 bundle
2. native sidecar 能通过 `/health` 报告当前运行时是否完整
3. `SD1.5` 的 `checkpoint -> diffusers -> fixed-shape ONNX` 准备链路已经落在仓库里

当前还没有提交到仓库里的部分是 Qualcomm SDK 约束下的真实 QNN 推理核心。

这也是为什么当前第一条真实 NPU 路线不是视频，而是更小、更可控的 `SD1.5 txt2img/img2img`。

## Runtime Layers

- `app/`
  Android 端 Compose UI、服务、bundle 下载与本地文件管理。
- `native/`
  sidecar 进程，暴露 `/health`、`/generate_image`、`/cancel`。如果构建时设置了 `QNN_SDK_ROOT`，`native/CMakeLists.txt` 会打开 `LOCALMOTION_QNN_AVAILABLE=1`。
- `tools/convert-qnn/`
  两条职责并存：一条是 `SD1.5` 导出准备，另一条是运行时 bundle 打包和 `runtime-manifest.json` 生成。
- `models/shortlist/`
  本地候选模型和中间导出物，默认不提交到 Git。
- `third_party/qnn/`
  本地放置 Qualcomm AI Runtime SDK / QNN SDK 的目录，占位目录已预留，但 SDK 本体不会提交。

## SD1.5 First-NPU Flow

推荐先把下面这条链路推通：

1. `v1-5-pruned-emaonly.safetensors -> diffusers pipeline`
2. `diffusers pipeline -> fixed-shape ONNX submodels`
3. `ONNX -> QDQ ONNX`
4. `ORT QNN EP + HTP` 首轮验证
5. `session.disable_cpu_ep_fallback=1` 真 HTP 检查
6. `ep.context_enable=1` context cache 生成

其中前两步已经在仓库里提供了脚本，第三步开始依赖 WSL/Linux x64 与本地 QNN SDK。

## Bundle Boundary

当前 App 已经切到独立的 `sd15-v1` bundle，默认期待：

- `models/text_encoder.bin`
- `models/text_encoder.so`
- `models/unet.bin`
- `models/unet.so`
- `models/vae_encoder.bin`
- `models/vae_encoder.so`
- `models/vae_decoder.bin`
- `models/vae_decoder.so`
- `tokenizer/tokenizer.json`
- `tokenizer/tokenizer_config.json`
- `qnn/lib/libQnnSystem.so`
- `qnn/lib/libQnnHtp.so`
- 其他 `qnn/`、`assets/` 下的支持文件

这样做的目的，是先把首个 `SD1.5 txt2img/img2img` 里程碑和旧视频 demo bundle 解耦。

旧 `video-v1` manifest 仍然可以被识别为 legacy workload，但当前 APK 只接受 `sd15` workload 作为正式安装目标。
