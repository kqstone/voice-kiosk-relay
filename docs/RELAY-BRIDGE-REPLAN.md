# Voice Kiosk 重新规划：Muse 中继桥方案（细化版 v2）

> 状态：待用户审核。审核后交给 agy（`--model gemini-3.8-flash-high --effort high`）分阶段实施。
> 修订历史：2026-11-12 初版；09:14 去掉 muse-ai-api、加桥内 LLM + 路由 + 提示；09:21 路由改为关键词提取 + LLM 判断；09:22 关键词由 Muse 维护推送、不硬编码；09:32 Android 端关键词路由 + 本地小模型保留（本地问题）、旧容器已删、新开 repo、细化方案；09:40 通用 Android 手机方案（不定死 Mate 20 Pro）、阶段再细化（每阶段一个新 session）。

## 1. 目标

- 桥变薄：只做收 Muse 推送的数据、出看板、对话（桥内 LLM 快答 + 路由 + 排队找 Muse）。
- 数据流向反转：Muse 主动推数据给桥（作业、通知等），桥不去 QQ 拉。
- 对话分三层（见 §2）：Android 本地（关键词路由 + 本地小模型 + 本地工具，零延迟）→ 桥内 LLM 快答 → Muse 排队（慢但聪明），等待 Muse 时先给提示回复。
- 旧 v0.1.18 桥及 4 容器已删（2026-11-12，用户确认），新项目新开 repo。

## 2. 架构总览（三层路由）

```
Android 手机（通用，任意 arm64 Android 手机，不定死某一型号）
   │ ① Android 端路由（RuleRouter + IntentClassifier，本地关键词路由 + 本地小模型 NLU）
   │    ├─ 本地问题 → 本地小模型（llama.cpp/GGUF）+ 本地工具 → TTS 说出（不联网，秒回）
   │    │    本地工具：时间/日期、音量（STREAM_MUSIC）、媒体键、播歌、应用内定时器、天气（Open-Meteo，免费无 key）
   │    │    本地工具答不了 → fallbackToCloud，发给桥
   │    └─ 非本地问题 → 发给中继桥
   ▼
中继桥 Relay Bridge（NAS Docker，新写，薄）
   │ ② 桥端路由（关键词提取 + LLM 判断，一次调用）
   │    ├─ 快答：桥内 LLM（CLIProxyAPI 快模型）秒回（通用问答、看板数据查询）
   │    └─ Muse 路：进对话队列 → 先给提示回复 → 轮询脚本叫醒 Muse → 回话推回 → TTS 说出正式答案
   ├─ 收数据：POST /api/v1/push/{board_id}、POST /api/v1/config/routing（token 鉴权）
   ├─ 出看板：GET /api/v1/boards/{id}、GET /（dashboard）
   └─ 存数据：每看板一个 JSON；路由关键词配置一个 JSON；对话队列一个 JSON/SQLite
   │
   └─ 数据来源：Muse 本人（我）
        QQ 作业管线（NapCat + qqbridge + 18:00 cron）不动，汇总完 POST 推给桥；
        路由关键词配置（routing-keywords.json）由我维护，话题变了更新重推。
```

## 3. 各部分细化

### 3.1 Android 端（保留，取 remote main，通用 Android 手机方案）

