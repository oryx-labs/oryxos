# Research: 032 支付宝生活号客服（草稿，未收口）

**Date**: 2026-09-11  
**Status**: DRAFT — **不授权 BUILD**；待 030 优先级确认后收口  
**对照**: [030 plan](../030-cn-c-im-roadmap/plan.md)

## 候选路径（未最终钉死）

| 方向 | 候选 | 说明 |
|------|------|------|
| 入站 | 生活号应用网关 POST | `biz_content` XML，`MsgType=text` |
| 验签 | RSA2 + 支付宝公钥 | 成功回 `success` |
| 出站 | `alipay.open.public.message.custom.send` | 用户交互后约 48h |
| 排除 | 交易投诉、模板/群发、个人号 | 不得标 IM 完成 |

## 开放风险

- 官方提示生活号文档后续不再更新 / 迁生活号+：收口时核对现网是否仍可用。  
- `verifygw` 激活回执是否只需 `success`：真机前再验。

## 收口前不做

- 不建 `oryxos-channel-alipay`  
- 不改 Runtime / POM
