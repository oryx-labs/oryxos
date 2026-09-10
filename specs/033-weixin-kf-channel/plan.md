# Implementation Plan: 033 微信客服入站（官方直连）

**Branch（BUILD 时）**: `feat/033-weixin-kf-channel`  
**Date**: 2026-09-11  
**Status**: BUILT（单测绿；真机待资质）  
**对照**: [research](./research.md)、[030](../030-cn-c-im-roadmap/plan.md)、[017 契约](../017-feishu-im-channel/contracts/inbound-channel-contract.md)

## Summary

新建 `oryxos-channel-weixin-kf`（名称可微调）：回调 `kf_msg_or_event` → `kf/sync_msg` 拉文本 → 编排 → `kf/send_msg` 回复。工厂 `type: weixin_kf`。与现有 `wecom` **并列**，不合并 type。

## 决策

| 项 | 决定 |
|----|------|
| 协议 | 微信客服 OpenAPI；直连；无聚合 |
| 入站 | Webhook 唤醒 + sync 拉取（非单包带正文） |
| 出站 | `kf/send_msg`；48h / 5 条 fail-loud |
| 会话 | 启动后确保「智能助手接待」态，否则发信失败 |
| Notify | MVP 不做 |

## BUILD 落点（确认后开工）

| 项 | 路径 |
|----|------|
| 模块 | Adapter（WebhookHandler）/ Normalizer / SyncClient / MessageSender / Session（条数窗） |
| 注册 | Runtime `weixin_kf`；cli/boot POM |
| 配置 | `channels.yaml.example`；Setup 文档 |
| 测试 | 回调验签、sync 样例归一化、窗外/超 5 条拒绝、契约档 |

## 非目标

服务号/小程序/小店客服；媒体二期；多 `open_kfid` 路由二期。

## 验收（BUILD 后）

- [ ] 单测绿  
- [ ] Setup 可跟做  
- [ ] 真机：用户微信侧发文本 ↔ Agent 回复（有企微+微信客服资质时）  
