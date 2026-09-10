# Implementation Plan: 030 国内 C 端经营私信 / 客服（OS 直连）

**Branch**: 规划；落地按渠道开独立分支（建议 `feat/031-douyin-channel` 起）  
**Date**: 2026-09-10  
**Status**: PLANNED  
**对照**: [017 入站契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)、[026](../026-im-channel-roadmap/plan.md)、[029 QQ](../029-qq-im-channel/plan.md)

## Summary

OryxOS 作为 Agent OS，应对齐「**一种 IM / 经营触达面 = 一个直连适配器**」：官方协议成熟则排期接入，不经过第三方 IM 聚合中台。国内 **B 端协作**已由飞书/企微/钉钉覆盖；**C 端官方 Bot** 已由 [029 QQ](../029-qq-im-channel/plan.md) 开路。本 PLAN 排 **国内内容/交易平台经营私信与客服消息**（抖音、支付宝、快手、B 站、小红书），与 026 海外真机队列分离。

## 原则（产品）

1. **直连官方**：`oryxos-channel-*` ↔ 平台开放 API / Webhook；禁止个人号协议、群控、非官方中继。  
2. **成熟才接**：可申请权限、有稳定文档、可验签或可会话鉴权 → 进实现波次；否则挂候选。  
3. **编排不扩散**：适配器只做协议 ↔ `InboundMessage` + `start/stop/status/sendReply`；去重/会话/失败文案在 `InboundMessageService`。  
4. **会话窗 fail-loud**：凡有 24h / 条数 / 模板限制，对齐 WhatsApp——窗外拒绝并可读报错，不静默。  
5. **一渠道一模块**：工厂 `type` 注册 + `docs/*ChannelSetup.md` + Normalizer/契约单测 + `http.allowed_domains`。

## 场景对照

```text
国内员工 ────────── 飞书 / 企微 / 钉钉                 ✅
国内 C 官方 Bot ─── QQ（Gateway）                     ✅ 029 / #434 #435
国内 C 经营私信 ─── 抖音 → 支付宝 → 快手 → B站 → 小红书  ← 本 PLAN
出海 / 私有化 ───── 见 026（海外真机部分暂停另计）
```

叙事：**经营触达 / 客服私信**，不是「再接五个企业 IM」。

## 渠道清单与波次

| ID | 渠道 | 模块（拟） | `type` | 官方可接面（初判） | 波次 | 状态 |
|----|------|------------|--------|-------------------|------|------|
| C0 | QQ | `oryxos-channel-qq` | `qq` | Bot API v2 Gateway | — | ✅ 029 |
| C1 | 抖音 | `oryxos-channel-douyin` | `douyin` | 开放平台私信 Webhook + `/im/send/msg/` | **1** | ✅ [031](../031-douyin-im-channel/plan.md) 代码（真机待资质） |
| C2 | 支付宝 | `oryxos-channel-alipay` | `alipay` | 开放平台客服/消息（钉死一条路径） | **2** | ⬜ |
| C3 | 快手 | `oryxos-channel-kuaishou` | `kuaishou` | 客服/私信上下行（确认后再开；订阅消息≠IM） | **3** | ⬜ |
| C4 | B 站 | `oryxos-channel-bilibili` | `bilibili` | UP/开放能力；私信 Bot 面待 research | **4** | 🔍 候选 |
| C5 | 小红书 | `oryxos-channel-xiaohongshu` | `xiaohongshu` | 第三方 Bot 面历史上最紧 | **4** | 🔍 候选 / 可 Won’t |

实现前每家必须有同目录 `research` 结论或独立 `03x-*-channel/research.md`：**权限名、事件、会话窗、媒体、沙箱域名、资质门槛**。C4/C5 research 写不出稳定双向私信则标 **Later/Won’t**，不进编码。

## 波次 1：抖音（下一刀）

- 独立实现 PLAN：**[031](../031-douyin-im-channel/plan.md)** / [research](../031-douyin-im-channel/research.md)（已 PLANNED，待 BUILD）。  
- 入站：Webhook（`InboundWebhookHandler`）→ 文本 MVP。  
- 出站：场景一 `im_reply_msg`；24h / 条数 fail-loud。  
- 凭证：`DOUYIN_CLIENT_*` + 经营者 OAuth `access_token` + `open_id`。  
- Notify：MVP 不做。  
- **验收**：见 031；有企业号资质后再标真机。

## 波次 2–3：支付宝 / 快手

- 各开 `032` / `033`（或并入本目录 research 后再开分支）。  
- 支付宝：**一条**客服/消息路径，不把订阅模板冒充双向 IM。  
- 快手：先确认私信/客服文档；订阅消息可作 notify 子能力。

## 波次 4：B 站 / 小红书

- Research-only 直至接口成熟；通过后再开模块。

## MVP 统一契约（各实现 PR）

- 私聊文本往返；群/粉丝群若有 @ 规则再对齐 A1。  
- `chatId` 编码平台主体（如 `douyin:{open_id}`）。  
- 媒体（图/PDF/语音/视频）二期，对齐 QQ/飞书落盘模式。  
- 配置进 `channels.yaml.example`；管理台 Notify `SUPPORTED_TYPES` 按需加。

## 非目标

- 第三方「聚合 IM / 云客服中继」作为唯一入站路径（可另做可选集成，**不替代**直连）。  
- 个人号、矩阵群发、绕过会话窗。  
- 塞进 026 未完成的海外真机项。

## 与 026 / 029 的边界

| PLAN | 管什么 |
|------|--------|
| 026 | 全球清单 + webhook 底座 + 出海/私有化 |
| 029 | QQ 官方 Bot（已实现） |
| **030** | 国内 C 端经营私信总表与排期 |
| 031+ | 单渠道实现（抖音起） |

## 检查清单

- [x] 原则：直连 + 成熟才接  
- [x] 总表与波次  
- [x] 031 抖音 research + plan  
- [x] 031 BUILD（`oryxos-channel-douyin`；真机待资质）  
- [ ] 032 支付宝  
- [ ] 033 快手  
- [ ] C4/C5 research 结论（接 / Later / Won’t）  
- [x] 026 §D 回链本 PLAN  
