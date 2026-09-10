# Implementation Plan: 035 个人微信 iLink Bot

**Branch**: `feat/035-weixin-ilink`  
**Date**: 2026-09-11  
**Status**: IMPLEMENTED（单测；真机待扫码凭证）  
**对照**: [research](./research.md)、Telegram 长轮询、Hermes/OpenClaw iLink

## Summary

新建 `oryxos-channel-weixin`：`type: weixin`；长轮询 `getupdates` + `sendmessage`。MVP 私聊文本；凭证来自扫码（OpenClaw/Hermes 或后续 CLI）。

## BUILD 落点

| 项 | 内容 |
|----|------|
| 模块 | Adapter / IlinkClient / Normalizer / Sender / ContextTokenStore |
| 注册 | Runtime `weixin`；cli/boot POM |
| 配置 | channels example；`ilinkai.weixin.qq.com` |
| 文档 | `docs/WeixinChannelSetup.md`（含从 OpenClaw 取 token） |
| 测试 | Normalizer、Sender/Client 契约、无 context_token 拒绝 |

## 非目标

群、媒体、QR 内嵌 CLI（Setup 写清用 OpenClaw 扫码）、微信客服 KF。