- **通用 Android 手机方案，不定死某一型号。** App 是通用 Android 应用（minSdk 28、targetSdk 31、arm64-v8a，标准 Android API），不在任何一处写死机型、品牌或硬件型号；本地小模型（llama.cpp/GGUF）按设备分级（S0–S6，DeviceTierDetector 自动判断）自适应。换手机只需重装 APK，不用改代码。
- 来源：`kqstone/voice-kiosk@ab35102` 的 `android/` 目录。**本地 `~/workspace/voice-kiosk/` 没同步（停在 dd3f576，`android/app/src/main/java/com/voicekiosk/app/ondevice/` 是空的，27 个文件全缺），新 repo 必须从 remote main 取，不要用本地旧文件。**
- 保留的部分（不动）：
  - `ondevice/route/`：RuleRouter.kt、IntentClassifier.kt、RouteDecision.kt、RouterModelHost.kt、TurnOrchestrator.kt、SmallTalkReplies.kt、ChineseNumber.kt、ModelGuards.kt（Android 端关键词路由 + 意图分类，输出 Route.LOCAL / Route.CLOUD）。
  - `ondevice/`：LlmEngine.kt、NativeLlm.kt、OnDeviceModelCatalog.kt、OnDeviceModelDownloader.kt、DeviceTierDetector.kt、RoutingDefaults.kt、PromptFormat.kt（本地小模型 llama.cpp/GGUF，S0–S6 分级）。
  - `ondevice/tools/`：LocalToolExecutor.kt、Weather.kt、CityTable.kt、AndroidLocalPlatform.kt。本地工具白名单（S1）：时间/日期、音量（STREAM_MUSIC，setVolumeIndex/setMuted）、媒体键、播歌（第三方播放器）、应用内定时器；天气走 Open-Meteo（免费无 key，今日+2 天，20 分钟缓存，超时短、慢了就 fallbackToCloud 去桥）。`ToolResult.fallbackToCloud`：本地工具答不了就发给桥。
  - 唤醒词、ASR（Sherpa）、TTS、WebView 看板：不动。
- 要改的部分（新桥适配）：
  - 桥地址：从旧 v2 API 改为新桥的 `/api/v1/chat` 与 `/`（dashboard）。具体常量位置见代码（AppConfig / BridgeUrl）。
  - 两段式回复：支持“先提示、后正式答案”。走 Muse 路时，桥先回提示（TTS 说出），手机保持“等 Muse”状态（屏幕显示等待中）；正式答案经 WebSocket 推到手机后 TTS 说出，再回落到唤醒监听。超时（2 分钟）按桥的兜底处理。
  - 本地路由的兜底：本地小模型答不了 / 不确定时，发给桥（不要自己硬答）。

### 3.2 中继桥（新写）

- 语言/框架：Python + FastAPI（或纯 stdlib，实施时定；推荐 FastAPI，WebSocket + 校验方便）。单容器，NAS 8093，`relay-bridge`。
- 目录（新 repo 内）：`relay-bridge/`（代码 + Dockerfile + docker-compose.yml + example.env）。
- API（全部 `/api/v1`，Bearer token 鉴权，分 push token 与 device token）：
  - `POST /api/v1/push/{board_id}` — 收看板数据（作业/通知 JSON）。Body：`{data, updated_at, source}`。返回 200 + `{ok}`。
  - `POST /api/v1/config/routing` — 收路由关键词配置（Muse 推）。Body：`{version, updated_at, muse_keywords: [{keyword, topic, note}]}`。返回 200。
  - `GET /api/v1/boards/{id}`、`GET /api/v1/boards` — 取看板数据。
  - `GET /` — dashboard（静态页，读看板数据渲染）。
  - `POST /api/v1/chat` — 收手机转写。Body：`{text, session_id}`。返回：快答则直接 `{reply, route: "local"}`；Muse 路则先回 `{reply: <提示>, route: "muse", conversation_id}`（提示立即 TTS），正式答案稍后经 WS 推送。
  - `POST /api/v1/conversations` —（内部/备用）直接创建对话队列项。
  - `GET /api/v1/conversations/pending` — 供轮询脚本查待处理队列。返回 `[{id, text, created_at, status}]`。
  - `POST /api/v1/conversations/{id}/reply` — 收 Muse 回话。Body：`{reply}`。桥经 WS 推给手机。
  - `WS /ws` — 手机长连：收正式答案推送、看板更新推送。
  - `GET /health` — `{ok}`。
