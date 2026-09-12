# WhatsApp Cloud API 渠道接入指南

依赖波次 0 共享入站 Webhook：`GET/POST /api/v1/channels/inbound/{name}`。发信走 Graph `/{phone-number-id}/messages`。

> **24 小时会话窗**：超时后 `sendReply` **硬拒绝**（可读错误），不静默。窗外主动触达只允许已审核模板（Notify `config.template`）。商业验证 / WABA / 模板预审是环境前置，未过审不写 COMPLETE。

## 一、Meta 侧

1. 创建 Meta 应用并接入 **WhatsApp** 产品，拿到 Access Token、App Secret、Phone Number ID。
2. 回调 URL 指到公网 HTTPS（或开发隧道，**不要**写进默认配置）：`https://<host>/api/v1/channels/inbound/cs-whatsapp`。
3. 订阅 `messages`；Verify Token 与 `extra.verify_token` 一致。
4. 签名头 `X-Hub-Signature-256` 用 App Secret。

## 二、OryxOS 侧

```yaml
channels:
  - name: cs-whatsapp
    type: whatsapp
    app_id: ${WHATSAPP_ACCESS_TOKEN}
    app_secret: ${WHATSAPP_APP_SECRET}
    agent: cs-agent
    extra:
      verify_token: ${WHATSAPP_VERIFY_TOKEN}
      phone_number_id: ${WHATSAPP_PHONE_NUMBER_ID}
    enabled: true
```

白名单：`graph.facebook.com`。

## 三、Notify

`type: whatsapp`：`token` + `phone_number_id` + `to`。可选 `template` 发模板；未给模板则发会话内文本（窗外由 Graph 或入站适配器拒绝）。

## 四、实测清单

- [x] 订阅挑战通过
- [x] Developers Dashboard「Send to server」入站（样本窗外回信硬拒绝为预期）
- [ ] 会话内真机文本往返（需 **已商业注册认证** 的 WABA + 号 `CONNECTED`；个人未认证账号无法完成）
- [ ] 窗外发送失败点名（真机会话内验证后）
- [ ] 媒体落盘（图/语音）
