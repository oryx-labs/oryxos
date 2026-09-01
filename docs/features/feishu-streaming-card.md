# 飞书流式卡片回复功能

## 功能概述

实现类似 OpenClaw 的飞书流式回复体验：
- 收到消息后立即添加 ⌨️ 表情反馈（已读）
- 发送交互式卡片，实时展示 Agent 的思考过程和工具调用状态
- 处理完成后移除表情，更新卡片为最终结果

## 核心实现

### 1. FeishuCardBuilder（卡片构建器）

**位置**: `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuCardBuilder.java`

**功能**:
- `buildInitialCard()` - 构建初始"正在分析"卡片
- `buildProcessingCard(thinkingProcess, activeTools, completedTools)` - 构建处理中卡片
  - 展示最近 5 行思考过程
  - 展示正在执行的工具（🔧）
  - 展示已完成的工具（✅）
- `buildCompletedCard(finalAnswer)` - 构建完成卡片（绿色头部）
- `buildErrorCard(errorMessage)` - 构建错误卡片（红色头部）

**卡片状态颜色**:
- 蓝色：处理中
- 绿色：已完成
- 红色：错误

### 2. FeishuReactionManager（表情管理器）

**位置**: `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuReactionManager.java`

**功能**:
- `addKeyboardReaction(messageId)` - 添加 ⌨️ 表情，返回 reactionId
- `removeReaction(messageId, reactionId)` - 移除表情

**使用场景**:
- 收到用户消息时立即添加（表示已读）
- 处理完成后移除（避免视觉噪音）

### 3. FeishuStreamListener（流式监听器）

**位置**: `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuStreamListener.java`

**核心方法**:
```java
// 启动流程：添加表情 + 发送初始卡片
void start()

// StreamListener 接口实现
void onTextDelta(String delta)           // 接收 LLM 输出的文本增量
void onToolStart(String name, String input)    // 工具开始执行
void onToolEnd(String name, ToolResult result) // 工具执行完成

// 结束流程：移除表情 + 更新最终卡片
void finish(String finalAnswer, String error)
```

**更新策略**:
- 工具调用时立即更新（重要节点）
- 文本累积到一定量后更新（避免频繁 API 调用）
- 完成时更新为最终结果

### 4. FeishuMessageSender 扩展

**位置**: `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuMessageSender.java`

**新增方法**:
- `sendCard(chatId, cardJson, replyToMessageId)` - 发送交互式卡片，返回消息 ID
- `updateCard(messageId, cardJson)` - 更新已发送的卡片内容

### 5. InboundMessageService 集成

**位置**: `oryxos-core/src/main/java/io/oryxos/core/channel/InboundMessageService.java`

**改动**:
1. 调用 `replyVia.createStreamListener(msg)` 创建流式监听器
2. 飞书监听器调用 `start()` 启动（添加表情 + 发送初始卡片）
3. 将监听器传入 `agentService.process()` 进行流式回调
4. 处理完成后调用 `finish()` 收尾（移除表情 + 更新最终卡片）

### 6. FeishuChannelAdapter 适配

**位置**: `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuChannelAdapter.java`

**新增方法**:
```java
@Override
public StreamListener createStreamListener(InboundMessage msg) {
  return new FeishuStreamListener(
      messageSender,
      reactionManager, 
      cardBuilder,
      msg.chatId(),
      msg.chatKind() == ChatKind.GROUP ? msg.messageId() : null,
      msg.messageId()
  );
}
```

### 7. InboundChannelAdapter 接口扩展

**位置**: `oryxos-core/src/main/java/io/oryxos/core/channel/InboundChannelAdapter.java`

**新增方法**:
```java
default StreamListener createStreamListener(InboundMessage msg) {
  return null;  // 默认不支持流式（渠道自行实现）
}
```

## 用户体验流程

```
用户发送消息
  ↓
立即添加 ⌨️ 表情（已读确认）
  ↓
发送初始卡片："正在分析你的问题..."
  ↓
Agent 开始推理
  ↓
实时更新卡片：
  - 展示思考过程（最近 5 行）
  - 展示工具调用状态（🔧 正在执行 / ✅ 已完成）
  ↓
处理完成
  ↓
移除 ⌨️ 表情
  ↓
更新卡片为最终结果（只保留答案，不显示过程）
```

## API 使用

### 飞书 API

1. **添加表情**: `POST /im/v1/messages/{message_id}/reactions`
2. **移除表情**: `DELETE /im/v1/messages/{message_id}/reactions/{reaction_id}`
3. **发送卡片**: `POST /im/v1/messages?msg_type=interactive`
4. **更新卡片**: `PATCH /im/v1/messages/{message_id}`

### 权限要求

需要在飞书开放平台申请以下权限：
- `im:message` - 发送消息
- `im:message:reaction:create` - 添加表情
- `im:message:reaction:delete` - 删除表情

## 配置

无需额外配置，自动启用。如需禁用流式，在 `FeishuChannelAdapter` 中返回 `null`。

## 降级方案

如果任何 API 失败（表情、卡片发送、卡片更新），自动降级为传统文本回复，不影响核心功能。

## 测试

单元测试：`FeishuStreamListenerTest.java`
- 测试启动流程（表情 + 初始卡片）
- 测试工具调用更新
- 测试成功完成
- 测试错误处理

