# Research: 035 个人微信 iLink Bot

**Date**: 2026-09-11  
**Status**: READY（可 BUILD）  
**对照**: [plan](./plan.md)、[030](../030-cn-c-im-roadmap/plan.md)、Hermes `gateway/platforms/weixin.py`、OpenClaw `@tencent-weixin/openclaw-weixin`

## 与「微信客服 / 企微」区分

| | 本渠道 `weixin` | 033 微信客服 | 现有 `wecom` |
|--|-----------------|--------------|--------------|
| 身份 | 个人微信扫码 → **iLink Bot** | 企业客服账号 | 企微应用/智能机器人 |
| 传输 | 长轮询 `getupdates` | 回调 + `sync_msg` | WS / 应用回调 |
| 基址 | `https://ilinkai.weixin.qq.com` | `qyapi.weixin.qq.com` | `openws` / `qyapi` |

**不是**个微协议逆向；是腾讯官方 iLink Bot API（OpenClaw/Hermes 同路）。

## 钉死路径

| 步骤 | API | 说明 |
|------|-----|------|
| 登录 | `ilink/bot/get_bot_qrcode` + `get_qrcode_status` | 扫码得 `ilink_bot_id` + `bot_token`（MVP 可复用 OpenClaw/Hermes 已扫结果） |
| 入站 | `POST ilink/bot/getupdates` | body：`get_updates_buf` + `base_info`；Bearer token；长轮询约 35s |
| 出站 | `POST ilink/bot/sendmessage` | `msg.to_user_id` + `item_list` 文本；**必须带**对端最新 `context_token` |
| 头 | `Authorization: Bearer`、`AuthorizationType: ilink_bot_token`、`iLink-App-Id: bot`、`iLink-App-ClientVersion`、`X-WECHAT-UIN` | |

## 会话 / 限制

- 回复依赖入站缓存的 `context_token`；缺失时 fail-loud（可尝试无 token 发送，但 MVP 要求有缓存）。  
- 群：iLink Bot 身份多数场景**进不了普通群**；MVP **仅私聊**。  
- 媒体：入站走 `novac2c.cdn.weixin.qq.com` + AES-128-ECB（对齐 Hermes）；出站媒体二期。

## 配置

```yaml
type: weixin
app_id: ${WEIXIN_ACCOUNT_ID}    # ilink_bot_id
app_secret: ${WEIXIN_TOKEN}     # bot_token
extra:
  base_url: https://ilinkai.weixin.qq.com   # 可选
```

## 沙箱域名

- `ilinkai.weixin.qq.com`  
- 媒体二期：`novac2c.cdn.weixin.qq.com`

## 030 修正

原「个人微信 ❌ Won’t」仅针对**非官方协议号**。iLink Bot **改为 ✅ Ready**。