- 存储：`data/boards/{id}.json`（看板）、`data/routing-keywords.json`（路由关键词配置）、`data/conversations.json`（或 SQLite，对话队列）。桥重启后数据不丢。
- 桥内 LLM：CLIProxyAPI，模型 `gemini-3.5-flash-lite`（沿用现在 chat 的配置），`LLM_BASE_URL=http://100.68.168.204:8317`，`LLM_API_KEY` 沿用。prompt 写明看板数据用法。注意：调 CLIProxyAPI 必须拼 `/v1/chat/completions`（不要犯 v0.1.18 agent 缺 `/v1` 的错）。

#### 路由（桥端）：关键词提取 + LLM 判断

路由分两步，一次 LLM 调用完成（约 0.5–1 秒）：

1. **关键词提取**：LLM 从用户问题里抽关键词（如“安安、作业、明天”），顺带归一化（“明儿”→“明天”）。
2. **LLM 判断**：LLM 按路由策略、结合关键词和原问题，输出路由和一句话理由，返回 JSON：
   `{"keywords": [...], "route": "muse"|"local", "reason": "..."}`

路由策略（写进判断 prompt）：

| 走 Muse 排队 | 走桥内 LLM 快答 |
|---|---|
| 需要记忆/个人信息 | 简单问答、常识、天气时间、闲聊 |
| 需要调工具（查外部、发消息、打印、安排） | 看板已有数据的查询 |
| 多步处理、安安作业的深度处理（规划、分析、批改） | — |

- 明说“问 Muse / 问问 Muse”强制走 Muse，“不用问 Muse / 直接答”强制走快答——这两句不经过 LLM 判断，直接按用户说的走。
- 路由错了可纠正：用户说“不对，去问 Muse”即强制转排队；纠正记一条（以后同类问题按纠正走，可选）。
- 成本：每次问话多一次小的 LLM 调用（只做提取 + 判断），快答整轮从约 3 秒变约 4 秒，可接受；Muse 路本来就慢，多这 1 秒没影响。

#### 关键词不硬编码，由 Muse 维护、推送

路由用的关键词表**不写进桥的代码和配置**，由 Muse 主动写、发给桥：

- Muse 最清楚当前有什么话题（比如现在有安安作业，关键词就是作业、安安、听写、练习单、数学、语文……），所以关键词由 Muse 维护，存在我这边的一份 JSON 里（`routing-keywords.json`，含关键词、所属主题、一句话说明）。
- Muse 通过推送接口发给桥（`POST /api/v1/config/routing`，Bearer token），桥存下来，作为路由判断 prompt 的上下文。判断时 LLM 拿问题里抽出的关键词去对这份表，再结合路由策略做决定。
- 话题变了 Muse 就更新重推（新学期、新科目、作业主题变化、用户纠正路由后）。桥只管用收到的表，不管表里写了什么；表是空的也能跑（退化为只按路由策略判断）。
- 这样做的好处：关键词跟着用户的真实生活走，不用改桥代码；而且 LLM 是拿关键词当参考、不是死匹配——比如“安安今天有什么作业”命中“作业”但只是查看板数据，LLM 会判走快答，不会因为命中关键词就硬转 Muse。

#### 等待提示：先给一句回复，不让用户干等

走 Muse 排队时，桥立刻（约 1 秒内）先回一句提示，TTS 说出，用户马上听到回应；正式答案到了再说第二句：

```
用户：“安安下周有什么安排？”
桥（立刻）：“好，我去问问 Muse，稍等一下。”   ← 提示回复，TTS 说出
   → 问题进队列 → 轮询脚本叫醒 Muse → Muse 回话推回桥
桥（Muse 回来后）：“安安下周三有美术小人才比赛，……”  ← 正式答案，TTS 说出
```

