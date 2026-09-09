# Implementation Plan: 全球 IM 渠道清单（国内 + 出海，C 端 + B 端）

**Branch**: `026-im-channel-roadmap`（规划；落地按波次开独立分支）  
**Date**: 2026-09-08  
**Status**: PLAN（未宣称各渠道已实现）  
**对照契约**: [017 入站契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)、[004 出站 Notify](../004-notify-outbound/plan.md)

## Summary

OryxOS 入站已有飞书 / 企微 / 钉钉 / Slack / Discord（+ CLI）。出站 Notify 已有 webhook / 飞书 / 企微 / 钉钉 / email，**没有** Slack / Discord / 海外通道。

本 PLAN 把剩余刚需渠道一次排进清单，不挑 C/B、不挑国内/出海：国内企业要用国内 IM；出海项目要对欧美员工通道和客户通道。原则不变——**新增 IM = 新 `oryxos-channel-*` 模块 + 工厂注册，编排语义仍只在 `InboundMessageService`**。唯一允许的 core 一次性补齐是：**入站 HTTP Webhook 接收面** + `ChannelConfig` 可选扩展字段（Teams / WhatsApp / Google Chat 凭证多于 `appId/appSecret` 两格）。

## 为什么都算刚需

| 使用方 | 没有这些渠道时的缺口 |
|--------|----------------------|
| 国内企业内部 | 飞书/企微/钉钉已覆盖 |
| 国内企业出海（员工协作） | Slack / Teams / Google Chat；社区与研发常用 Discord / Telegram |
| 国内企业出海（对客 / C 端） | WhatsApp（欧美主客服通道）；Telegram 作辅通道 |
| 欧盟私有化 / 数据不出域 | Mattermost、Matrix/Element |
| Agent 主动推送 | 入站能聊但 `notify` 缺 Slack/Discord/海外，定时任务与告警回不了欧美频道 |

## 全量清单

图例：✅ 已有 · 🔶 部分（入站有 / 出站无或反之） · ⬜ 未做 · — 不做

### A. 已交付（巩固，不重做入站）

| ID | 渠道 | 模块 | 入站 | `notify` 出站 | 人群 | 连接 |
|----|------|------|------|---------------|------|------|
| A1 | 飞书 / Lark | `oryxos-channel-feishu` | ✅ | ✅ 群机器人 webhook | 国内 B + Lark 出海 | 长连接 |
| A2 | 企业微信 | `oryxos-channel-wecom` | ✅ | ✅ | 国内 B | 长连接 |
| A3 | 钉钉 | `oryxos-channel-dingtalk` | ✅ | ✅ | 国内 B | Stream 长连接 |
| A4 | Slack | `oryxos-channel-slack` | ✅ | ⬜ | 欧美 B 科技/中大型 | Socket Mode |
| A5 | Discord | `oryxos-channel-discord` | ✅ | ⬜（手册写明本期不做） | 欧美社区 / 研发 | Gateway WSS |
| A6 | CLI | `oryxos-channel-cli` | ✅ | — | 本机调试 | stdin |
| A7 | 通用 webhook / email | `oryxos-tool` | — | ✅ | 任意系统 | HTTP / SMTP |

### B. 本 PLAN 承诺交付（全排入）

| ID | 渠道 | 建议模块 | 入站 | `notify` | 主场景 | 连接 | 波次 |
|----|------|----------|------|----------|--------|------|------|
| P0a | Slack notify | `oryxos-tool`（`SlackNotifyAdapter`） | 已有 | ⬜→✅ | 任务/告警打回 Slack | Bot Token REST 或 Incoming Webhook | **0** |
| P0b | Discord notify | `oryxos-tool`（`DiscordNotifyAdapter`） | 已有 | ⬜→✅ | 任务/告警打回 Discord | Bot Token REST | **0** |
| P0c | 入站 Webhook 接收面 | `oryxos-core` + `oryxos-web` | 平台能力 | — | 给必须回调的渠道共用 | `POST /api/v1/channels/inbound/{name}` 验签后归一化 | **0** |
| P0d | `ChannelConfig.extra` | `oryxos-core` | 配置 | — | WhatsApp phone_number_id、Teams tenant 等 | YAML 可选 map，仍 `${ENV}` | **0** |
| B1 | Telegram | `oryxos-channel-telegram` | ⬜ | ⬜ | 欧盟个人 / 出海运营 / C 辅 | **长轮询**（免公网 URL） | **1** |
| B2 | WhatsApp Cloud API | `oryxos-channel-whatsapp` | ⬜ | ⬜ | 欧美对客 C / SMB 客服 | **Webhook 必选** + Graph 发信 | **2** |
| B3 | Microsoft Teams | `oryxos-channel-teams` | ⬜ | ⬜ | 欧美传统企业 / 政务 M365 | Azure Bot + Webhook（或 Graph） | **3** |
| B4 | Google Chat | `oryxos-channel-gchat` | ⬜ | ⬜ | Workspace 企业（不用 Slack/Teams 的那批） | HTTP + 可选 Pub/Sub | **4** |
| B5 | Mattermost | `oryxos-channel-mattermost` | ⬜ | ⬜ | 欧盟私有化、国内出海私有部署 | WS / REST，近 Slack | **5** |
| B6 | Matrix / Element | `oryxos-channel-matrix` | ⬜ | ⬜ | 欧盟主权云、开源协作 | Client-Server API + sync | **5** |

