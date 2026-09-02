# 钉钉机器人入站渠道接入指南

本文是 OryxOS 钉钉入站渠道的部署操作手册。架构对称飞书/企微：以 **Stream WebSocket 长连接** 主动连接钉钉开放平台（`api.dingtalk.com`），**免公网回调地址**，服务器只需出方向可达该域名；回复经消息附带的 `sessionWebhook` 发往 `oapi.dingtalk.com`。

> 范围：钉钉开放平台 **Stream 模式机器人**。群机器人 webhook 出站通知仍走 Notify `type=dingtalk`，与本入站渠道分离。

## 一、钉钉侧：创建应用并启用机器人

1. 打开 [钉钉开放平台](https://open.dingtalk.com/)，登录后进入「应用开发」。
2. 创建 **企业内部应用**（或 H5 微应用，以当前后台文案为准），记下 **ClientId** 与 **ClientSecret**（应用凭证页）。
3. 为应用添加 **机器人** 能力，消息接收方式选择 **Stream 模式**（不要选 HTTP 回调）。
4. 按需开通机器人相关权限（单聊、群聊 @ 机器人等，以开放平台当前权限列表为准）。
5. 发布应用版本并使机器人对目标组织/群可见。

   - ⚠️ 凭证只经环境变量注入 OryxOS，禁止写入仓库或明文配置文件。

## 二、OryxOS 侧：配置与启动

1. **凭证走环境变量**：

   ```bash
   export DINGTALK_CLIENT_ID=dingxxxxxxxx
   export DINGTALK_CLIENT_SECRET=xxxxxxxx
   ```

2. **渠道绑定** `.oryxos/channels.yaml`（模板见 `config/channels.yaml.example`）：

   ```yaml
   channels:
     - name: ops-dingtalk
       type: dingtalk
       app_id: ${DINGTALK_CLIENT_ID}       # ClientId
       app_secret: ${DINGTALK_CLIENT_SECRET}
       agent: ops-agent
       enabled: true
   ```

3. **出站域名白名单**：确保 `http.allowed_domains` 包含：
   - `api.dingtalk.com` — Stream 网关开连接
   - `oapi.dingtalk.com` — `sessionWebhook` 文本回复

   示例配置已写入 `config/application.yml.example` 与 jar 内默认 `application.yml`。

4. 启动后查渠道状态：`GET /api/v1/channels/status`，期望 `CONNECTED`。

## 三、使用方式

- **单聊**：在钉钉中打开该机器人会话，直接发文本或图片。
- **群聊**：将机器人拉入群后 `@机器人 + 问题`（或图片）；平台只推送 `isInAtList=true` 的群消息。
- **图片**：Stream 回调常见 `downloadCode`（无直链）。渠道会调用开放平台「下载机器人接收消息的文件内容」换临时 URL 并落盘，再交给 Vision；`robotCode` 默认等于 ClientId（`app_id`）。

## 四、与飞书/企微的差异（运维须知）

| | 飞书 | 企微 | 钉钉（本渠道） |
|--|------|------|----------------|
| 凭证 | App ID / App Secret | BotID / 长连接 Secret | ClientId / ClientSecret |
| 连接 | SDK 长连接 | `openws.work.weixin.qq.com` WS | `api.dingtalk.com` Stream WS |
| 回复 | im API | 长连接 `aibot_send_msg` | `sessionWebhook` POST |
| 入站图 | message_id + image_key 下载 | payload 直链 URL | `downloadCode` → 临时 URL 落盘 |
| 群 @ 关联 | open_id / mentioned | `quote.msgid` | `at.atUserIds`（B4） |

## 五、非目标（本期不做）

- HTTP 回调 + 加解密旧模式
- 流式逐 token 刷屏（Agent 仍整段推理后再回发）
- 模板卡片 / 富媒体 / HITL
- 语音 / 视频 / 文件入站（仅图片 + 文本）
