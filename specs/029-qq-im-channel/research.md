# Research: 029 QQ 官方机器人

## 协议面

**决定**：仅 QQ 开放平台官方 Bot API v2（`https://api.bot.qq.com` + Gateway WSS）。

**原因**：与 026「微信个人号不合规」同口径；NapCat / go-cqhttp 属协议号风险，产品线不接。

## 入站形态

**决定**：Gateway WebSocket（Hello → Identify → Heartbeat → Dispatch），不对齐本期 Webhook 回调。

**原因**：对称 Discord/企微长连接；免公网回调 URL，便于本机联调。Webhook（op13 验签）留二期可选。

## Intent

**决定**：只订 `GROUP_AND_C2C_EVENT`（`1 << 25`）。

**原因**：MVP 只要群 `@Bot`（`GROUP_AT_MESSAGE_CREATE`）与单聊（`C2C_MESSAGE_CREATE`）。频道 `PUBLIC_GUILD_MESSAGES` / `AT_MESSAGE_CREATE` 二期再开。

## 鉴权

**决定**：`POST /app/getAppAccessToken`（body: `appId` + `clientSecret`）→ `Authorization: QQBot {access_token}`；Identify 的 `token` 同格式。废弃旧 `Bot {appid}.{token}`。

**原因**：官方文档已弃用 Bot Token；access_token 约 7200s，客户端在过期前刷新即可，不落盘。

## chatId 编码

**决定**：归一化后 `chatId` 为 `group:{group_openid}` 或 `user:{user_openid}`。

**原因**：`sendReply` 接口无 `ChatKind`；群与单聊 REST 路径不同（`/v2/groups/...` vs `/v2/users/...`），必须在 chatId 可解析。

## 去重与被动窗

**决定**：以事件 `d.id` 作 `messageId`（去重键 + 群被动 `msg_id`）；平台可能重推同一 id，依赖 `InboundMessageService` 去重。

**原因**：官方：被动回复带 `msg_id`，约 5 分钟；相同 `msg_id+msg_seq` 不可重复发。Sender 对同一 `msg_id` 递增 `msg_seq`；过期错误时降级去掉 `msg_id` 再试一次。
