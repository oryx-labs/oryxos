# QQ 官方机器人入站渠道接入指南

本文是 OryxOS QQ 入站渠道的部署手册。以 **Gateway WebSocket** 连接 QQ 开放平台（免公网回调）；回复经 REST `/v2/groups|users/.../messages`。出站告警另见 Notify `type: qq`。

> 范围：QQ **群** `@Bot`（`GROUP_AT_MESSAGE_CREATE`）与 **单聊**（`C2C_MESSAGE_CREATE`）。频道 / 子频道为二期。不做个人号协议。

## 一、开放平台侧

1. 打开 [QQ 开放平台](https://q.qq.com/) / [机器人文档](https://bot.q.qq.com/wiki/) 创建机器人，记下 **AppID**、**AppSecret**。
2. 管理端订阅事件：至少开启群与单聊相关能力（对应 Intent `GROUP_AND_C2C_EVENT`）。
3. 将机器人拉入**沙箱/测试群**；群内通常需 **@Bot** 才会推送群消息事件。
4. 若启用 IP 白名单：正式环境把本机/服务器出口 IP 加入白名单（沙箱一般不受限）。接口域：
   - 换票 / OpenAPI：`https://api.bot.qq.com`
   - Gateway：自 `GET /gateway` 返回的 `wss://…`（常见 `api.bot.qq.com` 同源）
5. 凭证只走环境变量，禁止写入仓库。

## 二、OryxOS 侧

```bash
export QQ_APP_ID=...
export QQ_APP_SECRET=...
```

`.oryxos/channels.yaml`（模板见 `config/channels.yaml.example`）：

```yaml
channels:
  - name: ops-qq
    type: qq
    app_id: ${QQ_APP_ID}
    app_secret: ${QQ_APP_SECRET}
    agent: demo-agent
    enabled: true
```

`http.allowed_domains` 需包含 `api.bot.qq.com`（见 `config/application.yml.example`）。

启动后 `GET /api/v1/channels/status` 期望 `CONNECTED`（Gateway READY）。

## 三、行为约定

| 场景 | 行为 |
|------|------|
| 群 `@Bot` 文本 | 进编排；回复带被动 `msg_id`（约 5 分钟窗） |
| 单聊文本 | 进编排；无私聊 @ 要求；回复走 `/v2/users/{openid}/messages` |
| 重复 `msg_id` | 去重，只答一次 |
| 频道消息 | MVP 不处理 |

内部 `chatId` 形如 `group:{group_openid}` / `user:{user_openid}`（发信路径编码，对用户不可见）。

## 四、Notify

管理台 `type: qq`：

- `token`：已换好的 `access_token`（`QQBot` 鉴权值，不含前缀）
- `group_openid` **或** `user_openid`
- 联调可另给 `url` 打到假 HTTP，body 仍为 `{ "msg_type": 0, "content": "..." }`

主动消息受官方限额与用户/群「允许主动消息」开关约束；群被动优先用入站 `msg_id`。

## 五、实测清单

- 群 `@Bot` 往返  
- 单聊往返  
- `notify` 进指定 openid  
- 无凭证时以单测为准，不宣称生产联调完成
