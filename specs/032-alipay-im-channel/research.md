# Research: 032 支付宝生活号客服

**Date**: 2026-09-12  
**Status**: PATH LOCKED — **默认不授权 BUILD**（平台迁生活号+；新号消息能力受限）  
**对照**: [plan](./plan.md)、[030](../030-cn-c-im-roadmap/plan.md)

## 目标

官方直连：用户在支付宝 **生活号会话** 发文本 → Agent → 客服单发回复。  
排除交易投诉、模板/群发、个人号。

## 产品面澄清

| | 本渠道（拟 `alipay`） | 非本渠道 |
|--|----------------------|----------|
| 用户在哪聊 | 支付宝 App 生活号 / 咨询反馈 | — |
| API | 开放平台生活号网关 + `alipay.open.public.message.custom.send` | 小程序订阅消息、交易投诉 |
| 资质 | 生活号应用（开发者模式） | — |

## 钉死路径（旧文档仍可查）

权威入口（文档标注**后续不再更新**，指向生活号+）：  
[生活号快速接入 / 开发者模式](https://opendoc.alipay.com/fw/guide/105933)、[生活号发送消息](https://opendoc.alipay.com/fw/api/105938)、[custom.send API](https://doc.open.alipay.com/docs/api.htm?apiId=1125&docType=4)。

| 步骤 | 能力 | 说明 |
|------|------|------|
| 1 | 开发者模式 | 应用网关 URL；支付宝 POST `service=alipay.service.check` + `biz_content` XML |
| 2 | 激活验签 | `EventType=verifygw`；**支付宝公钥** RSA2 验签；编码常为 **GBK** |
| 3 | 激活回执 | 验签成功后按文档回写（公钥方式含加密后的开发者公钥等；以官方 demo / 接入文为准，**勿只回裸 `success` 字符串当唯一形态**） |
| 4 | 入站消息 | 同网关 POST；`biz_content` XML，`MsgType=text`，用户标识 `FromUserId` |
| 5 | ACK | 同步短回（demo 常回成功标识）；Agent 异步 |
| 6 | 出站 | `alipay.open.public.message.custom.send`：`to_user_id` + `msg_type=text` + `text.content` |
| 7 | 聊天展示 | 建议 `chat=1`（咨询反馈列表）；`0` 落生活号主页（非会话感） |

### 会话窗

- 用户主动与生活号交互后约 **48 小时**内可 `custom.send`。  
- 公开文未钉「每回合 N 条」；MVP 可先按 **48h + 平台错误码 fail-loud**，若真机有条数再收紧。

### chatId（草案）

`alipay:{appId}:user:{fromUserId}`

### 沙箱域名

- `openapi.alipay.com`（出站）  
- 入站为**我方网关**，无平台域名白名单需求（出站仍要 `http.allowed_domains`）

## 平台迁移风险（为何默认不 BUILD）

| 事实 | 含义 |
|------|------|
| 生活号开放文档「后续不再更新」 | API 面冻结风险；以现网为准 |
| [生活号+](https://opendoc.alipay.com/b/03b81x)：**消息 Tab / 发送能力仅旧生活号升级商家继续开放**；**新注册生活号+暂时无法体验** | 新商家可能**没有**可真机的客服 IM |
| 消息产品升级（粉丝头条等） | 群发/素材路径在变；**客服 custom.send 是否对升级号长期保留**须账号侧确认 |

→ 技术路径可写 PLAN，但 **OS 默认排期不应假设「任意支付宝商家都能接」**。

## 排除

- `message.total.send` / 模板 `single.send` / 粉丝头条冒充客服 IM  
- 交易投诉、商户工单  
- Cookie / 非官方协议  

## 准入缺口

- [x] 入站：应用网关 + verifygw + 文本 XML 族  
- [x] 出站：`alipay.open.public.message.custom.send` + 48h  
- [x] 与投诉/模板边界写清  
- [ ] 目标账号仍具备消息 Tab / custom.send（旧号升级或现网验证）  
- [ ] verifygw 回包形态按密钥类型（普通公钥 vs 证书）钉死并单测  
- [ ] 你确认可 BUILD（默认否）  

## 当前结论

| 项 | 说明 |
|----|------|
| 成熟度 | 🔶 路径清晰，**产品面 ⏸**（新号能力受限 + 文档冻结） |
| 可写 PLAN | ✅ 占位实现形状可写，标明 Blocked |
| 可马上编码 | ❌ 除非你有可用的旧生活号/已验证消息能力的账号并点头 |

## 建议下一动作

1. 有可用生活号消息能力 → 可授权 **032 BUILD**（文本 MVP）。  
2. 没有 → **032 停在 research**；路线图下一刀 **W3 小程序客服** 或 **E3 拼多多 research**。  
