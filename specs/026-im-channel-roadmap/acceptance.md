# 026 验收记录

**日期**: 2026-09-08（WhatsApp 接线 / Mattermost·Matrix 本机往返补记 2026-09-09；对齐与合入补记 2026-09-10；WhatsApp Dashboard 入站补记 2026-09-12）  
**范围**: 波次 0–5 代码 + 契约/归一化单测。真平台按已有凭证推进。  
**合入**: Mattermost/Matrix 入站媒体与 Matrix notify PUT → [PR #432](https://github.com/oryx-labs/oryxos/pull/432)。

## 单测（本机 JDK 21）

| 模块 | 结果 |
|------|------|
| ChannelConfigLoader extra 回写/解析 | 通过（POSIX 权限用例 Windows 跳过，既有） |
| VendorNotifyAdapter（含 slack/discord/telegram/whatsapp/teams/gchat/mattermost/matrix） | 29 通过 |
| ChannelInboundWebhookController（未知渠道 / 非 webhook → 404；挑战回写） | 3 通过 |
| telegram / whatsapp / teams / gchat / mattermost / matrix 契约 + 归一化 | 全部通过 |
| WhatsApp 24h 窗外硬拒绝 + 验签/挑战 | 4 通过 |

全仓 `mvn test` 另有既有失败：Windows 上 `MasterKeyResolverTest`（POSIX 0600）与 `AgentSkillStartupOrderTest`（symlink），与本变更无关。

## 真平台

本机 `.env` 已有 Slack / Discord / Telegram 凭证（不入库）。2026-09-08 探测：

| 项 | 结果 |
|----|------|
| Telegram `getMe` | 通过。Bot 是提交者**个人测试号**（username=`rchuangbot`），不是仓库官方 Bot，凭证只在本机 `.env` |
| Telegram 入站 `CONNECTED` | 本机 `oryxos serve` 后 `GET /api/v1/channels/status` 中 `ops-telegram` 为 `CONNECTED` |
| Telegram 私聊往返 | 通过：个人测试 Bot 收到私聊后建了 `telegram:*:demo-agent` 会话（2 条消息），DeepSeek 已回写 |
| Telegram `notify` | 通过：Bot API `sendMessage` 成功（个人测试会话） |
| Telegram 群 `@Bot` | 通过（2026-09-08）：个人测试 Bot 已拉进测试群，BotFather `/setprivacy` Disable 后移出再拉回；群内 `@rchuangbot test` 引用回复，本机 21:56 DeepSeek 已推理。群聊按契约不落 `sessions` 表（每次 @ 无状态）。未 @ 的群消息不处理 |
| Slack 入站 | 主仓已测并合入：[PR #420](https://github.com/oryx-labs/oryxos/pull/420) Socket Mode `CONNECTED` + 文本往返；[PR #421](https://github.com/oryx-labs/oryxos/pull/421) 图片/文件入站。本机 `ops-slack` 仍为 `CONNECTED`，既有 Slack 会话可回放 |
| Discord 入站 | 主仓已测并合入：[PR #422](https://github.com/oryx-labs/oryxos/pull/422) Gateway 文本 DM / 公会 `@Bot`；[#423](https://github.com/oryx-labs/oryxos/pull/423)/[#425](https://github.com/oryx-labs/oryxos/pull/425)/[#426](https://github.com/oryx-labs/oryxos/pull/426)/[#427](https://github.com/oryx-labs/oryxos/pull/427) 图/文件/语音/视频 soak。本机 `ops-discord` 仍为 `CONNECTED`，既有 Discord 会话可回放 |
| Slack / Discord `notify` | 026 [PR #429](https://github.com/oryx-labs/oryxos/pull/429) 已补适配器；出站路径与入站回复同源（Slack `chat.postMessage` / Discord REST）。不要把后续 `conversations.list` / 列频道探测当成「未打真实频道」 |
| WhatsApp 入站接线 | 本机 `.env` 已有 `WHATSAPP_*`（不入库）。`cs-whatsapp` 绑 `demo-agent` 后 `GET /api/v1/channels/status` 为 `CONNECTED`。本机与 Cloudflare quick tunnel 的 GET 订阅挑战均回写 `hub.challenge`（200） |
| WhatsApp Graph 凭证 | 通过（2026-09-09）：系统用户令牌（`whatsapp_business_messaging` + `whatsapp_business_management`）后 Graph `/{phone-number-id}` HTTP 200，`code_verification_status=VERIFIED` |
| WhatsApp Dashboard 入站 | **通过（2026-09-12）**：Developers → Webhooks → `whatsapp_business_account` 订 `messages`，Callback 指向当前隧道；Dashboard **Send to server** 样本入站成功，落 `whatsapp:*:demo-agent` 会话。样本时间戳过旧触发 **24h 窗硬拒绝回信**（预期，证明编排与出站纪律生效）。未宣称真机 COMPLETE |
| WhatsApp 真机往返 | **挂起，不标 COMPLETE（2026-09-12）**：自有号 Graph `status=PENDING` / `platform_type=NOT_APPLICABLE`；WABA `account_review_status=PENDING`、企业验证 `pending_submission`；Graph `register` 报 **Unverified WABA**。个人开发者无商业主体证照无法完成 Meta 商业注册认证。真机 `hi`/`hello` 未进 webhook。**真机复验前提**：已通过商业注册认证的 WABA + 号 `CONNECTED`。Teams / GChat 真机仍暂停 |
| Mattermost 入站接线 | 本机 Docker `oryxos-mattermost`（`mattermost/mattermost-preview` `:8065`）。`.env` 已有 `MATTERMOST_*`（不入库）。`ops-mattermost` 绑 `demo-agent` 后 `GET /api/v1/channels/status` 为 `CONNECTED`。Outgoing Webhook 回调 `http://host.docker.internal:8080/api/v1/channels/inbound/ops-mattermost`；站点 `AllowedUntrustedInternalConnections` 含 `host.docker.internal`（否则静默 address forbidden）。`http.allowed_domains` 含 `127.0.0.1` / `localhost` |
| Mattermost 房间 `@Bot` 往返 | 通过（2026-09-09）：Town Square `@oryxbot` → DeepSeek 推理 → `POST /api/v4/posts` 回帖（约 23 字）。入站 token 与发帖 PAT 分开（`app_secret` vs `extra.access_token`） |
| Mattermost `notify` | 通过（2026-09-09）：Incoming Webhook 进 Town Square；管理台渠道 `ops-mm-notify`（`type: mattermost`）；`demo-agent` `notify` 工具推送后房间可见（marker 命中，约 48 字）。webhook URL 只在本机 `.env` `MATTERMOST_INCOMING_WEBHOOK_URL` |
| Mattermost 图 / PDF | 通过（2026-09-09）：Webhook 后 PAT 拉 `file_ids` 落盘。API 小 PNG + 文本 PDF 回帖约 168 字。Town Square UI 真机：`@oryxbot` 与图 / PDF **同一条帖**发出后落盘并分别说明。只贴图不 @、或先发 @ 再另贴附件，都不会进媒体路径 |
| Matrix 入站接线 | 本机 Docker `oryxos-synapse`（`matrixdotorg/synapse` `:8008`）。`.env` 已有 `MATRIX_*`（不入库）。`ops-matrix` 绑 `demo-agent` 后 `GET /api/v1/channels/status` 为 `CONNECTED`。`/sync` 长轮询，无需公网回调。`http.allowed_domains` 含 `127.0.0.1` / `localhost` |
| Matrix 房间 `@Bot` 往返 | 通过（2026-09-09）：Town Square `@oryxbot:localhost` / `@oryxbot` / `m.mentions` → 回帖。Element Web：`oryxos-element` `:8087` |
| Matrix Element UI（Chrome） | 通过（2026-09-09）：本机 Google Chrome + Playwright `channel=chrome` 打开 `:8087`，homeserver 选 `localhost`，`admin` 登录（首次需重置数字身份），Town Square `@oryxbot` 可见 Bot 回帖 |
| Matrix 对齐飞书/企微/钉钉（Chrome） | 通过（2026-09-10）：房间 `@oryxbot`+图、`@oryxbot`+PDF 同条发出并回帖；私聊无 @ 往返；`ops-mx-notify` 进房间。媒体落盘 / 慢路径「处理中」/ 私聊不要求 @，口径对齐国内三家 |
| Matrix 私聊 | 通过（2026-09-09）：邀请自动加入；`m.direct` 读顶层 account_data 并跨 sync 记住；`is_direct` 邀请记入私聊集合。私聊不要求 @，已有往返 |
| Matrix 图 / PDF | 通过（2026-09-09）：`mxc://` 鉴权下载落盘（日志「媒体已落盘」）。与飞书相同 Vision / `read_file`。极小/残缺 PNG 会被 Vision 拒后降级纯文本（与 Mattermost 小图实测相同） |
| Matrix `notify` | 通过（2026-09-09）：管理台渠道 `ops-mx-notify`（`type: matrix`，`homeserver` + `token` + `room_id`）。Client-Server 发信必须 **PUT**（POST 为 405）。`NotifyPoster` 对完整 URL 用 `URI.create`，避免房间 ID 二次编码（否则 403 not in room）。`demo-agent` `notify` 后房间可见 marker |
| Teams / GChat | 代码 + 契约单测已有；本机无 `TEAMS_*` / `GCHAT_*`。与 WhatsApp 一并**暂停真机**，未宣称 COMPLETE |

管理台 Notify 已补齐 026 类型（原先 API 只允许飞书/企微/钉钉/webhook/email，适配器注册了也建不了渠道）。

## 暂停说明（2026-09-12 更新）

- **继续**：国内飞书/企微/钉钉；已合入的 Slack/Discord/Telegram；Mattermost/Matrix 本机环境。  
- **WhatsApp**：代码与 Dashboard 入站已通过；**真机**待具备 **商业注册认证** 的 WABA / 号 `CONNECTED` 后再测。  
- **仍暂停真机**：Teams / Google Chat（本机无凭证）。  
- **国内后续**：QQ 见 [029](../029-qq-im-channel/plan.md)；经营私信（抖音等）见 [030](../030-cn-c-im-roadmap/plan.md)，不在 026 海外真机队列内交付。
