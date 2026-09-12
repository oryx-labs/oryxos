# Research: 037 微信小程序客服消息（W3）

**Date**: 2026-09-12  
**Status**: READY（路径已钉死；**BUILD 待你确认**）  
**对照**: [plan](./plan.md)、[030](../030-cn-c-im-roadmap/plan.md)、[036 服务号](../036-weixin-mp-channel/research.md)

## 产品面澄清（禁止与 036 混 type）

| | `weixin_mp`（036） | **本渠道 `weixin_mini`（拟）** |
|--|-------------------|-------------------------------|
| 用户入口 | 关注服务号会话 | 小程序内 `<button open-type="contact">` / 客服会话 |
| 后台配置 | 服务号「消息与事件推送」 | 小程序「开发管理 → 消息推送」 |
| AppId | 公众号 AppId | **小程序 AppId**（OpenID 空间不同） |
| GET 验 URL | 安全模式常为 `msg_signature` + **加密 echostr**（与企微同族） | 文档示例：`signature`=SHA1(token,ts,nonce)，**原样回 echostr**（明文校验） |
| POST 包体 | 多为 XML | **XML 或 JSON**（后台可选） |
| 出站 | 同 `message/custom/send` | 同 `api.weixin.qq.com/.../message/custom/send` |

不得把「036 已接」当成「小程序客服已覆盖」——配置入口、验签、AppId/OpenID 均独立。

## 钉死路径（官方文档）

| 步骤 | 能力 | 说明 |
|------|------|------|
| 1 | 消息推送配置 | URL + Token + EncodingAESKey；推荐**安全模式**；数据格式 XML 或 JSON |
| 2 | URL 验证 | GET：`signature`/`timestamp`/`nonce`/`echostr`；Token+ts+nonce 字典序 SHA-1，相等则**原样返回 echostr** |
| 3 | 入站 | POST 用户客服会话消息；`MsgType=text` → `FromUserName`(OpenID)、`Content`、`MsgId` |
| 4 | ACK | 回空串或 `success`；Agent 异步（勿用被动长文挡 5s） |
| 5 | 出站 | `POST /cgi-bin/message/custom/send`：`touser` + `msgtype=text` |
| 6 | access_token | 小程序 `appid` + `secret` → `cgi-bin/token` |

权威入口：  
[消息推送](https://developers.weixin.qq.com/miniprogram/dev/framework/server-ability/message-push.html)、  
[接收消息和事件](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/customer-message/receive.html)、  
[客服消息使用指南](https://developers.weixin.qq.com/miniprogram/introduction/custom)、  
[发送客服消息](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/customer-message/send.html)。

### 入站文本样例

JSON：

```json
{
  "ToUserName": "toUser",
  "FromUserName": "fromUser",
  "CreateTime": 1482048670,
  "MsgType": "text",
  "Content": "this is a test",
  "MsgId": 1234567890123456
}
```

XML：同字段 CDATA 包一层。

安全模式：包体含 `Encrypt`；用 `msg_signature` 验签；解密帧与公众平台同族（`random + msg_len + msg + appid`，PKCS#7=32，receiveId=**小程序 AppId**）。

### 会话窗（fail-loud）

[客服消息使用指南](https://developers.weixin.qq.com/miniprogram/introduction/custom)：

| 用户动作 | 下行额度 | 有效期 |
|----------|----------|--------|
| **用户发送消息** | **5 条** | **48 小时** |
| 用户进入客服消息 | 2 条 | 1 分钟 |

MVP：只把「用户发送的 text」当主触发；「进入客服」事件可记日志、默认不进 Agent（1min/2 条易撞墙）。

## 与云开发 / 云托管

云函数、云托管也可收客服推送——**OS 默认走自建 URL**（与其它渠道一致）。云路径可作为可选部署说明，不替代 `oryxos-channel-*` 直连。

转发人工：`transfer_customer_service` 响应 — **非目标**（可二期命令）。

## 凭证与配置（拟）

```yaml
type: weixin_mini
app_id: ${WEIXIN_MINI_APP_ID}
app_secret: ${WEIXIN_MINI_APP_SECRET}
extra:
  token: ${WEIXIN_MINI_TOKEN}
  encoding_aes_key: ${WEIXIN_MINI_AES_KEY}
  # data_format: xml   # MVP 钉死一种；建议 xml 便于与 WXBizMsgCrypt 对齐
```

## chatId

`mini:{appId}:user:{openId}`

## 沙箱域名

- `api.weixin.qq.com`

## 与 036 代码复用边界

| 可对齐 | 须分开 |
|--------|--------|
| AES 解密帧 / PKCS7=32 / custom.send / 48h·5 条窗 | GET URL 验签（小程序多为明文 echostr） |
| OutboundGuard 白名单 | Runtime `type`、配置、Setup、OpenID 空间 |
| | JSON 入站（036 MVP 未做） |

**禁止**：把小程序 AppId 配进 `weixin_mp` 渠道「凑合用」。

## 排除

- 订阅消息 / 模板消息冒充客服 IM  
- 仅云开发、无自建 URL 的「完成定义」  
- 个人号  

## 准入（BUILD 前）

- [x] 入站推送 + 文本样例 + 安全模式解密要点  
- [x] 出站 custom/send + 5 条/48h  
- [x] 与 036/033/wecom 边界写清  
- [x] GET 验签与 036 差异写清  
- [ ] 你确认可以 BUILD  
- [ ] 有可测小程序（可后置）

## 当前结论

| 项 | 说明 |
|----|------|
| 成熟度 | ✅ Ready |
| 可写 PLAN | ✅ |
| 可马上编码 | 等你点头 BUILD |
