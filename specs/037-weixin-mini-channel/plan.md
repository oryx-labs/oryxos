# Implementation Plan: 037 微信小程序客服消息（W3）

**Branch（BUILD 时）**: `feat/037-weixin-mini-channel`  
**Date**: 2026-09-12  
**Status**: RESEARCH/PLAN — **BUILD 待确认**  
**对照**: [research](./research.md)、[030](../030-cn-c-im-roadmap/plan.md)、[036](../036-weixin-mp-channel/plan.md)、[017 契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)

## Summary

新建 `oryxos-channel-weixin-mini`：小程序消息推送 URL → 文本归一化 → 快速 `success` → Agent → `message/custom/send`。工厂 `type: weixin_mini`。与 `weixin_mp` **并列**，不合并。

## 决策

| 项 | 决定 |
|----|------|
| 协议 | 小程序官方消息推送 + 客服发送；直连 |
| GET 验签 | **明文** `signature` + 原样 `echostr`（与 036 加密 echostr 路径分开实现） |
| POST | MVP **安全模式 + XML**（AES 帧对齐公众平台；JSON 二期） |
| ACK | `success`；Agent 异步 |
| 出站 | `custom/send` 文本；用户发消息窗 **48h / 5 条** fail-loud |
| 事件 | 「进入客服」等默认不进编排 |
| 媒体 | 二期 |

## BUILD 落点（确认后开工）

| 项 | 路径 |
|----|------|
| 模块 | Adapter / MsgCrypt（或共享解密 + 独立 URL 验签）/ Normalizer / CustomSend / ReplySessionStore |
| 注册 | Runtime `weixin_mini`；cli/boot POM |
| 配置 | `channels.yaml.example`；`docs/WeixinMiniChannelSetup.md` |
| 测试 | GET echostr、POST 密文文本、窗外/超 5、契约档 |
| 白名单 | `api.weixin.qq.com` |

## 非目标

云函数/云托管唯一入站；订阅消息；人工 `transfer_customer_service`；与 `weixin_mp` 共用 type。

## 验收（BUILD 后）

- [ ] 单测绿  
- [ ] Setup 可跟做  
- [ ] 真机：小程序客服会话发文本 ↔ Agent 回复  
