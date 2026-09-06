# Discord 机器人入站渠道接入指南

本文是 OryxOS Discord 入站渠道的部署操作手册。架构对称飞书/企微/钉钉/Slack：以 **Gateway WebSocket** 主动连接 Discord（免公网回调 URL）；回复经 REST `POST /channels/{id}/messages`。

> 范围：Discord Bot **Gateway v10**。文本私聊与公会频道 `@Bot`；入站图片/文件/语音/视频经 CDN 下载落盘（语音/视频音轨走 Whisper ASR，对齐飞书/企微/钉钉）。

## 一、Discord 侧：创建 Application 并启用 Intents

1. 打开 [Discord Developer Portal](https://discord.com/developers/applications) → **New Application**，命名后创建。
2. 左侧 **Bot** → **Add Bot**（若尚未创建）。
3. **Privileged Gateway Intents** 打开：
   - **Message Content Intent**（必开，否则收不到消息正文）
   - （可选）Server Members Intent — MVP 不需要
4. Bot 页 **Reset Token** / 复制 **Bot Token**（保密，对应 OryxOS `DISCORD_BOT_TOKEN`）。
5. 左侧 **General Information** 复制 **Application ID**（对应 `DISCORD_APPLICATION_ID`；Bot 的 User ID 通常与此相同，用于 `@` 匹配）。
6. **OAuth2 → URL Generator**：
   - Scopes：`bot`
   - Bot Permissions：至少 `Send Messages`、`Read Message History`、`View Channels`、`Attach Files`（附件入站/出站体验）；私聊还需能接收 DM（默认 Bot 可开 DM）
   - 生成 URL，用浏览器邀请 Bot 进目标服务器。
7. 用户需在 Discord **用户设置 → 隐私与安全** 允许来自服务器成员的私信（若测 DM）。

   - ⚠️ 凭证只经环境变量注入 OryxOS，禁止写入仓库或明文配置文件。

## 二、OryxOS 侧：配置与启动

1. **凭证走环境变量**：

   ```bash
   export DISCORD_BOT_TOKEN=...
   export DISCORD_APPLICATION_ID=...
   # 语音 / 视频音轨转写（与其它 IM 渠道共用）
   export OPENAI_API_KEY=...   # 或 ORYXOS_ASR_API_KEY / ORYXOS_ASR_BASE_URL
   # 视频抽轨 / 非原生音频格式常需 ffmpeg
   export ORYXOS_FFMPEG=...    # 或确保 ffmpeg 在 PATH
   ```

2. **渠道绑定** `.oryxos/channels.yaml`（模板见 `config/channels.yaml.example`）：

   ```yaml
   channels:
     - name: ops-discord
       type: discord
       app_id: ${DISCORD_BOT_TOKEN}            # Bot Token → Gateway Identify + REST
       app_secret: ${DISCORD_APPLICATION_ID}   # Application ID → @提及匹配
       agent: ops-agent
       enabled: true
   ```

3. **出站域名白名单**：确保 `http.allowed_domains` 包含：
   - `discord.com` — REST API
   - `gateway.discord.gg` — Gateway WSS
   - `cdn.discordapp.com` / `media.discordapp.net` / `*.discordapp.com` / `*.discordapp.net` — 入站附件 CDN

4. 启动后查渠道状态：`GET /api/v1/channels/status`，期望 `CONNECTED`。

## 三、使用方式

- **私聊**：在 Discord 中打开该 Bot 的 DM，直接发文本、图片、文件、语音或视频。
- **公会频道**：将 Bot 拉入服务器/频道后 `@Bot + 问题`（可带附件；平台推送 `MESSAGE_CREATE` 且含提及）。
- **图片 / 文件**：经 `attachments[].url`（CDN）带 Bot Token 落盘到 `.oryxos/inbound-media/`；图片可供 Vision，文件路径写入 Agent 提示。
- **语音**：`content_type` 为 `audio/*`、Voice Message（`flags` 含语音位）、或带 `waveform`/`duration_secs` 的附件 → 落盘后经 Whisper 转写注入正文。
- **视频**：`content_type` 为 `video/*` 或常见视频扩展名 → 落盘；默认尝试抽音轨 ASR（可用 `ORYXOS_VIDEO_ASR=0` 关闭）；**不自动理解画面**。
- **联网检索**：须在绑定 Agent 的 `AGENT.md` `tools:` 中加入 `web_search` 等，见 Tool 文档。

## 四、与其它渠道的差异

| | 飞书/企微/钉钉 | Slack | Discord（本渠道） |
|--|----------------|-------|-------------------|
| 凭证 | App ID/Secret 等 | Bot Token + App-Level Token | Bot Token + Application ID |
| 连接 | 各家长连接 | Socket Mode WSS | Gateway WSS v10 |
| 回复 | 各平台 API | chat.postMessage | channels/{id}/messages |
| MVP 媒体 | 图/文件/音视频 | 图片+文件 | **图片 + 文件 + 语音 + 视频** |

## 五、非目标（本期不做）

- 视频画面理解 / 抽帧 Vision
- Slash Commands / Interactions / Components
- HTTP Interactions 公网回调
- Notify `type=discord`
