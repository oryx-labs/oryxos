# 企微智能机器人入站渠道接入指南

本文是 OryxOS 企微入站渠道的部署操作手册。架构对称飞书：以 **WebSocket 长连接** 主动连接企业微信（`wss://openws.work.weixin.qq.com`），**免公网回调地址、免 EncodingAESKey**，服务器只需出方向可达该域名。

> 范围：企业微信 **智能机器人（API 模式 + 长连接）**。群机器人 webhook 出站通知仍走 Notify `type=wecom`，与本入站渠道分离。

## 一、企微侧：创建智能机器人

1. 打开企业微信管理端 →「安全与管理」/「智能机器人」相关入口（以当前企微后台文案为准）。
2. 创建 **API 模式** 智能机器人，连接方式选择 **「使用长连接」**（不要选「设置接收消息 URL」）。
3. 记下 **BotID** 与 **长连接 Secret**。
   - ⚠️ 只经环境变量注入 OryxOS，禁止写入仓库或明文配置文件。

## 二、OryxOS 侧：配置与启动

1. **凭证走环境变量**：

   ```bash
   export WECOM_BOT_ID=xxxxxxxx
   export WECOM_BOT_SECRET=xxxxxxxx
   ```

2. **渠道绑定** `.oryxos/channels.yaml`（模板见 `config/channels.yaml.example`）：

   ```yaml
   channels:
     - name: ops-wecom
       type: wecom
       app_id: ${WECOM_BOT_ID}          # BotID
       app_secret: ${WECOM_BOT_SECRET}  # 长连接 Secret
       agent: ops-agent
       enabled: true
   ```

3. **出站域名白名单**：确保 `http.allowed_domains` 包含 `openws.work.weixin.qq.com`（或 `*.work.weixin.qq.com`）。示例配置已写入 `config/application.yml.example`。

4. 启动后查渠道状态：`GET /api/v1/channels/status`，期望 `CONNECTED`。

## 三、使用方式

- **私聊**：在企微中打开该智能机器人会话，直接发文本、图片、文件、语音或视频。
- **群聊**：将机器人拉入群后 `@机器人 + 问题`（或图片/文件/视频）；平台只推送 @ 本机器人的群消息。
- **图片**：回调里的 COS 临时 URL 会先下载落盘再送 Vision（避免 provider 直拉临时链失败）。
- **文件**：同样先下载落盘（优先 `.oryxos/inbound-media/`），正文提示本地路径供 Agent `read_file`（须在 FILE 沙箱白名单内；**文本型 PDF 可抽正文**）；不走 Vision。
- **语音**：智能机器人回调已含平台 ASR（`voice.content`），直接当用户问题编排；**仅单聊**。空转写且带 `voice.url` 时落盘并走 Whisper 兜底；否则明确提示改发文字。
- **视频**：`msgtype=video` 经 COS URL（及可选 AES）落盘；配置 Whisper + ffmpeg 时可抽音轨转写（不理解画面；`ORYXOS_VIDEO_ASR=0` 可关）。
- **媒体根**：优先 `.oryxos/inbound-media/{channel}/`（单文件 ≤50MB）；TTL/配额见 `ORYXOS_INBOUND_MEDIA_TTL_HOURS`（默认 24）与 `ORYXOS_INBOUND_MEDIA_MAX_MB`（默认 2048）。COS 下载禁用自动跟跳，重定向逐跳校验白名单。

## 四、与飞书的差异（运维须知）

| | 飞书 | 企微（本渠道） |
|--|------|----------------|
| 凭证 | App ID / App Secret | BotID / 长连接 Secret |
| 连接 | `open.feishu.cn` SDK 长连接 | `openws.work.weixin.qq.com` WebSocket |
| 回复 | im/v1 messages API（post + md） | 长连接 `aibot_send_msg`（markdown） |
| 进度提示 | 交互卡片原地 PATCH（思考→工具→终态；`/stop` 红卡「已停止」） | 占位 + 可选长 TTFT 心跳 + 至多一条工具 + 终态（无原地编辑；失败/`/stop` 专用句） |
| 入站图/文件 | image_key 官方下载 | COS URL + AES 落盘（≤50MB；防重定向 SSRF） |
| ASR | Whisper + ffmpeg（silk 常见） | 平台 ASR；空转写可 Whisper 兜底 |
| 同一 Bot 连接数 | SDK 管理 | 同时仅一条有效长连接（新连踢旧） |
| 命令 | 私聊 `/new` 清会话；私聊/群聊 `/stop` 停进行中推理（下一轮生效） | 同（核心编排，渠道无特殊实现） |

## 五、非目标（本期不做）

- 自建应用 HTTP 回调 + AES 加解密（入站图 COS 解密除外）
- 逐 token 刷屏 / 模板卡片 HITL / 企微原地 PATCH
- 群聊语音（平台仅单聊语音）；视频画面理解 / 抽帧 Vision
