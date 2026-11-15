# voice-kiosk-relay

Generic Android voice kiosk, not tied to a specific phone model.

## Three-Tier Routing

1. Tier 1 (On-Device Local): Rule-based intent matching, local tools, and embedded models for offline low-latency responses.
2. Tier 2 (Bridge LLM Fast Answer): Edge relay service providing fast answers and conversational interactions.
3. Tier 3 (Muse Queue): Asynchronous task enqueueing and deep context execution routed to the Muse backend.

## Documentation

See [RELAY-BRIDGE-REPLAN.md](docs/RELAY-BRIDGE-REPLAN.md) for the full architecture and implementation plan.

## 本机 TTS 与对话音频策略

- **引擎可见性与查询**：在 `AndroidManifest.xml` 中声明 `android.intent.action.TTS_SERVICE` 查询（`<queries>`），确保 Android 11+ (API 30+) 能够发现系统已安装的第三方与原生 TTS 引擎。
- **设置页试听与系统 TTS**：设置页提供系统 TTS 引擎列表选择与实时试听测试，支持系统默认引擎与候选引擎自动回退，完全运行于本地设备，无云端 TTS 依赖。
- **对话时音频焦点与音乐恢复**：
  - 对话唤醒（语音唤醒、界面交互、模拟指令）时，请求瞬态独占音频焦点（`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`，使用 `USAGE_ASSISTANT`），并暂停背景音乐。
  - 对话结束时，按策略仅在由本应用暂停时恢复播放；若用户在会话中主动下达了媒体控制指令（如播放、暂停），则遵从用户意图，不再自动恢复。
  - 定时器/计时器播报仅使用 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 降低背景音量，不中断音乐播放。
- **音乐卡片检测与 TTS 隔离**：
  - 本机 TTS 使用 `AudioAttributes.USAGE_ASSISTANT`；兜底音乐检测仅统计 `USAGE_MEDIA` 和 `USAGE_GAME`，并排除自身应用包名与 UID。
  - 对话进行中及 TTS 播完后 3 秒内处于门控抑制期，兜底音乐检测禁止将状态切为播放中，音乐卡片不会将 TTS 误认作媒体播放。
  - 无通知使用权且检测到第三方媒体播放时，展示精简引导卡片（点击可跳转开启通知使用权）。