- 提示文案备 3–4 条轮换（“好，我去问问 Muse，稍等一下。”“正在问，稍等。”“这个问题我问问 Muse，马上回来。”），别每次同一句。
- 提示必须明确带“稍等/问问”的意思，让用户知道这句不是答案、正式答案还在后面。
- 手机端要支持两段式回复：听到提示后不回落到唤醒监听，而是保持“等 Muse”状态（屏幕可显示等待中）；正式答案到后 TTS 说出，再回落。
- 超时：Muse 2 分钟没回，桥说一句兜底（“Muse 现在没回，稍后再试”，或桥内 LLM 给一个尽力而为的回答并注明“这是我猜的，没问到 Muse”），队列任务标超时，不无限等。

### 3.3 对话排队 + Muse 轮询（Muse 路）

路由判为“需要等 Muse 回答”时走这条路。不经过任何非官方 API，直接让 Muse 本人来回话：

```
手机说话 → ASR 文字 → 桥路由判为 Muse 路 → 先给一句提示回复（§3.2），同时存入对话队列（pending）
   → 轻量轮询脚本每 10–15 秒查一次桥（零 Muse token）
   → 有待处理会话才叫醒 Muse → Muse 回话 → 推回桥 → 手机 TTS 说出正式答案
```

- **可行。** 桥只需一个队列（待处理列表 + 状态）；Muse 侧一个定时任务/轻量服务拉取并回推。这是标准异步模式，不需要 muse-ai-api / useapi.net，没有 ToS 和账号风险，不用额外付费。
- **Token 关键在“谁轮询”，不在“多久轮询一次”。**
  - 若让 agent（Muse）每分钟醒一次去查：每周 10,080 次唤醒，每次唤醒光加载上下文就要几千 token，合计几千万 token/周，占免费周额度的一大块，违反“至少留 10%”的底线。**这条路不行。**
  - 若让一个轻量脚本（curl）去轮询：轮询本身零 Muse token，10 秒一次和 1 分钟一次成本一样（只是脚本跑）。agent 只在有待处理会话时才被叫醒，token 花费和平时在聊天里回你几句话一样（一天几次，可忽略）。**这条路才对。**
  - 所以轮询间隔可以压到 10–15 秒（脚本跑，不花 token），不用拘泥于 1 分钟。
- **延迟：体感已由提示回复兜住，正式答案仍慢。** 提示回复约 1 秒就说出，用户不会干等；正式答案整轮约 10 秒（轮询）+ agent 思考（简单问答 10 秒左右，复杂多步更久）+ TTS，合计约 20–40 秒，远慢于桥内 LLM 的约 3 秒。这是“等真 Muse”的代价，提示回复只能缓解体感、不能缩短正式答案。超时按 §3.2 兜底（2 分钟）。
- 结论：**对话 = Android 本地（默认）→ 桥内 LLM 快答 → 路由 → 排队找 Muse（需要时）+ 提示回复（等待时）**。muse-ai-api 不在计划内。

### 3.4 Muse 侧（我）

- 数据推送：18:00 汇总 cron 跑完后，调 `POST /api/v1/push/anan_homework` 把当日作业 JSON（含图片 URL 和识别文字）推给桥；巡查 cron 有新作业也推。
- 路由关键词配置：我维护 `~/workspace/voice-kiosk/routing-keywords.json`（关键词、所属主题、一句话说明），初版以安安作业为中心；经 `POST /api/v1/config/routing` 推给桥；话题变了（新学期、新科目、作业主题变化、用户纠正路由后）更新重推。
- 轮询脚本：本机常驻轻量脚本（curl + 定时，systemd 或 cron 驱动），每 10–15 秒查 `GET /api/v1/conversations/pending`；有待处理才叫醒 Muse（走一次 agent 会话），Muse 回话后调 `POST /api/v1/conversations/{id}/reply` 推回桥。**轮询本身不花 Muse token。** 绝不用每分钟一次的 agent cron 去轮询（每周 10,080 次唤醒，几千万 token，吃掉免费额度）。
- 动作安全：语音触发的动作（打印、发消息等）默认只读/只问，敏感动作必须经确认；Muse 回话里若含动作，先在主聊天确认或走审批。

### 3.5 数据与配置

