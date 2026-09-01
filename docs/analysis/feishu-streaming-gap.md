# 飞书渠道流式回复差距分析

## 现状

OryxOS 当前飞书渠道实现（017 特性）采用**一次性回复**模式：

```java
// InboundMessageService.java L102
inference = () ->
    replyVia.sendReply(msg.chatId(), agentService.process(session, msg.content()), null);
```

用户体验：
- ❌ 发消息后长时间无响应（10-30秒）
- ❌ 回答突然整段出现
- ❌ 不知道 Agent 是否在处理、处理到哪一步了
- ✅ 只有一个"已收到，正在处理中"的兜底提示（8秒后发送）

## 019 流式能力现状

OryxOS 已经在 019 特性中实现了完整的流式基础设施：

### 核心接口
```java
// StreamListener.java
public interface StreamListener {
  void onToken(String delta);           // LLM 生成增量
  void onToolStart(String toolName);    // 工具开始
  void onToolEnd(String toolName, boolean success); // 工具结束
}
```

### 已支持的端点
- ✅ Web SSE: `POST /api/v1/sessions/{id}/messages` (Accept: text/event-stream)
- ✅ CLI: `oryxos chat` 打字机效果
- ✅ 管理台：打字机 + 工具进度提示

### 核心已就绪
- ✅ `AgentService.process(session, message, listener)` 四参方法
- ✅ `ReActLoop` 支持流式输出
- ✅ Provider 层流式调用（Spring AI）

## 差距根因

**飞书渠道没有接入 019 流式能力**，原因：

1. **时间顺序**：017（飞书）2026-08-20 立项，019（SSE 流式）2026-08-27 立项 → 飞书先做完，那时流式还不存在
2. **契约未更新**：`InboundMessageService` 编排层调用的是三参 `agentService.process(session, msg)`，没有传 `StreamListener`
3. **飞书 API 特性**：飞书消息是独立的 `im/v1/messages` 调用，不是长连接流 → 需要设计"如何把流式增量映射到多条飞书消息"

## 对比：OpenClaw 的体验

根据用户描述，OpenClaw 应该实现了：
- ✅ 打字机效果：回复逐段出现
- ✅ 过程可见：能看到工具调用状态
- ✅ 即时反馈：不用等全部完成

## 技术方案

### 方案一：飞书流式 Listener（推荐）

实现 `FeishuStreamListener implements StreamListener`：

**策略**：
- `onToken(delta)` → 累积到一定长度（如 200 字符）或时间间隔（如 2 秒）后，发一条飞书消息
- `onToolStart(name)` → 发一条状态消息「🔧 正在执行：{name}」
- `onToolEnd(name, success)` → 发一条状态消息「✅ {name} 完成」或「❌ {name} 失败」
- 最后拼接所有 token，发送完整回复（确保一致性）

**优点**：
- 用户体验接近 Web SSE 的打字机效果
- 复用 019 的流式基础设施，改动小
- 过程可见（工具调用状态）

**挑战**：
- 飞书消息有频控限制（需要查文档）
- 多条消息 vs 单条消息的权衡
- 需要处理"累积发送"的复杂性

**实现位置**：
```
oryxos-channel-feishu/
  └─ FeishuStreamListener.java  (新增)
oryxos-core/
  └─ channel/InboundMessageService.java (改：传入 listener)
```

### 方案二：飞书消息编辑（如果 API 支持）

检查飞书是否支持"编辑已发送的消息"：
- 先发一条占位消息
- 不断编辑这条消息，更新内容
- 最终固定为完整回复

**优点**：
- 单条消息，更清爽
- 真正的"打字机"效果

**挑战**：
- 需要确认飞书 API 是否支持编辑
- 编辑频率限制

### 方案三：保持现状 + 优化提示（最简单）

不做流式，但优化体验：
- 立即回复「📝 正在思考中...」
- 工具调用时更新为「🔧 正在执行 xxx...」
- 完成后整段发送

**优点**：
- 改动最小
- 不涉及流式复杂性

**缺点**：
- 体验仍不如真正的流式
- 长回答仍需等待

## 推荐路径

### 立即可做（MVP）：方案一的简化版

1. **实现 FeishuStreamListener**：
   - `onToken()` → 累积所有 token，不提前发送
   - `onToolStart(name)` → 立即发一条「🔧 {name}」
   - `onToolEnd(name, true)` → 立即发一条「✅ {name} 完成」
   - 最后发送完整回复

2. **修改 InboundMessageService**：
   - 创建 `FeishuStreamListener` 实例
   - 调用四参 `agentService.process(session, msg, listener)`

3. **效果**：
   - 用户能看到工具调用进度（比现状好）
   - 最终回答仍是一次性（但有过程可见性）
   - 改动约 50 行代码

### 后续优化（v0.3）：真正的分段发送

- 研究飞书 API 频控限制
- 实现 token 累积发送策略
- 考虑消息编辑能力

## 对比表

| 维度 | 现状 (017) | 019 Web SSE | OpenClaw 飞书 (推测) | 方案一 MVP |
|------|-----------|-------------|---------------------|-----------|
| 打字机效果 | ❌ | ✅ | ✅ | ⚠️ (工具可见) |
| 过程可见 | ❌ | ✅ | ✅ | ✅ |
| 等待时间感知 | 兜底提示 | 即时 | 即时 | 即时 |
| 实现复杂度 | - | 高 | 中 | 低 |
| 改动范围 | - | 核心+Web | 渠道 | 渠道+编排 |

## 下一步

1. **确认需求**：用户期望的是"完整打字机"还是"过程可见"？
2. **验证飞书 API**：频控、编辑能力
3. **实现 MVP**：工具进度可见版（1-2 小时）
4. **迭代完整版**：分段发送（如果需要）

## 相关文件

- `specs/017-feishu-im-channel/spec.md` - 飞书渠道规格
- `specs/019-sse-streaming/spec.md` - 流式能力规格
- `oryxos-core/src/main/java/io/oryxos/core/agent/StreamListener.java` - 流式接口
- `oryxos-core/src/main/java/io/oryxos/core/channel/InboundMessageService.java` - 入站编排（需改）
- `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuMessageSender.java` - 飞书发送器