## 技术细节

### 避免限流

- 卡片更新采用累积策略（工具调用时立即更新，文本增量累积）
- 避免每个 token 都更新（会被限流）

### 线程安全

- 使用 `synchronized` 保护卡片更新状态
- 避免并发更新导致卡片内容混乱

### 反射调用

`InboundMessageService` 使用反射调用 `start()` 和 `finish()` 方法，避免在 core 模块中引入飞书依赖。

## 与 OpenClaw 的对比

| 特性 | OpenClaw | OryxOS 飞书渠道 |
|------|----------|----------------|
| 已读表情 | ✅ ⌨️ | ✅ ⌨️ |
| 流式卡片 | ✅ | ✅ |
| 思考过程展示 | ✅ | ✅（最近 5 行） |
| 工具调用状态 | ✅ | ✅（🔧/✅） |
| 最终结果清爽 | ✅ | ✅（只保留答案） |
| 错误处理 | ✅ | ✅（红色卡片） |

## 未来优化

1. **可配置化**：允许通过配置开关流式功能
2. **更新策略优化**：根据实际使用调整更新频率
3. **卡片样式增强**：支持更多飞书卡片组件（按钮、链接等）
4. **性能监控**：记录卡片更新次数和 API 调用耗时
5. **其他渠道支持**：企微、钉钉也可以实现类似体验

## 符合 OryxOS 规范

- ✅ 宪法 VII：同步执行模型（StreamListener 是同步回调）
- ✅ 模块边界：改动集中在 `oryxos-channel-feishu` 和 `oryxos-core/channel`
- ✅ 沙箱：飞书 API 域名已在白名单
- ✅ 架构一致：复用 019 特性的 `StreamListener` 接口
- ✅ 审计：所有工具调用照常写入 `tool_invocations` 表

## 文件清单

### 新增文件（4 个）
1. `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuCardBuilder.java`
2. `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuReactionManager.java`
3. `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuStreamListener.java`
4. `oryxos-channel-feishu/src/test/java/io/oryxos/channel/feishu/FeishuStreamListenerTest.java`

### 修改文件（4 个）
1. `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuMessageSender.java`
   - 新增 `sendCard()` 方法
   - 新增 `updateCard()` 方法
   
2. `oryxos-channel-feishu/src/main/java/io/oryxos/channel/feishu/FeishuChannelAdapter.java`
   - 实现 `createStreamListener()` 方法
   
3. `oryxos-core/src/main/java/io/oryxos/core/channel/InboundChannelAdapter.java`
   - 新增 `createStreamListener()` 接口方法（default 实现）
   
4. `oryxos-core/src/main/java/io/oryxos/core/channel/InboundMessageService.java`
   - 集成流式监听器逻辑
   - 处理启动和完成回调

## PR 提交建议

### PR 标题
```
feat(channel-feishu): 实现流式卡片回复（类似 OpenClaw）
```

### PR 描述模板
```markdown
## 功能描述

为飞书渠道实现流式卡片回复，提升用户体验，类似 OpenClaw 的处理方式。

### 核心特性
- ⌨️ 收到消息立即添加表情（已读确认）
- 📊 实时更新交互式卡片展示 Agent 思考过程
- 🔧 展示工具调用状态（正在执行/已完成）
- ✅ 完成后移除表情，卡片更新为最终结果

### 技术实现
- 新增 `FeishuCardBuilder`、`FeishuReactionManager`、`FeishuStreamListener`
- 扩展 `FeishuMessageSender` 支持卡片发送和更新
- 集成到 `InboundMessageService` 的流式处理流程
- 复用 019 特性的 `StreamListener` 接口

### 用户体验提升
- 从"干等 10-30 秒"→"立即反馈 + 实时进度"
- 过程透明：看得到 Agent 在做什么
- 结果清爽：完成后只保留答案

### 符合规范
- ✅ 符合宪法 VII（同步执行模型）
- ✅ 模块边界清晰（飞书渠道内）
- ✅ 沙箱白名单合规
- ✅ 架构一致性（复用 StreamListener）

### 测试
- 单元测试：`FeishuStreamListenerTest`
- 手动验证：待真实飞书环境测试

### 截图/视频
（建议录制演示视频展示效果）
```

## 本地验证步骤

### 1. 编译项目
```bash
mvn clean compile -DskipTests
```

### 2. 运行单元测试
```bash
mvn test -pl oryxos-channel-feishu -Dtest=FeishuStreamListenerTest
```

### 3. 真实环境测试

需要：
1. 飞书开放平台应用（配置好权限）
2. 配置 `.oryxos/agents/<agent>/channels.yaml`
3. 启动 OryxOS：`bin/start.sh gateway`
4. 在飞书中 @ 机器人发送消息
5. 观察：
   - 是否立即出现 ⌨️ 表情
   - 是否发送初始卡片
   - 卡片是否实时更新（思考过程 + 工具调用）
   - 完成后表情是否移除
   - 卡片是否更新为最终结果

## 开发日志

- 2026-09-01: 初版实现完成
  - 核心组件：CardBuilder、ReactionManager、StreamListener
  - 集成到 InboundMessageService
  - 编译通过
  - 单元测试待真实环境验证
