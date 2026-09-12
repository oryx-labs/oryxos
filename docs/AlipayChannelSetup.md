# 支付宝生活号客服渠道（`alipay.open.public.message.custom.send`）

用户在支付宝 **生活号 / 咨询反馈** 发文本；OryxOS 通过生活号**应用网关**收信，经 `custom.send` 回复。  
与微信客服 / 服务号 / 小程序、支付宝交易投诉、模板群发 **不是同一产品**。

## 前置

1. 生活号应用具备**消息能力**（旧号升级或现网已开通；生活号+ 新号可能没有）
2. 开放平台：AppId、应用私钥（PKCS#8）、应用公钥、支付宝公钥（普通公钥模式）
3. 开发者模式：应用网关 URL 指向 OryxOS
4. 公网 HTTPS：`POST /api/v1/channels/inbound/{name}`

## channels.yaml

```yaml
  - name: ops-alipay
    type: alipay
    app_id: ${ALIPAY_APP_ID}
    app_secret: ${ALIPAY_APP_PRIVATE_KEY}   # PKCS#8 Base64/PEM
    agent: demo-agent
    extra:
      alipay_public_key: ${ALIPAY_PUBLIC_KEY}
      app_public_key: ${ALIPAY_APP_PUBLIC_KEY}  # verifygw 回执
    enabled: true
```

网关地址示例：`https://<host>/api/v1/channels/inbound/ops-alipay`

## 出站白名单

`http.allowed_domains` 需包含 `openapi.alipay.com`。

## 行为摘要

| 步骤 | 说明 |
|------|------|
| 激活 | POST form（常 GBK）：`service=alipay.service.check` + `EventType=verifygw` → RSA2 验签 → 回 XML（含应用公钥 + 签名） |
| 入站 | 同网关；`biz_content` XML，`MsgType=text`，`FromUserId` + `Text`/`Content` |
| ACK | 文本回 `success`；Agent 异步 |
| 事件/媒体 | MVP **不进 Agent**，仍回 `success` |
| 出站 | `alipay.open.public.message.custom.send`，`chat=1`；**约 48h** 窗外 fail-loud |
| chatId | `alipay:{appId}:user:{fromUserId}` |

## 密钥说明

- 验签用 **支付宝公钥**（不是应用公钥）
- verifygw 成功回执的 `biz_content` 填 **应用公钥**
- 出站请求用 **应用私钥** RSA2 签名

## MVP 非目标

模板/群发/粉丝头条；交易投诉；公钥证书模式（后续可扩）；媒体消息。
