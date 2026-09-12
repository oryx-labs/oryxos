# Implementation Plan: 032 支付宝生活号客服

**Status**: **BLOCKED（账号/平台）** — 路径已钉死，默认禁止编码  
**Date**: 2026-09-12  
**对照**: [research](./research.md)、[030](../030-cn-c-im-roadmap/plan.md)、[017 契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)

## Summary（确认 BUILD 后）

`type: alipay`：生活号应用网关（RSA2 验签 + `biz_content` XML）→ 文本 → Agent → `alipay.open.public.message.custom.send`（建议 `chat=1`）。48h 窗外 fail-loud。

## 模块落点（仅授权后建）

| 项 | 拟定 |
|----|------|
| Maven | `oryxos-channel-alipay` |
| 入站 | Webhook：form POST；GBK/`charset`；验签；`verifygw` 与 `MsgType=text` |
| 出站 | `openapi.alipay.com` + RSA2 签名请求 |
| 配置 | `app_id`、应用私钥、支付宝公钥（或证书路径）、网关相关 extra |
| 文档 | `docs/AlipayChannelSetup.md` |
| 白名单 | `openapi.alipay.com` |

## 非目标

模板/群发/粉丝头条；交易投诉；新媒体号无消息能力时硬接。

## 检查清单（BUILD 前）

- [ ] research 准入缺口勾完（含账号现网消息能力）  
- [ ] 你确认可以 BUILD  
- [ ] 单测：验签、verifygw 回包、文本归一化、窗外拒绝  
