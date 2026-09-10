# Research: 031 抖音经营私信（官方直连）

**Date**: 2026-09-10  
**对照**: [plan](./plan.md)、[030](../030-cn-c-im-roadmap/plan.md)、[017 webhook](../017-feishu-im-channel/contracts/inbound-channel-contract.md)、[026 波次 0](../026-im-channel-roadmap/plan.md)

## 协议面

**决定**：只接 [抖音开放平台 · 移动/网站应用](https://developer.open-douyin.com/) **私信 OpenAPI + Webhooks**。不接个人号、不接非官方中继、MVP **不做**小程序「消息推送客服」第二套栈（与私信 API 并存时易双路径；二期若文档证明更易申请再评估）。

**原因**：030「官方成熟才直连」；私信管理有独立 scope、收事件、发消息文档，可对齐 OryxOS 入站契约。

## 入站形态

**决定**：HTTPS Webhook → 复用 `InboundWebhookHandler` + `POST /api/v1/channels/inbound/{name}`（026 波次 0）。

**原因**：官方私信事件为 Webhook 推送，无 Discord/QQ 式 Gateway。本机联调需公网 HTTPS 或隧道（同 WhatsApp）。

### 验签与挑战

| 项 | 规则 |
|----|------|
| 配置校验 | `event=verify_webhook`，`content.challenge` → 响应 body `{"challenge": <同值>}`（JSON text） |
| 日常验签 | Header `X-Douyin-Signature` = `sha1(client_secret + rawBody)`（十六进制）；失败 401，不入编排 |
| Scope | 事件需用户/经营者已授权对应 scope，否则无推送 |

### MVP 订阅事件

| 事件 | Scope | MVP |
|------|-------|-----|
| `im_receive_msg` | `im.direct_message` | ✅ 官方「接收私信」；场景一回复依赖此事件取 `server_message_id` |
| `im_send_msg` | `im.direct_message` | 可选对照；Normalizer 去重后与上者只保留「用户→经营者」方向 |
| `im_enter_direct_msg` | `im.direct_message` | ⬜ 二期（进会话页主动触达，30s/条数更严） |
| `im_group_*` | `im.group_message` | ⬜ 二期群聊 |

**方向过滤**：以绑定经营者 `open_id`（配置 `extra.open_id`）为准，只处理私聊 `conversation_type=1` 且对端为 C 端用户的文本；忽略本应用 `source=client_key` 回声（若有）。

**messageId**：`content.server_message_id`（去重 + 回复 `msg_id`）。

**chatId**：`user:{from_user_open_id}`（对端用户）；`sendReply` 还需会话上下文见下。

## 出站 / 会话窗

**决定**：`POST https://open.douyin.com/im/send/msg/`，Header `access-token`；MVP 仅 **场景一** `scene=im_reply_msg`（可默认）。

硬限制（适配器 fail-loud，对齐 WA）：

- `msg_id`（=`server_message_id`）约 **24h** 有效；过期拒绝并点名「需用户再发一条私信」。  
- 用户未发下一条前，24h 内最多约 **6** 条回复（以官方最新为准）。  
- 必传：`to_user_id`、`conversation_id`（=`conversation_short_id`）、`msg_id`（场景一）。

**会话上下文**：Webhook 入站后需在渠道侧记住 `open_id → {conversation_id, last_msg_id, expiresAt}`（进程内 + 可选落盘最小结构），供 `sendReply(chatId, text, replyToMessageId)` 使用；`replyToMessageId` 优先作 `msg_id`。

**非 MVP**：场景二进页触达、场景三 B2B、场景四主动授权触达、消息卡片 / 小程序卡片。

## 鉴权与凭证

**决定**：

| 用途 | 凭证 |
|------|------|
| Webhook 验签 / 应用身份 | `client_key` + `client_secret` → 映射 `app_id` / `app_secret` |
| 发私信 | 经营者扫码 OAuth 的 `access_token`（`/oauth/access_token/`）；**非**纯 client_credential |

配置形状（拟）：

```yaml
- name: ops-douyin
  type: douyin
  app_id: ${DOUYIN_CLIENT_KEY}
  app_secret: ${DOUYIN_CLIENT_SECRET}
  agent: demo-agent
  enabled: true
  extra:
    open_id: ${DOUYIN_OPEN_ID}           # 经营者 open_id
    access_token: ${DOUYIN_ACCESS_TOKEN} # 或 refresh 流程
    # refresh_token: ${DOUYIN_REFRESH_TOKEN}  # BUILD 时二选一实现
```

**资质**：当前开放范围含认证企业号/员工号、小程序品牌号等（以控制台为准）；个人号无此能力。申请路径：能力实验室 → 正式「互动管理」。

**BUILD 前缺口**：本机是否已有可授权企业号 / 能否申请 `im.direct_message`——无资质则只交契约单测 + Setup，不宣称真机 COMPLETE（同 WA）。

## 媒体

**决定**：MVP **仅 text**。`message_type=image|video|…` 二期（图片另需 `tool.image.upload` 等 scope）。

## Notify

**决定**：MVP **不做** `DouyinNotifyAdapter`。主动触达属场景四，权限与产品规则更重；入站回复走 `sendReply` 即可。

## 沙箱域名

`http.allowed_domains` 至少：`open.douyin.com`；媒体二期再补 CDN（如 `*.douyinpic.com`）。

## 对照 QQ / WhatsApp

| | QQ 029 | 抖音 031 | WhatsApp |
|--|--------|----------|----------|
| 连接 | Gateway WS | Webhook | Webhook |
| Token | App access_token | OAuth 经营者 token | 系统用户 token |
| 会话窗 | 群被动约 5min | 回复 24h / ~6 条 | 24h |
| MVP | 群@+单聊文本+媒体已做 | 单聊文本 | 文本+窗 |

## 开放问题（BUILD 前关闭或标风险）

1. `im_receive_msg` vs `im_send_msg` 在「用户→商家」上的实际触发——以沙箱日志为准，Normalizer 双订 + 方向过滤。  
2. `access_token` 刷新：是否必须 refresh_token 长期挂 `.env`，还是运维定期换——BUILD 选一种写进 Setup。  
3. Webhook 是否要求固定域名（禁止频繁换 quick tunnel）——文档要求 HTTPS；建议固定反代，验收说明同 WA。
