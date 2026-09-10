# Implementation Plan: 029 QQ 官方机器人入站 / notify

**Branch**: `feat/029-qq-channel`  
**Date**: 2026-09-10  
**Status**: IMPLEMENTED（单测绿；真机待 `QQ_APP_*`）  
**对照**: [017 入站契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)、[026 §D](../026-im-channel-roadmap/plan.md)、[004 Notify](../004-notify-outbound/plan.md)

## Summary

接入 [QQ 开放平台官方 Bot API v2](https://bot.q.qq.com/wiki/)：Gateway WebSocket 收事件 + HTTP 发信。新建 `oryxos-channel-qq`，工厂注册 `type: qq`；Notify `QqNotifyAdapter`。

## 决策

| 项 | 决定 |
|----|------|
| 协议 | **只做**官方 Bot（`api.bot.qq.com`）；**不做** NapCat / go-cqhttp / 个人号 |
| MVP 入站 | 群 `GROUP_AT_MESSAGE_CREATE`（事件即 @Bot）+ 单聊 `C2C_MESSAGE_CREATE` |
| 二期 | QQ 频道 / 子频道——同模块扩展，不另起栈 |
| 连接 | Gateway 长连接（对齐 Discord）；发信 `Authorization: QQBot {access_token}` |
| 凭证 | `app_id`=`QQ_APP_ID`，`app_secret`=`QQ_APP_SECRET`；`getAppAccessToken` 换票，不落盘长期 token |
| Intent | `GROUP_AND_C2C_EVENT`（`1 << 25`） |
| 群回复 | 被动带 `msg_id`（约 5 分钟窗）；过期可降级无 `msg_id` 主动发（限额见官方） |

## 落点

| 项 | 路径 |
|----|------|
| 模块 | `oryxos-channel-qq`：`QqAccessTokenClient` / `QqGatewayClient` / `QqChannelAdapter` / `QqEventNormalizer` / `QqMessageSender` |
| 注册 | `OryxOsRuntime` `factories.put("qq", …)` + notifyAdapters |
| POM | 根 modules + DM；cli / boot 依赖 |
| Notify | `QqNotifyAdapter`；`NotifyChannelApiController.SUPPORTED_TYPES` |
| 配置 | `channels.yaml.example`；`http.allowed_domains`：`api.bot.qq.com` |
| 文档 | `docs/QqChannelSetup.md` |
| 测试 | Normalizer + `InboundMessageService` 契约档 |

## 配置形状

```yaml
- name: ops-qq
  type: qq
  app_id: ${QQ_APP_ID}
  app_secret: ${QQ_APP_SECRET}
  agent: demo-agent
  enabled: true
```

Notify：`type: qq`，`token`（access_token）+ `group_openid` 或 `user_openid`；联调可给 `url` 打假 HTTP。

## 非目标（MVP）

个人号、频道消息、流式富媒体完整对齐、Webhook 回调模式（本期只用 Gateway）。
