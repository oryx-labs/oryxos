# Research: 026 IM 渠道

## Google Chat 入站形态（波次 4）

**决定**：Chat API **HTTP 端点**，不用 Cloud Pub/Sub。

**原因**：与波次 0 共享 `POST /api/v1/channels/inbound/{name}` 对齐；Pub/Sub 需额外 GCP 订阅与推送鉴权，MVP 体积更大。空间 @ 以官方 `argumentText` / annotations 判定。

## WhatsApp 24h 窗

**决定**：适配器硬拒绝窗外 `sendReply`，错误文案点名「只能发送已审核模板」。不在适配器内伪造模板发送成功。

## Teams JWT

**决定**：MVP 拆 Bot Framework Activity 并经 `serviceUrl` 回复；边缘 JWT 校验由 Azure Bot Service / 反代承担，不在 core 引入 OpenID 依赖。

## 国内 QQ（026 外候选）

**现状**：026 主清单未排 QQ；§C 只排除「微信个人号」类无合规协议，未点名 QQ。

**倾向（待另开 PLAN 确认）**：

- 只评估 **QQ 开放平台官方机器人面**（如 QQ 频道 / 开放平台文档所载 Bot），不接个人号协议挂机。  
- 入站形态优先对齐已有 Webhook 接收面或官方长连接（以开放平台当期文档为准）。  
- 与飞书/企微/钉钉重叠度：国内 B 端已三家；QQ 更偏 C 端/社群，是否刚需由国内 PLAN 的场景表决定，**不塞进 026 海外真机恢复队列**。
