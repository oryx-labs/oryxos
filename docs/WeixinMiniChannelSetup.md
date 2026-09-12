# 微信小程序客服渠道（`message/custom/send`）

用户在**小程序内**通过 `<button open-type="contact">` 或客服会话发消息；OryxOS 通过小程序「消息推送」URL 收信，经 `message/custom/send` 回复。  
与 `weixin_mp`（服务号）、`weixin_kf`（企微微信客服）、`wecom`（企微应用）、`weixin`（个人 iLink）**不是同一产品**。

## 前置

1. 已注册**小程序**（企业主体），已开通客服消息能力
2. 小程序后台 → 开发管理 → 开发设置：AppId + AppSecret
3. 开发管理 → 消息推送：URL + Token + EncodingAESKey（**推荐安全模式 / AES**，数据格式 **XML**）
4. 公网 HTTPS 可达 OryxOS：`GET/POST /api/v1/channels/inbound/{name}`

## channels.yaml

```yaml
  - name: ops-weixin-mini
    type: weixin_mini
    app_id: ${WEIXIN_MINI_APP_ID}              # 小程序 AppId
    app_secret: ${WEIXIN_MINI_APP_SECRET}      # AppSecret
    agent: demo-agent
    extra:
      token: ${WEIXIN_MINI_TOKEN}
      encoding_aes_key: ${WEIXIN_MINI_AES_KEY}
    enabled: true
```

回调地址示例：`https://<host>/api/v1/channels/inbound/ops-weixin-mini`

## 出站白名单

`http.allowed_domains` 需包含 `api.weixin.qq.com`（示例配置已有）。

## 行为摘要

| 步骤 | 说明 |
|------|------|
| URL 验证 | GET：`signature` + `timestamp` + `nonce` + `echostr` → SHA1(token,ts,nonce) 验签后**原样回写 echostr**（明文，不解密） |
| 入站 | POST 加密 XML → `msg_signature` 验签解密；`MsgType=text` 时读 `FromUserName`（OpenID）、`Content`、`MsgId` |
| ACK | 5 秒内回 `success`；Agent 异步编排 |
| 事件/媒体 | 进入客服会话/图片等 MVP **不进 Agent**，仍回 `success` |
| 出站 | `POST /cgi-bin/message/custom/send` 文本；**48h / 每回合最多 5 条**，窗外 fail-loud |
| chatId | `mini:{appId}:user:{openId}` |

## 与服务号 GET 验签差异

| | `weixin_mini`（本渠道） | `weixin_mp` |
|--|------------------------|-------------|
| GET 参数 | `signature` | `msg_signature` |
| echostr | 明文原样返回 | 加密 echostr，验签后解密回写 |

## 与其它微信系对照

| type | 场景 |
|------|------|
| `weixin_mini`（本渠道） | 小程序内客服会话 |
| `weixin_mp` | 认证服务号粉丝会话（见 `WeixinMpChannelSetup.md`） |
| `weixin_kf` | 企业对客微信客服（企微 `kf/*`） |
| `wecom` | 企微应用内机器人 |
| `weixin` | 个人微信 iLink Bot |

## MVP 非目标

JSON 入站格式、媒体消息、订阅/模板消息、云函数/云托管唯一入站、人工 `transfer_customer_service`。
