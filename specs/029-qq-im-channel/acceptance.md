# 029 验收

**日期**: 2026-09-10  
**范围**: QQ 官方 Bot 入站 + notify 代码与单测；真机视本机是否有 `QQ_APP_*`。

## 单测

| 项 | 结果 |
|----|------|
| `QqEventNormalizer` | 通过（群 @ / C2C / 非 MVP 丢弃 / 空内容） |
| `QqChannelContractTest` | 通过（`InboundMessageService` 契约档） |
| `QqNotifyAdapter`（VendorNotify） | 通过（`msg_type=0` + `Authorization: QQBot`；缺配置拒绝） |
| 模块 `verify`（qq/tool/web/cli） | 通过（含 spotless / checkstyle / spotbugs） |

## 真机

本机 `.env` **无** `QQ_APP_ID` / `QQ_APP_SECRET` → **不宣称 COMPLETE**。清单：

1. 沙箱/测试群：`@Bot` 文本 → Agent 被动回帖（带 `msg_id`）
2. 单聊文本往返
3. `notify` 打进指定 `group_openid`（主动消息限额见官方）
4. 重复 `msg_id` 去重；官方群事件仅推 @Bot
5. 不宣称：个人号、频道 MVP、富媒体完整对齐

## 非宣称

- NapCat / 个人号协议  
- QQ 频道子频道消息  
- 流式 / 图文件完整出站对齐
