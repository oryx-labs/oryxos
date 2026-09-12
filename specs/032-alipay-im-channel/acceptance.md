# Acceptance: 032 支付宝生活号客服

## 代码

- [x] `oryxos-channel-alipay`：网关验签 / verifygw / 文本入站 / `custom.send`
- [x] Runtime + boot/cli 注册 `type: alipay`
- [x] Webhook form GBK 解析（`ChannelInboundWebhookController`）
- [x] 文档 `docs/AlipayChannelSetup.md` + `channels.yaml.example`
- [x] 单测：RSA2、verifygw、归一化、48h 窗、契约

## 真机（账号侧）

- [x] 生活号网关 URL + 密钥配置（`ops-alipay`）；兼容截断 URL `/api/v1`
- [x] 激活开发者模式（`verifygw` 通过，2026-09-12）
- [ ] 支付宝 App 发一句文本 → Agent 回复（当前生活号基础包无咨询消息推送，待具备消息能力的账号复验）
