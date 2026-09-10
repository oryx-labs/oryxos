# Research: 033 微信客服（对微信用户的官方客服）

**Date**: 2026-09-11  
**Status**: READY（可写实现 PLAN；**BUILD 待你确认**）  
**对照**: [plan](./plan.md)、[030](../030-cn-c-im-roadmap/plan.md)、现有 `oryxos-channel-wecom`（应用内机器人，**不是本产品**）

## 产品面澄清

| | 现有 `wecom` | 本渠道「微信客服」 |
|--|--------------|-------------------|
| 用户在哪聊 | 企业微信里（员工/应用） | **微信**里找企业客服 |
| API 前缀 | 应用消息 / 智能机器人等 | `qyapi.weixin.qq.com/cgi-bin/kf/*` |
| 完成定义 | 已接 | 独立 `type`（建议 `weixin_kf`） |

不得把「企微渠道已有」当成「微信客服已覆盖」。

## 钉死路径（官方文档）

| 步骤 | 能力 | 说明 |
|------|------|------|
| 1 | 开启 API | 微信客服管理后台开启；回调 URL + Token + EncodingAESKey（与企微回调同族） |
| 2 | 入站唤醒 | 回调事件 `kf_msg_or_event`（加密 XML）；**不带全文** |
| 3 | 拉消息 | `POST /cgi-bin/kf/sync_msg`：`open_kfid` + 回调里的 `token`（约 10min）+ `cursor`；可拉近 3 天；`has_more` 翻页 |
| 4 | 出站 | `POST /cgi-bin/kf/send_msg`：`touser` + `open_kfid` + `msgtype=text` 等 |
| 5 | 会话态 | 仅「新接入待处理」或「**由智能助手接待**」可发；需会话分配/`service_state` 相关接口把会话交给智能助手（实现 PLAN 写清调用序） |

权威入口（企微文档中心）：接收消息与事件、发送消息、回调通知（`kf_msg_or_event`）。

## 会话窗（fail-loud）

官方明确：

- 用户主动发消息后 **48 小时**内可回复  
- 每个用户回合最多 **5 条**企业下行；用户再发可再开一轮  
- 失败事件里可见「超 48h / 超 5 条」等 `fail_type`

适配器：`sendReply` 窗外或超条数 **硬拒绝**（对齐 WhatsApp/抖音纪律）。

## 凭证与配置（拟）

```yaml
type: weixin_kf   # 或 wecom_kf；勿复用 type: wecom
app_id: ${WECOM_CORP_ID}           # CorpId
app_secret: ${WEIXIN_KF_SECRET}    # 微信客服 Secret（非普通应用 secret 时单独配）
extra:
  token: ${WEIXIN_KF_TOKEN}
  encoding_aes_key: ${WEIXIN_KF_AES_KEY}
  open_kfid: ${WEIXIN_KF_OPEN_KFID}  # 可多账号二期
```

`access_token`：`corpid` + 客服 secret 换票（与现有 wecom token 客户端可复用模式，密钥隔离）。

## chatId

建议：`kf:{open_kfid}:user:{external_userid}`（或文档中的客户标识字段，以实现 PLAN 对照样例报文为准）。

## 沙箱域名

- `qyapi.weixin.qq.com`（已有企微白名单通常已覆盖）  
- 媒体下载域名按素材接口再补

## 与现有 wecom 模块关系

- **不要**硬塞进 `WeComChannelAdapter` 同一 `type`（协议与会话窗不同，配置易混）。  
- **可以**复用：AES 加解密、签名校验、Http 出站 Guard 模式（抽共享工具或复制最小集，实现时再定，research 不强制重构）。

## MVP 范围

- 文本入站（`sync_msg` 里 origin=微信客户）→ 编排 → 文本 `send_msg`  
- 单 `open_kfid`  
- 会话交给智能助手（否则发信会被拒）

## 非目标（MVP）

- 人工接待排班 UI、菜单消息、小程序卡片  
- 服务号客服 / 小程序客服 / 微信小店客服（030 §C W2–W4，另开 research）  
- 第三方聚合云客服

## 准入结论

| 项 | 状态 |
|----|------|
| 权限可申请 | ✅ 企业微信 + 微信客服产品 |
| 上下行 API 名 | ✅ 钉死 |
| 验签/加密 | ✅ 企微回调同族 |
| 会话窗 | ✅ 48h / 5 条 |
| 排除混淆 | ✅ 非应用机器人、非个微 |

→ **可开实现 PLAN；你确认后再 BUILD。**
