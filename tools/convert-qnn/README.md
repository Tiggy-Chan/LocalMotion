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

## 当前运行时 Bundle 约定

现有 Android app 仍然沿用视频路线的 bundle 结构，默认期待：

- `models/sd15_img2img.bin`
- `models/depth_anything_v2_small.bin`
- `models/rife46_lite.bin`
- 可选支持文件放在 `qnn/` 或 `assets/`

这只是当前 app 的占位协议，不代表首个 NPU 实例必须按这个结构走。

如果下一步先落地 `SD1.5 txt2img/img2img`，更合理的做法是单独定义一套 `sd15-v1` bundle。

## 生成 `runtime-manifest.json`

```bash
python3 tools/convert-qnn/package_runtime_bundle.py \
  --bundle-dir /path/to/runtime_bundle \
  --base-url https://example.com/localmotion/video-v1 \
  --version 0.1.0 \
  --output /path/to/runtime_bundle/runtime-manifest.json
```

或：

```bash
bash tools/convert-qnn/export_sm8650.sh /path/to/runtime_bundle https://example.com/localmotion/video-v1 0.1.0
```

## 这个仓库不会提交的内容

- QNN SDK / Qualcomm AI Runtime SDK
- 转换后的 QNN 权重
- Qualcomm 许可约束下的运行时库

这些产物需要放在仓库外部，通过 release 资产、私有文件服务器或本地导入方式交付。
