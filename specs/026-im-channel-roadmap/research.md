# Research: 026 IM 渠道

## Google Chat 入站形态（波次 4）

**决定**：Chat API **HTTP 端点**，不用 Cloud Pub/Sub。

**原因**：与波次 0 共享 `POST /api/v1/channels/inbound/{name}` 对齐；Pub/Sub 需额外 GCP 订阅与推送鉴权，MVP 体积更大。空间 @ 以官方 `argumentText` / annotations 判定。

## WhatsApp 24h 窗

**决定**：适配器硬拒绝窗外 `sendReply`，错误文案点名「只能发送已审核模板」。不在适配器内伪造模板发送成功。

## Teams JWT

**决定**：MVP 拆 Bot Framework Activity 并经 `serviceUrl` 回复；边缘 JWT 校验由 Azure Bot Service / 反代承担，不在 core 引入 OpenID 依赖。

## 国内 QQ（026 外 → 029）

**现状**：已落地 [029](../029-qq-im-channel/plan.md) / [research](../029-qq-im-channel/research.md)（#434/#435）。

**口径**：只做开放平台官方 Bot（Gateway + `api.bot.qq.com`）；不接个人号；群 `@Bot` + 单聊 MVP；频道二期。**不塞进 026 海外真机恢复队列**。

## 国内经营私信（026 外 → 030）

**现状**：总表 [030](../030-cn-c-im-roadmap/plan.md) / [research](../030-cn-c-im-roadmap/research.md)。

**口径**：抖音 / 支付宝 / 快手 / B 站 / 小红书 = **官方成熟才直连**；下一实现抖音 031。不经第三方 IM 聚合替代直连；**不塞进 026 海外真机恢复队列**。
