# Mattermost 渠道接入指南

私有化优先于 Matrix。入站用 **Outgoing Webhook**（表单 POST 到共享 inbound URL）；回复走 `POST /api/v4/posts`。

## 一、Mattermost 侧

1. 系统控制台 → Integrations → Outgoing Webhooks：Callback URL = `https://<host>/api/v1/channels/inbound/ops-mattermost`，记下 Token。本机 Docker 回调宿主机 OryxOS 用 `http://host.docker.internal:8080/api/v1/channels/inbound/ops-mattermost`。
2. Trigger Word 设为 `@你的Bot用户名`（频道 @ 规则）。
3. 发帖用 **Personal Access Token**（或 Bot token）写入 `extra.access_token`。Outgoing Webhook 的 Token 只用于入站校验（`app_secret`），一般不能调 `POST /api/v4/posts`。
4. `extra.base_url` 为站点根（如 `https://mm.example.com` 或本机 `http://127.0.0.1:8065`），需加入 `http.allowed_domains`。

## 二、OryxOS 侧

```yaml
channels:
  - name: ops-mattermost
    type: mattermost
    app_id: ${MATTERMOST_BOT_USERNAME}
    app_secret: ${MATTERMOST_TOKEN}
    agent: ops-agent
    extra:
      base_url: ${MATTERMOST_BASE_URL}
      access_token: ${MATTERMOST_ACCESS_TOKEN} # PAT；省略则回退 app_secret
    enabled: true
```

自托管域名不进默认白名单，请在部署配置里显式加入。本机 Docker 回调宿主机时，Mattermost 还需把 `host.docker.internal`（或对应 CIDR）写入 **AllowedUntrustedInternalConnections**，否则 Outgoing Webhook 会静默 `address forbidden`。

## 三、Notify

`type: mattermost`：Incoming Webhook `url`。

## 四、图片 / PDF

Outgoing Webhook **不带附件**。OryxOS 在入站后用 PAT 调 `GET /api/v4/posts/{id}` 取 `file_ids`，再 `GET /api/v4/files/{id}` 落盘（`.oryxos/inbound-media/`），交给与飞书相同的 Vision / `read_file` 路径。

发图或 PDF 时说明里必须带 `@Bot`（否则 Webhook 不会触发）。`@Bot` 和附件必须在**同一条帖**里一次发出；先发文字再补附件会变成两条（第二条无 trigger，Agent 看不到文件）。只 @ 不配字、但带了附件，也会进编排。

## 五、实测清单

- 私聊 / 房间 @ 往返
- `notify` 进房间（本机 Incoming Webhook + `ops-mm-notify` 已测）
- 房间 `@Bot` + 图片 / PDF（说明里带 @，与附件同一条帖；本机 Town Square UI 已测）
