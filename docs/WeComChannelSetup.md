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

- **私聊**：在企微中打开该智能机器人会话，直接发文本或图片。
- **群聊**：将机器人拉入群后 `@机器人 + 问题`（或图片）；平台只推送 @ 本机器人的群消息。
- **图片**：回调里的 COS 临时 URL 会先下载落盘再送 Vision（避免 provider 直拉临时链失败）。

## 四、与飞书的差异（运维须知）

| | 飞书 | 企微（本渠道） |
|--|------|----------------|
| 凭证 | App ID / App Secret | BotID / 长连接 Secret |
| 连接 | `open.feishu.cn` SDK 长连接 | `openws.work.weixin.qq.com` WebSocket |
| 回复 | im/v1 messages API | 长连接 `aibot_send_msg`（markdown） |
| 入站图 | image_key 官方下载 | COS 临时 URL → 本地落盘 |
| 同一 Bot 连接数 | SDK 管理 | 同时仅一条有效长连接（新连踢旧） |

## 五、非目标（本期不做）

- 自建应用 HTTP 回调 + AES 加解密
- 流式逐 token 刷屏（Agent 仍整段推理后再回发）
- 模板卡片 / 富媒体 / HITL
- 语音 / 视频 / 文件入站（仅图片 + 文本）
