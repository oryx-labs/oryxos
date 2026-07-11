# Quickstart: Notify 验证指引

**Date**: 2026-07-11 | **Feature**: [spec.md](./spec.md)

## 前置

- JDK 21、Maven；16/17/18 节交付物在位（63 测试绿）。

## 自动化验收（harness 第一批，本节判卷）

```bash
mvn clean verify                       # 全量门禁（完成定义）
mvn test -pl oryxos-tool -am -Dtest='WebhookNotifyAdapterTest,NotifyToolsTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期全绿。守点对号：

| 测试 | 守点 |
|---|---|
| WebhookNotifyAdapterTest | POST body 带 content；URL 来自 NotifyTarget.config 非硬编码（换 url 即换目标）；假 webhook 返回 500 异常上抛不吞；config 缺 url 报错不发请求 |
| NotifyToolsTest（第一批可测集） | notify_channels 未配置 → 明确报错零请求；channel 缺省/"default" → 第一个渠道；指定类型命中/未命中；上下文缺失报错；成功委托 adapter 并回"已推送" |

## 留待后续节的回归（不在本节判卷）

- 24 节：InOrder `发送前必须先过白名单校验`（enforce 先于 send）；
- 20 节：notify 经 ToolRegistry 注册后，chat 里真实调用链（ToolExecutor 审计落 tool_invocations）。

## 回归证据（跨节契约)

```bash
mvn test -pl oryxos-core,oryxos-provider,oryxos-storage,oryxos-tool -am   # 前序 63 + 本节全绿
mvn dependency:tree -pl oryxos-tool | grep spring-web                     # BOM 解析确认
```

## 人工项（课件"五、怎么用，做完怎么验"）

1. **真实 webhook**：配一个真实群机器人地址到某 Profile 的 notify_channels（url 走 `${TEAM_WEBHOOK_URL}`），20 节接线后在 chat 里说"把'测试消息'推送一下"，群里收到（假 webhook 测协议，真 webhook 验配置）。
2. **接口中立性思维自查**：换成企业微信官方 SDK 实现，`NotifyChannelAdapter.send(NotifyTarget, String)` 签名需要改吗？答案应为不需要。
