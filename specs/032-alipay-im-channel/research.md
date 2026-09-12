# Research: 032 支付宝生活号客服

**Date**: 2026-09-12  
**Status**: **BUILD AUTHORIZED**（用户确认：需写代码才能实测；平台能力仍以账号侧为准）  
**对照**: [plan](./plan.md)、[030](../030-cn-c-im-roadmap/plan.md)

## 目标

官方直连：用户在支付宝 **生活号会话** 发文本 → Agent → 客服单发回复。  
排除交易投诉、模板/群发、个人号。

## 产品面澄清

| | 本渠道（`alipay`） | 非本渠道 |
|--|----------------------|----------|
| 用户在哪聊 | 支付宝 App 生活号 / 咨询反馈 | — |
| API | 开放平台生活号网关 + `alipay.open.public.message.custom.send` | 小程序订阅消息、交易投诉 |
| 资质 | 生活号应用（开发者模式） | — |

## 钉死路径

权威入口（文档标注**后续不再更新**，指向生活号+）：  
[生活号快速接入 / 开发者模式](https://opendoc.alipay.com/fw/guide/105933)、[生活号发送消息](https://opendoc.alipay.com/fw/api/105938)、[custom.send API](https://doc.open.alipay.com/docs/api.htm?apiId=1125&docType=4)。

| 步骤 | 能力 | 说明 |
|------|------|------|
| 1 | 开发者模式 | 应用网关 URL；支付宝 POST `service=alipay.service.check` + `biz_content` XML |
| 2 | 激活验签 | `EventType=verifygw`；**支付宝公钥** RSA2 验签；编码常为 **GBK** |
| 3 | 激活回执 | 普通公钥：XML 含 `<success>true</success>` + 应用公钥 + RSA2 签名 |
| 4 | 入站消息 | 同网关 POST；`biz_content` XML，`MsgType=text`，用户标识 `FromUserId` |
| 5 | ACK | 同步短回 `success`；Agent 异步 |
| 6 | 出站 | `alipay.open.public.message.custom.send`：`to_user_id` + `msg_type=text` + `text.content` |
| 7 | 聊天展示 | `chat=1`（咨询反馈列表） |

### 会话窗

- 用户主动与生活号交互后约 **48 小时**内可 `custom.send`。  
- MVP：**48h + 平台错误码 fail-loud**（不钉每回合 N 条）。

### chatId

`alipay:{appId}:user:{fromUserId}`

### 沙箱域名

- `openapi.alipay.com`（出站）  
- 入站为**我方网关**

## 平台迁移风险（实测前置）

| 事实 | 含义 |
|------|------|
| 生活号开放文档「后续不再更新」 | API 面冻结风险；以现网为准 |
| 生活号+：消息能力对新号常受限 | **真机**仍需账号具备消息 Tab / custom.send |

→ 代码已授权 BUILD；无消息能力的账号仍无法完成端到端实测。

## 排除

- `message.total.send` / 模板 `single.send` / 粉丝头条冒充客服 IM  
- 交易投诉、商户工单  
- Cookie / 非官方协议  

## 准入缺口

- [x] 入站：应用网关 + verifygw + 文本 XML 族  
- [x] 出站：`alipay.open.public.message.custom.send` + 48h  
- [x] 与投诉/模板边界写清  
- [ ] 目标账号仍具备消息 Tab / custom.send（现网验证）  
- [x] verifygw 回包形态（普通公钥）钉死并单测  
- [x] 确认可 BUILD  

## 当前结论

| 项 | 说明 |
|----|------|
| 成熟度 | 🔶 路径清晰；真机依赖账号消息能力 |
| 可写 PLAN / BUILD | ✅ 已授权 |
| 模块 | `oryxos-channel-alipay`，`type: alipay` |