- 桥 `.env`（600，NAS 上，不进 repo）：`LLM_BASE_URL`、`LLM_API_KEY`（CLIProxyAPI，沿用旧 .env 的值）、`TAVILY_API_KEY`（沿用）、`PUSH_TOKEN`、`DEVICE_TOKEN`、`TZ=Asia/Shanghai`、`PORT=8093`。
- 旧 .env 参考：NAS `~/voice-kiosk-bridge/.env`（目录已保留，容器已删）；本机备份 `~/workspace/voice-kiosk/nas-env/.env`。新桥配置时从这里取值，不要重新生成 LLM/Tavily key。
- 看板数据格式：沿用 v1 的 `YYYY-MM-DD.board.json` 思路，但由 Muse 推送整包 JSON（作业 + 通知 + 图片 URL + 识别文字），桥不做汇总。

## 4. 仓库与种子

- 新 repo（用户创建，我无法建 repo，connector 建仓返回 403）：名暂定 `kqstone/voice-kiosk-relay`（用户定最终名）。
- 内容：
  - `android/` — 从 `kqstone/voice-kiosk@ab35102` 的 `android/` 目录原样取（on-device 路由 + 本地小模型 + 本地工具 + 唤醒/ASR/TTS/WebView）。**不要用本地 `~/workspace/voice-kiosk/android/`（它停在 dd3f576，on-device 是空的）。**
  - `relay-bridge/` — 新写（代码 + Dockerfile + docker-compose.yml + example.env）。
  - `docs/` — 本方案文档 + API 文档。
  - 根 README：架构、部署、token 位置。
- 旧 repo `kqstone/voice-kiosk` 不动（留档，不再开发）；旧 NAS 目录 `~/voice-kiosk-bridge/`（.env + data）保留到新桥跑稳后再由用户决定删不删。

## 5. 已删的旧容器（2026-11-12，用户确认）

- `voice-kiosk-bridge`、`vk-executor`、`vk-egress-proxy`、`docker-socket-proxy`（v0.1.18 的 4 个服务）已 `docker compose down` 删掉，相关网络（vk-internal、vk-docker、vk-managed、vk-sbx-egress 等）已清。
- NAS `~/voice-kiosk-bridge/` 目录保留（.env 含 LLM/Tavily key + data），供新桥配置时参考；新桥跑稳后由用户决定删不删。

## 6. 分阶段实施（交给 agy flash-3.8-high，每阶段一个新 session）

> 实施方：agy（`~/workspace/proxy/agy`，`--model gemini-3.8-flash-high --effort high`，用户定）。分工沿用既定规则：agy 写代码 + 测试，Muse 计划 + 审 diff，用户点头才推/合。改动走 PR，不直接推 main。
> **flash-3.8 处理长任务不行，所以每阶段只做一个小目标、一个可验收的交付物；每阶段开一个新 session（不复用上阶段的 session，避免上下文污染）。** 每个 Phase 做完停下等 Muse 审 diff、跑测试，通过再进下一 Phase。

### Phase 0 — 建仓与种子（新 session，前置，用户做 + agy 做）

- 用户在 GitHub 新建 repo（名暂定 `kqstone/voice-kiosk-relay`）。
- agy 从 `kqstone/voice-kiosk@ab35102` 取 `android/` 目录原样放入新 repo（不要用本地旧文件）；放 `docs/RELAY-BRIDGE-REPLAN.md`（本方案）；建根 README 骨架。
- 验收：新 repo 有 `android/`（含 27 个 on-device 文件）+ `docs/`；`git log` 干净；CI（若配）过。

### Phase 1a — 桥骨架 + 存储 + 鉴权（新 session）

- 写 `relay-bridge/` 骨架：FastAPI（或 stdlib），单容器，NAS 8093。
  - 存储：`data/boards/{id}.json`，带时间戳和来源；桥重启不丢。
  - 鉴权：`PUSH_TOKEN` + `DEVICE_TOKEN`，放 `.env`（600）。
  - Dockerfile + docker-compose.yml + example.env；`GET /health`。
