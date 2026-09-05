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

- **单聊**：在钉钉中打开该机器人会话，直接发文本、图片、文件、语音或视频。
- **群聊**：将机器人拉入群后 `@机器人 + 问题`（或图片/文件/视频）；平台只推送 `isInAtList=true` 的群消息。
- **图片**：Stream 回调常见 `downloadCode`（无直链）。渠道会调用开放平台「下载机器人接收消息的文件内容」换临时 URL 并落盘，再交给 Vision；`robotCode` 默认等于 ClientId（`app_id`）。
- **文件**：同样经 `downloadCode`（或直链）落盘到 `.oryxos/inbound-media/`，正文提示本地路径供 `read_file`（**文本型 PDF 可抽正文**）；不走 Vision。
- **语音**：`msgtype=audio` 经 `downloadCode` 落盘；配置 `OPENAI_API_KEY`（或 `ORYXOS_ASR_API_KEY`）后用 Whisper 转写进 Agent。非 Whisper 原生格式（如 silk/amr）会经本机 `ffmpeg`（`PATH` / `ORYXOS_FFMPEG`）转 wav；未安装则转写失败并提示。
- **视频**：`msgtype=video` 经 `downloadCode` 落盘；有 Whisper + ffmpeg 时可抽音轨转写（不理解画面；`ORYXOS_VIDEO_ASR=0` 可关）。
- **媒体根**：`.oryxos/inbound-media/`（≤50MB）；TTL/配额：`ORYXOS_INBOUND_MEDIA_TTL_HOURS` / `ORYXOS_INBOUND_MEDIA_MAX_MB`。临时链下载禁用自动跟跳，重定向逐跳校验钉钉/OSS 白名单。

## 四、与飞书/企微的差异（运维须知）

| | 飞书 | 企微 | 钉钉（本渠道） |
|--|------|------|----------------|
| 凭证 | App ID / App Secret | BotID / 长连接 Secret | ClientId / ClientSecret |
| 连接 | SDK 长连接 | `openws.work.weixin.qq.com` WS | `api.dingtalk.com` Stream WS |
| 回复 | im API（post + md） | 长连接 `aibot_send_msg` | `sessionWebhook` POST markdown |
| 进度提示 | 交互卡片原地 PATCH | 占位 + 可选心跳 + 工具 + 终态 | 同企微（无原地 PATCH） |
| 入站图/文件 | message_id + image_key/file_key 下载 | COS URL + AES 落盘 | `downloadCode` → 临时 URL 落盘（防 SSRF） |
| ASR | Whisper + ffmpeg | 平台 ASR（可 Whisper 兜底） | Whisper + ffmpeg |
| 群 @ 关联 | open_id / mentioned | `quote.msgid` | `at.atUserIds`（非真引用线程） |
| 命令 | `/new`（私聊）；`/stop`（私聊/群聊进行中任务） | 同 | 同 |

## 五、非目标（本期不做）

- HTTP 回调 + 加解密旧模式
- 逐 token 刷屏 / 模板卡片 HITL / 钉钉原地 PATCH / 真·引用线程回复
- 视频画面理解 / 抽帧 Vision；未配置 Whisper / 未安装 ffmpeg 时非原生语音与视频音轨仅落盘无法听懂内容