### C. 明确不做（写进清单以免膨胀）

| 渠道 | 原因 |
|------|------|
| Signal / iMessage | 无可用官方机器人面 |
| Facebook Messenger | 与 WhatsApp 同属 Meta，审核与体验更差；先 WhatsApp |
| Webex / Zoom Chat / Twist | 存量不够 |
| Line / Kakao / 微信个人号 | 非本清单欧美+国内企业主路径；个微无合规 Bot |
| IRC | 仅演示，不当产品渠道 |

## 场景对照（刚需怎么覆盖）

```text
国内员工 ────────── 飞书 / 企微 / 钉钉          ✅
出海员工（科技）── Slack + Discord             入站✅ 出站⬜（P0）
出海员工（传统）── Teams + Google Chat         ⬜ B3/B4
出海对客 C 端 ──── WhatsApp + Telegram         ⬜ B2/B1
私有化 / 不出域 ── Mattermost + Matrix         ⬜ B5/B6
主动推送 ───────── 各渠道 notify + 已有 email  P0 起补齐
```

同一 Agent 可绑多条 `channels.yaml` 条目（一应用一 Agent）。出海项目典型绑法：

- `ops-slack` + `ops-teams` + `ops-telegram`（员工）
- `cs-whatsapp`（客服，另绑客服 Agent，会话窗口 24h 必须写进该 Agent 正文）

## 架构约束（沿 017 / 宪法）

1. 适配器只做「平台协议 ↔ `InboundMessage`」+ `start/stop/status/sendReply`。  
2. 去重、私聊会话、群无状态、失败文案、审计、处理中提示 **禁止**在渠道模块复制。  
3. 凭证只走 `${ENV}`，不落盘明文。  
4. 出站 URL 先 `sandbox.enforce(HTTP_REQUEST)`。  
5. 同步 + 虚拟线程；渠道 SDK 若只有 callback，在适配器边界转同步。  
6. 每个新渠道：归一化单测 + 引用 `InboundMessageService` 契约测试档 + `docs/*ChannelSetup.md` + `channels.yaml.example` 注释条目。  
7. **群消息仍只处理 @Bot**（A1），与飞书/Discord 一致；WhatsApp 无群 @ 则按官方「业务会话」规则单独立项写进该模块 README。

## 波次 0：平台补齐（先做，否则 B2–B4 会各写一套验签 HTTP）

**目标**：后续 webhook 渠道只注册 `type` + Normalizer + Sender。

| 项 | 做法 |
|----|------|
| 入站 Webhook | `oryxos-web` 增加受控入口；按 `channel name` 找适配器；适配器实现可选接口 `InboundWebhookHandler`（验签、挑战握手、拆事件）。未实现该接口的渠道 404。默认不暴露；需配置公开 HTTPS 或反代。 |
| 配置扩展 | `ChannelConfig` 增加 `Map<String,String> extra`（raw 保留 `${}`，resolved 才展开）。现有两字段渠道行为不变。 |
| Slack / Discord notify | 与飞书 NotifyAdapter 同级：`type: slack` / `type: discord`；配置 `url` 或 bot token + `channel_id`。补沙箱域名：`slack.com`、`discord.com`。 |

**验收**：契约测试「未知渠道 webhook → 404」；Slack/Discord `notify` 各一条假 HTTP 单测；飞书/企微/钉钉入站回归绿。

## 波次 1：Telegram（最快海外第二条）

- Bot API `getUpdates` 长轮询，对齐 Slack/Discord「免回调」。  
- 私聊 / 群 `@Bot`；图片文件走 `getFile` 落盘；语音走现有 Whisper。  
- Notify：`sendMessage` 到 `chat_id`。  
- 文档：`docs/TelegramChannelSetup.md`。  
- **验收**：`CONNECTED`；私聊往返；群 @ 回复带 `replyToMessageId`；`notify` 打进指定 chat。

