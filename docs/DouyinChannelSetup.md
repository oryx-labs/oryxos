# 抖音经营私信渠道接入指南

依赖波次 0 共享入站 Webhook：`POST /api/v1/channels/inbound/{name}`。发信走 `POST https://open.douyin.com/im/send/msg/`（场景一 `im_reply_msg`）。

> **会话窗**：用户私信后约 **24h** 内可回复，且条数有上限（适配器默认按官方约 6 条计）。窗外 / 超条数 / 无上下文时 `sendReply` **硬拒绝**（可读错误），不静默。  
> **资质**：需认证企业号（或文档允许的经营号）申请 `im.direct_message`；经营者 OAuth `access_token`。无资质不写真机 COMPLETE。

## 一、抖音开放平台

1. 创建移动/网站应用，拿到 `client_key` / `client_secret`。  
2. 控制台配置 Webhooks 回调（须 **固定 HTTPS**）：`https://<host>/api/v1/channels/inbound/ops-douyin`。  
3. 保存时平台推 `event=verify_webhook`；OryxOS 回 `{"challenge":…}`。  
4. 验签头 `X-Douyin-Signature` = `sha1(client_secret + rawBody)`。  
5. 订阅 `im_receive_msg`（必）；可选 `im_send_msg`。申请 scope `im.direct_message`。  
6. 经营者扫码授权，取得 `open_id` 与 `access_token`（发信用）。

## 二、OryxOS 侧

```yaml
channels:
  - name: ops-douyin
    type: douyin
    app_id: ${DOUYIN_CLIENT_KEY}
    app_secret: ${DOUYIN_CLIENT_SECRET}
    agent: demo-agent
    extra:
      open_id: ${DOUYIN_OPEN_ID}
      access_token: ${DOUYIN_ACCESS_TOKEN}
    enabled: true
```

白名单：`open.douyin.com`。

## 三、行为

| 场景 | 行为 |
|------|------|
| 用户→经营者 文本私信 | 进编排；`chatId=user:{open_id}` |
| 回复 | `im_reply_msg` + 入站缓存的 `conversation_id`/`msg_id` |
| 非文本 | `textual=false`（MVP 不落媒体） |
| 群 / 进页触达 / Notify | 非 MVP |

## 四、实测清单

- [ ] `verify_webhook` 配置成功  
- [ ] 验签失败 401  
- [ ] 私聊文本往返（有资质时）  
- [ ] 窗外 / 超条数错误点名  

详见 `specs/031-douyin-im-channel/`。
