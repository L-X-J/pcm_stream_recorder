## 0.0.3

- 新增音频焦点管理功能（AudioFocusManager），用于在从 WebRTC 等通信场景返回后恢复音频播放
- Android 平台添加 MODIFY_AUDIO_SETTINGS 权限支持
- 优化音频焦点重置逻辑，确保从通信场景返回后能立即恢复媒体播放

## 0.0.2

- 修复 iOS 上录音时耳机输入输出未正确路由的问题，支持蓝牙/有线耳机自动切换。
- 插件更名为 `pcm_stream_recorder`，新增 PCM 实时回调与 WAV 录音描述。

## 0.0.1

- 初始发布，提供基础录音能力。