## 波次 2：WhatsApp Cloud API（C 端 / 出海客服）

- 依赖 P0 webhook：Meta 订阅校验 + 签名。  
- Graph `/{phone-number-id}/messages` 发信；遵守 **24h 会话窗**（超时只允许模板消息——适配器拒绝违规发送并回可读错误，不静默）。  
- 商业验证、WABA、模板预审是**环境前置**，PLAN 不伪造已过 Meta 审核。  
- **验收**：验签挑战通过；会话内文本往返；窗外发送失败点名；媒体落盘（图/语音）。

## 波次 3：Microsoft Teams（欧美 B 端标配）

- Azure Bot / Graph；入站走 P0 webhook。  
- 凭证：`app_id` + `app_secret` + `extra.tenant_id`。  
- 自适应卡片不当 MVP；先做纯文本 + 引用。  
- **验收**：Teams 1:1 与频道 @Bot 各一条往返；`notify` 打到 team/channel。

## 波次 4：Google Chat

- Chat API + HTTP 端点（或 Cloud Pub/Sub，二选一写进 research）。  
- Workspace 管理控制台挂 Bot。  
- **验收**：DM / 空间 @Bot 往返；`notify` 进空间。

## 波次 5：Mattermost + Matrix（私有化）

- Mattermost：WebSocket 近似 Slack，移植成本低于 Teams。  
- Matrix：`sync` + 发信；homeserver URL 进 `extra`。  
- **验收**：自建测试实例上私聊/房间 @ 往返；`notify` 进房间。可与客户环境联调后再标生产就绪。

## 模块与仓库位置

```text
oryxos-core/channel/     # extra 字段 + 可选 InboundWebhookHandler
oryxos-web/              # POST /api/v1/channels/inbound/{name}
oryxos-tool/notify/      # SlackNotifyAdapter DiscordNotifyAdapter
                         # 及后续 Telegram/WhatsApp/Teams/GChat/Mattermost/Matrix
oryxos-channel-telegram/
oryxos-channel-whatsapp/
oryxos-channel-teams/
oryxos-channel-gchat/
oryxos-channel-mattermost/
oryxos-channel-matrix/
docs/*ChannelSetup.md
config/channels.yaml.example
website/zh/docs/tool.md / profile.md   # 渠道表同步
```

不在 core 为每个厂商写 if/else。

## 风险与依赖

| 风险 | 处理 |
|------|------|
| WhatsApp / Teams 必须公网 HTTPS | P0 文档写清反代与验签；本机可用 ngrok **仅开发**，生产禁止把隧道写进默认配置 |
| Meta / Azure / Google 审核周期 | 代码与审核并行；未过审不宣称该渠道 COMPLETE |
| 24h 会话窗 / 模板消息 | WhatsApp 适配器硬校验，Agent 正文写约束 |
| `ChannelConfig` 两字段不够 | P0 加 `extra`，一次 core diff，后续渠道零 core |
| 渠道过多拖质量 | 波次串行；每波 `mvn verify` + 一篇 Setup；不平行六条半成品 |
| Discord 已能演示 | 不替代 Telegram/WhatsApp/Teams，人群不同 |

## 完成定义（整份清单，不是官方产品 COMPLETE）

- [ ] P0：Webhook 接收面 + `extra` + Slack/Discord notify，测试绿  
- [ ] B1 Telegram 入站 + notify + Setup  
- [ ] B2 WhatsApp 入站 + notify + 会话窗错误 + Setup  
- [ ] B3 Teams 入站 + notify + Setup  
- [ ] B4 Google Chat 入站 + notify + Setup  
- [x] B5 Mattermost 入站 + notify + Setup（本机 Docker 2026-09-09）  
- [x] B6 Matrix 入站 + notify + Setup（本机 Synapse + Element 2026-09-09）  
- [ ] 网站文档渠道表与 `channels.yaml.example` 与上表一致  
- [ ] 不把「未过 Meta/Azure 审核的演示」写成生产就绪  

## 建议开工顺序（刚需仍要排队）

1. 开分支做 **P0**（平台 + 已有欧美渠道出站）。  
2. **Telegram**（与 Discord 对称，最快可对外）。  
3. **WhatsApp** 与 **Teams** 按客户哪边先签约并行准备审核，代码仍建议 WhatsApp 先于 Teams（Bot API 比 Azure 短）。  
4. Google Chat。  
5. Mattermost → Matrix。

下一步若开工，先落 P0 的 `research.md`（Webhook 验签、`extra` 字段兼容、Slack Incoming Webhook vs `chat.postMessage` 二选一）。
