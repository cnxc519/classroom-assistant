# 第三方组件

- Sherpa-ONNX 1.13.8 Java/JNI：Apache License 2.0，https://github.com/k2-fsa/sherpa-onnx 。
- ONNX Runtime Android（由上述官方发布包内嵌分发）：MIT，https://github.com/microsoft/onnxruntime 。
- Silero VAD 官方模型：MIT，https://github.com/snakers4/silero-vad 。
- KaTeX 0.19.0：MIT，https://github.com/KaTeX/KaTeX ，脚本、样式和字体在 assets/renderer，原许可在同目录 KATEX-LICENSE.txt。
- markdown-it 15.0.2：MIT，https://github.com/markdown-it/markdown-it ，脚本及 MARKDOWN-IT-LICENSE.txt 在 assets/renderer。
- SenseVoiceSmall 原始通用模型的 int8 ONNX 导出（2024-07-17）：由 Sherpa-ONNX 官方发布，来源 https://github.com/FunAudioLLM/SenseVoice ，模型卡 https://huggingface.co/FunAudioLLM/SenseVoiceSmall 。模型使用 FunASR Model Open Source License Agreement，属于自定义模型许可，并非 Apache/MIT；全文及模型卡已附在 assets/FUNASR-MODEL-LICENSE.txt 与 assets/SENSEVOICE-MODEL-CARD.md。遵守完整许可中的使用和再分发条件。

没有修改第三方库或模型权重；应用层实现、音频处理与线程调度由本项目新增。安装包附带上述许可证原文，dependency-hashes.json记录本次下载内容的SHA256，不等同于上游签名验证。本版不再使用Vosk和JNA。
