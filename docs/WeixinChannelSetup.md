# 个人微信（iLink Bot）渠道 — 个人 Harness

腾讯 **iLink Bot API**（与 OpenClaw `openclaw-weixin` / Hermes `weixin` 同路）。  
定位：**个人 ↔ OryxOS** 的随身控制面（查状态、驱动 Agent、自测），**不是**企业客服、不是企微应用机器人。

> 私聊文本 + 图/语音/文件/视频入站（CDN 下载 + AES 解密落盘）；群多数场景不可用。  
> 回复必须带入站缓存的 `context_token`，否则硬拒绝。

## 一、拿凭证（扫码）

本模块 MVP **不内嵌 QR CLI**。任选其一扫码后，把 `account_id` + `token` 配进 OryxOS：

1. **OpenClaw**（本机已有）：`openclaw channels login --channel openclaw-weixin`，凭证在 `~/.openclaw/openclaw-weixin/`  
2. **Hermes**：`hermes gateway setup` → Weixin  
3. 或自行调 `ilink/bot/get_bot_qrcode` + `get_qrcode_status`

## 二、OryxOS 配置

```yaml
channels:
  - name: ops-weixin
    type: weixin
    app_id: ${WEIXIN_ACCOUNT_ID}      # ilink_bot_id
    app_secret: ${WEIXIN_TOKEN}       # bot_token
    agent: demo-agent                 # 个人 Harness 绑定的 Agent
    enabled: true
    # extra:
    #   base_url: https://ilinkai.weixin.qq.com
```

`.env` 示例：

```bash
WEIXIN_ACCOUNT_ID=...
WEIXIN_TOKEN=...
```

白名单：`ilinkai.weixin.qq.com`、`novac2c.cdn.weixin.qq.com`、`*.weixin.qq.com`。

可选 `extra.cdn_base_url`（默认 `https://novac2c.cdn.weixin.qq.com/c2c`）。

## 三、行为

| 场景 | 行为 |
|------|------|
| 用户私聊 Bot 文本 | 进编排；`chatId=user:{from_user_id}` |
| 图 / 文件 / 视频 | CDN 下载解密后落盘 `.oryxos/inbound-media/`；图走 Vision，文件可供 `read_file` |
| 语音 | 优先用平台 ASR 文本；同时落盘 silk（需本机 ASR/ffmpeg 链路时再转写） |
| Agent 回复 | `sendmessage` + 缓存的 `context_token`（MVP 仍为文本出站） |
| 群 | 非目标 |
| 会话过期 (-14) | 日志告警并暂停轮询约 10 分钟 |

## 四、与其它「微信」面

| type | 用途 |
|------|------|
| `weixin`（本渠道） | 个人 Harness |
| `wecom` | 企微员工协作 |
| `weixin_kf`（见 [WeixinKfChannelSetup](./WeixinKfChannelSetup.md)） | 企业对客微信客服 |

详见 `specs/035-weixin-ilink-channel/`。
