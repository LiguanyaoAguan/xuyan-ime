# 序言输入法 · Xuyan IME  v1.0

> 一款 Android 上的**纯语音输入法**：本地离线识别、边说边出字、可拖到任意位置的悬浮胶囊。
> 不做键盘，只做语音。

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)]()
[![License](https://img.shields.io/badge/License-Apache--2.0-blue)]()
[![No Gradle](https://img.shields.io/badge/build-aapt2%2Bjavac%2Bd8-orange)]()

---

## 它是什么

一个**体积极小、纯语音**的输入法：

```
        ┌──────────────────────────────┐
        │  ( 🎤 )   ( ↵ )  ( ⌫ )  ( ⌃ ) │   ← 216×56dp 胶囊，可拖到任何位置
        └──────────────────────────────┘
```

- **点麦克风**：开始录音，说话时文字**实时**出现在目标输入框
- **按住麦克风**：按住说话，松手即结束（对讲机式）
- **松手后**：用小模型出来的草稿会被**准确模型重新识别**并替换
- **点 ⌃**：展开功能面板（撤销 / 剪贴板 / 语言 / 设置）
- **长按 ⌃**：直接拖动胶囊
- 位置**永久记住**，下次唤醒还在原地

## 和普通输入法的区别

| | 普通输入法 | 序言 |
|---|---|---|
| 形态 | 占满屏幕底部 | 一个可拖动的小胶囊，**App 画面完全不位移** |
| 输入方式 | 键盘 | 只有语音 |
| 文字在哪 | 输入法自己的界面 | **只在目标 App 的输入框里** |
| 网络 | 多数要联网 | 默认**全离线**，音频不出设备 |

## 技术要点

### 双窗口结构（这是做到"不顶画面"的关键）

```
IME 窗口（高度 0px）──── 只提供 InputConnection，不占任何视觉空间
      +
悬浮窗窗口（TYPE_APPLICATION_OVERLAY）──── 画那颗胶囊，可随意摆放
```

多数输入法只能钉在底部，是因为 `SoftInputWindow` 默认 `MATCH_PARENT × MATCH_PARENT` 并 `gravity=BOTTOM`。本项目的做法是：

1. 把 IME 窗口压到 **0 高度**（系统判定键盘不可见 → App 不位移）
2. 胶囊用 **`SYSTEM_ALERT_WINDOW`** 单独开窗（可任意定位）
3. 没有悬浮窗权限时自动降级为「窗口即胶囊」，功能不丢

> 踩过的坑：只把输入视图设 1px 而窗口仍全屏，会让 WebView 被压成白屏。

### 双模型

| 模型 | 体积 | 作用 |
|---|---|---|
| SenseVoice int8 | ~239MB | **最终文字**，离线整段识别 |
| streaming-zipformer-small-ctc-zh int8 | ~26MB | **边说边出字**的草稿 |

草稿用 `commitText` 逐字落定 + `setComposingText` 保留尾巴；
松手后把整段草稿撤回，用 SenseVoice 的结果重新写入。

### 安全换行

`commitText("\n")` 在 WebView 里会被翻译成 **Enter 按键事件**，对「Enter = 发送」的应用是灾难。所以：

- 目标包名含 `harness` → 发 **Shift+Enter**
- 其它应用 → `commitText("\n")`
- **任何情况都不模拟裸 Enter**，识别结束也绝不自动追加回车

## 构建（不需要 Gradle）

本机工具链：`aapt2 + javac + d8 + apksigner`，十几秒出包。

```bash
# 需要：JDK 21、Android build-tools、sherpa-onnx 的 Java classes
./build.sh          # 产物在 dist/VoiceIME-1.0.0-debug.apk
```

需要的依赖文件（**不入库**，见 `.gitignore`）：

| 文件 | 用途 | 获取方式 |
|---|---|---|
| `tools/android.jar` | 编译期 Android API | 从 Android SDK platform 取 |
| `tools/sherpa-classes.jar` | sherpa-onnx Java 类 | `sherpa-onnx` Android AAR 解包 |
| `tools/kotlin-stdlib.jar` | sherpa API 依赖 | Maven |
| `tools/debug.keystore` | 调试签名 | 自行生成 |
| `jni/arm64-v8a/*.so` | sherpa-onnx 原生库 | 从 sherpa-onnx Android AAR 解包复制 |

## 模型（不入库）

模型文件体积大且有自己的许可证，请自行下载后放进 App 私有目录：

```bash
# 最终识别
sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17
  → /data/user/0/<pkg>/files/models/{model.int8.onnx,tokens.txt}

# 流式草稿
sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01
  → /data/user/0/<pkg>/files/models-stream/{model.int8.onnx,tokens.txt}
```

两个模型都来自 [k2-fsa/sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases)。

## 权限

| 权限 | 必需 | 说明 |
|---|---|---|
| `RECORD_AUDIO` | ✅ | 录音 |
| `SYSTEM_ALERT_WINDOW` | 推荐 | 胶囊自由摆放；没有则退化为底部模式 |
| `INTERNET` | 可选 | 仅云端识别/AI 润色时使用 |

## 可选的云端能力

- **云端识别**：任何 OpenAI 兼容的 `chat/completions` + `input_audio` 接口（示例：小米 MiMo `mimo-v2.5-asr`）
- **AI 润色**：识别后把文字交给大模型修错别字、补标点、去口癖（只发文字，不发录音）

两者都需要用户自己填 API Key，默认关闭。

## 目录

```
app/            源码与资源
  src/dev/dsh/voiceime/
    VoiceImeService.java   IME 主服务：状态机、上屏、窗口
    VoicePadView.java      胶囊与面板的全部绘制（Canvas 手绘）
    AsrEngine.java         离线识别（SenseVoice）
    StreamingAsr.java      流式草稿（异步解码线程）
    CloudAsr.java          云端识别客户端
    CloudPolish.java       AI 润色客户端
    Recorder.java          16kHz 录音
    AsrSettings.java       配置
    MainActivity.java      语音控制中心（三栏）
build.sh         免 Gradle 构建脚本
jni/             sherpa-onnx 原生库
```

## 已知限制

- 没有实时上屏之外的流式后端（云端 ASR 是一次性上传）
- 历史记录 / 语言切换 未实现
- 只有深色主题
- 窄屏手机未做单栏适配

## License

Apache-2.0
