# 微信客服渠道（企业微信 `kf/*`）

用户在**微信**里咨询企业客服；OryxOS 通过企微「微信客服」OpenAPI 收发。  
与现有 `wecom`（企微应用内智能机器人 WS）及 `weixin`（个人 iLink）**不是同一产品**。

## 前置

1. 企业微信已开通**微信客服**，并有客服账号 `open_kfid`
2. 使用**微信客服 Secret**（非普通应用 secret）换 `access_token`
3. 回调配置：URL + Token + EncodingAESKey（企微同族加解密）
4. 公网 HTTPS 可达 OryxOS：`GET/POST /api/v1/channels/inbound/{name}`

## channels.yaml

```yaml
  - name: ops-weixin-kf
    type: weixin_kf
    app_id: ${WECOM_CORP_ID}              # CorpId
    app_secret: ${WEIXIN_KF_SECRET}       # 微信客服 Secret
    agent: demo-agent
    extra:
      token: ${WEIXIN_KF_TOKEN}
      encoding_aes_key: ${WEIXIN_KF_AES_KEY}
      open_kfid: ${WEIXIN_KF_OPEN_KFID}
    enabled: true
```

回调地址示例：`https://<host>/api/v1/channels/inbound/ops-weixin-kf`

## 出站白名单

`http.allowed_domains` 需包含 `qyapi.weixin.qq.com`（示例配置已有）。

## 行为摘要

| 步骤 | 说明 |
|------|------|
| 入站 | 回调 `kf_msg_or_event` → `kf/sync_msg` 拉消息（文本 + 图/文件/语音/视频）；语音 `voice_format=0`（AMR，便于本机 ffmpeg→Whisper） |
| sync 游标 | 持久化 `.oryxos/weixin-kf-sync-cursor-{name}.txt`。**启动时排空积压**（推进 cursor、不进 Agent）。进程宕机期间到达的消息会因此丢弃——刻意取舍，防止历史 HI/图回放刷屏并撞每回合 5 条上限 |
| 媒体 | `media_id` → `GET /cgi-bin/media/get` 落盘；先回「处理中」，再编排（Vision / `read_file` / Whisper）。sync 拉批与 cursor 更新在锁内，下载/编排放锁外，避免大文件拖住其它回调 |
| 会话 | 私聊连续记忆（与其它 IM 一致）；`/new` 仅可选手动清空 |
| 会话态 | 尽量转到「智能助手接待」后再编排/发信；`service_state` 无权限（48002）时跳过仍尝试 `send_msg` |
| 出站 | `kf/send_msg` 文本；**48h / 每回合最多 5 条**，窗外硬拒绝 |
| chatId | `kf:{open_kfid}:user:{external_userid}` |

## 与其它微信系对照

| type | 场景 |
|------|------|
| `weixin_kf`（本渠道） | 企业对客微信客服 |
| `wecom` | 企微应用内机器人 |
| `weixin` | 个人微信 iLink Bot |

## MVP 非目标

出站发图/文件；多 `open_kfid` 路由；人工排班 UI；服务号/小程序客服。