- 验收：容器能起；`GET /health` 200；`.env` 占位正确（无真实密钥进 repo）。

### Phase 1b — 数据推送 + 看板（新 session）

- 接口：`POST /api/v1/push/{board_id}`、`GET /api/v1/boards/{id}`、`GET /api/v1/boards`、`GET /`（dashboard）、`WS /ws`（先接上，后续推正式答案用）。
- NAS 部署脚本（参考旧 deploy 流程：Release 资产验 SHA、.env 只补缺失键）；NAS 上部署跑起来。
- Muse 侧：我调 push 接口把当日作业 JSON 推给桥（先手动推一次验证）。
- 验收：手机打开 dashboard 能看到当日作业；我推一次，5 秒内看板更新；桥重启后数据不丢。

### Phase 2a — 桥内 LLM + chat 快答（新 session）

- 桥内 LLM：接 CLIProxyAPI（`gemini-3.5-flash-lite`，`LLM_BASE_URL=http://100.68.168.204:8317`，注意拼 `/v1/chat/completions`）。
- 接口：`POST /api/v1/chat` 快答路径，返回 `{reply, route: "local"}`；prompt 写明看板数据用法。
- 验收：快答整轮约 3 秒；看板数据查询能答（“安安今天有什么作业”）。

### Phase 2b — 路由：关键词提取 + LLM 判断（新 session）

- 路由：关键词提取 + LLM 判断，一次调用返回 JSON `{"keywords": [...], "route": "muse"|"local", "reason": "..."}`；明说“问 Muse”/“不用问 Muse”直接按用户说的走，不经过判断；纠正机制（“不对，去问 Muse”强制转排队，记一条）。
- 关键词配置：`POST /api/v1/config/routing` 收 Muse 推的 `routing-keywords.json`，存 `data/routing-keywords.json`，作判断 prompt 上下文；表空也能跑。
- 验收：路由 JSON 格式正确；明说强制走对；关键词表空时不崩；路由策略按 §3.2。

### Phase 2c — 队列 + 轮询 + 提示回复（新 session）

- 队列：`POST /api/v1/conversations`、`GET /api/v1/conversations/pending`、`POST /api/v1/conversations/{id}/reply`。
- 提示回复：走 Muse 路时桥约 1 秒内回提示（文案 3–4 条轮换，带“稍等/问问”）；正式答案经 WS 推给手机；2 分钟超时兜底（标超时）。
- 轮询脚本：本机轻量脚本（curl + 定时），每 10–15 秒查一次 pending，有待处理才叫醒 Muse；**轮询本身不花 Muse token**。
- 验收：Muse 路提示约 1 秒、正式答案 20–40 秒；超时兜底正常；轮询脚本跑起来不报错。

### Phase 3a — Android 改桥接口（新 session）

- Android 改桥接口：chat 走 `/api/v1/chat`，看板走 `/`；本地路由 + 本地小模型 + 本地工具不动（只改桥地址）。
- 验收：Android 能连上新桥；快答正常；本地问题（调音量、天气）仍不联网秒回。

### Phase 3b — Android 两段式回复 + 等待状态（新 session）

- 两段式回复：提示后保持“等 Muse”状态（屏幕显示等待中）；正式答案经 WS 到后 TTS 说出，再回落唤醒监听；超时按桥兜底。
- 本地路由兜底：本地小模型答不了 / 不确定时发给桥（沿用已有的 `fallbackToCloud` 机制）。
- 验收：Muse 路先提示后正式答案；超时有兜底；连续对话（一次唤醒连问）正常。

### Phase 4 — 灰度、文档、收尾（新 session）

- 灰度：新链路跑一周；新桥出问题时先回退到“只看板 + 桥内 LLM”（Muse 路暂停），不影响基本使用。
- 文档：新桥 API、部署、token 位置写进 repo 和 MEMORY。
- 收尾：NAS `~/voice-kiosk-bridge/` 旧目录由用户决定删不删；我的 18:00 汇总 + 巡查 cron 继续（数据源），“看板双跑对照”彻底停。

