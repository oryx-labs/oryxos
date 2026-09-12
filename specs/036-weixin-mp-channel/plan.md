# Implementation Plan: 036 微信服务号客服消息（W2）

**Branch（BUILD 时）**: `feat/036-weixin-mp-channel`  
**Date**: 2026-09-12  
**Status**: RESEARCH/PLAN — **BUILD 待确认**  
**对照**: [research](./research.md)、[030](../030-cn-c-im-roadmap/plan.md)、[017 契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)

## Summary

新建 `oryxos-channel-weixin-mp`：服务号服务器 URL（验签 + 安全模式解密）→ 文本归一化 → 快速 `success` → Agent → `message/custom/send`。工厂 `type: weixin_mp`。与 `weixin_kf` / `weixin` / `wecom` **并列**。

## 决策

| 项 | 决定 |
|----|------|
| 协议 | 公众平台官方；直连；无聚合 |
| 入站 | Webhook 带正文（非 sync 拉）；MsgId 去重 |
| ACK | 5s 内 `success`/空串；Agent 异步 |
| 出站 | `custom/send` 文本；用户消息触发窗 **48h / 5 条** fail-loud |
| 事件 | 菜单/关注/扫码默认不进编排 |
| Notify | MVP 不做 |
| 媒体 | 二期（image/voice media_id 下载） |

## BUILD 落点（确认后开工）

| 项 | 路径 |
|----|------|
| 模块 | Webhook 验签/加解密、Normalizer、CustomSendClient、回合条数窗 |
| 注册 | Runtime `weixin_mp`；cli/boot POM |
| 配置 | `channels.yaml.example`；`docs/WeixinMpChannelSetup.md` |
| 测试 | GET echostr、POST 明文/密文文本、窗外/超 5 条、契约档 |
| 白名单 | `api.weixin.qq.com` |

## 非目标

小程序客服（W3）、订阅通知/模板/群发、多客服人工台、媒体 MVP。

## 验收（BUILD 后）

- [ ] 单测绿  
- [ ] Setup 可跟做  
- [ ] 真机：粉丝给服务号发文本 ↔ Agent 经 custom/send 回复（有认证服务号时）  
