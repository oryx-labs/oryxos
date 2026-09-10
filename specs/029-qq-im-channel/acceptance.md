# 029 验收

**日期**: 2026-09-10  
**范围**: QQ 官方 Bot 入站 + notify + 真机单聊图/PDF。

## 单测

| 项 | 结果 |
|----|------|
| `QqEventNormalizer`（含 attachments） | 通过 |
| `QqChannelContractTest` | 通过 |
| `QqNotifyAdapter` | 通过 |

## 真机

| 项 | 结果 |
|----|------|
| 换票 / Gateway CONNECTED | 通过 |
| 单聊文本 | 通过（20:48） |
| 单聊图片 | **通过**（21:41 落盘 `qq-media.jpg` + Vision） |
| 单聊 PDF | **通过**（21:42 落盘约 17MB + `read_file` success） |
| 群 `@Bot` | 阻塞：个人认证入群待腾讯灰度 |
| notify | 待测 |

## 非宣称

- NapCat / 个人号  
- 频道消息  
- 出站富媒体完整对齐  
