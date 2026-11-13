# ASR 模型（不入库）

当前模型：`sherpa-onnx-streaming-paraformer-bilingual-zh-en`（中英双语，int8）

| 文件 | 大小 | 说明 |
|---|---|---|
| `encoder.int8.onnx` | 158MB | 编码器 |
| `decoder.int8.onnx` | 69MB | 解码器（paraformer，无 joiner） |
| `tokens.txt` | 74KB | 8404 条，含 26 个拉丁字母（已入库） |

## 为什么两个 .onnx 不在 git 里

227MB 的二进制一旦进提交就永久留在 `.git` 里，切分支、clone 都会变慢，而且这个
分支可能被丢弃。`.gitignore` 里加了 `app/src/main/assets/asr/*.onnx`。

## 怎么补回来

```bash
curl -L -o para.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2
tar -xjf para.tar.bz2
cp sherpa-onnx-streaming-paraformer-bilingual-zh-en/encoder.int8.onnx \
   sherpa-onnx-streaming-paraformer-bilingual-zh-en/decoder.int8.onnx \
   sherpa-onnx-streaming-paraformer-bilingual-zh-en/tokens.txt .
```

换模型必须同步改 `VoiceAssets.ASR_MODEL_TAG`，否则手机上旧的解压目录会被当成
"已解压"跳过，跑的还是旧模型。
