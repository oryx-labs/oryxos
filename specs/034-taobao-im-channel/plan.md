# Implementation Plan: 034 淘宝/天猫客服

**Status**: **BLOCKED（缺入驻）** — 真路径已钉死，禁止无资质编码  
**Date**: 2026-09-12  
**对照**: [research](./research.md)、[030](../030-cn-c-im-roadmap/plan.md)、[017 契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)

## Summary

`type: taobao`（天猫同模块）：奇门入站 `qimen.taobao.message.chatrobot.sync`（经 `mappcloud-gw…/invokeChatRobot/conversation`）→ 归一化文本 → Agent；出站 `taobao.message.chatrobot.async`。仅 ISV/智能客服入驻后可真机。

## 模块落点（确认 BUILD 后再建）

| 项 | 拟定 |
|----|------|
| Maven | `oryxos-channel-taobao` |
| Runtime type | `taobao` |
| 入站 | HTTP：网关 POST；验 `param.sign`；解析 event |
| 出站 | TOP：`taobao.message.chatrobot.async`；窗外/未知限流 → fail-loud |
| 配置 | `appKey` / `appSecret` / `cloudAppId` / `cloudEnv` / `path` / `requestToken`（名以 Setup 为准） |
| 文档 | `docs/TaobaoChannelSetup.md` |
| 白名单 | `mappcloud-gw.taobao.com`、`gw.api.taobao.com`、`eco.taobao.com`（按需） |

## 非目标

- TMC 订单/物流冒充 IM  
- 千牛 Cookie / 协议号  
- 以聚合中台为唯一入站  

## 检查清单（BUILD 前）

- [ ] research 准入缺口全部勾掉  
- [ ] 你确认可以 BUILD  
- [ ] 单测：验签、event 归一化、async 出站、契约档  
- [ ] Setup + `channels.yaml.example`  
