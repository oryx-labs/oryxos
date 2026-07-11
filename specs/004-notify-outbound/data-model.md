# Data Model: Notify——结果主动送出去的统一出口

**Date**: 2026-07-11 | **Feature**: [spec.md](./spec.md)

## 值对象（oryxos-tool，非持久化）

### NotifyTarget（record）

| 字段 | 类型 | 说明 |
|---|---|---|
| channelType | String | 渠道类型（核心阶段唯一取值 `webhook`；扩展档位如 wecom/feishu 后加） |
| config | Map\<String,String\> | 渠道特定配置，实现自行解释（webhook 档必需键：`url`）；compact ctor Map.copyOf |

来源映射：16 节 `Profile.NotifyChannel(type, config)` → `NotifyTarget(type, config)`，字段一一对应零转换（ProfileLoader 已把 `${ENV}` 占位解析掉）。

## 接口与实现

- `NotifyChannelAdapter`：`void send(NotifyTarget target, String content)`——失败以异常表达，无返回值。
- `WebhookNotifyAdapter`：POST JSON `{"content": "<content>"}` 到 `config["url"]`；url 缺失 → IllegalArgumentException 点名；非 2xx → RestClient 默认异常上抛。

## notify 工具输入（JSON Schema，getInputSchema 手写）

| 参数 | 必填 | 说明 |
|---|---|---|
| content | 是 | 要推送的内容 |
| channel | 否 | 渠道类型；空/空白/"default" → 取 notify_channels 第一个；指定类型匹配不到 → 失败结果点名 |

## 无持久化变更

- 无新表、无 Profile 字段变更（notify_channels 16 节已交付）；审计走 tool_invocations 既有路径。

## 失败语义汇总（全部为"明确失败"，零静默）

| 情况 | 表达形式 |
|---|---|
| 当前无 Agent 上下文 / notify_channels 未配置 / channel 类型不存在 / content 缺失 | NotifyTools 返回失败 ToolResult（errorMessage 点名），不发请求 |
| config 缺 url | WebhookNotifyAdapter 抛 IllegalArgumentException（→ ToolExecutor 转失败结果+审计） |
| 对端非 2xx / 连接失败 | RestClient 异常上抛（→ 同上） |
