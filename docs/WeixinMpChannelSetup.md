# 微信服务号渠道（公众平台 `message/custom/send`）

粉丝在**微信**里给**认证服务号**发消息；OryxOS 通过公众平台服务器配置 URL 收信，经 `message/custom/send` 回复。  
与 `weixin_kf`（企微微信客服）、`wecom`（企微应用）、`weixin`（个人 iLink）**不是同一产品**。

## 前置

1. **认证服务号**（企业主体），已开通客服消息能力
2. 公众平台 → 开发 → 基本配置：AppId + AppSecret
3. 服务器配置：URL + Token + EncodingAESKey（**推荐安全模式 / AES**）
4. 公网 HTTPS 可达 OryxOS：`GET/POST /api/v1/channels/inbound/{name}`

## channels.yaml

```yaml
  - name: ops-weixin-mp
    type: weixin_mp
    app_id: ${WEIXIN_MP_APP_ID}              # 公众平台 AppId
    app_secret: ${WEIXIN_MP_APP_SECRET}      # AppSecret
    agent: demo-agent
    extra:
      token: ${WEIXIN_MP_TOKEN}
      encoding_aes_key: ${WEIXIN_MP_AES_KEY}
    enabled: true
```

回调地址示例：`https://<host>/api/v1/channels/inbound/ops-weixin-mp`

## 出站白名单

`http.allowed_domains` 需包含 `api.weixin.qq.com`（示例配置已有）。

## 行为摘要

| 步骤 | 说明 |
|------|------|
| URL 验证 | GET：`msg_signature` + `timestamp` + `nonce` + `echostr` → 验签解密后原样回写明文 |
| 入站 | POST 加密 XML → 解密；`MsgType=text` 时读 `FromUserName`（OpenID）、`Content`、`MsgId` |
| ACK | 5 秒内回 `success`；Agent 异步编排 |
| 事件/媒体 | 关注/菜单/图片等 MVP **不进 Agent**，仍回 `success` |
| 出站 | `POST /cgi-bin/message/custom/send` 文本；**48h / 每回合最多 5 条**，窗外 fail-loud |
| chatId | `mp:{appId}:user:{openId}` |

## 与其它微信系对照

| type | 场景 |
|------|------|
| `weixin_mp`（本渠道） | 认证服务号粉丝会话 |
| `weixin_mini` | 小程序内客服会话（见 `WeixinMiniChannelSetup.md`） |
| `weixin_kf` | 企业对客微信客服（企微 `kf/*`） |
| `wecom` | 企微应用内机器人 |
| `weixin` | 个人微信 iLink Bot |

## MVP 非目标

媒体消息、模板/群发、多客服人工台。
