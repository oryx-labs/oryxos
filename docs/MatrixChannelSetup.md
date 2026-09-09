# Matrix / Element 渠道接入指南

Client-Server API **`/sync` 长轮询**（免公网回调）。`extra.homeserver` 为 homeserver 根 URL。

## 一、Homeserver 侧

1. 为 Bot 注册账号（如 `@oryx:example.com`），创建 access token。
2. 邀请 Bot 进房间；房间消息需提及 Bot MXID（正文含 `@bot:server` 或 `m.mentions.user_ids`）。
3. 把 homeserver 主机名加入 `http.allowed_domains`（自托管，不进默认清单）。本机 Docker 用 `127.0.0.1` / `localhost`。

本机验收可用：

- Synapse：`oryxos-synapse` → `:8008`（`MATRIX_HOMESERVER=http://127.0.0.1:8008`）
- Element：`oryxos-element` → http://127.0.0.1:8087 ，Homeserver 填 `http://127.0.0.1:8008`
- 用户 `admin` / `tester` / Bot `oryxbot`，密码只在本机 `.env` `MATRIX_*_PASSWORD`

## 二、OryxOS 侧

```yaml
channels:
  - name: ops-matrix
    type: matrix
    app_id: ${MATRIX_BOT_USER}
    app_secret: ${MATRIX_ACCESS_TOKEN}
    agent: ops-agent
    extra:
      homeserver: ${MATRIX_HOMESERVER}
    enabled: true
```

## 三、Notify

`type: matrix`：`homeserver` + `token` + `room_id`。发信走 **PUT** `/_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txn}`（POST 会被 homeserver 以 405 拒绝）。

## 四、图片 / PDF

事件里的 `mxc://` 用 Bot token 走鉴权媒体接口落盘（`.oryxos/inbound-media/`），再交给与飞书相同的 Vision / `read_file` / Whisper。房间图/文件须提及 Bot（`@bot:server`、`@localpart` 或 `m.mentions`）；私聊不要求 @。

## 五、实测清单

- 房间 @ 往返（本机 Town Square + Element `:8087`）
- 私聊（邀请自动加入；`m.direct` 在顶层 account_data，跨 sync 记住）
- `notify` 进房间（本机 `ops-mx-notify` 已测）
- 房间 `@Bot` + 图片 / PDF（说明里带 @，与附件同条或紧随）
