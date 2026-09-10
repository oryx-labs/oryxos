# Implementation Plan: 031 抖音经营私信入站（官方直连）

**Branch**: `feat/031-douyin-channel`（BUILD 时开）  
**Date**: 2026-09-10  
**Status**: IMPLEMENTED（单测；真机待企业号资质）  
**对照**: [research](./research.md)、[030](../030-cn-c-im-roadmap/plan.md)、[017 契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)、[026 Webhook](../026-im-channel-roadmap/plan.md)、[029 QQ](../029-qq-im-channel/plan.md)

## Summary

新建 `oryxos-channel-douyin`：抖音开放平台 **私信 Webhook 入站** + `POST /im/send/msg/` **场景一回复**。工厂 `type: douyin`；复用 `InboundWebhookHandler`。MVP **仅私聊文本**；群、进页触达、卡片、媒体、Notify 二期。

## 决策（摘要）

| 项 | 决定 |
|----|------|
| 协议 | 移动/网站应用私信 OpenAPI；不做个人号 / 聚合中台 |
| 入站 | Webhook：`verify_webhook` + `X-Douyin-Signature`（sha1 secret+body） |
| 事件 | MVP：`im_receive_msg`（必）；`im_send_msg` 可选对照 |
| 出站 | `im_reply_msg`；24h `msg_id` + 条数限制 fail-loud |
| 凭证 | `client_key/secret` = app_id/secret；发信用经营者 OAuth `access_token` + `extra.open_id` |
| chatId | `user:{open_id}`；会话 `conversation_id` / `msg_id` 渠道内缓存 |
| Notify | MVP 不做 |

细节与开放问题见 [research](./research.md)。

## BUILD 落点（待开工）

| 项 | 路径 |
|----|------|
| 模块 | `oryxos-channel-douyin`：`DouyinChannelAdapter`（WebhookHandler）/ `DouyinEventNormalizer` / `DouyinMessageSender` / 签名工具 / 可选 Token 刷新 |
| 注册 | `OryxOsRuntime` `factories.put("douyin", …)`；cli/boot POM |
| Webhook | 实现 `InboundWebhookHandler`；挑战与验签单测 |
| 配置 | `channels.yaml.example`；`http.allowed_domains`：`open.douyin.com` |
| 文档 | `docs/DouyinChannelSetup.md`（资质、授权、固定 HTTPS 回调） |
| 测试 | Normalizer + 验签/挑战 + `InboundMessageService` 契约档 |

## 配置形状（拟）

```yaml
- name: ops-douyin
  type: douyin
  app_id: ${DOUYIN_CLIENT_KEY}
  app_secret: ${DOUYIN_CLIENT_SECRET}
  agent: demo-agent
  enabled: true
  extra:
    open_id: ${DOUYIN_OPEN_ID}
    access_token: ${DOUYIN_ACCESS_TOKEN}
```

回调 URL：`https://<公网>/api/v1/channels/inbound/ops-douyin`

## 非目标（MVP）

- 群私信、`im_enter_direct_msg` 主动触达、B2B/授权持续触达  
- 图/视频/卡片入站出站  
- `DouyinNotifyAdapter`  
- 小程序客服推送第二套入站  
- 无企业号资质时宣称真机 COMPLETE  

## 验收（BUILD 后）

- [ ] 单测：验签失败拒绝；`verify_webhook` 回 challenge；文本归一化；窗外发送失败文案  
- [ ] Setup 文档可跟做  
- [ ] 有资质时：用户私信 → Agent 回复可见（再标真机）  

## 检查清单

- [x] research 钉死协议 / 窗 / 凭证  
- [x] 本 PLAN  
- [ ] 开分支编码（用户确认 BUILD 后再做）  
- [ ] 030 表 C1 状态改为实现中 / 完成  
