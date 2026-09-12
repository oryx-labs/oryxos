# Research: 036 微信服务号客服消息（W2）

**Date**: 2026-09-12  
**Status**: BUILD（036 文本 MVP 编码中/已合入）  
**对照**: [plan](./plan.md)、[030](../030-cn-c-im-roadmap/plan.md)、[033 微信客服](../033-weixin-kf-channel/research.md)、[035 iLink](../035-weixin-ilink-channel/plan.md)

## 产品面澄清（禁止混接）

| | `wecom` | `weixin_kf`（033） | `weixin` iLink（035） | **本渠道 `weixin_mp`（拟）** |
|--|---------|-------------------|----------------------|------------------------------|
| 用户在哪聊 | 企微应用 | 微信里找**企业客服** | 个人微信扫码 Bot | 微信里找**服务号**会话 |
| API 域 | `qyapi…` 应用/机器人 | `qyapi…/kf/*` | iLink 网关 | `api.weixin.qq.com` 公众平台 |
| 入站 | 企微回调/WS | `kf_msg_or_event` + sync | iLink 推送 | 服务号**服务器配置 URL** XML 推送 |
| 出站 | 应用消息等 | `kf/send_msg` | iLink 发信 | `message/custom/send` |
| 资质 | 企微 | 企微+微信客服 | 个人微信 | **认证服务号**（企业主体） |

不得把「033 已接」或「企微已有」当成「服务号客服已覆盖」。

## 钉死路径（官方文档）

权威入口：[消息与事件推送](https://developers.weixin.qq.com/doc/service/guide/dev/push/)、[接收普通消息](https://developers.weixin.qq.com/doc/service/guide/product/message/Receiving_standard_messages.html)、[发送客服消息](https://developers.weixin.qq.com/doc/service/api/customer/message/api_sendcustommessage.html)、[消息加解密](https://developers.weixin.qq.com/doc/service/guide/dev/push/encryption.html)。

| 步骤 | 能力 | 说明 |
|------|------|------|
| 1 | 服务器配置 | URL + Token + EncodingAESKey；**强烈推荐安全模式**（AES）；数据格式 XML |
| 2 | URL 验证 | GET：`signature`/`timestamp`/`nonce`/`echostr`；Token+timestamp+nonce 字典序拼接后 SHA-1，相等则原样回 `echostr` |
| 3 | 入站 | POST XML（或加密包）；`MsgType=text` 时读 `FromUserName`（OpenID）、`Content`、`MsgId` |
| 4 | 快速 ACK | **5 秒内**须响应；Agent 编排慢 → MVP **回空串或 `success`**（勿依赖被动回复 XML 承载 Agent 全文） |
| 5 | 出站 | `POST https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=…`，`msgtype=text` + `touser=OpenID` |
| 6 | access_token | `appid` + `secret` 换票（公众平台；与企微 corpid **不是同一套**） |

### 入站文本样例（明文）

```xml
<xml>
  <ToUserName><![CDATA[gh_xxx]]></ToUserName>
  <FromUserName><![CDATA[OPENID]]></FromUserName>
  <CreateTime>1348831860</CreateTime>
  <MsgType><![CDATA[text]]></MsgType>
  <Content><![CDATA[this is a test]]></Content>
  <MsgId>1234567890123456</MsgId>
</xml>
```

### 出站文本样例

```json
{
  "touser": "OPENID",
  "msgtype": "text",
  "text": { "content": "你好" }
}
```

可选 `customservice.kf_account` 以客服号身份发；`aimsgcontext.is_ai_msg=1` 可打「第三方 AI 生成」灰字（2025-11 文档更新）。

## 会话窗（fail-loud）

官方额度表（[sendCustomMessage](https://developers.weixin.qq.com/doc/service/api/customer/message/api_sendcustommessage.html)）：

| 触发场景 | 下行额度 | 有效期 |
|----------|----------|--------|
| **用户发送消息** | **5 条** | **48 小时** |
| 点击自定义菜单（特定类型） | 3 条 | 1 分钟 |
| 关注公众号 | 3 条 | 1 分钟 |
| 扫描二维码 | 3 条 | 1 分钟 |

MVP 纪律：

- 仅把「用户发来的普通消息」当主会话触发；菜单/关注/扫码事件可记日志，**默认不进 Agent**（或进了也只给 1min/3 条窗，极易撞墙）。  
- 适配器按 OpenID 记账：用户文本回合后最多 5 条 `custom/send`；窗外或超条数 **硬拒绝**（对齐 033）。  
- 常见错误：`45015` response out of time 等 → fail-loud，不静默吞。

## 被动回复 vs 客服接口（选型）

| 方式 | 用途 | Agent OS |
|------|------|----------|
| 被动回复 XML（5s 内） | 即时短回、转人工 `transfer_customer_service` | **不用**承载完整 Agent 答（超时重试） |
| `message/custom/send` | 交互后异步发信 | **MVP 出站唯一路径** |
| 转发多客服 | 人工工作台 | 非目标（可二期命令触发） |

## 凭证与配置（拟）

```yaml
type: weixin_mp
app_id: ${WEIXIN_MP_APP_ID}
app_secret: ${WEIXIN_MP_APP_SECRET}
extra:
  token: ${WEIXIN_MP_TOKEN}
  encoding_aes_key: ${WEIXIN_MP_AES_KEY}   # 安全模式必填
  # encrypt_mode: safe                    # 默认安全
```

## chatId

建议：`mp:{appId}:user:{openId}`。

排重：有 `MsgId` 用 MsgId；事件用 `FromUserName+CreateTime`（官方建议）。

## 沙箱域名

- `api.weixin.qq.com`（token + custom/send；媒体二期再补）

## 与 W3 小程序客服

小程序客服消息同属公众平台客服能力族（推送 + `custom/send` 系），但绑定小程序 AppId、推送配置入口不同。  
**本 036 只钉服务号**；W3 另开 research，禁止混进同一 `type` 除非产品确认「一模块多 app 类型」。

## 排除

- 订阅号未认证 / 无客服接口权限  
- 群发、模板消息、订阅通知冒充「客服 IM 完成」  
- 个人号 / 协议号  
- 把本渠道并进 `weixin_kf` 或 `wecom`

## 准入（BUILD 前）

- [x] 入站：URL 验签 + XML 文本样例  
- [x] 出站：`message/custom/send` + 额度表  
- [x] 与 033/035/wecom 边界写清  
- [x] 资质：认证服务号（企业主体）  
- [x] 你确认可以 BUILD  
- [ ] 有测试号或认证号可真机（可后置）

## 当前结论

| 项 | 说明 |
|----|------|
| 成熟度 | ✅ Ready（文档完整，比淘宝易真机） |
| 可写 PLAN | ✅ |
| 可马上编码 | 等你点头 BUILD |
