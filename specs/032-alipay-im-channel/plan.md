# Implementation Plan: 032 支付宝生活号客服

**Status**: **BUILD IN PROGRESS**  
**Date**: 2026-09-12  
**对照**: [research](./research.md)、[030](../030-cn-c-im-roadmap/plan.md)、[017 契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)

## Summary

`type: alipay`：生活号应用网关（RSA2 验签 + `biz_content` XML）→ 文本 → Agent → `alipay.open.public.message.custom.send`（`chat=1`）。48h 窗外 fail-loud。

## 模块落点

| 项 | 拟定 |
|----|------|
| Maven | `oryxos-channel-alipay` |
| 入站 | Webhook：form POST；GBK/`charset`；验签；`verifygw` 与 `MsgType=text` |
| 出站 | `openapi.alipay.com` + RSA2 签名请求 |
| 配置 | `app_id`、应用私钥（`app_secret`）、`extra.alipay_public_key` / `app_public_key` |
| 文档 | `docs/AlipayChannelSetup.md` |
| 白名单 | `openapi.alipay.com` |
| Webhook | `ChannelInboundWebhookController`：form-urlencoded 自 raw body 按 GBK 解析（避免 `@RequestBody` 吃流） |

## 非目标

模板/群发/粉丝头条；交易投诉；公钥证书模式；新媒体号无消息能力时硬接成功预期。

## 检查清单

- [x] research 准入缺口（BUILD 授权 + verifygw 单测）  
- [x] 用户确认可以 BUILD  
- [x] 单测：验签、verifygw 回包、文本归一化、窗外拒绝  