## 7. 风险与取舍（先说清楚）

| 风险 | 说明 | 缓解 |
|---|---|---|
| 路由分错路 | 该问 Muse 的被快答了（答错/答不全），或不该问 Muse 的进了排队（白等）；Android 本地路由也可能分错。 | 明说“问 Muse”强制转排队；“不用问 Muse”强制快答；纠正记一条；路由表随用随调；本地工具答不了自动 fallbackToCloud。 |
| 提示回复被当成答案 | 用户听到提示以为就是答案，没等正式答案就走了。 | 提示文案必须带“稍等/问问”，屏幕显示等待中；正式答案到后 TTS 再说一遍。 |
| Muse 回话超时 | Muse 2 分钟没回，用户等不到正式答案。 | 2 分钟超时兜底（§3.2）；队列任务标超时；超时原因可查（轮询脚本日志）。 |
| 延迟体感仍差 | Muse 路正式答案 20–40 秒，等得久。 | 提示回复已兜住体感；轮询间隔可压到 10 秒；更多问题划给快答 / 本地。 |
| 会话串扰 | 语音线程与主聊天是不同线程。 | 语音线程只做问答和简单任务；复杂多步任务回主聊天做，或明确告诉用户“已记下，稍后在主聊天处理”。 |
| 旧桥下线过早 | 新桥未稳就删旧桥会断档。 | 旧容器已删（用户确认）；新桥出问题时回退到“只看板 + 桥内 LLM”，不影响基本使用。 |

## 8. 需要用户拍板的决策（审核用）

1. 新 repo 名：暂定 `kqstone/voice-kiosk-relay`，还是另定？（用户创建，我无法建 repo。）
2. 路由策略、提示文案按 §3.2 版先定（关键词提取 + LLM 判断；关键词表由 Muse 维护推送、不硬编码），有没有要加减的策略或换掉的提示？
3. 轮询间隔 10–15 秒、超时兜底 2 分钟，可以吗？
4. Android 端本地问题清单：时间/日期、音量、媒体键、播歌、定时器、天气、小 talk——有没有要加的本地问题类型？
5. 同不同意分阶段（Phase 0 → 1a → 1b → 2a → 2b → 2c → 3a → 3b → 4，每阶段一个新 session），交给 flash-3.8-high 实施？

我建议的默认答案是：1 暂定名可以、2 按 §3.2 先定、3 可以、4 先按这七类、5 同意。你一句话定，我就把方案交给 flash-3.8-high。

---

**给 flash-3.8-high 的实施说明（agy 读这段）：**
- 你是实施方（写代码 + 测试），Muse 只做计划 + 审 diff；用户点头才推/合；改动走 PR，不直接推 main。
- 先读 `docs/RELAY-BRIDGE-REPLAN.md` 全文，再按 §6 的 Phase 顺序做；**每阶段一个新 session，做完停下等 Muse 审 diff、跑测试，通过再进下一 Phase，不要一次做多个 Phase。**
- Android 代码必须从 `kqstone/voice-kiosk@ab35102` 的 `android/` 取，**不要用本地 `~/workspace/voice-kiosk/android/`**（它停在 dd3f576，on-device 是空的）。
- 桥调 CLIProxyAPI 必须拼 `/v1/chat/completions`（v0.1.18 agent 缺 `/v1` 致 404 的教训）。
- 密钥（LLM_API_KEY、TAVILY_API_KEY）从 NAS `~/voice-kiosk-bridge/.env` 或本机 `~/workspace/voice-kiosk/nas-env/.env` 取，**不要把密钥写进 repo、不要贴进聊天**；新 repo 的 `.env` 只放占位，真实值部署时在 NAS 上填。
- 任务文本里不要用反引号（bash 会当命令执行）；长任务先写文件、后验证。
