# QNN Conversion Notes

`tools/convert-qnn/` 现在有两部分职责：

1. `SD1.5` 的首轮 `checkpoint -> diffusers -> fixed-shape ONNX`
2. 运行时 bundle 的打包和 `runtime-manifest.json` 生成

## 当前推荐的首个 NPU 实例

不是视频模型，而是：

- `SD1.5 txt2img`
- `SD1.5 img2img`

对应文档：

- `docs/sd15-first-npu.md`
- `docs/wsl-sd15-qnn-checklist.md`
- `tools/convert-qnn/sd15/README.md`

当前仓库已经完成：

- `models/shortlist/candidates/SD15-Base/v1-5-pruned-emaonly.safetensors`
- `models/shortlist/converted/sd15-diffusers`
- `models/shortlist/converted/sd15-onnx`

真正还没做完的是：

- 量化
- QNN/HTP 验证
- context cache 生成
- Android 侧真实 QNN 推理接入

为了把 WSL 侧准备动作固定下来，仓库里新增了：

- `tools/convert-qnn/sd15/prepare_wsl_sd15.sh`
- `tools/convert-qnn/sd15/check_wsl_sd15_qnn.py`
- `tools/convert-qnn/sd15/requirements.txt`

推荐先执行：

```bash
bash tools/convert-qnn/sd15/prepare_wsl_sd15.sh
```

如果只想核对当前仓库状态：

```bash
python tools/convert-qnn/sd15/check_wsl_sd15_qnn.py
```

## 当前运行时 Bundle 约定

当前 Android app 已经切到首个 `sd15-v1` bundle，默认期待：

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

这一步的目的是把运行时协议先和旧视频占位结构解耦。

旧的 `video-v1` 结构仍然能被解析为 legacy workload，但当前 APK 不会把它当成可安装的目标运行时。

## 生成 `runtime-manifest.json`

建议先组装 bundle：

```bash
bash tools/convert-qnn/sd15/stage_runtime_bundle.sh /path/to/runtime_bundle
```

再生成清单：

```bash
python3 tools/convert-qnn/package_runtime_bundle.py \
  --profile sd15 \
  --bundle-dir /path/to/runtime_bundle \
  --base-url https://example.com/localmotion/sd15-v1 \
  --version 0.1.0 \
  --output /path/to/runtime_bundle/runtime-manifest.json
```

或：

```bash
bash tools/convert-qnn/export_sm8650.sh /path/to/runtime_bundle https://example.com/localmotion/sd15-v1 0.1.0
```

## 这个仓库不会提交的内容

- QNN SDK / Qualcomm AI Runtime SDK
- 转换后的 QNN 权重
- Qualcomm 许可约束下的运行时库

这些产物需要放在仓库外部，通过 release 资产、私有文件服务器或本地导入方式交付。
